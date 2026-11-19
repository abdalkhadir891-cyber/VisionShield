package com.yusuf.shieldguard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yusuf.shieldguard.data.ProtectionStateHolder
import com.yusuf.shieldguard.perf.DeviceCompatibilityHelper
import com.yusuf.shieldguard.permissions.PermissionManager
import com.yusuf.shieldguard.service.ProtectionForegroundService
import com.yusuf.shieldguard.ui.MainScreen
import com.yusuf.shieldguard.ui.MainViewModel
import com.yusuf.shieldguard.ui.ShieldGuardTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val settingsReturnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ProtectionForegroundService::class.java).apply {
                putExtra(ProtectionForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ProtectionForegroundService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            viewModel.setProtectionRequested(true)
        } else {
            viewModel.setProtectionRequested(false)
            Toast.makeText(this, "MediaProjection consent was not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ShieldGuardTheme {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
                val permissions by viewModel.permissions.collectAsStateWithLifecycle()
                val batteryUnrestricted by viewModel.batteryUnrestricted.collectAsStateWithLifecycle()
                val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
                val ramPssMb by viewModel.ramPssMb.collectAsStateWithLifecycle()

                MainScreen(
                    settings = settings,
                    runtimeState = runtimeState,
                    permissions = permissions,
                    installedApps = installedApps,
                    ramPssMb = ramPssMb,
                    onToggleProtection = { enable -> handleProtectionToggle(enable) },
                    onGrantOverlay = {
                        settingsReturnLauncher.launch(PermissionManager.createOverlaySettingsIntent(this))
                    },
                    onGrantAccessibility = {
                        settingsReturnLauncher.launch(PermissionManager.createAccessibilitySettingsIntent())
                    },
                    onGrantNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onSensitivityChange = viewModel::updateSensitivity,
                    onBlurIntensityChange = viewModel::updateBlurIntensity,
                    onProtectImagesChange = viewModel::setProtectImages,
                    onProtectVideosChange = viewModel::setProtectVideos,
                    onExplicitCategoryChange = viewModel::setDetectExplicit,
                    onGenderCategoryChange = viewModel::setDetectGender,
                    onPerformanceProfileChange = viewModel::setPerformanceProfile,
                    onToggleExcludedPackage = viewModel::toggleExcludedPackage,
                    isBatteryUnrestricted = batteryUnrestricted,
                    onOpenBatterySettings = {
                        runCatching {
                            settingsReturnLauncher.launch(
                                DeviceCompatibilityHelper.createBatteryOptimizationSettingsIntent()
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ProtectionStateHolder.setDashboardVisible(true)
        viewModel.refreshPermissions()
    }

    override fun onPause() {
        super.onPause()
        ProtectionStateHolder.setDashboardVisible(false)
    }

    private fun handleProtectionToggle(enable: Boolean) {
        if (!enable) {
            stopService(Intent(this, ProtectionForegroundService::class.java))
            viewModel.setProtectionRequested(false)
            return
        }

        val status = PermissionManager.checkStatus(this)
        if (!status.hasOverlayPermission) {
            settingsReturnLauncher.launch(PermissionManager.createOverlaySettingsIntent(this))
            return
        }
        if (!status.hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
