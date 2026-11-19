package com.yusuf.shieldguard.ai

import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.data.ProtectionStateHolder
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisualContentAnalyzerTest {

    private class FakeDetector(
        private val proposals: List<RawRegionProposal>,
        override val health: ModelHealth = ModelHealth("FakeDetector", ModelLoadStatus.READY)
    ) : RegionDetector {
        override fun detect(frame: FrameData, minScore: Float): List<RawRegionProposal> = proposals
        override fun close() {}
    }

    private class FakeClassifier(
        private val scoreForRegion: (PixelRect) -> Float,
        override val health: ModelHealth = ModelHealth("FakeClassifier", ModelLoadStatus.READY)
    ) : RegionClassifier {
        override fun classifyPositiveProbability(frame: FrameData, region: PixelRect): Float =
            scoreForRegion(region)

        override fun close() {}
    }

    @Before
    fun resetState() {
        ProtectionStateHolder.resetForTest()
    }

    @Test
    fun analyzesTwoAdjacentPersonsBlursFemaleAndPreservesMale() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val leftPerson = RawRegionProposal(PixelRect(20, 100, 230, 500), 0.90f, "person")
        val rightPerson = RawRegionProposal(PixelRect(220, 100, 430, 500), 0.92f, "person")

        val analyzer = VisualContentAnalyzer(
            regionDetector = FakeDetector(listOf(leftPerson, rightPerson)),
            explicitClassifier = FakeClassifier({ 0.05f }),
            genderClassifier = FakeClassifier({ rect ->
                if (rect.centerX < 225f) 0.95f else 0.03f
            }),
            inferenceDispatcher = dispatcher
        )

        val frame = FrameData(IntArray(480 * 800), 480, 800, timestampMs = 1000L)
        val settings = ProtectionSettings(
            sensitivityPercent = 60,
            detectExplicitContent = true,
            detectGenderSpecific = true
        )

        val result = analyzer.analyzeFrame(frame, settings)

        assertTrue(result.analyzedSuccessfully)
        assertEquals(2, result.regions.size)
        assertEquals(1, result.regionsToBlur.size)
        assertEquals(DetectionCategory.GENDER_FEMALE, result.regionsToBlur.first().category)
        assertEquals(1L, ProtectionStateHolder.state.value.framesAnalyzed)
    }

    @Test
    fun reportsFailureGracefullyWhenDetectorAssetIsMissing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val missingDetector = FakeDetector(
            proposals = emptyList(),
            health = ModelHealth("PersonDetector", ModelLoadStatus.MISSING_ASSET, "Missing file")
        )
        val analyzer = VisualContentAnalyzer(
            regionDetector = missingDetector,
            explicitClassifier = null,
            genderClassifier = null,
            inferenceDispatcher = dispatcher
        )

        val frame = FrameData(IntArray(320 * 320), 320, 320, timestampMs = 2000L)
        val result = analyzer.analyzeFrame(frame, ProtectionSettings())

        assertFalse(result.analyzedSuccessfully)
        assertEquals("Missing file", result.failureReason)
        assertEquals(0L, ProtectionStateHolder.state.value.framesAnalyzed)
    }
}
