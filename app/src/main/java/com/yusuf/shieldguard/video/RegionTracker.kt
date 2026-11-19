package com.yusuf.shieldguard.video

import com.yusuf.shieldguard.ai.DetectionCategory
import com.yusuf.shieldguard.ai.EvaluatedRegion
import com.yusuf.shieldguard.ai.FrameData
import com.yusuf.shieldguard.ai.PixelRect
import com.yusuf.shieldguard.overlay.MosaicPatch
import com.yusuf.shieldguard.overlay.MosaicSampler
import com.yusuf.shieldguard.overlay.OverlayCoordinateMapper
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TrackedRegionState(
    val id: Int,
    var captureBounds: PixelRect,
    var renderCaptureBounds: PixelRect,
    var vxPxPerSec: Float,
    var vyPxPerSec: Float,
    var category: DetectionCategory,
    var emaConfidence: Float,
    var isBlurred: Boolean,
    var positiveHits: Int,
    var negativeHits: Int,
    var lastSupportedMs: Long,
    var lastUpdateMs: Long,
    var cachedPatch: MosaicPatch?
)

class RegionTracker(
    private val maxTracks: Int = 10,
    private val positiveThreshold: Int = 1,
    private val negativeThreshold: Int = 3,
    private val holdDurationMs: Long = 1050L
) {
    private val tracks = ArrayList<TrackedRegionState>(maxTracks)
    private var nextTrackId = 1

    fun clear() {
        tracks.clear()
    }

    fun activeTrackCount(): Int = tracks.size

    fun hasActiveBlurredTracks(): Boolean = tracks.any { it.isBlurred }

    fun getCoveredCaptureBounds(): List<PixelRect> {
        return tracks.filter { it.isBlurred }.map { it.renderCaptureBounds }
    }

    fun holdStaticTracks(nowMs: Long) {
        for (i in tracks.indices) {
            val tr = tracks[i]
            tr.vxPxPerSec = 0f
            tr.vyPxPerSec = 0f
            tr.lastSupportedMs = nowMs
            tr.lastUpdateMs = nowMs
        }
    }

    fun predictAndShiftTracks(
        nowMs: Long,
        scrollDyCapturePx: Int,
        captureW: Int,
        captureH: Int,
        screenW: Int,
        screenH: Int,
        overlayOffsetX: Int,
        overlayOffsetY: Int
    ): List<MosaicPatch> {
        val iter = tracks.iterator()
        while (iter.hasNext()) {
            val tr = iter.next()
            if (nowMs - tr.lastSupportedMs > holdDurationMs) {
                iter.remove()
                continue
            }

            val dtSec = ((nowMs - tr.lastUpdateMs).coerceIn(0L, 400L)) / 1000f
            val dx = (tr.vxPxPerSec * dtSec).roundToInt()
            val dy = scrollDyCapturePx + (tr.vyPxPerSec * dtSec).roundToInt()

            if (dx != 0 || dy != 0) {
                val shifted = PixelRect(
                    left = tr.captureBounds.left + dx,
                    top = tr.captureBounds.top + dy,
                    right = tr.captureBounds.right + dx,
                    bottom = tr.captureBounds.bottom + dy
                )

                if (shifted.bottom <= 4 || shifted.top >= captureH - 4 ||
                    shifted.right <= 4 || shifted.left >= captureW - 4
                ) {
                    iter.remove()
                    continue
                }

                tr.captureBounds = shifted.clampTo(captureW, captureH)
            }

            tr.vxPxPerSec *= 0.82f
            tr.vyPxPerSec *= 0.82f
            tr.lastUpdateMs = nowMs
        }

        recomputeTrimmedRenderBounds()
        return buildProjectedPatches(
            captureW = captureW,
            captureH = captureH,
            screenW = screenW,
            screenH = screenH,
            overlayOffsetX = overlayOffsetX,
            overlayOffsetY = overlayOffsetY
        )
    }

    fun updateWithEvaluations(
        evaluatedRegions: List<EvaluatedRegion>,
        frame: FrameData,
        isCleanFrame: Boolean,
        blurIntensity: Int,
        screenW: Int,
        screenH: Int,
        overlayOffsetX: Int,
        overlayOffsetY: Int,
        nowMs: Long
    ): List<MosaicPatch> {
        val diag = hypot(frame.width.toFloat(), frame.height.toFloat()).coerceAtLeast(1f)
        val maxVelX = frame.width * 0.65f
        val maxVelY = frame.height * 0.65f

        data class MatchPair(val trackIdx: Int, val detIdx: Int, val score: Float)
        val pairs = ArrayList<MatchPair>()

        for (tIdx in tracks.indices) {
            val tr = tracks[tIdx]
            val dtSec = ((nowMs - tr.lastUpdateMs).coerceIn(0L, 450L)) / 1000f
            val predCx = tr.captureBounds.centerX + tr.vxPxPerSec * dtSec
            val predCy = tr.captureBounds.centerY + tr.vyPxPerSec * dtSec

            for (dIdx in evaluatedRegions.indices) {
                val det = evaluatedRegions[dIdx]
                val iou = tr.captureBounds.iou(det.bounds)
                val distNorm = hypot(det.bounds.centerX - predCx, det.bounds.centerY - predCy) / diag
                val combined = iou - distNorm * 0.55f
                if (iou > 0.18f || distNorm < 0.12f) {
                    pairs.add(MatchPair(tIdx, dIdx, combined))
                }
            }
        }
        pairs.sortByDescending { it.score }

        val matchedTrack = BooleanArray(tracks.size)
        val matchedDet = BooleanArray(evaluatedRegions.size)

        for (pair in pairs) {
            if (matchedTrack[pair.trackIdx] || matchedDet[pair.detIdx]) continue
            matchedTrack[pair.trackIdx] = true
            matchedDet[pair.detIdx] = true

            val tr = tracks[pair.trackIdx]
            val det = evaluatedRegions[pair.detIdx]
            val dtSec = max(0.04f, ((nowMs - tr.lastUpdateMs).coerceIn(40L, 1000L)) / 1000f)

            val instVx = ((det.bounds.centerX - tr.captureBounds.centerX) / dtSec).coerceIn(-maxVelX, maxVelX)
            val instVy = ((det.bounds.centerY - tr.captureBounds.centerY) / dtSec).coerceIn(-maxVelY, maxVelY)
            tr.vxPxPerSec = tr.vxPxPerSec * 0.45f + instVx * 0.55f
            tr.vyPxPerSec = tr.vyPxPerSec * 0.45f + instVy * 0.55f

            tr.captureBounds = PixelRect(
                left = (det.bounds.left * 75 + tr.captureBounds.left * 25) / 100,
                top = (det.bounds.top * 75 + tr.captureBounds.top * 25) / 100,
                right = (det.bounds.right * 75 + tr.captureBounds.right * 25) / 100,
                bottom = (det.bounds.bottom * 75 + tr.captureBounds.bottom * 25) / 100
            ).clampTo(frame.width, frame.height)

            tr.lastSupportedMs = nowMs
            tr.lastUpdateMs = nowMs

            if (isCleanFrame || !tr.isBlurred) {
                tr.emaConfidence = tr.emaConfidence * 0.35f + det.confidence * 0.65f
                if (det.shouldBlur) {
                    tr.category = det.category
                    tr.positiveHits++
                    tr.negativeHits = 0
                    if (tr.positiveHits >= positiveThreshold) {
                        tr.isBlurred = true
                    }
                } else {
                    tr.negativeHits++
                    tr.positiveHits = 0
                    if (tr.negativeHits >= negativeThreshold) {
                        tr.isBlurred = false
                        tr.category = det.category
                        tr.cachedPatch = null
                    }
                }
            }
        }

        for (dIdx in evaluatedRegions.indices) {
            if (matchedDet[dIdx] || tracks.size >= maxTracks) continue
            val det = evaluatedRegions[dIdx]
            val newTrack = TrackedRegionState(
                id = nextTrackId++,
                captureBounds = det.bounds.clampTo(frame.width, frame.height),
                renderCaptureBounds = det.renderBounds.clampTo(frame.width, frame.height),
                vxPxPerSec = 0f,
                vyPxPerSec = 0f,
                category = det.category,
                emaConfidence = det.confidence,
                isBlurred = det.shouldBlur && positiveThreshold <= 1,
                positiveHits = if (det.shouldBlur) 1 else 0,
                negativeHits = if (det.shouldBlur) 0 else 1,
                lastSupportedMs = nowMs,
                lastUpdateMs = nowMs,
                cachedPatch = null
            )
            tracks.add(newTrack)
        }

        val iter = tracks.iterator()
        var idx = 0
        while (iter.hasNext()) {
            val tr = iter.next()
            val wasMatched = idx < matchedTrack.size && matchedTrack[idx]
            idx++
            if (!wasMatched) {
                if (isCleanFrame) {
                    tr.negativeHits++
                    tr.positiveHits = 0
                    if (tr.negativeHits >= negativeThreshold) {
                        iter.remove()
                        continue
                    }
                }
                if (nowMs - tr.lastSupportedMs > holdDurationMs) {
                    iter.remove()
                    continue
                }
            }
        }

        recomputeTrimmedRenderBounds()

        val patches = ArrayList<MosaicPatch>(tracks.size)
        for (tr in tracks) {
            if (!tr.isBlurred) continue

            val overlayRect = OverlayCoordinateMapper.captureRectToOverlayRect(
                captureRect = tr.renderCaptureBounds,
                captureW = frame.width,
                captureH = frame.height,
                screenW = screenW,
                screenH = screenH,
                overlayOffsetX = overlayOffsetX,
                overlayOffsetY = overlayOffsetY
            )

            val patch = if (isCleanFrame || tr.cachedPatch == null) {
                MosaicSampler.buildMosaicPatch(
                    frame = frame,
                    captureBounds = tr.renderCaptureBounds,
                    overlayBounds = overlayRect,
                    blurIntensity = blurIntensity,
                    category = tr.category,
                    confidence = tr.emaConfidence
                ).also { tr.cachedPatch = it }
            } else {
                tr.cachedPatch!!.copy(
                    captureBounds = tr.renderCaptureBounds,
                    overlayBounds = overlayRect,
                    confidence = tr.emaConfidence
                ).also { tr.cachedPatch = it }
            }
            patches.add(patch)
        }
        return patches
    }

    private fun recomputeTrimmedRenderBounds() {
        for (i in tracks.indices) {
            val tr = tracks[i]
            tr.renderCaptureBounds = tr.captureBounds
            if (!tr.isBlurred) continue

            var l = tr.captureBounds.left
            val t = tr.captureBounds.top
            var r = tr.captureBounds.right
            val b = tr.captureBounds.bottom

            for (j in tracks.indices) {
                if (i == j) continue
                val other = tracks[j]
                if (other.isBlurred) continue

                val sb = other.captureBounds
                val interL = max(l, sb.left)
                val interT = max(t, sb.top)
                val interR = min(r, sb.right)
                val interB = min(b, sb.bottom)

                if (interR > interL && interB > interT) {
                    if (tr.captureBounds.centerX < sb.centerX) {
                        r = min(r, max(l + 12, sb.left - 2))
                    } else {
                        l = max(l, min(r - 12, sb.right + 2))
                    }
                }
            }
            tr.renderCaptureBounds = PixelRect(l, t, r, b)
        }
    }

    private fun buildProjectedPatches(
        captureW: Int,
        captureH: Int,
        screenW: Int,
        screenH: Int,
        overlayOffsetX: Int,
        overlayOffsetY: Int
    ): List<MosaicPatch> {
        val patches = ArrayList<MosaicPatch>(tracks.size)
        for (tr in tracks) {
            if (!tr.isBlurred) continue
            val existing = tr.cachedPatch ?: continue
            val overlayRect = OverlayCoordinateMapper.captureRectToOverlayRect(
                captureRect = tr.renderCaptureBounds,
                captureW = captureW,
                captureH = captureH,
                screenW = screenW,
                screenH = screenH,
                overlayOffsetX = overlayOffsetX,
                overlayOffsetY = overlayOffsetY
            )
            val updated = existing.copy(
                captureBounds = tr.renderCaptureBounds,
                overlayBounds = overlayRect
            )
            tr.cachedPatch = updated
            patches.add(updated)
        }
        return patches
    }
}
