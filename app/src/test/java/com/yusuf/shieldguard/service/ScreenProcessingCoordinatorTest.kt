package com.yusuf.shieldguard.service

import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.ModelHealth
import com.yusuf.shieldguard.ai.ModelLoadStatus
import com.yusuf.shieldguard.ai.PixelRect
import com.yusuf.shieldguard.ai.RawRegionProposal
import com.yusuf.shieldguard.ai.RegionClassifier
import com.yusuf.shieldguard.ai.RegionDetector
import com.yusuf.shieldguard.ai.VisualContentAnalyzer
import com.yusuf.shieldguard.capture.FrameCapturer
import com.yusuf.shieldguard.data.ProtectionRuntimeState
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.data.ProtectionStateHolder
import com.yusuf.shieldguard.overlay.MosaicPatch
import com.yusuf.shieldguard.overlay.OverlayRenderer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenProcessingCoordinatorTest {

    private class FakeCapturer(var nextFrame: FrameData?) : FrameCapturer {
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2340
        override fun ensureDownscaleWidth(targetWidthPx: Int) {}
        override fun acquireLatestFrame(): FrameData? = nextFrame
        override fun release() {}
    }

    private class FakeOverlay : OverlayRenderer {
        var renderedPatches: List<MosaicPatch> = emptyList()
        var clearCalls: Int = 0
        override fun getWindowOffset(): Pair<Int, Int> = Pair(0, 0)
        override fun showPatches(patches: List<MosaicPatch>) {
            renderedPatches = patches
        }
        override fun clear() {
            renderedPatches = emptyList()
            clearCalls++
        }
        override fun detach() {}
    }

    @Before
    fun resetState() {
        ProtectionStateHolder.resetForTest()
    }

    @Test
    fun pausesAndClearsOverlayWhenExcludedAppIsActive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val overlay = FakeOverlay()
        val capturer = FakeCapturer(FrameData(IntArray(100 * 100) { 0xFF555555.toInt() }, 100, 100, 10L))
        val analyzer = VisualContentAnalyzer(
            regionDetector = object : RegionDetector {
                override val health = ModelHealth("Det", ModelLoadStatus.READY)
                override fun detect(frame: FrameData, minScore: Float) = emptyList<RawRegionProposal>()
                override fun close() {}
            },
            explicitClassifier = null,
            genderClassifier = null,
            inferenceDispatcher = dispatcher
        )

        val coordinator = ScreenProcessingCoordinator("com.yusuf.shieldguard", capturer, analyzer, overlay)
        val settings = ProtectionSettings(excludedPackages = setOf("com.bank.app"))
        val runtime = ProtectionRuntimeState(
            isForegroundServiceRunning = true,
            isScreenInteractive = true,
            activeForegroundPackage = "com.bank.app",
            isCurrentWindowAccessible = true
        )

        val outcome = coordinator.processTick(settings, runtime)
        assertEquals(TickOutcome.PAUSED_INACTIVE_OR_EXCLUDED, outcome)
        assertTrue(overlay.renderedPatches.isEmpty())
    }

    @Test
    fun detectsBlackProtectedFrameAndIncrementsProtectedCounter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val overlay = FakeOverlay()
        val blackFrame = FrameData(IntArray(100 * 100) { 0xFF000000.toInt() }, 100, 100, 20L)
        val capturer = FakeCapturer(blackFrame)
        val analyzer = VisualContentAnalyzer(
            regionDetector = object : RegionDetector {
                override val health = ModelHealth("Det", ModelLoadStatus.READY)
                override fun detect(frame: FrameData, minScore: Float) = emptyList<RawRegionProposal>()
                override fun close() {}
            },
            explicitClassifier = null,
            genderClassifier = null,
            inferenceDispatcher = dispatcher
        )

        val coordinator = ScreenProcessingCoordinator("com.yusuf.shieldguard", capturer, analyzer, overlay)
        val settings = ProtectionSettings(detectExplicitContent = true)
        val runtime = ProtectionRuntimeState(
            isForegroundServiceRunning = true,
            isScreenInteractive = true,
            activeForegroundPackage = "com.drm.video",
            isCurrentWindowAccessible = true
        )

        val outcome = coordinator.processTick(settings, runtime)
        assertEquals(TickOutcome.PROTECTED_WINDOW_SKIPPED, outcome)
        assertEquals(1L, ProtectionStateHolder.state.value.protectedWindowEvents)
        assertTrue(ProtectionStateHolder.state.value.isProtectedBlackFrame)
    }

    @Test
    fun rendersMosaicThenHoldsStaticSceneWithoutRerunningInference() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val overlay = FakeOverlay()
        val normalFrame = FrameData(IntArray(200 * 200) { 0xFF4488AA.toInt() }, 200, 200, 30L)
        val capturer = FakeCapturer(normalFrame)
        var detectCalls = 0

        val analyzer = VisualContentAnalyzer(
            regionDetector = object : RegionDetector {
                override val health = ModelHealth("Det", ModelLoadStatus.READY)
                override fun detect(frame: FrameData, minScore: Float): List<RawRegionProposal> {
                    detectCalls++
                    return listOf(RawRegionProposal(PixelRect(20, 20, 120, 160), 0.90f, "person"))
                }
                override fun close() {}
            },
            explicitClassifier = object : RegionClassifier {
                override val health = ModelHealth("Exp", ModelLoadStatus.READY)
                override fun classifyPositiveProbability(frame: FrameData, region: PixelRect) = 0.95f
                override fun close() {}
            },
            genderClassifier = null,
            inferenceDispatcher = dispatcher
        )

        val coordinator = ScreenProcessingCoordinator("com.yusuf.shieldguard", capturer, analyzer, overlay)
        val settings = ProtectionSettings(sensitivityPercent = 60, detectExplicitContent = true)
        val runtime = ProtectionRuntimeState(
            isForegroundServiceRunning = true,
            isScreenInteractive = true,
            activeForegroundPackage = "com.social.feed",
            isCurrentWindowAccessible = true
        )

        val firstOutcome = coordinator.processTick(settings, runtime)
        assertEquals(TickOutcome.ANALYZED_AND_RENDERED, firstOutcome)
        assertEquals(1, overlay.renderedPatches.size)
        assertEquals(1, detectCalls)

        val secondOutcome = coordinator.processTick(settings, runtime)
        assertEquals(TickOutcome.STATIC_SCENE_HELD, secondOutcome)
        assertEquals(1, detectCalls)
        assertEquals(1, overlay.renderedPatches.size)
    }
}
