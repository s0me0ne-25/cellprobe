// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.main

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.actions.ProviderPageLabels
import org.s0me0ne25.cellprobe.core.actions.StatusAction
import org.s0me0ne25.cellprobe.core.actions.StatusActions
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.engine.CheckResult
import org.s0me0ne25.cellprobe.engine.ListSection
import org.s0me0ne25.cellprobe.ui.apps.AppIcon
import org.s0me0ne25.cellprobe.ui.apps.AppPickerDialog
import org.s0me0ne25.cellprobe.ui.apps.actionRes
import org.s0me0ne25.cellprobe.ui.apps.openSettings
import org.s0me0ne25.cellprobe.ui.apps.openUrl
import org.s0me0ne25.cellprobe.ui.apps.tryStart

/**
 * What the user can do about the last status (spec §7.3): launch the app of
 * a role, open a provider account page or a system settings screen. The
 * main action is a filled button, the others are outlined.
 */
@Composable
fun StatusActionsSection(
    result: CheckResult,
    sections: List<ListSection>,
    roles: Map<AppRole, RoleApp>,
    providerPages: List<String>,
    launchIntent: (AppRole) -> Intent?,
) {
    val actions = StatusActions.forStatus(result.status, result.networkType)
    if (actions.isEmpty()) return

    val context = LocalContext.current
    var pickerRole by rememberSaveable { mutableStateOf<AppRole?>(null) }
    var failedLabel by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        failedLabel?.let { label ->
            Text(
                text = stringResource(R.string.main_launch_failed, label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        actions.forEachIndexed { index, action ->
            val primary = index == 0
            when (action) {
                is StatusAction.LaunchRole -> {
                    val app = roles[action.role]
                    val label = app?.label
                    if (app != null && label != null) {
                        ActionButton(primary = primary, onClick = {
                            val intent = launchIntent(action.role)
                            if (intent != null && context.tryStart(intent)) {
                                failedLabel = null
                            } else {
                                failedLabel = label
                                pickerRole = action.role
                            }
                        }) {
                            AppIcon(app.packageName, 24.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_open_app, label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        if (app != null) {
                            Text(
                                text = stringResource(R.string.role_app_missing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        ActionButton(primary = primary, onClick = { pickerRole = action.role }) {
                            Text(stringResource(action.role.chooseRes()))
                        }
                    }
                }
                StatusAction.ProviderPages -> ProviderPageButtons(providerPages, sections)
                is StatusAction.OpenSettings -> ActionButton(primary = primary, onClick = { context.openSettings(action.screen) }) {
                    Text(stringResource(action.screen.actionRes()))
                }
            }
        }
    }

    pickerRole?.let { role ->
        AppPickerDialog(role = role, current = roles[role]?.packageName, onDismiss = { pickerRole = null })
    }
}

/** One button per provider page, marked if the page responded in the last check. */
@Composable
private fun ProviderPageButtons(urls: List<String>, sections: List<ListSection>) {
    if (urls.isEmpty()) {
        Text(
            text = stringResource(R.string.main_provider_pages_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val context = LocalContext.current
    val labels = ProviderPageLabels.labels(urls)
    val rows = sections.firstOrNull { it.id == CheckListId.PROVIDER }?.rows
    urls.forEachIndexed { index, url ->
        // Rows follow the list order, unless the list changed since the check.
        val responded = rows?.takeIf { it.size == urls.size }?.get(index)?.outcome?.code?.reachable == true
        ActionButton(primary = index == 0, onClick = { context.openUrl(url) }) {
            Text(if (responded) "✅ ${labels[index]}" else labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionButton(primary: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val modifier = Modifier.fillMaxWidth()
    val row: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { row() }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { row() }
    }
}

private fun AppRole.chooseRes(): Int = when (this) {
    AppRole.VPN_CLIENT -> R.string.action_choose_vpn_client
    AppRole.OLCRTC -> R.string.action_choose_olcrtc
    AppRole.I2P -> R.string.action_choose_i2p
}
