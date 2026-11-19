package com.yusuf.shieldguard.perf

import com.yusuf.shieldguard.data.PerformanceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAndThermalGovernorTest {

    private val governor = MemoryAndThermalGovernor(softRamBudgetMb = 128f, hardRamBudgetMb = 145f)

    @Test
    fun staysNormalWithinMemoryAndThermalBudgets() {
        val snap = DeviceResourceSnapshot(
            pssMb = 92f,
            systemLowMemory = false,
            isLowRamDevice = false,
            isPowerSaveMode = false,
            thermalStatus = MemoryAndThermalGovernor.THERMAL_NONE
        )

        val adj = governor.evaluate(snap, PerformanceProfile.BALANCED)
        assertEquals(GovernorLevel.NORMAL, adj.level)
        assertEquals(448, adj.applyDownscaleWidth(448))
        assertEquals(1.0f, adj.intervalMultiplier, 0.001f)
        assertFalse(adj.disableSecondaryClassifier)
    }

    @Test
    fun switchesToConservativeUnderPowerSaveOrModerateThermal() {
        val snap = DeviceResourceSnapshot(
            pssMb = 105f,
            systemLowMemory = false,
            isLowRamDevice = false,
            isPowerSaveMode = true,
            thermalStatus = MemoryAndThermalGovernor.THERMAL_MODERATE
        )

        val adj = governor.evaluate(snap, PerformanceProfile.RESPONSIVE)
        assertEquals(GovernorLevel.CONSERVATIVE, adj.level)
        assertTrue(adj.applyDownscaleWidth(480) <= 360)
        assertTrue(adj.intervalMultiplier > 1.3f)
        assertFalse(adj.disableSecondaryClassifier)
    }

    @Test
    fun engagesCriticalThrottleWhenPssHitsHardBudget() {
        val snap = DeviceResourceSnapshot(
            pssMb = 148f,
            systemLowMemory = false,
            isLowRamDevice = false,
            isPowerSaveMode = false,
            thermalStatus = MemoryAndThermalGovernor.THERMAL_LIGHT
        )

        val adj = governor.evaluate(snap, PerformanceProfile.RESPONSIVE)
        assertEquals(GovernorLevel.CRITICAL_THROTTLE, adj.level)
        assertEquals(320, adj.applyDownscaleWidth(480))
        assertEquals(2.0f, adj.intervalMultiplier, 0.001f)
        assertTrue(adj.disableSecondaryClassifier)
    }
}
