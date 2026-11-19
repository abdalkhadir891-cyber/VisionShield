package com.yusuf.shieldguard.data

enum class PerformanceProfile(val frameIntervalMs: Long, val downscaleWidthPx: Int) {
    BATTERY_SAVER(frameIntervalMs = 660L, downscaleWidthPx = 320),
    BALANCED(frameIntervalMs = 400L, downscaleWidthPx = 448),
    RESPONSIVE(frameIntervalMs = 250L, downscaleWidthPx = 480)
}

data class ProtectionSettings(
    val protectionRequested: Boolean = false,
    val sensitivityPercent: Int = 60,
    val blurIntensity: Int = 12,
    val protectImages: Boolean = true,
    val protectVideos: Boolean = true,
    val detectExplicitContent: Boolean = true,
    val detectGenderSpecific: Boolean = true,
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
    val excludedPackages: Set<String> = emptySet()
) {
    fun clampedSensitivity(): Int = sensitivityPercent.coerceIn(10, 95)

    fun confidenceThreshold(): Float {
        val normalized = (clampedSensitivity() - 10) / 85f
        return (0.85f - normalized * 0.60f).coerceIn(0.25f, 0.85f)
    }

    fun clampedBlurIntensity(): Int = blurIntensity.coerceIn(4, 24)

    fun isPackageExcluded(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return excludedPackages.contains(packageName)
    }
}
