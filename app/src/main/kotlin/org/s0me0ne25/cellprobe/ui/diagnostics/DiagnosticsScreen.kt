// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.ui.components.BackButton
import org.s0me0ne25.cellprobe.ui.titleRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A new check on every visit, but not on configuration changes.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            viewModel.start()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = viewModel::start, enabled = !state.running) {
                        Text(stringResource(R.string.diagnostics_repeat))
                    }
                },
            )
        },
    ) { innerPadding ->
        // Side insets matter in landscape, where the navigation bar or a
        // display cutout sits at the left or right edge.
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.failed) {
                item {
                    Text(
                        text = stringResource(R.string.diagnostics_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(state.sections, key = { it.id }) { section -> SectionCard(section) }
        }
    }
}

@Composable
private fun SectionCard(section: ListSection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(section.id.titleRes()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = verdictText(section),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (section.available) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (section.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_empty_list),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            section.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                EntryRowView(row)
            }
        }
    }
}

@Composable
private fun verdictText(section: ListSection): String = when (section.available) {
    null -> stringResource(
        if (section.round > 1) R.string.diagnostics_verdict_retrying else R.string.diagnostics_verdict_checking,
    )
    true -> stringResource(R.string.diagnostics_verdict_available)
    false -> stringResource(
        if (section.rows.isEmpty()) R.string.diagnostics_verdict_empty else R.string.diagnostics_verdict_unavailable,
    )
}

@Composable
private fun EntryRowView(row: EntryRow) {
    val outcome = row.outcome
    val (emoji, statusDescription) = statusMark(outcome)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (outcome != null) {
                Text(
                    text = outcomeDetails(outcome),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = emoji,
            fontSize = 20.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .clearAndSetSemantics { contentDescription = statusDescription },
        )
    }
}

@Composable
private fun statusMark(outcome: ProbeOutcome?): Pair<String, String> = when {
    outcome == null -> "⏳" to stringResource(R.string.diagnostics_status_pending)
    outcome.code == ProbeCode.SKIPPED -> "⏭️" to stringResource(R.string.probe_code_skipped)
    outcome.code.reachable -> "✅" to stringResource(R.string.diagnostics_status_ok)
    else -> "❌" to stringResource(R.string.diagnostics_status_failed)
}

/**
 * "HTTP 200 · 143 ms · «Title»" for successes, "Description (CODE) · 5012 ms" otherwise;
 * VLESS inbounds also show the received volume.
 */
@Composable
private fun outcomeDetails(outcome: ProbeOutcome): String {
    if (outcome.code == ProbeCode.SKIPPED) return stringResource(R.string.probe_code_skipped)
    val parts = mutableListOf<String>()
    if (!outcome.code.reachable) {
        parts += stringResource(R.string.diagnostics_code_format, stringResource(outcome.code.descriptionRes()), outcome.code.name)
    } else if (outcome.code != ProbeCode.OK) {
        parts += stringResource(outcome.code.descriptionRes())
    }
    outcome.httpStatus?.let { parts += stringResource(R.string.diagnostics_http_status, it) }
    parts += stringResource(R.string.diagnostics_millis, outcome.durationMillis)
    outcome.receivedBytes?.let { parts += stringResource(R.string.diagnostics_received_kb, it / BYTES_PER_KB) }
    outcome.pageTitle?.let { parts += stringResource(R.string.diagnostics_page_title, it) }
    return parts.joinToString(" · ")
}

@StringRes
private fun ProbeCode.descriptionRes(): Int = when (this) {
    ProbeCode.OK -> R.string.probe_code_ok
    ProbeCode.OK_UNVERIFIED -> R.string.probe_code_ok_unverified
    ProbeCode.DNS_FAIL -> R.string.probe_code_dns_fail
    ProbeCode.TCP_TIMEOUT -> R.string.probe_code_tcp_timeout
    ProbeCode.TCP_RESET -> R.string.probe_code_tcp_reset
    ProbeCode.TLS_FAIL -> R.string.probe_code_tls_fail
    ProbeCode.CERT_INVALID -> R.string.probe_code_cert_invalid
    ProbeCode.CERT_RU_CA -> R.string.probe_code_cert_ru_ca
    ProbeCode.HTTP_ERROR -> R.string.probe_code_http_error
    ProbeCode.STALLED -> R.string.probe_code_stalled
    ProbeCode.CONTENT_MISMATCH -> R.string.probe_code_content_mismatch
    ProbeCode.SKIPPED -> R.string.probe_code_skipped
}

private const val BYTES_PER_KB = 1024L
