package com.yusuf.shieldguard.ui

import android.app.Application
import android.content.Intent
import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yusuf.shieldguard.data.PerformanceProfile
import com.yusuf.shieldguard.data.ProtectionRuntimeState
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.data.ProtectionStateHolder
import com.yusuf.shieldguard.data.SettingsRepository
import com.yusuf.shieldguard.data.settingsDataStore
import com.yusuf.shieldguard.perf.DeviceCompatibilityHelper
import com.yusuf.shieldguard.permissions.PermissionManager
import com.yusuf.shieldguard.permissions.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class InstalledAppItem(
    val packageName: String,
    val label: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application.settingsDataStore)

    val settings: StateFlow<ProtectionSettings> = repository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProtectionSettings()
    )

    val runtimeState: StateFlow<ProtectionRuntimeState> = ProtectionStateHolder.state

    private val _permissions = MutableStateFlow(PermissionManager.checkStatus(application))
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _batteryUnrestricted = MutableStateFlow(
        DeviceCompatibilityHelper.isIgnoringBatteryOptimizations(application)
    )
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private val _ramPssMb = MutableStateFlow(0f)
    val ramPssMb: StateFlow<Float> = _ramPssMb.asStateFlow()

    init {
        loadLaunchableApps()
        startMemoryPolling()
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        _permissions.value = PermissionManager.checkStatus(app)
        _batteryUnrestricted.value = DeviceCompatibilityHelper.isIgnoringBatteryOptimizations(app)
    }

    private fun loadLaunchableApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val pm = app.packageManager
            val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(queryIntent, 0)
            val items = resolved
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == app.packageName) return@mapNotNull null
                    val label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }
                    InstalledAppItem(packageName = pkg, label = label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            _installedApps.value = items
        }
    }

    private fun startMemoryPolling() {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                _ramPssMb.value = Debug.getPss() / 1024f
                delay(1500L)
            }
        }
    }

    fun setProtectionRequested(enabled: Boolean) {
        viewModelScope.launch { repository.setProtectionRequested(enabled) }
    }

    fun updateSensitivity(percent: Int) {
        viewModelScope.launch { repository.setSensitivity(percent) }
    }

    fun updateBlurIntensity(level: Int) {
        viewModelScope.launch { repository.setBlurIntensity(level) }
    }

    fun setProtectImages(enabled: Boolean) {
        viewModelScope.launch { repository.setProtectImages(enabled) }
    }

    fun setProtectVideos(enabled: Boolean) {
        viewModelScope.launch { repository.setProtectVideos(enabled) }
    }

    fun setDetectExplicit(enabled: Boolean) {
        viewModelScope.launch { repository.setDetectExplicit(enabled) }
    }

    fun setDetectGender(enabled: Boolean) {
        viewModelScope.launch { repository.setDetectGender(enabled) }
    }

    fun setPerformanceProfile(profile: PerformanceProfile) {
        viewModelScope.launch { repository.setPerformanceProfile(profile) }
    }

    fun toggleExcludedPackage(packageName: String) {
        viewModelScope.launch { repository.toggleExcludedPackage(packageName) }
    }
}
