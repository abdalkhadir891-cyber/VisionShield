package com.yusuf.shieldguard.video

import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.PixelRect
import kotlin.math.abs

enum class MotionState {
    STATIC,
    SCROLLING,
    VIDEO_MOTION,
    MINOR_CHANGE,
    SCENE_CUT
}

data class MotionEstimate(
    val state: MotionState,
    val outsideMeanDiff: Float,
    val scrollDyCapturePx: Int,
    val consecutiveMotionFrames: Int
)

class MotionAndScrollEstimator(
    private val profileRows: Int = 48,
    private val sampleCols: Int = 16,
    private val maxRowLag: Int = 8
) {
    companion object {
        private const val STATIC_DIFF_THRESHOLD = 2.5f
        private const val SCENE_CUT_DIFF_THRESHOLD = 38.0f
        private const val MAX_SCROLL_MATCH_SAD = 18.0f
        private const val SCROLL_IMPROVEMENT_RATIO = 0.58f
    }

    private val prevProfile = IntArray(profileRows) { -1 }
    private val currProfile = IntArray(profileRows) { -1 }
    private var hasBaseline = false
    private var consecutiveMotionCount = 0

    fun reset() {
        prevProfile.fill(-1)
        currProfile.fill(-1)
        hasBaseline = false
        consecutiveMotionCount = 0
    }

    fun analyzeMotion(
        frame: FrameData,
        outsideMeanDiff: Float,
        excludedCaptureRegions: List<PixelRect>,
        marginPx: Int = 6
    ): MotionEstimate {
        val startRow = (profileRows * 0.06f).toInt().coerceAtLeast(1)
        val endRow = (profileRows * 0.94f).toInt().coerceAtMost(profileRows - 1)

        currProfile.fill(-1)
        for (ry in startRow until endRow) {
            val py = (ry * frame.height) / profileRows
            val rowOffset = py * frame.width
            var lumaSum = 0
            var validCols = 0

            for (cx in 0 until sampleCols) {
                val px = ((cx * 2 + 1) * frame.width) / (sampleCols * 2)
                if (isInsideAny(px, py, excludedCaptureRegions, marginPx)) continue
                val argb = frame.pixels[rowOffset + px]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                lumaSum += (r * 2 + g * 5 + b) shr 3
                validCols++
            }
            currProfile[ry] = if (validCols >= 3) lumaSum / validCols else -1
        }

        if (!hasBaseline) {
            System.arraycopy(currProfile, 0, prevProfile, 0, profileRows)
            hasBaseline = true
            consecutiveMotionCount = 0
            return MotionEstimate(
                state = MotionState.SCENE_CUT,
                outsideMeanDiff = outsideMeanDiff,
                scrollDyCapturePx = 0,
                consecutiveMotionFrames = 0
            )
        }

        val lag0Sad = computeMeanSadForLag(lag = 0, startRow = startRow, endRow = endRow)
        var bestLag = 0
        var bestSad = lag0Sad

        for (lag in -maxRowLag..maxRowLag) {
            if (lag == 0) continue
            val sad = computeMeanSadForLag(lag = lag, startRow = startRow, endRow = endRow)
            if (sad < bestSad) {
                bestSad = sad
                bestLag = lag
            }
        }

        System.arraycopy(currProfile, 0, prevProfile, 0, profileRows)

        if (outsideMeanDiff < STATIC_DIFF_THRESHOLD) {
            consecutiveMotionCount = 0
            return MotionEstimate(
                state = MotionState.STATIC,
                outsideMeanDiff = outsideMeanDiff,
                scrollDyCapturePx = 0,
                consecutiveMotionFrames = 0
            )
        }

        val isScrollMatch = bestLag != 0 &&
            bestSad < MAX_SCROLL_MATCH_SAD &&
            lag0Sad > 3.0f &&
            bestSad < lag0Sad * SCROLL_IMPROVEMENT_RATIO

        if (isScrollMatch) {
            val dyCapturePx = (bestLag * frame.height) / profileRows
            consecutiveMotionCount = 0
            return MotionEstimate(
                state = MotionState.SCROLLING,
                outsideMeanDiff = outsideMeanDiff,
                scrollDyCapturePx = dyCapturePx,
                consecutiveMotionFrames = 0
            )
        }

        if (outsideMeanDiff >= SCENE_CUT_DIFF_THRESHOLD) {
            consecutiveMotionCount = 0
            return MotionEstimate(
                state = MotionState.SCENE_CUT,
                outsideMeanDiff = outsideMeanDiff,
                scrollDyCapturePx = 0,
                consecutiveMotionFrames = 0
            )
        }

        consecutiveMotionCount++
        val motionState = if (consecutiveMotionCount >= 2) {
            MotionState.VIDEO_MOTION
        } else {
            MotionState.MINOR_CHANGE
        }

        return MotionEstimate(
            state = motionState,
            outsideMeanDiff = outsideMeanDiff,
            scrollDyCapturePx = 0,
            consecutiveMotionFrames = consecutiveMotionCount
        )
    }

    private fun computeMeanSadForLag(lag: Int, startRow: Int, endRow: Int): Float {
        var sadSum = 0L
        var pairs = 0
        for (y in startRow until endRow) {
            val py = y - lag
            if (py < startRow || py >= endRow) continue
            val cVal = currProfile[y]
            val pVal = prevProfile[py]
            if (cVal < 0 || pVal < 0) continue
            sadSum += abs(cVal - pVal)
            pairs++
        }
        return if (pairs >= 8) sadSum.toFloat() / pairs.toFloat() else Float.MAX_VALUE
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
