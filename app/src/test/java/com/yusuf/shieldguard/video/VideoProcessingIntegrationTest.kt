package com.yusuf.shieldguard.video

import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.ModelHealth
import com.yusuf.shieldguard.ai.ModelLoadStatus
import com.yusuf.shieldguard.ai.PixelRect
import com.yusuf.shieldguard.ai.RawRegionProposal
import com.yusuf.shieldguard.ai.RegionClassifier
import com.yusuf.shieldguard.ai.RegionDetector
import com.yusuf.shieldguard.ai.VisualContentAnalyzer
import com.yusuf.shieldguard.capture.FrameCapturer
import com.yusuf.shieldguard.data.PerformanceProfile
import com.yusuf.shieldguard.data.ProtectionRuntimeState
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.data.ProtectionStateHolder
import com.yusuf.shieldguard.overlay.MosaicPatch
import com.yusuf.shieldguard.overlay.OverlayRenderer
import com.yusuf.shieldguard.service.ScreenProcessingCoordinator
import com.yusuf.shieldguard.service.TickOutcome
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoProcessingIntegrationTest {

    private class SequenceCapturer(private val frames: List<FrameData>) : FrameCapturer {
        private var cursor = 0
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2340
        override fun ensureDownscaleWidth(targetWidthPx: Int) {}
        override fun acquireLatestFrame(): FrameData? {
            if (cursor >= frames.size) return frames.lastOrNull()
            return frames[cursor++]
        }
        override fun release() {}
    }

    private class RecordingOverlay : OverlayRenderer {
        var currentPatches: List<MosaicPatch> = emptyList()
        var clearCount: Int = 0
        override fun getWindowOffset(): Pair<Int, Int> = Pair(0, 0)
        override fun showPatches(patches: List<MosaicPatch>) {
            currentPatches = patches
        }
        override fun clear() {
            currentPatches = emptyList()
            clearCount++
        }
        override fun detach() {}
    }

    @Before
    fun setup() {
        ProtectionStateHolder.resetForTest()
    }

    @Test
    fun tracksMovingVideoSubjectAcrossFramesWhileSkippingIntermediateInference() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val w = 200
        val h = 300

        val frames = listOf(
            FrameData(IntArray(w * h) { 0xFF304050.toInt() }, w, h, timestampMs = 1000L),
            FrameData(IntArray(w * h) { 0xFF425262.toInt() }, w, h, timestampMs = 1100L),
            FrameData(IntArray(w * h) { 0xFF546474.toInt() }, w, h, timestampMs = 1200L),
            FrameData(IntArray(w * h) { 0xFF667686.toInt() }, w, h, timestampMs = 1450L)
        )

        var inferenceRuns = 0
        val analyzer = VisualContentAnalyzer(
            regionDetector = object : RegionDetector {
                override val health = ModelHealth("Det", ModelLoadStatus.READY)
                override fun detect(frame: FrameData, minScore: Float): List<RawRegionProposal> {
                    inferenceRuns++
                    val xOffset = if (frame.timestampMs >= 1400L) 20 else 0
                    return listOf(RawRegionProposal(PixelRect(40 + xOffset, 60, 140 + xOffset, 220), 0.91f, "person"))
                }
                override fun close() {}
            },
            explicitClassifier = object : RegionClassifier {
                override val health = ModelHealth("Exp", ModelLoadStatus.READY)
                override fun classifyPositiveProbability(frame: FrameData, region: PixelRect) = 0.94f
                override fun close() {}
            },
            genderClassifier = null,
            inferenceDispatcher = dispatcher
        )

        val overlay = RecordingOverlay()
        val coordinator = ScreenProcessingCoordinator(
            ownPackageName = "com.yusuf.shieldguard",
            capturer = SequenceCapturer(frames),
            analyzer = analyzer,
            overlayRenderer = overlay
        )

        val settings = ProtectionSettings(
            protectImages = true,
            protectVideos = true,
            detectExplicitContent = true,
            performanceProfile = PerformanceProfile.BALANCED
        )
        val runtime = ProtectionRuntimeState(
            isForegroundServiceRunning = true,
            isScreenInteractive = true,
            activeForegroundPackage = "com.video.player",
            isCurrentWindowAccessible = true
        )

        val o0 = coordinator.processTick(settings, runtime)
        assertEquals(TickOutcome.ANALYZED_AND_RENDERED, o0)
        assertEquals(1, inferenceRuns)
        assertEquals(1, overlay.currentPatches.size)

        val o1 = coordinator.processTick(settings, runtime)
        val o2 = coordinator.processTick(settings, runtime)
        assertEquals(TickOutcome.TRACK_PREDICTED_AND_SHIFTED, o1)
        assertEquals(TickOutcome.TRACK_PREDICTED_AND_SHIFTED, o2)
        assertEquals(1, inferenceRuns)
        assertEquals(1, overlay.currentPatches.size)
        assertEquals(0, overlay.clearCount)

        val o3 = coordinator.processTick(settings, runtime)
        assertEquals(TickOutcome.ANALYZED_AND_RENDERED, o3)
        assertEquals(2, inferenceRuns)
        assertEquals(1, overlay.currentPatches.size)
        assertTrue(ProtectionStateHolder.state.value.skippedInferenceTicks >= 2L)
    }
}
