package com.yusuf.shieldguard.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class BlurOverlayView(context: Context) : View(context) {

    private val cellPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    @Volatile
    private var patches: List<MosaicPatch> = emptyList()

    private val windowLocation = IntArray(2)

    fun readWindowScreenOffset(): Pair<Int, Int> {
        getLocationOnScreen(windowLocation)
        return Pair(windowLocation[0], windowLocation[1])
    }

    fun updatePatches(newPatches: List<MosaicPatch>) {
        patches = newPatches
        postInvalidateOnAnimation()
    }

    fun clearPatches() {
        if (patches.isNotEmpty()) {
            patches = emptyList()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = patches
        for (i in current.indices) {
            val patch = current[i]
            val rect = patch.overlayBounds
            if (rect.width < 4 || rect.height < 4) continue

            val cw = rect.width.toFloat() / patch.cols.toFloat()
            val ch = rect.height.toFloat() / patch.rows.toFloat()

            for (cy in 0 until patch.rows) {
                val top = rect.top + cy * ch
                val bottom = top + ch + 0.75f
                val rowOffset = cy * patch.cols
                for (cx in 0 until patch.cols) {
                    val left = rect.left + cx * cw
                    cellPaint.color = patch.cellColors[rowOffset + cx]
                    canvas.drawRect(left, top, left + cw + 0.75f, bottom, cellPaint)
                }
            }
        }
    }
}
