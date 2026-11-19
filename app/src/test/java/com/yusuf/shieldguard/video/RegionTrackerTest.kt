package com.yusuf.shieldguard.video

import com.yusuf.shieldguard.ai.DetectionCategory
import com.yusuf.shieldguard.ai.EvaluatedRegion
import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionTrackerTest {

    @Test
    fun hysteresisHoldsBlurAcrossBriefNegativeGlitchesAndClearsAfterThreeNegatives() {
        val tracker = RegionTracker(positiveThreshold = 1, negativeThreshold = 3, holdDurationMs = 2000L)
        val frame = FrameData(IntArray(400 * 600) { 0xFF776655.toInt() }, 400, 600, 1000L)
        val box = PixelRect(50, 100, 200, 350)

        val positive = EvaluatedRegion(box, box, DetectionCategory.GENDER_FEMALE, 0.92f, shouldBlur = true)
        val negative = EvaluatedRegion(box, box, DetectionCategory.SAFE_PERSON, 0.15f, shouldBlur = false)

        val p1 = tracker.updateWithEvaluations(listOf(positive), frame, true, 12, 1080, 2340, 0, 0, 1000L)
        assertEquals(1, p1.size)

        val p2 = tracker.updateWithEvaluations(listOf(negative), frame, true, 12, 1080, 2340, 0, 0, 1300L)
        assertEquals(1, p2.size)
        val p3 = tracker.updateWithEvaluations(listOf(negative), frame, true, 12, 1080, 2340, 0, 0, 1600L)
        assertEquals(1, p3.size)

        val p4 = tracker.updateWithEvaluations(listOf(negative), frame, true, 12, 1080, 2340, 0, 0, 1900L)
        assertEquals(0, p4.size)
    }

    @Test
    fun predictsVelocityAndShiftsCachedPatchSmoothlyDuringScroll() {
        val tracker = RegionTracker()
        val frame = FrameData(IntArray(400 * 600) { 0xFF336699.toInt() }, 400, 600, 1000L)
        val box1 = PixelRect(80, 200, 200, 400)
        val det1 = EvaluatedRegion(box1, box1, DetectionCategory.EXPLICIT_CONTENT, 0.90f, shouldBlur = true)

        val initial = tracker.updateWithEvaluations(listOf(det1), frame, true, 12, 800, 1200, 0, 0, 1000L)
        assertEquals(1, initial.size)
        val initialTop = initial[0].overlayBounds.top

        val shifted = tracker.predictAndShiftTracks(
            nowMs = 1100L,
            scrollDyCapturePx = -40,
            captureW = 400,
            captureH = 600,
            screenW = 800,
            screenH = 1200,
            overlayOffsetX = 0,
            overlayOffsetY = 0
        )
        assertEquals(1, shifted.size)
        assertTrue(shifted[0].overlayBounds.top < initialTop)
        assertEquals(initialTop - 80, shifted[0].overlayBounds.top)
    }

    @Test
    fun expiresGhostTracksAfterHoldDurationWithoutSupport() {
        val tracker = RegionTracker(holdDurationMs = 800L)
        val frame = FrameData(IntArray(400 * 600) { 0xFF444444.toInt() }, 400, 600, 1000L)
        val box = PixelRect(50, 100, 200, 300)
        val det = EvaluatedRegion(box, box, DetectionCategory.GENDER_FEMALE, 0.90f, shouldBlur = true)

        tracker.updateWithEvaluations(listOf(det), frame, true, 12, 800, 1200, 0, 0, 1000L)
        assertEquals(1, tracker.activeTrackCount())

        val afterTimeout = tracker.predictAndShiftTracks(2000L, 0, 400, 600, 800, 1200, 0, 0)
        assertEquals(0, afterTimeout.size)
        assertEquals(0, tracker.activeTrackCount())
    }
}
