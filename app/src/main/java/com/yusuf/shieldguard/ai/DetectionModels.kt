package com.yusuf.shieldguard.ai

import kotlin.math.max
import kotlin.math.min

enum class DetectionCategory {
    EXPLICIT_CONTENT,
    GENDER_FEMALE,
    SAFE_PERSON
}

enum class TensorDataType {
    FLOAT32,
    UINT8,
    INT8
}

enum class InputNormalization {
    RAW_0_TO_255,
    ZERO_TO_ONE,
    MINUS_ONE_TO_ONE
}

enum class ModelLoadStatus {
    READY,
    MISSING_ASSET,
    INIT_ERROR
}

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = max(0, right - left)
    val height: Int get() = max(0, bottom - top)
    val area: Int get() = width * height
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun isValid(): Boolean = right > left && bottom > top

    fun clampTo(frameW: Int, frameH: Int): PixelRect {
        val l = left.coerceIn(0, max(0, frameW - 1))
        val t = top.coerceIn(0, max(0, frameH - 1))
        val r = right.coerceIn(l + 1, max(1, frameW))
        val b = bottom.coerceIn(t + 1, max(1, frameH))
        return PixelRect(l, t, r, b)
    }

    fun iou(other: PixelRect): Float {
        val interL = max(left, other.left)
        val interT = max(top, other.top)
        val interR = min(right, other.right)
        val interB = min(bottom, other.bottom)
        if (interR <= interL || interB <= interT) return 0f
        val interArea = (interR - interL).toFloat() * (interB - interT).toFloat()
        val unionArea = (area + other.area).toFloat() - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}

class FrameData(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive." }
        require(pixels.size >= width * height) { "Pixel buffer smaller than width * height." }
    }
}

data class RawRegionProposal(
    val bounds: PixelRect,
    val detectorConfidence: Float,
    val label: String
)

data class EvaluatedRegion(
    val bounds: PixelRect,
    val renderBounds: PixelRect,
    val category: DetectionCategory,
    val confidence: Float,
    val shouldBlur: Boolean
)

data class ModelHealth(
    val name: String,
    val status: ModelLoadStatus,
    val detail: String = ""
)

data class FrameAnalysisResult(
    val timestampMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val regions: List<EvaluatedRegion>,
    val detectorLatencyMs: Float,
    val classifierLatencyMs: Float,
    val analyzedSuccessfully: Boolean,
    val failureReason: String? = null
) {
    val regionsToBlur: List<EvaluatedRegion>
        get() = regions.filter { it.shouldBlur }
}
