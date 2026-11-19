package com.yusuf.shieldguard.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PixelPreprocessorTest {

    @Test
    fun letterboxGeometryPreservesAspectRatio() {
        val geom = PixelPreprocessor.computeLetterboxGeometry(200, 100, 100)
        assertEquals(100, geom.scaledWidth)
        assertEquals(50, geom.scaledHeight)
        assertEquals(0, geom.padLeft)
        assertEquals(25, geom.padTop)
    }

    @Test
    fun fillLetterboxBufferRewindsPositionToZeroAndWritesExpectedRgb() {
        val frameW = 4
        val frameH = 4
        val packedRed = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50
        val pixels = IntArray(frameW * frameH) { packedRed }

        val targetDim = 2
        val buf = ByteBuffer.allocateDirect(targetDim * targetDim * 3 * 4).order(ByteOrder.nativeOrder())

        PixelPreprocessor.fillLetterboxBuffer(
            pixels = pixels,
            frameW = frameW,
            frameH = frameH,
            crop = PixelRect(0, 0, frameW, frameH),
            targetDim = targetDim,
            dataType = TensorDataType.FLOAT32,
            normalization = InputNormalization.RAW_0_TO_255,
            outBuffer = buf
        )

        assertEquals(0, buf.position())
        assertEquals(200f, buf.getFloat(0), 0.01f)
        assertEquals(100f, buf.getFloat(4), 0.01f)
        assertEquals(50f, buf.getFloat(8), 0.01f)
    }

    @Test
    fun uint8BufferWritesPaddingGrayAndRewinds() {
        val pixels = IntArray(4 * 2) { (0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30 }
        val targetDim = 4
        val buf = ByteBuffer.allocateDirect(targetDim * targetDim * 3).order(ByteOrder.nativeOrder())

        PixelPreprocessor.fillLetterboxBuffer(
            pixels = pixels,
            frameW = 4,
            frameH = 2,
            crop = PixelRect(0, 0, 4, 2),
            targetDim = targetDim,
            dataType = TensorDataType.UINT8,
            normalization = InputNormalization.RAW_0_TO_255,
            outBuffer = buf
        )

        assertEquals(0, buf.position())
        assertEquals(114, buf.get(0).toInt() and 0xFF)
        assertEquals(114, buf.get(1).toInt() and 0xFF)
        assertEquals(114, buf.get(2).toInt() and 0xFF)
    }
}
