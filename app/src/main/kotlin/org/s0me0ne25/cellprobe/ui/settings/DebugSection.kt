// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.AppContainer
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.stabilizer.Notice
import org.s0me0ne25.cellprobe.core.stabilizer.Volume
import org.s0me0ne25.cellprobe.engine.ProviderPages
import org.s0me0ne25.cellprobe.ui.localAppContainer
import org.s0me0ne25.cellprobe.ui.titleRes

/**
 * Debug builds only: posts a transition notification for any status, to
 * try the notification actions (spec §7.3, §7.4) without waiting for the
 * network to change. Not translated: users never see it.
 */
@Composable
fun DebugSection() {
    val container = localAppContainer()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "Debug",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        ListItem(
            headlineContent = { Text("Test transition notification") },
            supportingContent = {
                Text("A loud notification for a chosen status, with its actions; provider pages are marked by the last check.")
            },
            modifier = Modifier.clickable { choosing = true },
        )
        HorizontalDivider()
    }

    if (choosing) {
        val options: List<Pair<String, Notice>> =
            NetworkStatus.entries.map { status ->
                stringResource(status.titleRes()) to Notice.StatusChanged(status, null, Volume.LOUD)
            } + ("UNSTABLE" to Notice.Unstable(listOf(NetworkStatus.NORMAL, NetworkStatus.WHITELIST), null, Volume.LOUD))
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Status") },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    for ((label, notice) in options) {
                        ListItem(
                            headlineContent = { Text(label) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                choosing = false
                                scope.launch { post(container, notice) }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Posts [notice] as the stabilizer would, on the current network type. */
private suspend fun post(container: AppContainer, notice: Notice) {
    val networkType = withContext(Dispatchers.IO) { container.networkStateReader.read().activeNetwork }
    val withNetwork = when (notice) {
        is Notice.StatusChanged -> notice.copy(networkType = networkType)
        is Notice.Unstable -> notice.copy(networkType = networkType)
        is Notice.Stabilized -> notice.copy(networkType = networkType)
    }
    val urls = container.settingsRepository.resourceList(ResourceListId.PROVIDER).first().entries.map { it.url }
    val pages = ProviderPages.of(urls, container.checkController.state.value.sections)
    container.transitionNotifications.show(withNetwork, pages)
}
