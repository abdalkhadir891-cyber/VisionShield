package com.yusuf.shieldguard.permissions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.yusuf.shieldguard.service.GuardAccessibilityService

data class PermissionStatus(
    val hasOverlayPermission: Boolean,
    val hasAccessibilityPermission: Boolean,
    val hasNotificationPermission: Boolean
) {
    val allRequiredGranted: Boolean
        get() = hasOverlayPermission && hasAccessibilityPermission && hasNotificationPermission
}

object PermissionManager {

    fun checkStatus(context: Context): PermissionStatus {
        return PermissionStatus(
            hasOverlayPermission = Settings.canDrawOverlays(context),
            hasAccessibilityPermission = isAccessibilityEnabled(context),
            hasNotificationPermission = hasNotificationPermission(context)
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedComponent = ComponentName(context, GuardAccessibilityService::class.java)
            .flattenToString()

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = splitter.next()
            if (componentName.equals(expectedComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun createOverlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
