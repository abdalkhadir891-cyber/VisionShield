package com.yusuf.shieldguard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ProtectionRuntimeState(
    val isForegroundServiceRunning: Boolean = false,
    val isAccessibilityServiceConnected: Boolean = false,
    val isScreenInteractive: Boolean = true,
    val isDashboardVisible: Boolean = false,
    val activeForegroundPackage: String = "-",
    val isCurrentWindowAccessible: Boolean = true,
    val framesAnalyzed: Long = 0L,
    val protectedWindowEvents: Long = 0L,
    val activeBlurredRegions: Int = 0,
    val lastInferenceMs: Float = 0f,
    val isProtectedBlackFrame: Boolean = false,
    val motionStateName: String = "STATIC",
    val activeTrackedCount: Int = 0,
    val skippedInferenceTicks: Long = 0L,
    val modelHealthSummary: String = "-",
    val governorStatus: String = "NORMAL",
    val effectiveDownscaleWidthPx: Int = 448,
    val effectiveIntervalMs: Long = 400L
)

object ProtectionStateHolder {

    private val _state = MutableStateFlow(ProtectionRuntimeState())
    val state: StateFlow<ProtectionRuntimeState> = _state.asStateFlow()

    fun setForegroundServiceRunning(running: Boolean) {
        _state.update {
            if (!running) {
                it.copy(
                    isForegroundServiceRunning = false,
                    activeBlurredRegions = 0,
                    activeTrackedCount = 0,
                    isProtectedBlackFrame = false,
                    motionStateName = "STATIC",
                    governorStatus = "NORMAL"
                )
            } else {
                it.copy(isForegroundServiceRunning = true)
            }
        }
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _state.update { it.copy(isAccessibilityServiceConnected = connected) }
    }

    fun setDashboardVisible(visible: Boolean) {
        _state.update { it.copy(isDashboardVisible = visible) }
    }

    fun setScreenInteractive(interactive: Boolean) {
        _state.update {
            it.copy(
                isScreenInteractive = interactive,
                activeBlurredRegions = if (!interactive) 0 else it.activeBlurredRegions,
                activeTrackedCount = if (!interactive) 0 else it.activeTrackedCount
            )
        }
    }

    fun updateForegroundWindow(packageName: String, isAccessible: Boolean) {
        _state.update { current ->
            val nextProtectedCount = if (!isAccessible) {
                current.protectedWindowEvents + 1L
            } else {
                current.protectedWindowEvents
            }
            current.copy(
                activeForegroundPackage = packageName,
                isCurrentWindowAccessible = isAccessible,
                protectedWindowEvents = nextProtectedCount,
                activeBlurredRegions = if (!isAccessible) 0 else current.activeBlurredRegions
            )
        }
    }

    fun incrementFramesAnalyzed() {
        _state.update { it.copy(framesAnalyzed = it.framesAnalyzed + 1L) }
    }

    fun incrementSkippedInferenceTicks() {
        _state.update { it.copy(skippedInferenceTicks = it.skippedInferenceTicks + 1L) }
    }

    fun recordProtectedBlackFrame() {
        _state.update {
            it.copy(
                isProtectedBlackFrame = true,
                activeBlurredRegions = 0,
                activeTrackedCount = 0,
                protectedWindowEvents = it.protectedWindowEvents + 1L
            )
        }
    }

    fun updateModelHealthSummary(summary: String) {
        _state.update { it.copy(modelHealthSummary = summary) }
    }

    fun updateGovernorState(status: String, downscaleWidthPx: Int, intervalMs: Long) {
        _state.update {
            it.copy(
                governorStatus = status,
                effectiveDownscaleWidthPx = downscaleWidthPx,
                effectiveIntervalMs = intervalMs
            )
        }
    }

    fun updateOverlayTelemetry(
        activeRegionsCount: Int,
        totalLatencyMs: Float,
        motionStateName: String = _state.value.motionStateName,
        trackedCount: Int = activeRegionsCount
    ) {
        _state.update {
            it.copy(
                activeBlurredRegions = activeRegionsCount,
                lastInferenceMs = totalLatencyMs,
                isProtectedBlackFrame = false,
                motionStateName = motionStateName,
                activeTrackedCount = trackedCount
            )
        }
    }

    fun resetForTest() {
        _state.value = ProtectionRuntimeState()
    }
}
