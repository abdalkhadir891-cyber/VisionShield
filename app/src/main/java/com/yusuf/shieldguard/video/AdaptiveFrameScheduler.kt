package com.yusuf.shieldguard.video

import com.yusuf.shieldguard.data.PerformanceProfile
import com.yusuf.shieldguard.data.ProtectionSettings
import kotlin.math.max
import kotlin.math.min

enum class ScheduleAction {
    HOLD_STATIC,
    SKIP_DISABLED_MEDIA_MODE,
    PREDICT_TRACKS_ONLY,
    RUN_FULL_INFERENCE,
    REQUEST_CLEAN_KEYFRAME
}

data class ScheduleDecision(
    val action: ScheduleAction,
    val nextDelayMs: Long
)

class AdaptiveFrameScheduler {

    companion object {
        private const val TRACK_STEP_VIDEO_MS = 100L
        private const val TRACK_STEP_SCROLL_MS = 65L
        private const val CLEAN_KEYFRAME_COOLDOWN_MS = 1100L
        private const val MAX_ADAPTIVE_INFERENCE_INTERVAL_MS = 1200L
        private const val CPU_DUTY_MULTIPLIER = 2.6f
    }

    private var lastInferenceTimestampMs: Long = 0L
    private var lastCleanKeyframeMs: Long = 0L
    private var smoothedInferenceMs: Float = 0f

    fun reset() {
        lastInferenceTimestampMs = 0L
        lastCleanKeyframeMs = 0L
        smoothedInferenceMs = 0f
    }

    fun recordInferenceLatency(latencyMs: Float, nowMs: Long, wasCleanKeyframe: Boolean) {
        val safeLatency = latencyMs.coerceIn(0f, 5000f)
        smoothedInferenceMs = if (smoothedInferenceMs <= 0f) {
            safeLatency
        } else {
            smoothedInferenceMs * 0.65f + safeLatency * 0.35f
        }
        lastInferenceTimestampMs = nowMs
        if (wasCleanKeyframe) {
            lastCleanKeyframeMs = nowMs
        }
    }

    fun effectiveInferenceIntervalMs(profile: PerformanceProfile): Long {
        val latencyGuardMs = (smoothedInferenceMs * CPU_DUTY_MULTIPLIER).toLong()
        return min(
            MAX_ADAPTIVE_INFERENCE_INTERVAL_MS,
            max(profile.frameIntervalMs, latencyGuardMs)
        )
    }

    fun decide(
        motion: MotionEstimate,
        settings: ProtectionSettings,
        hasActiveBlurredTracks: Boolean,
        awaitingCleanFrame: Boolean,
        nowMs: Long
    ): ScheduleDecision {
        if (awaitingCleanFrame) {
            return ScheduleDecision(
                action = ScheduleAction.RUN_FULL_INFERENCE,
                nextDelayMs = effectiveInferenceIntervalMs(settings.performanceProfile)
            )
        }

        if (motion.state == MotionState.VIDEO_MOTION && !settings.protectVideos) {
            return ScheduleDecision(
                action = ScheduleAction.SKIP_DISABLED_MEDIA_MODE,
                nextDelayMs = 450L
            )
        }
        if (motion.state == MotionState.STATIC && !settings.protectImages && !hasActiveBlurredTracks) {
            return ScheduleDecision(
                action = ScheduleAction.SKIP_DISABLED_MEDIA_MODE,
                nextDelayMs = 600L
            )
        }

        val inferenceInterval = effectiveInferenceIntervalMs(settings.performanceProfile)
        val elapsedSinceInference = if (lastInferenceTimestampMs == 0L) {
            Long.MAX_VALUE
        } else {
            nowMs - lastInferenceTimestampMs
        }
        val elapsedSinceClean = if (lastCleanKeyframeMs == 0L) {
            Long.MAX_VALUE
        } else {
            nowMs - lastCleanKeyframeMs
        }

        return when (motion.state) {
            MotionState.STATIC -> {
                if (lastInferenceTimestampMs == 0L && settings.protectImages) {
                    ScheduleDecision(
                        action = ScheduleAction.RUN_FULL_INFERENCE,
                        nextDelayMs = inferenceInterval
                    )
                } else {
                    ScheduleDecision(
                        action = ScheduleAction.HOLD_STATIC,
                        nextDelayMs = (settings.performanceProfile.frameIntervalMs * 1.45f).toLong()
                    )
                }
            }

            MotionState.SCENE_CUT -> {
                if (hasActiveBlurredTracks) {
                    ScheduleDecision(
                        action = ScheduleAction.REQUEST_CLEAN_KEYFRAME,
                        nextDelayMs = 45L
                    )
                } else {
                    ScheduleDecision(
                        action = ScheduleAction.RUN_FULL_INFERENCE,
                        nextDelayMs = inferenceInterval
                    )
                }
            }

            MotionState.SCROLLING -> {
                if (hasActiveBlurredTracks && elapsedSinceClean >= CLEAN_KEYFRAME_COOLDOWN_MS) {
                    ScheduleDecision(
                        action = ScheduleAction.REQUEST_CLEAN_KEYFRAME,
                        nextDelayMs = 45L
                    )
                } else if (!hasActiveBlurredTracks && elapsedSinceInference >= inferenceInterval) {
                    ScheduleDecision(
                        action = ScheduleAction.RUN_FULL_INFERENCE,
                        nextDelayMs = inferenceInterval
                    )
                } else {
                    ScheduleDecision(
                        action = ScheduleAction.PREDICT_TRACKS_ONLY,
                        nextDelayMs = TRACK_STEP_SCROLL_MS
                    )
                }
            }

            MotionState.VIDEO_MOTION -> {
                if (hasActiveBlurredTracks && elapsedSinceClean >= CLEAN_KEYFRAME_COOLDOWN_MS) {
                    ScheduleDecision(
                        action = ScheduleAction.REQUEST_CLEAN_KEYFRAME,
                        nextDelayMs = 45L
                    )
                } else if (elapsedSinceInference >= inferenceInterval) {
                    ScheduleDecision(
                        action = ScheduleAction.RUN_FULL_INFERENCE,
                        nextDelayMs = TRACK_STEP_VIDEO_MS
                    )
                } else {
                    ScheduleDecision(
                        action = ScheduleAction.PREDICT_TRACKS_ONLY,
                        nextDelayMs = TRACK_STEP_VIDEO_MS
                    )
                }
            }

            MotionState.MINOR_CHANGE -> {
                if (elapsedSinceInference >= inferenceInterval) {
                    ScheduleDecision(
                        action = ScheduleAction.RUN_FULL_INFERENCE,
                        nextDelayMs = inferenceInterval
                    )
                } else {
                    ScheduleDecision(
                        action = ScheduleAction.PREDICT_TRACKS_ONLY,
                        nextDelayMs = TRACK_STEP_VIDEO_MS
                    )
                }
            }
        }
    }
}
