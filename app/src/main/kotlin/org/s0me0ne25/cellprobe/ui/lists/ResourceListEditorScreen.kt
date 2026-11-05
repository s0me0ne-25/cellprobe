// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.lists

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.data.RussianStateCaResult
import org.s0me0ne25.cellprobe.ui.components.BackButton
import org.s0me0ne25.cellprobe.ui.components.Emoji
import org.s0me0ne25.cellprobe.ui.components.EmojiButton
import org.s0me0ne25.cellprobe.ui.descriptionRes
import org.s0me0ne25.cellprobe.ui.messageRes
import org.s0me0ne25.cellprobe.ui.titleRes

/** Marker for the "add new entry" dialog in [rememberSaveable] state. */
private const val NEW_ENTRY = ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceListEditorScreen(
    listId: ResourceListId,
    onBack: () -> Unit,
    viewModel: ResourceListEditorViewModel = viewModel(
        key = "resource-list-${listId.name}",
        factory = ResourceListEditorViewModel.factory(listId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ruCaAnswered by viewModel.ruCaAnswered.collectAsStateWithLifecycle()
    val ruCaStep by viewModel.ruCaStep.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    // Dialog targets are kept as URLs so they survive configuration changes:
    // null - closed, NEW_ENTRY - adding, otherwise the URL of the edited entry.
    var dialogTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(resources.getString(it.messageRes())) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(listId.titleRes()),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
                    text = stringResource(listId.descriptionRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (listId.preset != null) {
                item {
                    OutlinedButton(
                        onClick = { confirmReset = true },
                        enabled = state?.customized == true,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        Text(stringResource(R.string.editor_reset_to_preset))
                    }
                }
            }
            val entries = state?.entries.orEmpty()
            if (state != null && entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.editor_empty_list),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(entries, key = { it.url }) { entry ->
                EntryRow(
                    entry = entry,
                    ruCa = if (listId.allowsRuCa) RuCaButton(entry.displayName in ruCaAnswered) else null,
                    onRuCa = { viewModel.toggleRuCa(entry) },
                    onEdit = { dialogTarget = entry.url },
                    onDelete = { deleteTarget = entry.url },
                )
                HorizontalDivider()
            }
        }
    }

    val entries = state?.entries.orEmpty()

    // Close dialogs whose entry disappeared meanwhile (e.g. reset to preset).
    LaunchedEffect(entries, dialogTarget, deleteTarget) {
        if (state == null) return@LaunchedEffect
        val urls = entries.map { it.url }.toSet()
        if (dialogTarget.let { it != null && it != NEW_ENTRY && it !in urls }) dialogTarget = null
        if (deleteTarget.let { it != null && it !in urls }) deleteTarget = null
    }

    dialogTarget?.let { target ->
        val editing = entries.firstOrNull { it.url == target }
        if (target != NEW_ENTRY && editing == null) return@let
        EntryDialog(
            initial = editing,
            existingUrls = entries.map { it.url }.toSet(),
            onDismiss = { dialogTarget = null },
            onConfirm = { entry ->
                if (editing == null) viewModel.add(entry) else viewModel.replace(editing.url, entry)
                dialogTarget = null
            },
        )
    }

    deleteTarget?.let { url ->
        val entry = entries.firstOrNull { it.url == url } ?: return@let
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.editor_delete_confirm_title, entry.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(url)
                    deleteTarget = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    ruCaStep?.let { step ->
        val entry = entries.firstOrNull { it.url == step.url }
        RuCaDialog(
            step = step,
            displayName = entry?.displayName ?: step.url,
            onConfirm = viewModel::confirmRuCa,
            onPickFiles = viewModel::importRuCa,
            onDismiss = viewModel::dismissRuCa,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.editor_reset_confirm_title)) },
            text = { Text(stringResource(R.string.editor_reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToPreset()
                    confirmReset = false
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The 👮 button of an entry; [highlighted] if its last check answered with a Russian state certificate. */
private data class RuCaButton(val highlighted: Boolean)

@Composable
private fun EntryRow(
    entry: ResourceEntry,
    ruCa: RuCaButton?,
    onRuCa: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.marker?.let { marker ->
                Text(
                    text = stringResource(R.string.editor_marker_line, marker),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (ruCa != null) {
            RuCaToggle(entry, ruCa.highlighted, onRuCa)
        }
        EmojiButton(
            emoji = Emoji.EDIT,
            contentDescription = stringResource(R.string.editor_edit_entry, entry.displayName),
            onClick = onEdit,
        )
        EmojiButton(
            emoji = Emoji.DELETE,
            contentDescription = stringResource(R.string.editor_delete_entry, entry.displayName),
            onClick = onDelete,
        )
    }
}

/**
 * Trust in the Russian state CA for this entry (spec §2.3): 👮, crossed
 * out with 🚫 while off; on a red ground when the last check of the entry
 * answered with a Russian state certificate.
 */
@Composable
private fun RuCaToggle(entry: ResourceEntry, highlighted: Boolean, onClick: () -> Unit) {
    val description = stringResource(
        if (entry.allowRuCa) R.string.editor_ru_ca_on else R.string.editor_ru_ca_off,
        entry.displayName,
    )
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.errorContainer, CircleShape) else Modifier)
                .clearAndSetSemantics {},
        ) {
            Text(text = Emoji.POLICE, fontSize = 20.sp)
            if (!entry.allowRuCa) Text(text = Emoji.PROHIBITED, fontSize = 24.sp)
        }
    }
}

@Composable
private fun RuCaDialog(
    step: RuCaStep,
    displayName: String,
    onConfirm: () -> Unit,
    onPickFiles: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onPickFiles)
    when (step) {
        is RuCaStep.Confirm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ru_ca_confirm_title, displayName)) },
            text = { Text(stringResource(R.string.ru_ca_confirm_text, displayName)) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.ru_ca_enable)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        is RuCaStep.Installing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ru_ca_installing)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is RuCaStep.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(step.result.titleRes())) },
            text = { Text(stringResource(step.result.textRes())) },
            // Certificate files come with all sorts of MIME types: let the user pick any.
            confirmButton = { TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.ru_ca_pick_file)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun RussianStateCaResult.titleRes(): Int = when (this) {
    RussianStateCaResult.DOWNLOAD_FAILED -> R.string.ru_ca_download_failed_title
    RussianStateCaResult.UNREADABLE -> R.string.ru_ca_unreadable_title
    RussianStateCaResult.WRONG_ROOT, RussianStateCaResult.INSTALLED -> R.string.ru_ca_wrong_root_title
}

private fun RussianStateCaResult.textRes(): Int = when (this) {
    RussianStateCaResult.DOWNLOAD_FAILED -> R.string.ru_ca_download_failed_text
    RussianStateCaResult.UNREADABLE -> R.string.ru_ca_unreadable_text
    RussianStateCaResult.WRONG_ROOT, RussianStateCaResult.INSTALLED -> R.string.ru_ca_wrong_root_text
}
