package com.yusuf.shieldguard.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.yusuf.shieldguard.data.ProtectionStateHolder

class GuardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ProtectionStateHolder.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
            if (pkg == "com.android.systemui") return

            val rootNode = try {
                rootInActiveWindow
            } catch (_: SecurityException) {
                null
            }
            val isAccessible = rootNode != null
            rootNode?.recycle()

            ProtectionStateHolder.updateForegroundWindow(
                packageName = pkg,
                isAccessible = isAccessible
            )
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        ProtectionStateHolder.setAccessibilityConnected(false)
        super.onDestroy()
    }
}
