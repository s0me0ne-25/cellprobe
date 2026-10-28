// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.lists

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.resources.EntryInput
import org.s0me0ne25.cellprobe.core.resources.EntryInputError
import org.s0me0ne25.cellprobe.core.resources.EntryInputResult
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.UrlNormalizer
import org.s0me0ne25.cellprobe.ui.messageRes

/**
 * Add/edit dialog for an HTTPS resource entry.
 *
 * @param initial entry being edited, or null when adding.
 * @param existingUrls URLs already in the list, for duplicate checks.
 */
@Composable
fun EntryDialog(
    initial: ResourceEntry?,
    existingUrls: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (ResourceEntry) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initial?.let { UrlNormalizer.editableForm(it.url) }.orEmpty()) }
    var marker by rememberSaveable { mutableStateOf(initial?.marker.orEmpty()) }
    var urlError by rememberSaveable { mutableStateOf<Int?>(null) }
    var markerError by rememberSaveable { mutableStateOf<Int?>(null) }

    fun submit() {
        when (val result = EntryInput.parse(url, marker)) {
            is EntryInputResult.Valid -> {
                val entry = result.entry
                if (entry.url != initial?.url && entry.url in existingUrls) {
                    urlError = R.string.list_error_duplicate
                } else {
                    onConfirm(entry)
                }
            }
            is EntryInputResult.Invalid -> when (val error = result.error) {
                is EntryInputError.InvalidUrl -> urlError = error.error.messageRes()
                EntryInputError.MarkerTooLong -> markerError = R.string.marker_error_too_long
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.entry_dialog_add_title else R.string.entry_dialog_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        urlError = null
                    },
                    label = { Text(stringResource(R.string.entry_dialog_url_label)) },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    isError = urlError != null,
                    supportingText = urlError?.let { error -> { ErrorText(error) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = marker,
                    onValueChange = {
                        marker = it
                        markerError = null
                    },
                    label = { Text(stringResource(R.string.entry_dialog_marker_label)) },
                    singleLine = true,
                    isError = markerError != null,
                    supportingText = {
                        val error = markerError
                        if (error != null) ErrorText(error) else Text(stringResource(R.string.entry_dialog_marker_hint))
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ErrorText(@StringRes message: Int) {
    Text(stringResource(message))
}
