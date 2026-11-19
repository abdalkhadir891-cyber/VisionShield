package com.yusuf.shieldguard.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

interface RegionDetector {
    val health: ModelHealth
    fun detect(frame: FrameData, minScore: Float): List<RawRegionProposal>
    fun close()
}

class MediaPipePersonDetector(
    context: Context,
    private val assetPath: String = "models/person_detector.tflite",
    maxResults: Int = 10
) : RegionDetector {

    private var detector: ObjectDetector? = null
    private var reusableBitmap: Bitmap? = null

    override var health: ModelHealth = ModelHealth("PersonDetector", ModelLoadStatus.MISSING_ASSET)
        private set

    init {
        if (!assetExists(context, assetPath)) {
            health = ModelHealth(
                name = "PersonDetector",
                status = ModelLoadStatus.MISSING_ASSET,
                detail = "Asset not found: $assetPath"
            )
        } else {
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(assetPath)
                    .setDelegate(Delegate.CPU)
                    .build()

                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(maxResults)
                    .setScoreThreshold(0.25f)
                    .setCategoryAllowlist(listOf("person"))
                    .build()

                detector = ObjectDetector.createFromOptions(context, options)
                health = ModelHealth(
                    name = "PersonDetector",
                    status = ModelLoadStatus.READY,
                    detail = "MediaPipe EfficientDet CPU"
                )
            } catch (t: Throwable) {
                health = ModelHealth(
                    name = "PersonDetector",
                    status = ModelLoadStatus.INIT_ERROR,
                    detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                )
            }
        }
    }

    override fun detect(frame: FrameData, minScore: Float): List<RawRegionProposal> {
        val activeDetector = detector ?: return emptyList()
        val bmp = obtainReusableBitmap(frame.width, frame.height)
        bmp.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)

        val mpImage = BitmapImageBuilder(bmp).build()
        val result = activeDetector.detect(mpImage) ?: return emptyList()

        val proposals = ArrayList<RawRegionProposal>(result.detections().size)
        for (detection in result.detections()) {
            val topCategory = detection.categories().firstOrNull() ?: continue
            if (topCategory.score() < minScore) continue

            val box = detection.boundingBox()
            val rect = PixelRect(
                left = box.left.toInt(),
                top = box.top.toInt(),
                right = box.right.toInt(),
                bottom = box.bottom.toInt()
            ).clampTo(frame.width, frame.height)

            if (rect.isValid()) {
                proposals.add(
                    RawRegionProposal(
                        bounds = rect,
                        detectorConfidence = topCategory.score(),
                        label = topCategory.categoryName().ifBlank { "person" }
                    )
                )
            }
        }
        return proposals
    }

    private fun obtainReusableBitmap(width: Int, height: Int): Bitmap {
        val existing = reusableBitmap
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableBitmap = it
        }
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return try {
            context.assets.openFd(path).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    override fun close() {
        try {
            detector?.close()
        } catch (_: Throwable) {}
        detector = null
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
