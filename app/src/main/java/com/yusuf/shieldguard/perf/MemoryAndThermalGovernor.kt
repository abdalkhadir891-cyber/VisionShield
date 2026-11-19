package com.yusuf.shieldguard.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import com.yusuf.shieldguard.data.PerformanceProfile
import kotlin.math.max
import kotlin.math.min

enum class GovernorLevel {
    NORMAL,
    CONSERVATIVE,
    CRITICAL_THROTTLE
}

data class DeviceResourceSnapshot(
    val pssMb: Float,
    val systemLowMemory: Boolean,
    val isLowRamDevice: Boolean,
    val isPowerSaveMode: Boolean,
    val thermalStatus: Int
)

data class GovernorAdjustment(
    val level: GovernorLevel = GovernorLevel.NORMAL,
    val maxDownscaleWidthPx: Int = 480,
    val intervalMultiplier: Float = 1.0f,
    val disableSecondaryClassifier: Boolean = false
) {
    companion object {
        val NONE = GovernorAdjustment()
    }

    fun applyDownscaleWidth(requestedWidthPx: Int): Int {
        return min(requestedWidthPx, maxDownscaleWidthPx).coerceAtLeast(240)
    }

    fun applyDelayMs(baseDelayMs: Long): Long {
        return (baseDelayMs * intervalMultiplier).toLong().coerceIn(45L, 2000L)
    }
}

class MemoryAndThermalGovernor(
    private val softRamBudgetMb: Float = 128f,
    private val hardRamBudgetMb: Float = 145f
) {
    companion object {
        const val THERMAL_NONE = 0
        const val THERMAL_LIGHT = 1
        const val THERMAL_MODERATE = 2
        const val THERMAL_SEVERE = 3
    }

    fun sampleDeviceResources(context: Context): DeviceResourceSnapshot {
        val pssMb = Debug.getPss() / 1024f
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return DeviceResourceSnapshot(
            pssMb = pssMb,
            systemLowMemory = memInfo.lowMemory,
            isLowRamDevice = am.isLowRamDevice,
            isPowerSaveMode = pm.isPowerSaveMode,
            thermalStatus = pm.currentThermalStatus
        )
    }

    fun evaluate(
        snapshot: DeviceResourceSnapshot,
        requestedProfile: PerformanceProfile
    ): GovernorAdjustment {
        val isCriticalRam = snapshot.pssMb >= hardRamBudgetMb || snapshot.systemLowMemory
        val isCriticalThermal = snapshot.thermalStatus >= THERMAL_SEVERE

        if (isCriticalRam || isCriticalThermal) {
            return GovernorAdjustment(
                level = GovernorLevel.CRITICAL_THROTTLE,
                maxDownscaleWidthPx = 320,
                intervalMultiplier = 2.0f,
                disableSecondaryClassifier = true
            )
        }

        val isElevatedRam = snapshot.pssMb >= softRamBudgetMb || snapshot.isLowRamDevice
        val isModerateThermal = snapshot.thermalStatus >= THERMAL_MODERATE
        val isBatterySaving = snapshot.isPowerSaveMode

        if (isElevatedRam || isModerateThermal || isBatterySaving) {
            val cappedWidth = min(requestedProfile.downscaleWidthPx, 360)
            return GovernorAdjustment(
                level = GovernorLevel.CONSERVATIVE,
                maxDownscaleWidthPx = max(288, cappedWidth),
                intervalMultiplier = 1.45f,
                disableSecondaryClassifier = false
            )
        }

        return GovernorAdjustment(
            level = GovernorLevel.NORMAL,
            maxDownscaleWidthPx = requestedProfile.downscaleWidthPx,
            intervalMultiplier = 1.0f,
            disableSecondaryClassifier = false
        )
    }
}
