package com.yusuf.shieldguard.overlay

import com.yusuf.shieldguard.ai.DetectionCategory
import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicSamplerAndMapperTest {

    @Test
    fun mapsCaptureRectToOverlayCoordinatesWithWindowOffset() {
        val mapped = OverlayCoordinateMapper.captureRectToOverlayRect(
            captureRect = PixelRect(50, 100, 150, 300),
            captureW = 200,
            captureH = 400,
            screenW = 1000,
            screenH = 2000,
            overlayOffsetX = 0,
            overlayOffsetY = 50
        )

        assertEquals(250, mapped.left)
        assertEquals(450, mapped.top)
        assertEquals(750, mapped.right)
        assertEquals(1450, mapped.bottom)
    }

    @Test
    fun higherBlurIntensityProducesCoarserMosaicGrid() {
        val box = PixelRect(0, 0, 300, 300)
        val light = MosaicSampler.computeGridSize(box, blurIntensity = 4)
        val heavy = MosaicSampler.computeGridSize(box, blurIntensity = 24)

        assertTrue(heavy.first < light.first)
        assertEquals(heavy.first, heavy.second)
    }

    @Test
    fun samplesExpectedSolidColorFromFrameData() {
        val teal = (0xFF shl 24) or (40 shl 16) or (160 shl 8) or 180
        val frame = FrameData(IntArray(100 * 100) { teal }, 100, 100, 500L)
        val patch = MosaicSampler.buildMosaicPatch(
            frame = frame,
            captureBounds = PixelRect(10, 10, 90, 90),
            overlayBounds = PixelRect(100, 100, 500, 500),
            blurIntensity = 12,
            category = DetectionCategory.GENDER_FEMALE,
            confidence = 0.92f
        )

        assertEquals(patch.cols * patch.rows, patch.cellColors.size)
        assertEquals(teal, patch.cellColors[0])
    }
}
