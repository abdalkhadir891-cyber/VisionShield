package com.yusuf.shieldguard.overlay

import com.yusuf.shieldguard.ai.DetectionCategory
import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.PixelRect
import kotlin.math.max
import kotlin.math.roundToInt

data class MosaicPatch(
    val captureBounds: PixelRect,
    val overlayBounds: PixelRect,
    val cols: Int,
    val rows: Int,
    val cellColors: IntArray,
    val category: DetectionCategory,
    val confidence: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MosaicPatch) return false
        return captureBounds == other.captureBounds &&
            overlayBounds == other.overlayBounds &&
            cols == other.cols &&
            rows == other.rows &&
            category == other.category &&
            confidence == other.confidence &&
            cellColors.contentEquals(other.cellColors)
    }

    override fun hashCode(): Int {
        var result = captureBounds.hashCode()
        result = 31 * result + overlayBounds.hashCode()
        result = 31 * result + cols
        result = 31 * result + rows
        result = 31 * result + cellColors.contentHashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }
}

object OverlayCoordinateMapper {

    fun captureRectToOverlayRect(
        captureRect: PixelRect,
        captureW: Int,
        captureH: Int,
        screenW: Int,
        screenH: Int,
        overlayOffsetX: Int,
        overlayOffsetY: Int
    ): PixelRect {
        val scaleX = screenW.toFloat() / max(1, captureW).toFloat()
        val scaleY = screenH.toFloat() / max(1, captureH).toFloat()

        val l = (captureRect.left * scaleX).roundToInt() - overlayOffsetX
        val t = (captureRect.top * scaleY).roundToInt() - overlayOffsetY
        val r = (captureRect.right * scaleX).roundToInt() - overlayOffsetX
        val b = (captureRect.bottom * scaleY).roundToInt() - overlayOffsetY

        return PixelRect(
            left = l,
            top = t,
            right = max(l + 2, r),
            bottom = max(t + 2, b)
        )
    }
}

object MosaicSampler {

    fun computeGridSize(
        overlayBounds: PixelRect,
        blurIntensity: Int
    ): Pair<Int, Int> {
        val clampedIntensity = blurIntensity.coerceIn(4, 24)
        val cols = (16 - ((clampedIntensity - 4) * 10) / 20).coerceIn(6, 16)
        val aspect = overlayBounds.height.toFloat() / max(1, overlayBounds.width).toFloat()
        val rows = (cols * aspect).roundToInt().coerceIn(6, 22)
        return Pair(cols, rows)
    }

    fun buildMosaicPatch(
        frame: FrameData,
        captureBounds: PixelRect,
        overlayBounds: PixelRect,
        blurIntensity: Int,
        category: DetectionCategory,
        confidence: Float
    ): MosaicPatch {
        val clampedCap = captureBounds.clampTo(frame.width, frame.height)
        val (cols, rows) = computeGridSize(overlayBounds, blurIntensity)
        val colors = IntArray(cols * rows)

        val bw = max(1, clampedCap.width)
        val bh = max(1, clampedCap.height)
        val maxX = max(0, frame.width - 1)
        val maxY = max(0, frame.height - 1)

        for (cy in 0 until rows) {
            val centerY = (clampedCap.top + ((cy * 2 + 1) * bh) / (rows * 2)).coerceIn(0, maxY)
            val rowOut = cy * cols

            for (cx in 0 until cols) {
                val centerX = (clampedCap.left + ((cx * 2 + 1) * bw) / (cols * 2)).coerceIn(0, maxX)

                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0

                for (dy in -1..1) {
                    val sy = (centerY + dy).coerceIn(0, maxY)
                    val rowOffset = sy * frame.width
                    for (dx in -1..1) {
                        val sx = (centerX + dx).coerceIn(0, maxX)
                        val argb = frame.pixels[rowOffset + sx]
                        rSum += (argb shr 16) and 0xFF
                        gSum += (argb shr 8) and 0xFF
                        bSum += argb and 0xFF
                        count++
                    }
                }

                val rAvg = rSum / count
                val gAvg = gSum / count
                val bAvg = bSum / count
                colors[rowOut + cx] = (0xFF shl 24) or (rAvg shl 16) or (gAvg shl 8) or bAvg
            }
        }

        return MosaicPatch(
            captureBounds = clampedCap,
            overlayBounds = overlayBounds,
            cols = cols,
            rows = rows,
            cellColors = colors,
            category = category,
            confidence = confidence
        )
    }
}
