package com.yusuf.shieldguard.video

import com.yusuf.shieldguard.data.PerformanceProfile
import com.yusuf.shieldguard.data.ProtectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveFrameSchedulerTest {

    @Test
    fun throttlesInferenceIntervalWhenDeviceLatencyIsHigh() {
        val scheduler = AdaptiveFrameScheduler()
        scheduler.recordInferenceLatency(latencyMs = 250f, nowMs = 1000L, wasCleanKeyframe = true)

        val interval = scheduler.effectiveInferenceIntervalMs(PerformanceProfile.RESPONSIVE)
        assertTrue("Expected latency-throttled interval >= 600ms, got $interval", interval >= 600L)
    }

    @Test
    fun skipsVideoFramesWhenProtectVideosToggleIsOff() {
        val scheduler = AdaptiveFrameScheduler()
        val settings = ProtectionSettings(protectImages = true, protectVideos = false)
        val videoMotion = MotionEstimate(MotionState.VIDEO_MOTION, 15f, 0, 3)

        val decision = scheduler.decide(
            motion = videoMotion,
            settings = settings,
            hasActiveBlurredTracks = false,
            awaitingCleanFrame = false,
            nowMs = 2000L
        )
        assertEquals(ScheduleAction.SKIP_DISABLED_MEDIA_MODE, decision.action)
    }

    @Test
    fun predictsBetweenVideoKeyframesThenRunsInferenceAtInterval() {
        val scheduler = AdaptiveFrameScheduler()
        val settings = ProtectionSettings(
            protectVideos = true,
            performanceProfile = PerformanceProfile.BALANCED
        )
        scheduler.recordInferenceLatency(latencyMs = 40f, nowMs = 1000L, wasCleanKeyframe = true)
        val videoMotion = MotionEstimate(MotionState.VIDEO_MOTION, 12f, 0, 3)

        val midDecision = scheduler.decide(videoMotion, settings, hasActiveBlurredTracks = true, awaitingCleanFrame = false, nowMs = 1100L)
        assertEquals(ScheduleAction.PREDICT_TRACKS_ONLY, midDecision.action)

        val keyDecision = scheduler.decide(videoMotion, settings, hasActiveBlurredTracks = true, awaitingCleanFrame = false, nowMs = 1450L)
        assertEquals(ScheduleAction.RUN_FULL_INFERENCE, keyDecision.action)
    }
}
