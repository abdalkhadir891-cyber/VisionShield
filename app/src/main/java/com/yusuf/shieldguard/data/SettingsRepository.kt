package com.yusuf.shieldguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shield_guard_settings"
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val PROTECTION_REQUESTED = booleanPreferencesKey("protection_requested")
        val SENSITIVITY_PERCENT = intPreferencesKey("sensitivity_percent")
        val BLUR_INTENSITY = intPreferencesKey("blur_intensity")
        val PROTECT_IMAGES = booleanPreferencesKey("protect_images")
        val PROTECT_VIDEOS = booleanPreferencesKey("protect_videos")
        val DETECT_EXPLICIT = booleanPreferencesKey("detect_explicit")
        val DETECT_GENDER = booleanPreferencesKey("detect_gender")
        val PERFORMANCE_PROFILE = stringPreferencesKey("performance_profile")
        val EXCLUDED_PACKAGES = stringSetPreferencesKey("excluded_packages")
    }

    val settingsFlow: Flow<ProtectionSettings> = dataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs ->
            val profileName = prefs[Keys.PERFORMANCE_PROFILE] ?: PerformanceProfile.BALANCED.name
            val profile = runCatching { PerformanceProfile.valueOf(profileName) }
                .getOrDefault(PerformanceProfile.BALANCED)

            ProtectionSettings(
                protectionRequested = prefs[Keys.PROTECTION_REQUESTED] ?: false,
                sensitivityPercent = (prefs[Keys.SENSITIVITY_PERCENT] ?: 60).coerceIn(10, 95),
                blurIntensity = (prefs[Keys.BLUR_INTENSITY] ?: 12).coerceIn(4, 24),
                protectImages = prefs[Keys.PROTECT_IMAGES] ?: true,
                protectVideos = prefs[Keys.PROTECT_VIDEOS] ?: true,
                detectExplicitContent = prefs[Keys.DETECT_EXPLICIT] ?: true,
                detectGenderSpecific = prefs[Keys.DETECT_GENDER] ?: true,
                performanceProfile = profile,
                excludedPackages = prefs[Keys.EXCLUDED_PACKAGES] ?: emptySet()
            )
        }

    suspend fun setProtectionRequested(enabled: Boolean) {
        dataStore.edit { it[Keys.PROTECTION_REQUESTED] = enabled }
    }

    suspend fun setSensitivity(percent: Int) {
        dataStore.edit { it[Keys.SENSITIVITY_PERCENT] = percent.coerceIn(10, 95) }
    }

    suspend fun setBlurIntensity(level: Int) {
        dataStore.edit { it[Keys.BLUR_INTENSITY] = level.coerceIn(4, 24) }
    }

    suspend fun setProtectImages(enabled: Boolean) {
        dataStore.edit { it[Keys.PROTECT_IMAGES] = enabled }
    }

    suspend fun setProtectVideos(enabled: Boolean) {
        dataStore.edit { it[Keys.PROTECT_VIDEOS] = enabled }
    }

    suspend fun setDetectExplicit(enabled: Boolean) {
        dataStore.edit { it[Keys.DETECT_EXPLICIT] = enabled }
    }

    suspend fun setDetectGender(enabled: Boolean) {
        dataStore.edit { it[Keys.DETECT_GENDER] = enabled }
    }

    suspend fun setPerformanceProfile(profile: PerformanceProfile) {
        dataStore.edit { it[Keys.PERFORMANCE_PROFILE] = profile.name }
    }

    suspend fun toggleExcludedPackage(packageName: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.EXCLUDED_PACKAGES] ?: emptySet()
            prefs[Keys.EXCLUDED_PACKAGES] = if (current.contains(packageName)) {
                current - packageName
            } else {
                current + packageName
            }
        }
    }
}
