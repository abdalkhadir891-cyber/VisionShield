package com.yusuf.shieldguard.ai

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PixelPreprocessor {

    private const val PAD_GRAY = 114

    data class LetterboxGeometry(
        val scaledWidth: Int,
        val scaledHeight: Int,
        val padLeft: Int,
        val padTop: Int
    )

    fun computeLetterboxGeometry(cropWidth: Int, cropHeight: Int, targetDim: Int): LetterboxGeometry {
        val bw = max(1, cropWidth)
        val bh = max(1, cropHeight)
        val scale = min(targetDim.toFloat() / bw.toFloat(), targetDim.toFloat() / bh.toFloat())
        val nw = max(1, (bw * scale).toInt())
        val nh = max(1, (bh * scale).toInt())
        val padX = (targetDim - nw) / 2
        val padY = (targetDim - nh) / 2
        return LetterboxGeometry(nw, nh, padX, padY)
    }

    fun fillLetterboxBuffer(
        pixels: IntArray,
        frameW: Int,
        frameH: Int,
        crop: PixelRect,
        targetDim: Int,
        dataType: TensorDataType,
        normalization: InputNormalization,
        quantScale: Float = 1f,
        quantZeroPoint: Int = 0,
        outBuffer: ByteBuffer
    ) {
        val clamped = crop.clampTo(frameW, frameH)
        val bw = clamped.width
        val bh = clamped.height
        val geom = computeLetterboxGeometry(bw, bh, targetDim)

        outBuffer.clear()

        for (y in 0 until targetDim) {
            val inY = y - geom.padTop
            val validY = inY in 0 until geom.scaledHeight
            val srcY = if (validY) {
                (clamped.top + (inY * bh) / geom.scaledHeight).coerceIn(0, frameH - 1)
            } else {
                0
            }
            val rowOffset = srcY * frameW

            for (x in 0 until targetDim) {
                val inX = x - geom.padLeft
                val r: Int
                val g: Int
                val b: Int
                if (validY && (inX in 0 until geom.scaledWidth)) {
                    val srcX = (clamped.left + (inX * bw) / geom.scaledWidth).coerceIn(0, frameW - 1)
                    val argb = pixels[rowOffset + srcX]
                    r = (argb shr 16) and 0xFF
                    g = (argb shr 8) and 0xFF
                    b = argb and 0xFF
                } else {
                    r = PAD_GRAY
                    g = PAD_GRAY
                    b = PAD_GRAY
                }

                writeRgb(outBuffer, r, g, b, dataType, normalization, quantScale, quantZeroPoint)
            }
        }

        outBuffer.rewind()
    }

    private fun writeRgb(
        buf: ByteBuffer,
        r: Int,
        g: Int,
        b: Int,
        dataType: TensorDataType,
        normalization: InputNormalization,
        quantScale: Float,
        quantZeroPoint: Int
    ) {
        when (dataType) {
            TensorDataType.FLOAT32 -> {
                buf.putFloat(normalizeChannel(r, normalization))
                buf.putFloat(normalizeChannel(g, normalization))
                buf.putFloat(normalizeChannel(b, normalization))
            }
            TensorDataType.UINT8 -> {
                buf.put(r.toByte())
                buf.put(g.toByte())
                buf.put(b.toByte())
            }
            TensorDataType.INT8 -> {
                if (quantScale > 0f && quantScale != 1f) {
                    val rq = (normalizeChannel(r, normalization) / quantScale + quantZeroPoint).roundToInt().coerceIn(-128, 127)
                    val gq = (normalizeChannel(g, normalization) / quantScale + quantZeroPoint).roundToInt().coerceIn(-128, 127)
                    val bq = (normalizeChannel(b, normalization) / quantScale + quantZeroPoint).roundToInt().coerceIn(-128, 127)
                    buf.put(rq.toByte())
                    buf.put(gq.toByte())
                    buf.put(bq.toByte())
                } else {
                    buf.put((r - 128).coerceIn(-128, 127).toByte())
                    buf.put((g - 128).coerceIn(-128, 127).toByte())
                    buf.put((b - 128).coerceIn(-128, 127).toByte())
                }
            }
        }
    }

    private fun normalizeChannel(value: Int, mode: InputNormalization): Float {
        return when (mode) {
            InputNormalization.RAW_0_TO_255 -> value.toFloat()
            InputNormalization.ZERO_TO_ONE -> value / 255.0f
            InputNormalization.MINUS_ONE_TO_ONE -> (value / 127.5f) - 1.0f
        }
    }
}
