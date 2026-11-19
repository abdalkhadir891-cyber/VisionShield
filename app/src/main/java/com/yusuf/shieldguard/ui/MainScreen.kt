package com.yusuf.shieldguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yusuf.shieldguard.R
import com.yusuf.shieldguard.data.PerformanceProfile
import com.yusuf.shieldguard.data.ProtectionRuntimeState
import com.yusuf.shieldguard.data.ProtectionSettings
import com.yusuf.shieldguard.permissions.PermissionStatus
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: ProtectionSettings,
    runtimeState: ProtectionRuntimeState,
    permissions: PermissionStatus,
    installedApps: List<InstalledAppItem>,
    ramPssMb: Float,
    onToggleProtection: (Boolean) -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantNotification: () -> Unit,
    onSensitivityChange: (Int) -> Unit,
    onBlurIntensityChange: (Int) -> Unit,
    onProtectImagesChange: (Boolean) -> Unit,
    onProtectVideosChange: (Boolean) -> Unit,
    onExplicitCategoryChange: (Boolean) -> Unit,
    onGenderCategoryChange: (Boolean) -> Unit,
    onPerformanceProfileChange: (PerformanceProfile) -> Unit,
    onToggleExcludedPackage: (String) -> Unit,
    isBatteryUnrestricted: Boolean = true,
    onOpenBatterySettings: () -> Unit = {}
) {
    var showExclusionsDialog by remember { mutableStateOf(false) }
    val isExcludedActive = settings.isPackageExcluded(runtimeState.activeForegroundPackage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.subtitle_local_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard {
                val statusText = when {
                    !runtimeState.isForegroundServiceRunning -> stringResource(R.string.status_inactive)
                    !runtimeState.isCurrentWindowAccessible || runtimeState.isProtectedBlackFrame ->
                        stringResource(R.string.status_protected_window)
                    isExcludedActive -> stringResource(R.string.status_paused_excluded)
                    else -> stringResource(R.string.status_active)
                }
                val statusColor = if (runtimeState.isForegroundServiceRunning && !isExcludedActive) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = stringResource(R.string.main_switch_label),
                    checked = runtimeState.isForegroundServiceRunning,
                    onCheckedChange = onToggleProtection
                )
            }

            SectionCard(title = stringResource(R.string.section_permissions)) {
                PermissionRow(
                    label = stringResource(R.string.perm_overlay),
                    granted = permissions.hasOverlayPermission,
                    onGrantClick = onGrantOverlay
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                PermissionRow(
                    label = stringResource(R.string.perm_accessibility),
                    granted = permissions.hasAccessibilityPermission,
                    onGrantClick = onGrantAccessibility
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                PermissionRow(
                    label = stringResource(R.string.perm_notifications),
                    granted = permissions.hasNotificationPermission,
                    onGrantClick = onGrantNotification
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                PermissionRow(
                    label = stringResource(R.string.perm_battery_unrestricted),
                    granted = isBatteryUnrestricted,
                    onGrantClick = onOpenBatterySettings
                )
            }

            SectionCard(title = stringResource(R.string.section_detection)) {
                Text(
                    text = stringResource(R.string.sensitivity_label, settings.clampedSensitivity()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.clampedSensitivity().toFloat(),
                    onValueChange = { onSensitivityChange(it.roundToInt()) },
                    valueRange = 10f..95f
                )

                Text(
                    text = stringResource(R.string.blur_intensity_label, settings.clampedBlurIntensity()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.clampedBlurIntensity().toFloat(),
                    onValueChange = { onBlurIntensityChange(it.roundToInt()) },
                    valueRange = 4f..24f
                )

                ToggleRow(
                    label = stringResource(R.string.toggle_images),
                    checked = settings.protectImages,
                    onCheckedChange = onProtectImagesChange
                )
                ToggleRow(
                    label = stringResource(R.string.toggle_videos),
                    checked = settings.protectVideos,
                    onCheckedChange = onProtectVideosChange
                )
                ToggleRow(
                    label = stringResource(R.string.cat_explicit),
                    checked = settings.detectExplicitContent,
                    onCheckedChange = onExplicitCategoryChange
                )
                ToggleRow(
                    label = stringResource(R.string.cat_gender_optional),
                    checked = settings.detectGenderSpecific,
                    onCheckedChange = onGenderCategoryChange
                )
            }

            SectionCard(title = stringResource(R.string.section_performance)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PerformanceProfile.entries.forEach { profile ->
                        val label = when (profile) {
                            PerformanceProfile.BATTERY_SAVER -> stringResource(R.string.perf_mode_battery)
                            PerformanceProfile.BALANCED -> stringResource(R.string.perf_mode_balanced)
                            PerformanceProfile.RESPONSIVE -> stringResource(R.string.perf_mode_responsive)
                        }
                        FilterChip(
                            selected = settings.performanceProfile == profile,
                            onClick = { onPerformanceProfileChange(profile) },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.downscale_label,
                            runtimeState.effectiveDownscaleWidthPx
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(
                title = stringResource(R.string.section_exclusions, settings.excludedPackages.size)
            ) {
                OutlinedButton(
                    onClick = { showExclusionsDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.manage_exclusions_btn))
                }
            }

            SectionCard(title = stringResource(R.string.section_stats)) {
                Text(
                    text = stringResource(R.string.stat_foreground_app, runtimeState.activeForegroundPackage),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.stat_frames_and_skipped,
                        runtimeState.framesAnalyzed,
                        runtimeState.skippedInferenceTicks
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.stat_tracks_and_latency,
                        runtimeState.activeBlurredRegions,
                        runtimeState.activeTrackedCount,
                        runtimeState.lastInferenceMs
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.stat_motion_and_governor,
                        runtimeState.motionStateName,
                        runtimeState.governorStatus
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.stat_protected_windows, runtimeState.protectedWindowEvents),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.stat_ram_pss, ramPssMb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    text = stringResource(R.string.stat_model_health, runtimeState.modelHealthSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showExclusionsDialog) {
        AlertDialog(
            onDismissRequest = { showExclusionsDialog = false },
            title = { Text(stringResource(R.string.exclusions_dialog_title)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(installedApps, key = { it.packageName }) { appItem ->
                        val isExcluded = settings.excludedPackages.contains(appItem.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isExcluded,
                                onCheckedChange = { onToggleExcludedPackage(appItem.packageName) }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(text = appItem.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = appItem.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExclusionsDialog = false }) {
                    Text(stringResource(R.string.close_label))
                }
            }
        )
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Text(
                text = stringResource(R.string.perm_granted),
                color = Color(0xFF10B981),
                fontWeight = FontWeight.Bold
            )
        } else {
            OutlinedButton(onClick = onGrantClick) {
                Text(stringResource(R.string.perm_grant_action))
            }
        }
    }
}
