// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.config

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.data.ConfigError
import org.s0me0ne25.cellprobe.data.ConfigImportError
import org.s0me0ne25.cellprobe.ui.components.BackButton
import java.util.UUID

/**
 * Export and import of the whole configuration (spec §9.5): one text,
 * moved through the clipboard or a file.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    viewModel: ConfigViewModel = viewModel(factory = ConfigViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    var importError by remember { mutableStateOf<ConfigImportError?>(null) }

    // The view model outlives the screen: each visit starts from the current configuration.
    val visit = rememberSaveable { UUID.randomUUID().toString() }
    LaunchedEffect(visit) { viewModel.onEnter(visit) }

    fun showMessage(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    val messages = ConfigMessages(
        imported = stringResource(R.string.config_imported),
        saved = stringResource(R.string.config_saved),
        fileFailed = stringResource(R.string.config_file_failed),
        tooLargeFile = stringResource(R.string.config_file_too_large),
    )
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ConfigEvent.Imported -> snackbarHostState.showSnackbar(messages.imported)
                ConfigEvent.Opened -> confirmImport = true
                is ConfigEvent.ImportFailed -> importError = event.error
                ConfigEvent.Saved -> snackbarHostState.showSnackbar(messages.saved)
                ConfigEvent.FileFailed -> snackbarHostState.showSnackbar(messages.fileFailed)
                ConfigEvent.TooLargeFile -> snackbarHostState.showSnackbar(messages.tooLargeFile)
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_TYPE)) { uri ->
        uri?.let(viewModel::saveTo)
    }
    // Files from other apps come with all sorts of types.
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openFrom)
    }
    val clipLabel = stringResource(R.string.config_title)
    val copied = stringResource(R.string.config_copied)
    val clipboardEmpty = stringResource(R.string.config_clipboard_empty)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.config_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = viewModel::loadCurrent, enabled = !state.busy) {
                        Text(stringResource(R.string.config_current))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.config_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = state.text.isNotEmpty(),
                    onClick = {
                        context.clipboard()?.setPrimaryClip(ClipData.newPlainText(clipLabel, state.text))
                        showMessage(copied)
                    },
                ) { Text(stringResource(R.string.config_copy_all)) }
                OutlinedButton(
                    onClick = {
                        val pasted = context.clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (pasted.isNullOrEmpty()) showMessage(clipboardEmpty) else viewModel.setText(pasted)
                    },
                ) { Text(stringResource(R.string.config_paste)) }
                OutlinedButton(enabled = state.text.isNotEmpty(), onClick = { viewModel.setText("") }) {
                    Text(stringResource(R.string.config_clear))
                }
                OutlinedButton(
                    enabled = state.text.isNotEmpty() && !state.busy,
                    onClick = { saveLauncher.launch(viewModel.suggestedFileName()) },
                ) { Text(stringResource(R.string.config_save_file)) }
                OutlinedButton(enabled = !state.busy, onClick = { openLauncher.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.config_open_file))
                }
            }
            Button(
                enabled = state.text.isNotBlank() && !state.busy,
                onClick = { confirmImport = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.config_import)) }
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.config_import_confirm_title)) },
            text = { Text(stringResource(R.string.config_import_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmImport = false
                        viewModel.importText()
                    },
                ) { Text(stringResource(R.string.config_import)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    importError?.let { error ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.config_import_failed)) },
            text = { Text(error.message()) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

private class ConfigMessages(val imported: String, val saved: String, val fileFailed: String, val tooLargeFile: String)

private const val MIME_TYPE = "application/json"

private fun Context.clipboard(): ClipboardManager? = getSystemService()

@Composable
private fun ConfigImportError.message(): String = when (this) {
    ConfigImportError.WriteFailed -> stringResource(R.string.config_error_write)
    is ConfigImportError.Invalid -> when (val reason = error) {
        ConfigError.NotConfig -> stringResource(R.string.config_error_not_config)
        is ConfigError.NewerFormat -> stringResource(R.string.config_error_newer)
        is ConfigError.BadSection -> stringResource(R.string.config_error_section, reason.name)
        ConfigError.WrongRussianStateCa -> stringResource(R.string.config_error_ca)
    }
}
