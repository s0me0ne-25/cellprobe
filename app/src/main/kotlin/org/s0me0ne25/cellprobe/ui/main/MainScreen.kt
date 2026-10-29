// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.main

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.engine.CheckResult
import org.s0me0ne25.cellprobe.engine.CheckState
import org.s0me0ne25.cellprobe.ui.labelRes
import org.s0me0ne25.cellprobe.ui.textRes
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenDetails: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state)
            Button(onClick = viewModel::checkNow, enabled = !state.running) {
                Text(stringResource(R.string.action_check_now))
            }
            OutlinedButton(onClick = onOpenDetails, enabled = state.running || state.result != null) {
                Text(stringResource(R.string.diagnostics_title))
            }
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.settings_title))
            }
        }
    }
}

@Composable
private fun StatusCard(state: CheckState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.main_status_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val result = state.result
            if (result == null) {
                Text(
                    text = stringResource(if (state.running) R.string.main_status_checking else R.string.main_status_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(result.status.textRes()),
                    style = MaterialTheme.typography.titleLarge,
                    color = statusColor(result.status),
                )
                Text(
                    text = resultDetails(result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.running) {
                    Text(
                        text = stringResource(R.string.main_status_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.failed) {
                Text(
                    text = stringResource(R.string.diagnostics_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Soft modes in the primary color, filtering and failures in the error color. */
@Composable
private fun statusColor(status: NetworkStatus): Color = when (status.severity) {
    0 -> MaterialTheme.colorScheme.primary
    null -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.error
}

/** "Wi-Fi · checked at 14:05". */
@Composable
private fun resultDetails(result: CheckResult): String {
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(result.finishedAtMillis))
    return stringResource(R.string.main_status_details, stringResource(result.networkType.labelRes()), time)
}
