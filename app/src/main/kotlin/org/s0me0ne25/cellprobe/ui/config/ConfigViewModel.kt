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
import kotlinx.coroutines.Job
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

    /** A file was read into the text: ask whether to import it. */
    data object Opened : ConfigEvent
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

    /** The visit of the screen the text was last filled for. */
    private var visit: String? = null

    /** The text was set by the user before the first visit was seen. */
    private var touched = false

    private var loading: Job? = null

    /**
     * Called each time the screen is entered. [visit] stays the same over a
     * rotation and a process restart, so a text the user is working on is
     * kept then; a new visit always starts from the current configuration,
     * since the one shown last time may be outdated.
     */
    fun onEnter(visit: String) {
        if (visit == this.visit) return
        // A process restart while the file picker was open: the file came first.
        val keep = this.visit == null && touched
        this.visit = visit
        touched = false
        if (!keep) loadCurrent()
    }

    /** Fills the text with the current configuration. */
    fun loadCurrent() {
        loading?.cancel()
        loading = work {
            val text = transfer.export()
            mutableState.update { it.copy(text = text) }
        }
    }

    fun setText(text: String) {
        userText()
        mutableState.update { it.copy(text = text) }
    }

    /** What the user put in wins over a configuration still being read. */
    private fun userText() {
        loading?.cancel()
        touched = true
    }

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

    /** Reads the file into the text, then asks whether to import it. */
    fun openFrom(uri: Uri) {
        userText()
        work {
            when (val read = withContext(Dispatchers.IO) { read(uri) }) {
                FileRead.Failed -> eventChannel.send(ConfigEvent.FileFailed)
                FileRead.TooLarge -> eventChannel.send(ConfigEvent.TooLargeFile)
                is FileRead.Text -> {
                    loading?.cancel()
                    mutableState.update { it.copy(text = read.text) }
                    eventChannel.send(ConfigEvent.Opened)
                }
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

    /** Jobs started by [work] and not finished yet. */
    private var running = 0

    private fun work(block: suspend () -> Unit): Job {
        running++
        mutableState.update { it.copy(busy = true) }
        return viewModelScope.launch {
            try {
                block()
            } finally {
                running--
                mutableState.update { it.copy(busy = running > 0) }
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
