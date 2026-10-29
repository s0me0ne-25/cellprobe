// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.main

import android.text.format.DateFormat
import android.text.format.Formatter
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.engine.CheckResult
import org.s0me0ne25.cellprobe.engine.CheckState
import org.s0me0ne25.cellprobe.ui.components.ListSectionCard
import org.s0me0ne25.cellprobe.ui.descriptionRes
import org.s0me0ne25.cellprobe.ui.labelRes
import org.s0me0ne25.cellprobe.ui.theme.color
import org.s0me0ne25.cellprobe.ui.titleRes
import org.s0me0ne25.cellprobe.ui.tone
import java.time.LocalDate
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenDetails: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val providerPages by viewModel.providerPages.collectAsStateWithLifecycle()

    // Apps may have been installed or removed while the screen was away.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshApps()
        onPauseOrDispose {}
    }

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
            state.result?.let { result ->
                StatusActionsSection(result, state.sections, roles, providerPages, viewModel::launchIntent)
            }
            state.sections.firstOrNull { it.id == CheckListId.VLESS && it.rows.isNotEmpty() }?.let { ListSectionCard(it) }
            DiagnosticsCard(state, traffic)
            Button(onClick = viewModel::checkNow, enabled = !state.running) {
                Text(stringResource(R.string.action_check_now))
            }
            OutlinedButton(onClick = onOpenDetails, enabled = state.running || state.result != null) {
                Text(stringResource(R.string.diagnostics_title))
            }
            OutlinedButton(onClick = onOpenJournal) {
                Text(stringResource(R.string.journal_title))
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
                    text = stringResource(result.status.titleRes()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = result.status.tone().color(),
                )
                result.status.descriptionRes()?.let { description ->
                    Text(
                        text = stringResource(description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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

/** Informational lines: check traffic, entries with Russian state certificates. */
@Composable
private fun DiagnosticsCard(state: CheckState, traffic: TrafficTotals) {
    val context = LocalContext.current
    val today = traffic.forDate(LocalDate.now())
    val ruCaEntries = state.sections.flatMap { section ->
        section.rows.filter { it.outcome?.code == ProbeCode.CERT_RU_CA }.map { it.name }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.main_traffic,
                    Formatter.formatShortFileSize(context, today.dayBytes),
                    Formatter.formatShortFileSize(context, today.monthBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ruCaEntries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.main_cert_ru_ca, ruCaEntries.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** "Wi-Fi · checked at 14:05". */
@Composable
private fun resultDetails(result: CheckResult): String {
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(result.finishedAtMillis))
    return stringResource(R.string.main_status_details, stringResource(result.networkType.labelRes()), time)
}
