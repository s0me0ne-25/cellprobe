// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.vless

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.vless.VlessEntry
import org.s0me0ne25.cellprobe.core.vless.VlessError
import org.s0me0ne25.cellprobe.core.vless.VlessInput
import org.s0me0ne25.cellprobe.core.vless.VlessParseResult
import org.s0me0ne25.cellprobe.ui.components.NoPersonalizedLearning
import org.s0me0ne25.cellprobe.ui.messageRes

/**
 * Catches links typed or pasted into a field before they are committed.
 *
 * The raw link never reaches the field state (and so never reaches saved
 * instance state or storage): the change is reverted and only the parse
 * result, which carries no secrets, is handed over.
 */
private class LinkInterceptor(private val onLink: (VlessParseResult) -> Unit) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (!VlessInput.containsLink(asCharSequence())) return
        val result = VlessInput.parse(asCharSequence().toString())
        revertAllChanges()
        onLink(result)
    }
}

/**
 * Add/edit dialog for a VLESS entry.
 *
 * @param initial entry being edited, or null when adding.
 * @param existingKeys keys of entries already in the list, for duplicate checks.
 */
@Composable
fun VlessEntryDialog(
    initial: VlessEntry?,
    existingKeys: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (VlessEntry) -> Unit,
) {
    val serverState = rememberTextFieldState(initial?.toManualLine().orEmpty())
    val labelState = rememberTextFieldState(initial?.label.orEmpty())
    var serverError by rememberSaveable { mutableStateOf<Int?>(null) }
    var labelError by rememberSaveable { mutableStateOf<Int?>(null) }

    // Parse result of an intercepted link, applied to both fields below.
    var intercepted by remember { mutableStateOf<VlessParseResult?>(null) }
    val interceptor = remember { LinkInterceptor { intercepted = it } }

    LaunchedEffect(intercepted) {
        when (val result = intercepted ?: return@LaunchedEffect) {
            is VlessParseResult.Valid -> {
                serverState.setTextAndPlaceCursorAtEnd(result.entry.toManualLine())
                result.entry.label?.let { labelState.setTextAndPlaceCursorAtEnd(it) }
                serverError = null
                labelError = null
            }
            is VlessParseResult.Invalid -> serverError = result.error.messageRes()
        }
        intercepted = null
    }

    fun submit() {
        when (val result = VlessInput.fromForm(serverState.text.toString(), labelState.text.toString())) {
            is VlessParseResult.Valid -> {
                val entry = result.entry
                if (entry.key != initial?.key && entry.key in existingKeys) {
                    serverError = R.string.list_error_duplicate
                } else {
                    onConfirm(entry)
                }
            }
            is VlessParseResult.Invalid ->
                if (result.error == VlessError.LABEL_TOO_LONG) {
                    labelError = result.error.messageRes()
                } else {
                    serverError = result.error.messageRes()
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.vless_dialog_add_title else R.string.vless_dialog_edit_title))
        },
        text = {
            NoPersonalizedLearning {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        state = serverState,
                        label = { Text(stringResource(R.string.vless_dialog_server_label)) },
                        placeholder = { Text("203.0.113.5:443 www.example.com chrome") },
                        isError = serverError != null,
                        supportingText = {
                            val error = serverError
                            Text(stringResource(error ?: R.string.vless_dialog_server_hint))
                        },
                        inputTransformation = interceptor,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                    OutlinedTextField(
                        state = labelState,
                        label = { Text(stringResource(R.string.vless_dialog_label_label)) },
                        isError = labelError != null,
                        supportingText = labelError?.let { error -> { Text(stringResource(error)) } },
                        inputTransformation = interceptor,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                }
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
