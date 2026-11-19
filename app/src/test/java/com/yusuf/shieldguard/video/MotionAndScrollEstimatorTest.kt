package com.yusuf.shieldguard.video

import com.yusuf.shieldguard.ai.FrameData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionAndScrollEstimatorTest {

    private fun buildStripedFrame(width: Int, height: Int, shiftRowsPx: Int, timestampMs: Long): FrameData {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val shiftedY = (y - shiftRowsPx).coerceIn(0, height - 1)
            val band = ((shiftedY / 20) % 4) * 70 + 20
            val argb = (0xFF shl 24) or (band shl 16) or (band shl 8) or band
            val rowOff = y * width
            for (x in 0 until width) {
                pixels[rowOff + x] = argb
            }
        }
        return FrameData(pixels, width, height, timestampMs)
    }

    @Test
    fun detectsVerticalScrollAndRecoversDisplacementSign() {
        val estimator = MotionAndScrollEstimator(profileRows = 48, sampleCols = 8, maxRowLag = 8)
        val w = 160
        val h = 480

        val frame0 = buildStripedFrame(w, h, shiftRowsPx = 0, timestampMs = 100L)
        val est0 = estimator.analyzeMotion(frame0, outsideMeanDiff = 255f, excludedCaptureRegions = emptyList())
        assertEquals(MotionState.SCENE_CUT, est0.state)

        val frame1 = buildStripedFrame(w, h, shiftRowsPx = 30, timestampMs = 200L)
        val est1 = estimator.analyzeMotion(frame1, outsideMeanDiff = 25f, excludedCaptureRegions = emptyList())

        assertEquals(MotionState.SCROLLING, est1.state)
        assertTrue("Expected positive downward shift near +30px, got ${est1.scrollDyCapturePx}", est1.scrollDyCapturePx in 20..40)
    }

    @Test
    fun classifiesConsecutiveNonScrollChangesAsVideoMotion() {
        val estimator = MotionAndScrollEstimator()
        val w = 160
        val h = 320

        val f0 = FrameData(IntArray(w * h) { 0xFF404040.toInt() }, w, h, 100L)
        val f1 = FrameData(IntArray(w * h) { 0xFF555555.toInt() }, w, h, 200L)
        val f2 = FrameData(IntArray(w * h) { 0xFF6A6A6A.toInt() }, w, h, 300L)

        estimator.analyzeMotion(f0, outsideMeanDiff = 255f, excludedCaptureRegions = emptyList())
        val firstChange = estimator.analyzeMotion(f1, outsideMeanDiff = 14f, excludedCaptureRegions = emptyList())
        val secondChange = estimator.analyzeMotion(f2, outsideMeanDiff = 14f, excludedCaptureRegions = emptyList())

        assertEquals(MotionState.MINOR_CHANGE, firstChange.state)
        assertEquals(MotionState.VIDEO_MOTION, secondChange.state)
        assertEquals(2, secondChange.consecutiveMotionFrames)
    }
}
