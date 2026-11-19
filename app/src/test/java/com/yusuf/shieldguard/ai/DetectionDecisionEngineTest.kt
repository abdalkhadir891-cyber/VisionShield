package com.yusuf.shieldguard.ai

import com.yusuf.shieldguard.data.ProtectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionDecisionEngineTest {

    @Test
    fun maleHeadVetoPreventsBlurringManEvenWithModerateFullScore() {
        val settings = ProtectionSettings(
            sensitivityPercent = 65,
            detectExplicitContent = false,
            detectGenderSpecific = true
        )
        val decision = DetectionDecisionEngine.decideRegion(
            bounds = PixelRect(100, 100, 300, 500),
            explicitScore = null,
            headFemaleScore = 0.04f,
            fullFemaleScore = 0.48f,
            settings = settings
        )

        assertFalse(decision.shouldBlur)
        assertEquals(DetectionCategory.SAFE_PERSON, decision.category)
    }

    @Test
    fun trimsBlurredWomanBoxWhenOverlappingAdjacentSafeMan() {
        val woman = EvaluatedRegion(
            bounds = PixelRect(20, 100, 260, 500),
            renderBounds = PixelRect(20, 100, 260, 500),
            category = DetectionCategory.GENDER_FEMALE,
            confidence = 0.94f,
            shouldBlur = true
        )
        val man = EvaluatedRegion(
            bounds = PixelRect(220, 100, 440, 500),
            renderBounds = PixelRect(220, 100, 440, 500),
            category = DetectionCategory.SAFE_PERSON,
            confidence = 0.96f,
            shouldBlur = false
        )

        val trimmed = DetectionDecisionEngine.trimOverlapWithSafeRegions(listOf(woman, man))
        val trimmedWoman = trimmed[0]
        assertTrue(trimmedWoman.renderBounds.right < man.bounds.left)
        assertEquals(218, trimmedWoman.renderBounds.right)
    }

    @Test
    fun capsOversizedRegionToPreventFullScreenBlurExplosion() {
        val hugeRaw = PixelRect(0, 0, 480, 900)
        val capped = DetectionDecisionEngine.expandAndCapRegion(hugeRaw, frameW = 480, frameH = 900)
        val ratio = capped.area.toFloat() / (480f * 900f)
        assertTrue("Expected area ratio <= 0.56, got $ratio", ratio <= 0.56f)
    }

    @Test
    fun nmsDropsDuplicateOverlappingProposals() {
        val p1 = RawRegionProposal(PixelRect(50, 50, 200, 300), 0.91f, "person")
        val p2 = RawRegionProposal(PixelRect(55, 52, 205, 305), 0.72f, "person")
        val p3 = RawRegionProposal(PixelRect(260, 50, 420, 300), 0.88f, "person")

        val kept = DetectionDecisionEngine.suppressOverlappingProposals(listOf(p1, p2, p3), 480, 800)
        assertEquals(2, kept.size)
        assertEquals(0.91f, kept[0].detectorConfidence, 0.001f)
        assertEquals(0.88f, kept[1].detectorConfidence, 0.001f)
    }
}
