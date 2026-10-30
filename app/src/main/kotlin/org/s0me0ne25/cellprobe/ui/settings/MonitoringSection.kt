// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.settings

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Background monitoring switch and the two check intervals (spec §8, §9.2).
 * Turning monitoring on asks for the notification permission on API 33+;
 * monitoring also works without it, only the status notification is hidden.
 */
@Composable
fun MonitoringSection(
    settings: MonitoringSettings,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (screenOn: Boolean, interval: Duration) -> Unit,
) {
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    fun notificationsAllowed() = !needsPermission ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Monitoring is turned on whatever the answer.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onEnabledChange(true)
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
            if (enabled && !notificationsAllowed() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onEnabledChange(enabled)
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.monitoring_enabled)) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.monitoring_enabled_description))
                    if (settings.enabled && !notificationsAllowed()) {
                        Text(
                            text = stringResource(R.string.monitoring_notifications_denied),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            trailingContent = { Switch(checked = settings.enabled, onCheckedChange = null) },
            modifier = Modifier.clickable(role = Role.Switch) { toggle(!settings.enabled) },
        )
        HorizontalDivider()
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
