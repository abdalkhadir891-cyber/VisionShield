package com.yusuf.shieldguard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yusuf.shieldguard.data.ProtectionRuntimeState
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.permissions.PermissionStatus
import com.yusuf.shieldguard.ui.MainScreen
import com.yusuf.shieldguard.ui.ShieldGuardTheme
import org.junit.Rule
import org.junit.Test

class MainScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardRendersCoreControlsAndStatus() {
        composeRule.setContent {
            ShieldGuardTheme {
                MainScreen(
                    settings = ProtectionSettings(),
                    runtimeState = ProtectionRuntimeState(isForegroundServiceRunning = false),
                    permissions = PermissionStatus(
                        hasOverlayPermission = false,
                        hasAccessibilityPermission = false,
                        hasNotificationPermission = true
                    ),
                    installedApps = emptyList(),
                    ramPssMb = 42.5f,
                    onToggleProtection = {},
                    onGrantOverlay = {},
                    onGrantAccessibility = {},
                    onGrantNotification = {},
                    onSensitivityChange = {},
                    onBlurIntensityChange = {},
                    onProtectImagesChange = {},
                    onProtectVideosChange = {},
                    onExplicitCategoryChange = {},
                    onGenderCategoryChange = {},
                    onPerformanceProfileChange = {},
                    onToggleExcludedPackage = {}
                )
            }
        }

        composeRule.onNodeWithText("ShieldGuard").assertIsDisplayed()
        composeRule.onNodeWithText("Protection Inactive").assertIsDisplayed()
        composeRule.onNodeWithText("Manage Excluded Apps").assertIsDisplayed()
    }
}
