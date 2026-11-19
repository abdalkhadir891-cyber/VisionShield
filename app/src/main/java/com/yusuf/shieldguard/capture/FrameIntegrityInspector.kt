package com.yusuf.shieldguard.capture

import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.PixelRect
import kotlin.math.abs

class FrameIntegrityInspector(
    private val gridCols: Int = 32,
    private val gridRows: Int = 32
) {
    private val prevLuma = IntArray(gridCols * gridRows)
    private var hasBaseline = false

    fun resetBaseline() {
        hasBaseline = false
    }

    fun isLikelyProtectedBlackFrame(frame: FrameData): Boolean {
        val stepX = (frame.width / 16).coerceAtLeast(1)
        val stepY = (frame.height / 16).coerceAtLeast(1)
        var nonBlackSamples = 0

        var y = stepY / 2
        while (y < frame.height) {
            val rowOffset = y * frame.width
            var x = stepX / 2
            while (x < frame.width) {
                val argb = frame.pixels[rowOffset + x]
                val rgb = argb and 0x00FFFFFF
                if (rgb != 0) {
                    nonBlackSamples++
                    if (nonBlackSamples >= 3) return false
                }
                x += stepX
            }
            y += stepY
        }
        return true
    }

    fun computeOutsideMeanDiffAndUpdate(
        frame: FrameData,
        excludedCaptureRegions: List<PixelRect>,
        marginPx: Int = 6
    ): Float {
        var diffSum = 0L
        var validSamples = 0

        val startRow = (gridRows * 0.06f).toInt().coerceAtLeast(1)

        for (gy in startRow until gridRows) {
            val py = (gy * frame.height) / gridRows
            val rowOffset = py * frame.width
            val gridRowOffset = gy * gridCols

            for (gx in 0 until gridCols) {
                val px = (gx * frame.width) / gridCols
                if (isInsideAny(px, py, excludedCaptureRegions, marginPx)) {
                    continue
                }

                val argb = frame.pixels[rowOffset + px]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val luma = (r * 2 + g * 5 + b) shr 3
                val idx = gridRowOffset + gx

                if (hasBaseline) {
                    diffSum += abs(luma - prevLuma[idx])
                }
                prevLuma[idx] = luma
                validSamples++
            }
        }

        val hadBaseline = hasBaseline
        hasBaseline = true

        if (!hadBaseline || validSamples < 16) {
            return 255f
        }
        return diffSum.toFloat() / validSamples.toFloat()
    }

    private fun isInsideAny(x: Int, y: Int, rects: List<PixelRect>, margin: Int): Boolean {
        for (i in rects.indices) {
            val r = rects[i]
            if (x >= r.left - margin && x <= r.right + margin &&
                y >= r.top - margin && y <= r.bottom + margin
            ) {
                return true
            }
        }
        return false
    }
}
