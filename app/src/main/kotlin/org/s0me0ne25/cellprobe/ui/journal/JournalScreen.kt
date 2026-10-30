// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.journal

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.journal.JournalBlock
import org.s0me0ne25.cellprobe.core.journal.JournalEntry
import org.s0me0ne25.cellprobe.engine.toSection
import org.s0me0ne25.cellprobe.ui.components.BackButton
import org.s0me0ne25.cellprobe.ui.components.ListSectionCard
import org.s0me0ne25.cellprobe.ui.labelRes
import org.s0me0ne25.cellprobe.ui.theme.color
import org.s0me0ne25.cellprobe.ui.titleRes
import org.s0me0ne25.cellprobe.ui.tone
import java.time.Instant
import java.time.ZoneId
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onBack: () -> Unit,
    viewModel: JournalViewModel = viewModel(factory = JournalViewModel.Factory),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // Expanded entries, by time: stable across new checks at the top.
    var expanded by rememberSaveable { mutableStateOf(emptyList<Long>()) }

    // Opening the journal always shows the newest entry. Later entries are
    // added above without moving the list, so reading older ones is not
    // disturbed; a configuration change is not an opening.
    val listState = rememberLazyListState()
    var openedAtTop by rememberSaveable { mutableStateOf(false) }
    val loaded = content != null
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
                    TextButton(onClick = { confirmClear = true }, enabled = content?.isEmpty == false) {
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
            if (content?.isEmpty == true) {
                item {
                    Text(
                        text = stringResource(R.string.journal_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (val shown = content) {
                null -> Unit
                is JournalContent.Detailed -> items(shown.entries, key = { it.timeMillis }) { entry ->
                    val isExpanded = entry.timeMillis in expanded
                    JournalEntryCard(
                        entry = entry,
                        expanded = isExpanded,
                        onToggle = {
                            expanded = if (isExpanded) expanded - entry.timeMillis else expanded + entry.timeMillis
                        },
                    )
                }
                // Keyed by the oldest check: it stays the same while new checks join the block.
                is JournalContent.Compact -> items(shown.blocks, key = { "block-${it.oldestMillis}" }) { block ->
                    JournalBlockCard(block)
                }
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

/** A run of equal results of the compact journal; it does not expand. */
@Composable
private fun JournalBlockCard(block: JournalBlock) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (block.withinOneMinute) entryTime(block.newestMillis) else blockTime(block),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val statusStyle = MaterialTheme.typography.titleMedium
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The network type as an icon as tall as the status line.
                Icon(
                    painter = painterResource(block.networkType.iconRes()),
                    contentDescription = stringResource(block.networkType.labelRes()),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(with(LocalDensity.current) { statusStyle.lineHeight.toDp() }),
                )
                Text(
                    text = stringResource(block.status.titleRes()),
                    style = statusStyle,
                    color = block.status.tone().color(),
                )
            }
        }
    }
}

/** Mobile network, Wi-Fi, Ethernet, a globe for another transport, a crossed circle when there is no network. */
private fun NetworkType?.iconRes(): Int = when (this) {
    NetworkType.MOBILE -> R.drawable.ic_network_mobile
    NetworkType.WIFI -> R.drawable.ic_network_wifi
    NetworkType.ETHERNET -> R.drawable.ic_network_ethernet
    NetworkType.OTHER -> R.drawable.ic_network_other
    null -> R.drawable.ic_network_none
}

/**
 * "30 Oct 2026 12:40 – 13:10" within one day, "29 Oct 2026 23:50 –
 * 30 Oct 2026 00:20" across days.
 */
@Composable
private fun blockTime(block: JournalBlock): String {
    val context = LocalContext.current
    val dateFormat = DateFormat.getMediumDateFormat(context)
    val timeFormat = DateFormat.getTimeFormat(context)
    val oldest = Date(block.oldestMillis)
    val newest = Date(block.newestMillis)
    val newestText = if (sameDay(block.oldestMillis, block.newestMillis)) {
        timeFormat.format(newest)
    } else {
        "${dateFormat.format(newest)} ${timeFormat.format(newest)}"
    }
    return stringResource(R.string.journal_time_range, "${dateFormat.format(oldest)} ${timeFormat.format(oldest)}", newestText)
}

private fun sameDay(aMillis: Long, bMillis: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(aMillis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(bMillis).atZone(zone).toLocalDate()
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
