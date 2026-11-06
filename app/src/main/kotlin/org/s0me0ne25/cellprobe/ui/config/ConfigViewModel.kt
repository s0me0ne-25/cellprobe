// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.config

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.data.ConfigBackup
import org.s0me0ne25.cellprobe.data.ConfigImportError
import org.s0me0ne25.cellprobe.data.ConfigTransfer
import org.s0me0ne25.cellprobe.ui.appContainer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate

data class ConfigUiState(
    /** The text being worked on: the current configuration, or one pasted or opened. */
    val text: String = "",
    /** Exporting, importing or reading a file. */
    val busy: Boolean = true,
)

/** One-shot results to show. */
sealed interface ConfigEvent {
    data object Imported : ConfigEvent
    data class ImportFailed(val error: ConfigImportError) : ConfigEvent
    data object Saved : ConfigEvent
    data object FileFailed : ConfigEvent
    data object TooLargeFile : ConfigEvent
}

class ConfigViewModel(
    private val transfer: ConfigTransfer,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ConfigEvent>(Channel.BUFFERED)
    val events: Flow<ConfigEvent> = eventChannel.receiveAsFlow()

    init {
        loadCurrent()
    }

    /** Fills the text with the current configuration. */
    fun loadCurrent() {
        work {
            val text = transfer.export()
            mutableState.update { it.copy(text = text) }
        }
    }

    fun setText(text: String) = mutableState.update { it.copy(text = text) }

    fun importText() {
        val text = mutableState.value.text
        work { eventChannel.send(transfer.import(text)?.let { ConfigEvent.ImportFailed(it) } ?: ConfigEvent.Imported) }
    }

    /** `cellprobe-config-YYYY-MM-DD-<hash>.json` for the text as it is now. */
    fun suggestedFileName(): String = ConfigBackup.fileName(mutableState.value.text, LocalDate.now().toString())

    fun saveTo(uri: Uri) {
        val text = mutableState.value.text
        work {
            val saved = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) } != null
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
            eventChannel.send(if (saved) ConfigEvent.Saved else ConfigEvent.FileFailed)
        }
    }

    fun openFrom(uri: Uri) {
        work {
            when (val read = withContext(Dispatchers.IO) { read(uri) }) {
                FileRead.Failed -> eventChannel.send(ConfigEvent.FileFailed)
                FileRead.TooLarge -> eventChannel.send(ConfigEvent.TooLargeFile)
                is FileRead.Text -> mutableState.update { it.copy(text = read.text) }
            }
        }
    }

    private sealed interface FileRead {
        data class Text(val text: String) : FileRead
        data object TooLarge : FileRead
        data object Failed : FileRead
    }

    private fun read(uri: Uri): FileRead = try {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                bytes.write(chunk, 0, count)
                // Far more than any configuration: not one.
                if (bytes.size() > MAX_FILE_BYTES) return FileRead.TooLarge
            }
            FileRead.Text(bytes.toByteArray().decodeToString())
        } ?: FileRead.Failed
    } catch (_: IOException) {
        FileRead.Failed
    } catch (_: SecurityException) {
        FileRead.Failed
    }

    private fun work(block: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        /** A configuration takes some kilobytes; a megabyte is not one. */
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024

        val Factory = viewModelFactory {
            initializer {
                ConfigViewModel(appContainer().configTransfer, checkNotNull(this[APPLICATION_KEY]).contentResolver)
            }
        }
    }
}
