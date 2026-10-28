// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.vless

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.vless.VlessEntry
import org.s0me0ne25.cellprobe.ui.components.BackButton
import org.s0me0ne25.cellprobe.ui.components.Emoji
import org.s0me0ne25.cellprobe.ui.components.EmojiButton
import org.s0me0ne25.cellprobe.ui.messageRes

/** Marker for the "add new entry" dialog in [rememberSaveable] state. */
private const val NEW_ENTRY = ""

/** Name shown for an entry: its label, or the address when there is none. */
private val VlessEntry.title: String
    get() = label ?: address

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlessEditorScreen(
    onBack: () -> Unit,
    viewModel: VlessEditorViewModel = viewModel(factory = VlessEditorViewModel.Factory),
) {
    val loaded by viewModel.entries.collectAsStateWithLifecycle()
    val entries = loaded.orEmpty()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    // Dialog targets are kept as entry keys so they survive configuration
    // changes: null - closed, NEW_ENTRY - adding, otherwise the edited entry.
    var dialogTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(resources.getString(it.messageRes())) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vless_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialogTarget = NEW_ENTRY }) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.editor_add),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                ),
            // Extra bottom space keeps the last row clear of the FAB.
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            item {
                Text(
                    text = stringResource(R.string.vless_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (loaded != null && entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.vless_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(entries, key = { it.key }) { entry ->
                VlessRow(
                    entry = entry,
                    onEdit = { dialogTarget = entry.key },
                    onDelete = { deleteTarget = entry.key },
                )
                HorizontalDivider()
            }
        }
    }

    // Close dialogs whose entry disappeared meanwhile.
    LaunchedEffect(entries, dialogTarget, deleteTarget) {
        if (loaded == null) return@LaunchedEffect
        val keys = entries.map { it.key }.toSet()
        if (dialogTarget.let { it != null && it != NEW_ENTRY && it !in keys }) dialogTarget = null
        if (deleteTarget.let { it != null && it !in keys }) deleteTarget = null
    }

    dialogTarget?.let { target ->
        val editing = entries.firstOrNull { it.key == target }
        if (target != NEW_ENTRY && editing == null) return@let
        VlessEntryDialog(
            initial = editing,
            existingKeys = entries.map { it.key }.toSet(),
            onDismiss = { dialogTarget = null },
            onConfirm = { entry ->
                if (editing == null) viewModel.add(entry) else viewModel.replace(editing.key, entry)
                dialogTarget = null
            },
        )
    }

    deleteTarget?.let { key ->
        val entry = entries.firstOrNull { it.key == key } ?: return@let
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.editor_delete_confirm_title, entry.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(key)
                    deleteTarget = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun VlessRow(
    entry: VlessEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val details = listOfNotNull(
        entry.address.takeIf { entry.label != null },
        stringResource(R.string.vless_sni_line, entry.displaySni),
        entry.fingerprint,
    ).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        EmojiButton(
            emoji = Emoji.EDIT,
            contentDescription = stringResource(R.string.editor_edit_entry, entry.title),
            onClick = onEdit,
        )
        EmojiButton(
            emoji = Emoji.DELETE,
            contentDescription = stringResource(R.string.editor_delete_entry, entry.title),
            onClick = onDelete,
        )
    }
}
