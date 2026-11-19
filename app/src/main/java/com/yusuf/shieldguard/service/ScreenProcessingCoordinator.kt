package com.yusuf.shieldguard.service

import com.yusuf.shieldguard.ai.VisualContentAnalyzer
import com.yusuf.shieldguard.capture.FrameCapturer
import com.yusuf.shieldguard.capture.FrameIntegrityInspector
import com.yusuf.shieldguard.data.ProtectionRuntimeState
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.data.ProtectionStateHolder
import com.yusuf.shieldguard.overlay.OverlayRenderer
import com.yusuf.shieldguard.perf.GovernorAdjustment
import com.yusuf.shieldguard.video.AdaptiveFrameScheduler
import com.yusuf.shieldguard.video.MotionAndScrollEstimator
import com.yusuf.shieldguard.video.RegionTracker
import com.yusuf.shieldguard.video.ScheduleAction

enum class TickOutcome {
    PAUSED_INACTIVE_OR_EXCLUDED,
    PROTECTED_WINDOW_SKIPPED,
    NO_FRAME_AVAILABLE,
    STATIC_SCENE_HELD,
    TRACK_PREDICTED_AND_SHIFTED,
    CLEARED_FOR_CLEAN_CAPTURE,
    ANALYZED_AND_RENDERED,
    ANALYSIS_FAILED
}

class ScreenProcessingCoordinator(
    private val ownPackageName: String,
    private val capturer: FrameCapturer,
    private val analyzer: VisualContentAnalyzer,
    private val overlayRenderer: OverlayRenderer,
    private val inspector: FrameIntegrityInspector = FrameIntegrityInspector(),
    private val motionEstimator: MotionAndScrollEstimator = MotionAndScrollEstimator(),
    private val scheduler: AdaptiveFrameScheduler = AdaptiveFrameScheduler(),
    private val tracker: RegionTracker = RegionTracker()
) {
    private var awaitingCleanFrame: Boolean = false
    private var recommendedDelayMs: Long = 350L

    fun clearAll() {
        awaitingCleanFrame = false
        tracker.clear()
        inspector.resetBaseline()
        motionEstimator.reset()
        scheduler.reset()
        overlayRenderer.clear()
        ProtectionStateHolder.updateOverlayTelemetry(0, 0f, "STATIC", 0)
    }

    suspend fun processTick(
        settings: ProtectionSettings,
        runtimeState: ProtectionRuntimeState,
        adjustment: GovernorAdjustment = GovernorAdjustment.NONE
    ): TickOutcome {
        val activePkg = runtimeState.activeForegroundPackage
        val protectionDisabled = !settings.protectImages && !settings.protectVideos
        val isExcluded = runtimeState.isDashboardVisible ||
            activePkg == ownPackageName ||
            settings.isPackageExcluded(activePkg)

        if (!runtimeState.isScreenInteractive || protectionDisabled || isExcluded) {
            if (tracker.activeTrackCount() > 0 || awaitingCleanFrame) {
                clearAll()
            }
            recommendedDelayMs = 500L
            return TickOutcome.PAUSED_INACTIVE_OR_EXCLUDED
        }

        if (!runtimeState.isCurrentWindowAccessible) {
            if (tracker.activeTrackCount() > 0 || awaitingCleanFrame) {
                clearAll()
            }
            recommendedDelayMs = 500L
            return TickOutcome.PROTECTED_WINDOW_SKIPPED
        }

        val effectiveWidth = adjustment.applyDownscaleWidth(settings.performanceProfile.downscaleWidthPx)
        capturer.ensureDownscaleWidth(effectiveWidth)

        val frame = capturer.acquireLatestFrame() ?: run {
            recommendedDelayMs = adjustment.applyDelayMs(settings.performanceProfile.frameIntervalMs)
            return TickOutcome.NO_FRAME_AVAILABLE
        }

        if (inspector.isLikelyProtectedBlackFrame(frame)) {
            clearAll()
            ProtectionStateHolder.recordProtectedBlackFrame()
            recommendedDelayMs = 500L
            return TickOutcome.PROTECTED_WINDOW_SKIPPED
        }

        val nowMs = frame.timestampMs
        val coveredCaptureRects = if (awaitingCleanFrame) emptyList() else tracker.getCoveredCaptureBounds()
        val outsideDiff = inspector.computeOutsideMeanDiffAndUpdate(frame, coveredCaptureRects)
        val motion = motionEstimator.analyzeMotion(frame, outsideDiff, coveredCaptureRects)

        val decision = scheduler.decide(
            motion = motion,
            settings = settings,
            hasActiveBlurredTracks = tracker.hasActiveBlurredTracks(),
            awaitingCleanFrame = awaitingCleanFrame,
            nowMs = nowMs
        )

        val governedDelay = if (decision.action == ScheduleAction.REQUEST_CLEAN_KEYFRAME) {
            decision.nextDelayMs
        } else {
            adjustment.applyDelayMs(decision.nextDelayMs)
        }
        recommendedDelayMs = governedDelay
        ProtectionStateHolder.updateGovernorState(
            status = adjustment.level.name,
            downscaleWidthPx = effectiveWidth,
            intervalMs = governedDelay
        )

        when (decision.action) {
            ScheduleAction.SKIP_DISABLED_MEDIA_MODE -> {
                if (tracker.hasActiveBlurredTracks()) {
                    tracker.clear()
                    overlayRenderer.clear()
                }
                ProtectionStateHolder.incrementSkippedInferenceTicks()
                ProtectionStateHolder.updateOverlayTelemetry(0, 0f, motion.state.name, 0)
                return TickOutcome.PAUSED_INACTIVE_OR_EXCLUDED
            }

            ScheduleAction.HOLD_STATIC -> {
                tracker.holdStaticTracks(nowMs)
                ProtectionStateHolder.incrementSkippedInferenceTicks()
                return TickOutcome.STATIC_SCENE_HELD
            }

            ScheduleAction.REQUEST_CLEAN_KEYFRAME -> {
                overlayRenderer.clear()
                awaitingCleanFrame = true
                return TickOutcome.CLEARED_FOR_CLEAN_CAPTURE
            }

            ScheduleAction.PREDICT_TRACKS_ONLY -> {
                val (offX, offY) = overlayRenderer.getWindowOffset()
                val projectedPatches = tracker.predictAndShiftTracks(
                    nowMs = nowMs,
                    scrollDyCapturePx = motion.scrollDyCapturePx,
                    captureW = frame.width,
                    captureH = frame.height,
                    screenW = capturer.screenWidth,
                    screenH = capturer.screenHeight,
                    overlayOffsetX = offX,
                    overlayOffsetY = offY
                )
                if (projectedPatches.isEmpty()) {
                    overlayRenderer.clear()
                } else {
                    overlayRenderer.showPatches(projectedPatches)
                }
                ProtectionStateHolder.incrementSkippedInferenceTicks()
                ProtectionStateHolder.updateOverlayTelemetry(
                    activeRegionsCount = projectedPatches.size,
                    totalLatencyMs = ProtectionStateHolder.state.value.lastInferenceMs,
                    motionStateName = motion.state.name,
                    trackedCount = tracker.activeTrackCount()
                )
                return TickOutcome.TRACK_PREDICTED_AND_SHIFTED
            }

            ScheduleAction.RUN_FULL_INFERENCE -> {
                val isCleanFrame = awaitingCleanFrame || !tracker.hasActiveBlurredTracks()
                awaitingCleanFrame = false

                val effectiveSettings = if (adjustment.disableSecondaryClassifier && settings.detectExplicitContent) {
                    settings.copy(detectGenderSpecific = false)
                } else {
                    settings
                }

                val analysis = analyzer.analyzeFrame(frame, effectiveSettings)
                if (!analysis.analyzedSuccessfully) {
                    overlayRenderer.clear()
                    tracker.clear()
                    ProtectionStateHolder.updateOverlayTelemetry(0, 0f, motion.state.name, 0)
                    return TickOutcome.ANALYSIS_FAILED
                }

                val totalLatencyMs = analysis.detectorLatencyMs + analysis.classifierLatencyMs
                scheduler.recordInferenceLatency(
                    latencyMs = totalLatencyMs,
                    nowMs = nowMs,
                    wasCleanKeyframe = isCleanFrame
                )

                val (offX, offY) = overlayRenderer.getWindowOffset()
                val patches = tracker.updateWithEvaluations(
                    evaluatedRegions = analysis.regions,
                    frame = frame,
                    isCleanFrame = isCleanFrame,
                    blurIntensity = settings.clampedBlurIntensity(),
                    screenW = capturer.screenWidth,
                    screenH = capturer.screenHeight,
                    overlayOffsetX = offX,
                    overlayOffsetY = offY,
                    nowMs = nowMs
                )

                if (patches.isEmpty()) {
                    overlayRenderer.clear()
                } else {
                    overlayRenderer.showPatches(patches)
                }

                ProtectionStateHolder.updateOverlayTelemetry(
                    activeRegionsCount = patches.size,
                    totalLatencyMs = totalLatencyMs,
                    motionStateName = motion.state.name,
                    trackedCount = tracker.activeTrackCount()
                )
                return TickOutcome.ANALYZED_AND_RENDERED
            }
        }
    }

    fun nextDelayMs(outcome: TickOutcome, settings: ProtectionSettings): Long {
        return when (outcome) {
            TickOutcome.CLEARED_FOR_CLEAN_CAPTURE -> 45L
            TickOutcome.PAUSED_INACTIVE_OR_EXCLUDED,
            TickOutcome.PROTECTED_WINDOW_SKIPPED -> 500L
            else -> recommendedDelayMs.coerceIn(45L, maxOf(2000L, settings.performanceProfile.frameIntervalMs * 2))
        }
    }
}
