// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.journal

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.journal.JournalEntry
import org.s0me0ne25.cellprobe.core.journal.JournalList
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.engine.EntryRow
import org.s0me0ne25.cellprobe.engine.ListSection
import org.s0me0ne25.cellprobe.ui.components.BackButton
import org.s0me0ne25.cellprobe.ui.components.ListSectionCard
import org.s0me0ne25.cellprobe.ui.labelRes
import org.s0me0ne25.cellprobe.ui.theme.color
import org.s0me0ne25.cellprobe.ui.titleRes
import org.s0me0ne25.cellprobe.ui.tone
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    viewModel: JournalViewModel = viewModel(factory = JournalViewModel.Factory),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // Expanded entries, by time: stable across new checks at the top.
    var expanded by rememberSaveable { mutableStateOf(emptyList<Long>()) }

    // Opening the journal always shows the newest entry. Later entries are
    // added above without moving the list, so reading older ones is not
    // disturbed; a configuration change is not an opening.
    val listState = rememberLazyListState()
    var openedAtTop by rememberSaveable { mutableStateOf(false) }
    val loaded = entries != null
    LaunchedEffect(loaded) {
        if (loaded && !openedAtTop) {
            listState.scrollToItem(0)
            openedAtTop = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = { confirmClear = true }, enabled = !entries.isNullOrEmpty()) {
                        Text(stringResource(R.string.journal_clear))
                    }
                },
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entries?.isEmpty() == true) {
                item {
                    Text(
                        text = stringResource(R.string.journal_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(entries.orEmpty(), key = { it.timeMillis }) { entry ->
                val isExpanded = entry.timeMillis in expanded
                JournalEntryCard(
                    entry = entry,
                    expanded = isExpanded,
                    onToggle = {
                        expanded = if (isExpanded) expanded - entry.timeMillis else expanded + entry.timeMillis
                    },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.journal_clear_confirm_title)) },
            text = { Text(stringResource(R.string.journal_clear_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clear()
                    },
                ) { Text(stringResource(R.string.journal_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun JournalEntryCard(entry: JournalEntry, expanded: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = entry.lists.isNotEmpty(), onClick = onToggle)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entryTime(entry.timeMillis),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(entry.status.titleRes()),
                style = MaterialTheme.typography.titleMedium,
                color = entry.status.tone().color(),
            )
            Text(
                text = entryDetails(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entry.lists.forEach { ListSectionCard(it.toSection()) }
            }
        }
    }
}

/** "Mobile network · 1.2 MB", plus a note if nothing was probed or the check was cut short. */
@Composable
private fun entryDetails(entry: JournalEntry): String {
    val context = LocalContext.current
    val parts = mutableListOf(stringResource(entry.networkType.labelRes()))
    entry.trafficBytes?.let { parts += Formatter.formatShortFileSize(context, it) }
    when {
        entry.interrupted -> parts += stringResource(R.string.journal_interrupted)
        entry.lists.isEmpty() -> parts += stringResource(R.string.journal_not_probed)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun entryTime(millis: Long): String {
    val context = LocalContext.current
    val date = Date(millis)
    return "${DateFormat.getMediumDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
}

private fun JournalList.toSection() = ListSection(
    id = id,
    rows = probes.map { probe ->
        EntryRow(
            name = probe.name,
            outcome = ProbeOutcome(
                code = probe.code,
                durationMillis = probe.millis,
                httpStatus = probe.httpStatus,
                receivedBytes = probe.receivedBytes,
                errorDetail = probe.error,
            ),
        )
    },
    round = if (retried) 2 else 1,
    available = available,
)
