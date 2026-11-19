package com.yusuf.shieldguard.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.view.WindowManager
import com.yusuf.shieldguard.ai.FrameData
import kotlin.math.max

interface FrameCapturer {
    val screenWidth: Int
    val screenHeight: Int
    fun ensureDownscaleWidth(targetWidthPx: Int)
    fun acquireLatestFrame(): FrameData?
    fun release()
}

class ScreenFrameCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val callbackHandler: Handler
) : FrameCapturer {

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override var screenWidth: Int = 1080
        private set
    override var screenHeight: Int = 2340
        private set

    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var configuredTargetWidth: Int = -1

    private var reusableRowBytes = ByteArray(0)
    private var reusablePixels = IntArray(0)

    override fun ensureDownscaleWidth(targetWidthPx: Int) {
        val clampedTarget = targetWidthPx.coerceIn(240, 480)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val currentScreenW = max(1, bounds.width())
        val currentScreenH = max(1, bounds.height())

        val dimensionsUnchanged = (currentScreenW == screenWidth && currentScreenH == screenHeight)
        if (configuredTargetWidth == clampedTarget &&
            dimensionsUnchanged &&
            virtualDisplay != null &&
            imageReader != null
        ) {
            return
        }

        releaseVirtualDisplayOnly()

        screenWidth = currentScreenW
        screenHeight = currentScreenH

        val isLandscape = screenWidth > screenHeight
        if (isLandscape) {
            captureHeight = max(240, clampedTarget and 0xFFF8)
            captureWidth = max(
                240,
                ((screenWidth.toLong() * captureHeight.toLong()) / screenHeight.toLong()).toInt() and 0xFFF8
            )
        } else {
            captureWidth = max(240, clampedTarget and 0xFFF8)
            captureHeight = max(
                240,
                ((screenHeight.toLong() * captureWidth.toLong()) / screenWidth.toLong()).toInt() and 0xFFF8
            )
        }

        val expectedPixels = captureWidth * captureHeight
        if (reusablePixels.size != expectedPixels) {
            reusablePixels = IntArray(expectedPixels)
        }
        if (reusableRowBytes.size < (captureWidth + 64) * 4) {
            reusableRowBytes = ByteArray((captureWidth + 64) * 4)
        }

        val scaledDpi = max(120, (context.resources.displayMetrics.densityDpi * captureWidth) / screenWidth)
        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        val display = mediaProjection.createVirtualDisplay(
            "ShieldGuardCaptureDisplay",
            captureWidth,
            captureHeight,
            scaledDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            callbackHandler
        )

        imageReader = reader
        virtualDisplay = display
        configuredTargetWidth = clampedTarget
    }

    fun invalidateForOrientationChange() {
        configuredTargetWidth = -1
    }

    override fun acquireLatestFrame(): FrameData? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            if (reusableRowBytes.size < rowStride) {
                reusableRowBytes = ByteArray(rowStride)
            }

            var outIdx = 0
            for (y in 0 until captureHeight) {
                buffer.position(y * rowStride)
                val bytesToRead = minOf(rowStride, buffer.remaining())
                buffer.get(reusableRowBytes, 0, bytesToRead)

                var pxOffset = 0
                for (x in 0 until captureWidth) {
                    val r = reusableRowBytes[pxOffset].toInt() and 0xFF
                    val g = reusableRowBytes[pxOffset + 1].toInt() and 0xFF
                    val b = reusableRowBytes[pxOffset + 2].toInt() and 0xFF
                    reusablePixels[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    pxOffset += pixelStride
                }
            }

            FrameData(
                pixels = reusablePixels,
                width = captureWidth,
                height = captureHeight,
                timestampMs = SystemClock.elapsedRealtime()
            )
        } finally {
            image.close()
        }
    }

    private fun releaseVirtualDisplayOnly() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Throwable) {}
        imageReader = null
    }

    override fun release() {
        releaseVirtualDisplayOnly()
        configuredTargetWidth = -1
    }
}
