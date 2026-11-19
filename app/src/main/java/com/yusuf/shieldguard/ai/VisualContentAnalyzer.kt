package com.yusuf.shieldguard.ai

import android.content.Context
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.data.ProtectionStateHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

class VisualContentAnalyzer(
    private val regionDetector: RegionDetector,
    private val explicitClassifier: RegionClassifier?,
    private val genderClassifier: RegionClassifier?,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockNanos: () -> Long = { System.nanoTime() }
) {
    private val inferenceMutex = Mutex()

    companion object {
        fun createDefault(context: Context): VisualContentAnalyzer {
            val appCtx = context.applicationContext
            val detector = MediaPipePersonDetector(
                context = appCtx,
                assetPath = "models/person_detector.tflite"
            )
            val explicitCls = LiteRtImageClassifier(
                context = appCtx,
                modelName = "ExplicitClassifier",
                assetPath = "models/content_classifier.tflite",
                positiveClassIndex = 1,
                normalization = InputNormalization.RAW_0_TO_255,
                numThreads = 2
            )
            val genderCls = LiteRtImageClassifier(
                context = appCtx,
                modelName = "GenderClassifier",
                assetPath = "models/gender_classifier.tflite",
                positiveClassIndex = 1,
                normalization = InputNormalization.RAW_0_TO_255,
                numThreads = 2
            )
            return VisualContentAnalyzer(
                regionDetector = detector,
                explicitClassifier = explicitCls,
                genderClassifier = genderCls
            )
        }
    }

    fun getHealthReport(): List<ModelHealth> {
        return listOfNotNull(
            regionDetector.health,
            explicitClassifier?.health,
            genderClassifier?.health
        )
    }

    suspend fun analyzeFrame(
        frame: FrameData,
        settings: ProtectionSettings
    ): FrameAnalysisResult = withContext(inferenceDispatcher) {
        if (!settings.detectExplicitContent && !settings.detectGenderSpecific) {
            return@withContext FrameAnalysisResult(
                timestampMs = frame.timestampMs,
                frameWidth = frame.width,
                frameHeight = frame.height,
                regions = emptyList(),
                detectorLatencyMs = 0f,
                classifierLatencyMs = 0f,
                analyzedSuccessfully = true
            )
        }

        if (regionDetector.health.status != ModelLoadStatus.READY) {
            return@withContext FrameAnalysisResult(
                timestampMs = frame.timestampMs,
                frameWidth = frame.width,
                frameHeight = frame.height,
                regions = emptyList(),
                detectorLatencyMs = 0f,
                classifierLatencyMs = 0f,
                analyzedSuccessfully = false,
                failureReason = regionDetector.health.detail
            )
        }

        inferenceMutex.withLock {
            val detStart = clockNanos()
            val rawProposals = regionDetector.detect(frame, minScore = 0.28f)
            val filteredProposals = DetectionDecisionEngine.suppressOverlappingProposals(
                proposals = rawProposals,
                frameW = frame.width,
                frameH = frame.height
            )
            val detMs = (clockNanos() - detStart) / 1_000_000f

            val clsStart = clockNanos()
            val evaluated = ArrayList<EvaluatedRegion>(filteredProposals.size)

            for (proposal in filteredProposals) {
                val expandedBox = DetectionDecisionEngine.expandAndCapRegion(
                    raw = proposal.bounds,
                    frameW = frame.width,
                    frameH = frame.height
                )

                val explicitScore = if (settings.detectExplicitContent &&
                    explicitClassifier?.health?.status == ModelLoadStatus.READY
                ) {
                    explicitClassifier.classifyPositiveProbability(frame, expandedBox)
                } else {
                    null
                }

                var headFemaleScore: Float? = null
                var fullFemaleScore: Float? = null
                if (settings.detectGenderSpecific &&
                    genderClassifier?.health?.status == ModelLoadStatus.READY
                ) {
                    val headBottom = expandedBox.top + max(12, (expandedBox.height * 0.52f).toInt())
                    val headBox = PixelRect(
                        left = expandedBox.left,
                        top = expandedBox.top,
                        right = expandedBox.right,
                        bottom = headBottom
                    ).clampTo(frame.width, frame.height)

                    headFemaleScore = genderClassifier.classifyPositiveProbability(frame, headBox)
                    fullFemaleScore = genderClassifier.classifyPositiveProbability(frame, expandedBox)
                }

                evaluated.add(
                    DetectionDecisionEngine.decideRegion(
                        bounds = expandedBox,
                        explicitScore = explicitScore,
                        headFemaleScore = headFemaleScore,
                        fullFemaleScore = fullFemaleScore,
                        settings = settings
                    )
                )
            }

            val finalRegions = DetectionDecisionEngine.trimOverlapWithSafeRegions(evaluated)
            val clsMs = (clockNanos() - clsStart) / 1_000_000f

            ProtectionStateHolder.incrementFramesAnalyzed()

            FrameAnalysisResult(
                timestampMs = frame.timestampMs,
                frameWidth = frame.width,
                frameHeight = frame.height,
                regions = finalRegions,
                detectorLatencyMs = detMs,
                classifierLatencyMs = clsMs,
                analyzedSuccessfully = true
            )
        }
    }

    fun close() {
        regionDetector.close()
        explicitClassifier?.close()
        genderClassifier?.close()
    }
}
