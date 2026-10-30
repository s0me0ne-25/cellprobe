// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringSettings
import org.s0me0ne25.cellprobe.system.MonitoringPermission
import org.s0me0ne25.cellprobe.system.MonitoringPermissions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Background monitoring switch and the two check intervals (spec §8, §9.2,
 * §9.3).
 *
 * Turning monitoring on also walks the user through what background work
 * needs and is not granted yet ([MonitoringPermission]): an explanation
 * first, then the system request, or skip. Monitoring is on whatever the
 * answers; what is still missing stays listed under the switch, rechecked
 * whenever the screen is shown again.
 */
@Composable
fun MonitoringSection(
    settings: MonitoringSettings,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (screenOn: Boolean, interval: Duration) -> Unit,
) {
    val context = LocalContext.current
    val permissions = remember(context) { MonitoringPermissions(context) }

    // Bumped on every resume: the user may have changed a permission in the system settings.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val missing = remember(resumes) { permissions.missing() }

    // Onboarding: the permissions still to explain, in order.
    var onboarding by rememberSaveable { mutableStateOf(emptyList<MonitoringPermission>()) }
    val nextStep = { onboarding = onboarding.drop(1).filterNot(permissions::isGranted) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumes++
        nextStep()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resumes++
        nextStep()
    }
    fun request(permission: MonitoringPermission, fromOnboarding: Boolean) {
        val launched = try {
            val intent = permissions.requestIntent(permission)
            when {
                intent != null -> settingsLauncher.launch(intent)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && fromOnboarding ->
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                // Once refused, the runtime request may no longer show: the app's notification settings always do.
                else -> settingsLauncher.launch(permissions.notificationSettingsIntent())
            }
            true
        } catch (_: ActivityNotFoundException) {
            // Some vendor builds lack the screen: nothing to ask.
            false
        }
        if (!launched && fromOnboarding) nextStep()
    }

    var intervalDialog by rememberSaveable { mutableStateOf<Boolean?>(null) }

    Column {
        Text(
            text = stringResource(R.string.settings_section_monitoring),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        val toggle = { enabled: Boolean ->
            onEnabledChange(enabled)
            if (enabled) onboarding = permissions.missing()
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.monitoring_enabled)) },
            supportingContent = { Text(stringResource(R.string.monitoring_enabled_description)) },
            trailingContent = { Switch(checked = settings.enabled, onCheckedChange = null) },
            modifier = Modifier.clickable(role = Role.Switch) { toggle(!settings.enabled) },
        )
        HorizontalDivider()
        if (settings.enabled) {
            for (permission in missing) {
                ListItem(
                    headlineContent = { Text(stringResource(permission.titleRes)) },
                    supportingContent = {
                        Text(text = stringResource(permission.missingRes), color = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { request(permission, fromOnboarding = false) },
                )
                HorizontalDivider()
            }
        }
        for (screenOn in listOf(true, false)) {
            ListItem(
                headlineContent = {
                    Text(stringResource(if (screenOn) R.string.monitoring_interval_screen_on else R.string.monitoring_interval_screen_off))
                },
                supportingContent = {
                    Text(text = intervalText(settings.interval(screenOn)), color = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { intervalDialog = screenOn },
            )
            HorizontalDivider()
        }
    }

    onboarding.firstOrNull()?.let { permission ->
        AlertDialog(
            onDismissRequest = nextStep,
            title = { Text(stringResource(permission.titleRes)) },
            text = { Text(stringResource(permission.explanationRes)) },
            confirmButton = {
                TextButton(onClick = { request(permission, fromOnboarding = true) }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = nextStep) { Text(stringResource(R.string.action_skip)) }
            },
        )
    }

    intervalDialog?.let { screenOn ->
        IntervalDialog(
            title = stringResource(if (screenOn) R.string.monitoring_interval_screen_on else R.string.monitoring_interval_screen_off),
            current = settings.interval(screenOn),
            onPick = { interval ->
                onIntervalChange(screenOn, interval)
                intervalDialog = null
            },
            onDismiss = { intervalDialog = null },
        )
    }
}

private val MonitoringPermission.titleRes: Int
    get() = when (this) {
        MonitoringPermission.NOTIFICATIONS -> R.string.permission_notifications_title
        MonitoringPermission.BATTERY -> R.string.permission_battery_title
        MonitoringPermission.EXACT_ALARMS -> R.string.permission_exact_alarms_title
    }

private val MonitoringPermission.explanationRes: Int
    get() = when (this) {
        MonitoringPermission.NOTIFICATIONS -> R.string.permission_notifications_explanation
        MonitoringPermission.BATTERY -> R.string.permission_battery_explanation
        MonitoringPermission.EXACT_ALARMS -> R.string.permission_exact_alarms_explanation
    }

private val MonitoringPermission.missingRes: Int
    get() = when (this) {
        MonitoringPermission.NOTIFICATIONS -> R.string.permission_notifications_missing
        MonitoringPermission.BATTERY -> R.string.permission_battery_missing
        MonitoringPermission.EXACT_ALARMS -> R.string.permission_exact_alarms_missing
    }

@Composable
private fun IntervalDialog(title: String, current: Duration, onPick: (Duration) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (choice in MonitoringSettings.INTERVAL_CHOICES) {
                    ListItem(
                        headlineContent = { Text(intervalText(choice)) },
                        leadingContent = { RadioButton(selected = choice == current, onClick = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = choice == current, role = Role.RadioButton) { onPick(choice) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** "30 s", "3 min". */
@Composable
private fun intervalText(interval: Duration): String =
    if (interval < 60.seconds) {
        stringResource(R.string.interval_seconds, interval.inWholeSeconds)
    } else {
        stringResource(R.string.interval_minutes, interval.inWholeMinutes)
    }
