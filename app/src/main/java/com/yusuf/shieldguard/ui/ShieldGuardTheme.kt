package com.yusuf.shieldguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ShieldDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF052E16),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF131D31),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFEF4444)
)

@Composable
fun ShieldGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShieldDarkColorScheme,
        content = content
    )
}
