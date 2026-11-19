package com.yusuf.shieldguard.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

interface OverlayRenderer {
    fun getWindowOffset(): Pair<Int, Int>
    fun showPatches(patches: List<MosaicPatch>)
    fun clear()
    fun detach()
}

class OverlayWindowController(private val context: Context) : OverlayRenderer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var overlayView: BlurOverlayView? = null

    @Volatile
    private var lastKnownOffset: Pair<Int, Int> = Pair(0, 0)

    fun attachIfPermitted() {
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            if (overlayView != null) return@post
            val view = BlurOverlayView(context)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            try {
                windowManager.addView(view, params)
                overlayView = view
                view.post {
                    lastKnownOffset = view.readWindowScreenOffset()
                }
            } catch (_: Throwable) {
                overlayView = null
            }
        }
    }

    override fun getWindowOffset(): Pair<Int, Int> {
        val view = overlayView
        if (view != null && view.isAttachedToWindow) {
            lastKnownOffset = view.readWindowScreenOffset()
        }
        return lastKnownOffset
    }

    override fun showPatches(patches: List<MosaicPatch>) {
        val view = overlayView
        if (view == null) {
            attachIfPermitted()
            return
        }
        view.updatePatches(patches)
    }

    override fun clear() {
        overlayView?.clearPatches()
    }

    override fun detach() {
        mainHandler.post {
            val view = overlayView ?: return@post
            try {
                view.clearPatches()
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {}
            overlayView = null
        }
    }
}
