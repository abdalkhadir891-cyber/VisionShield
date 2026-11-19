package com.yusuf.shieldguard

import com.yusuf.shieldguard.data.PerformanceProfile
import com.yusuf.shieldguard.data.ProtectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionSettingsTest {

    @Test
    fun sensitivityClampsAndMapsToConfidenceThreshold() {
        val low = ProtectionSettings(sensitivityPercent = 0)
        assertEquals(10, low.clampedSensitivity())
        assertEquals(0.85f, low.confidenceThreshold(), 0.001f)

        val high = ProtectionSettings(sensitivityPercent = 120)
        assertEquals(95, high.clampedSensitivity())
        assertEquals(0.25f, high.confidenceThreshold(), 0.001f)
    }

    @Test
    fun blurIntensityRespectsBounds() {
        assertEquals(4, ProtectionSettings(blurIntensity = 1).clampedBlurIntensity())
        assertEquals(24, ProtectionSettings(blurIntensity = 50).clampedBlurIntensity())
    }

    @Test
    fun packageExclusionsMatchExactPackageName() {
        val settings = ProtectionSettings(excludedPackages = setOf("com.example.bank", "com.example.notes"))
        assertTrue(settings.isPackageExcluded("com.example.bank"))
        assertFalse(settings.isPackageExcluded("com.facebook.katana"))
        assertFalse(settings.isPackageExcluded(null))
        assertFalse(settings.isPackageExcluded(""))
    }

    @Test
    fun performanceProfilesMaintainMemorySafeDownscale() {
        assertTrue(PerformanceProfile.BATTERY_SAVER.frameIntervalMs > PerformanceProfile.BALANCED.frameIntervalMs)
        assertTrue(PerformanceProfile.BALANCED.frameIntervalMs > PerformanceProfile.RESPONSIVE.frameIntervalMs)
        assertTrue(PerformanceProfile.RESPONSIVE.downscaleWidthPx <= 480)
    }
}
