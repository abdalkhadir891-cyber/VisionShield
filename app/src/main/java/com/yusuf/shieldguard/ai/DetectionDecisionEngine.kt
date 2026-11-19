package com.yusuf.shieldguard.ai

import com.yusuf.shieldguard.data.ProtectionSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object DetectionDecisionEngine {

    private const val MARGIN_RATIO = 0.06f
    private const val MAX_FRAME_AREA_RATIO = 0.55f
    private const val NMS_IOU_THRESHOLD = 0.45f
    private const val MALE_STRONG_SAFE_VETO = 0.22f

    fun expandAndCapRegion(
        raw: PixelRect,
        frameW: Int,
        frameH: Int
    ): PixelRect {
        var w = raw.width * (1f + MARGIN_RATIO * 2f)
        var h = raw.height * (1f + MARGIN_RATIO * 2f)

        val maxArea = frameW.toFloat() * frameH.toFloat() * MAX_FRAME_AREA_RATIO
        val currentArea = w * h
        if (currentArea > maxArea && currentArea > 0f) {
            val scale = sqrt(maxArea / currentArea)
            w *= scale
            h *= scale
        }

        val cx = raw.centerX
        val cy = raw.centerY
        return PixelRect(
            left = (cx - w * 0.5f).toInt(),
            top = (cy - h * 0.5f).toInt(),
            right = (cx + w * 0.5f).toInt(),
            bottom = (cy + h * 0.5f).toInt()
        ).clampTo(frameW, frameH)
    }

    fun suppressOverlappingProposals(
        proposals: List<RawRegionProposal>,
        frameW: Int,
        frameH: Int,
        maxResults: Int = 10
    ): List<RawRegionProposal> {
        if (proposals.isEmpty()) return emptyList()
        val minSide = max(12, min(frameW, frameH) / 28)
        val sorted = proposals
            .filter { it.bounds.width >= minSide && it.bounds.height >= minSide }
            .sortedByDescending { it.detectorConfidence }

        val kept = ArrayList<RawRegionProposal>(min(maxResults, sorted.size))
        for (candidate in sorted) {
            if (kept.size >= maxResults) break
            val overlaps = kept.any { it.bounds.iou(candidate.bounds) > NMS_IOU_THRESHOLD }
            if (!overlaps) {
                kept.add(candidate)
            }
        }
        return kept
    }

    fun decideRegion(
        bounds: PixelRect,
        explicitScore: Float?,
        headFemaleScore: Float?,
        fullFemaleScore: Float?,
        settings: ProtectionSettings
    ): EvaluatedRegion {
        val threshold = settings.confidenceThreshold()

        if (settings.detectExplicitContent && explicitScore != null && explicitScore >= threshold) {
            return EvaluatedRegion(
                bounds = bounds,
                renderBounds = bounds,
                category = DetectionCategory.EXPLICIT_CONTENT,
                confidence = explicitScore,
                shouldBlur = true
            )
        }

        if (settings.detectGenderSpecific && (headFemaleScore != null || fullFemaleScore != null)) {
            val head = headFemaleScore ?: fullFemaleScore ?: 0f
            val full = fullFemaleScore ?: headFemaleScore ?: 0f

            val effectiveFemaleScore = if (head < MALE_STRONG_SAFE_VETO && full < 0.50f) {
                head
            } else {
                (head * 0.65f) + (full * 0.35f)
            }

            if (effectiveFemaleScore >= threshold) {
                return EvaluatedRegion(
                    bounds = bounds,
                    renderBounds = bounds,
                    category = DetectionCategory.GENDER_FEMALE,
                    confidence = effectiveFemaleScore,
                    shouldBlur = true
                )
            }

            return EvaluatedRegion(
                bounds = bounds,
                renderBounds = bounds,
                category = DetectionCategory.SAFE_PERSON,
                confidence = 1f - effectiveFemaleScore,
                shouldBlur = false
            )
        }

        return EvaluatedRegion(
            bounds = bounds,
            renderBounds = bounds,
            category = DetectionCategory.SAFE_PERSON,
            confidence = 1f - (explicitScore ?: 0f),
            shouldBlur = false
        )
    }

    fun trimOverlapWithSafeRegions(regions: List<EvaluatedRegion>): List<EvaluatedRegion> {
        val safeBoxes = regions.filter { !it.shouldBlur }
        if (safeBoxes.isEmpty()) return regions

        return regions.map { region ->
            if (!region.shouldBlur) return@map region

            var l = region.renderBounds.left
            val t = region.renderBounds.top
            var r = region.renderBounds.right
            val b = region.renderBounds.bottom

            for (safe in safeBoxes) {
                val sb = safe.bounds
                val interL = max(l, sb.left)
                val interT = max(t, sb.top)
                val interR = min(r, sb.right)
                val interB = min(b, sb.bottom)

                if (interR > interL && interB > interT) {
                    if (region.bounds.centerX < sb.centerX) {
                        val trimmedRight = max(l + 16, sb.left - 2)
                        r = min(r, trimmedRight)
                    } else {
                        val trimmedLeft = min(r - 16, sb.right + 2)
                        l = max(l, trimmedLeft)
                    }
                }
            }
            region.copy(renderBounds = PixelRect(l, t, r, b))
        }
    }
}
