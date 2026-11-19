package com.yusuf.shieldguard.perf

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
import org.junit.Before
import org.junit.Test

class Phase5CompatibilityIntegrationTest {

    private class CapturerSpy : FrameCapturer {
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2340
        var lastRequestedWidth: Int = -1
        override fun ensureDownscaleWidth(targetWidthPx: Int) {
            lastRequestedWidth = targetWidthPx
        }
        override fun acquireLatestFrame(): FrameData =
            FrameData(IntArray(320 * 480) { 0xFF335577.toInt() }, 320, 480, 1000L)
        override fun release() {}
    }

    @Before
    fun reset() {
        ProtectionStateHolder.resetForTest()
    }

    @Test
    fun criticalGovernorCapsDownscaleAndDisablesSecondaryClassifierToProtectRam() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val capturer = CapturerSpy()
        var genderCalls = 0
        var explicitCalls = 0

        val analyzer = VisualContentAnalyzer(
            regionDetector = object : RegionDetector {
                override val health = ModelHealth("Det", ModelLoadStatus.READY)
                override fun detect(frame: FrameData, minScore: Float) =
                    listOf(RawRegionProposal(PixelRect(40, 40, 200, 320), 0.90f, "person"))
                override fun close() {}
            },
            explicitClassifier = object : RegionClassifier {
                override val health = ModelHealth("Exp", ModelLoadStatus.READY)
                override fun classifyPositiveProbability(frame: FrameData, region: PixelRect): Float {
                    explicitCalls++
                    return 0.88f
                }
                override fun close() {}
            },
            genderClassifier = object : RegionClassifier {
                override val health = ModelHealth("Gen", ModelLoadStatus.READY)
                override fun classifyPositiveProbability(frame: FrameData, region: PixelRect): Float {
                    genderCalls++
                    return 0.90f
                }
                override fun close() {}
            },
            inferenceDispatcher = dispatcher
        )

        val overlay = object : OverlayRenderer {
            override fun getWindowOffset() = Pair(0, 0)
            override fun showPatches(patches: List<MosaicPatch>) {}
            override fun clear() {}
            override fun detach() {}
        }

        val coordinator = ScreenProcessingCoordinator("com.yusuf.shieldguard", capturer, analyzer, overlay)
        val settings = ProtectionSettings(
            detectExplicitContent = true,
            detectGenderSpecific = true,
            performanceProfile = PerformanceProfile.RESPONSIVE
        )
        val runtime = ProtectionRuntimeState(
            isForegroundServiceRunning = true,
            isScreenInteractive = true,
            activeForegroundPackage = "com.social.app",
            isCurrentWindowAccessible = true
        )

        val criticalAdjustment = GovernorAdjustment(
            level = GovernorLevel.CRITICAL_THROTTLE,
            maxDownscaleWidthPx = 320,
            intervalMultiplier = 2.0f,
            disableSecondaryClassifier = true
        )

        val outcome = coordinator.processTick(settings, runtime, criticalAdjustment)

        assertEquals(TickOutcome.ANALYZED_AND_RENDERED, outcome)
        assertEquals(320, capturer.lastRequestedWidth)
        assertEquals(1, explicitCalls)
        assertEquals(0, genderCalls)
        assertEquals("CRITICAL_THROTTLE", ProtectionStateHolder.state.value.governorStatus)
    }
}
