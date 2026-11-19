package com.yusuf.shieldguard.capture

import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameIntegrityInspectorTest {

    @Test
    fun detectsCompletelyBlackProtectedFrame() {
        val inspector = FrameIntegrityInspector()
        val blackFrame = FrameData(IntArray(160 * 160) { 0xFF000000.toInt() }, 160, 160, 100L)
        assertTrue(inspector.isLikelyProtectedBlackFrame(blackFrame))

        val normalPixels = IntArray(160 * 160) { 0xFF336699.toInt() }
        val normalFrame = FrameData(normalPixels, 160, 160, 200L)
        assertFalse(inspector.isLikelyProtectedBlackFrame(normalFrame))
    }

    @Test
    fun ignoresChangesInsideCoveredOverlayRegion() {
        val inspector = FrameIntegrityInspector(gridCols = 16, gridRows = 16)
        val w = 160
        val h = 160
        val basePixels = IntArray(w * h) { 0xFF808080.toInt() }
        val coveredBox = PixelRect(40, 40, 120, 120)

        val firstDiff = inspector.computeOutsideMeanDiffAndUpdate(
            FrameData(basePixels, w, h, 100L),
            emptyList()
        )
        assertEquals(255f, firstDiff, 0.01f)

        val mutatedInside = basePixels.copyOf()
        for (y in 45 until 115) {
            for (x in 45 until 115) {
                mutatedInside[y * w + x] = 0xFFFFFFFF.toInt()
            }
        }
        val insideOnlyDiff = inspector.computeOutsideMeanDiffAndUpdate(
            FrameData(mutatedInside, w, h, 200L),
            listOf(coveredBox)
        )
        assertEquals(0f, insideOnlyDiff, 0.01f)
    }
}
