// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.lists

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.resources.ListEditError
import org.s0me0ne25.cellprobe.core.resources.ListEditResult
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListEditing
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.data.RussianStateCaResult
import org.s0me0ne25.cellprobe.data.RussianStateCaStore
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.ui.appContainer
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Turning on trust in the Russian state CA for one entry (spec §2.3), step by step. */
sealed interface RuCaStep {
    val url: String

    /** The warning before turning it on. */
    data class Confirm(override val url: String) : RuCaStep

    /** The certificates are being downloaded or read. */
    data class Installing(override val url: String) : RuCaStep

    /** They could not be installed: the user may pick the files. */
    data class Failed(override val url: String, val result: RussianStateCaResult) : RuCaStep
}

class ResourceListEditorViewModel(
    private val listId: ResourceListId,
    private val repository: SettingsRepository,
    private val russianStateCa: RussianStateCaStore,
    checkController: CheckController,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    /** Null until the settings are loaded. */
    val state: StateFlow<ResourceListState?> = repository.resourceList(listId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Display names of the entries whose last check answered with a Russian state certificate. */
    val ruCaAnswered: StateFlow<Set<String>> = checkController.state
        .map { check ->
            check.sections.firstOrNull { it.id == CheckListId.of(listId) }?.rows.orEmpty()
                .filter { it.outcome?.code == ProbeCode.CERT_RU_CA }
                .map { it.name }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val mutableRuCaStep = MutableStateFlow<RuCaStep?>(null)
    val ruCaStep: StateFlow<RuCaStep?> = mutableRuCaStep.asStateFlow()

    private val errorChannel = Channel<ListEditError>(Channel.BUFFERED)

    /** One-shot edit errors to show to the user. */
    val errors: Flow<ListEditError> = errorChannel.receiveAsFlow()

    fun add(entry: ResourceEntry) = edit { ResourceListEditing.add(listId, it, entry) }

    fun replace(oldUrl: String, entry: ResourceEntry) =
        edit { ResourceListEditing.replace(listId, it, oldUrl, entry) }

    fun remove(url: String) = edit { ResourceListEditing.remove(listId, it, url) }

    fun resetToPreset() = edit { ResourceListEditing.resetToPreset(listId) }

    /** The 👮 button: off at once, on after the warning and the certificates. */
    fun toggleRuCa(entry: ResourceEntry) {
        if (entry.allowRuCa) {
            setRuCa(entry.url, allow = false)
        } else {
            mutableRuCaStep.value = RuCaStep.Confirm(entry.url)
        }
    }

    /** The warning was accepted: download the certificates unless installed, then turn the flag on. */
    fun confirmRuCa() {
        val step = mutableRuCaStep.value as? RuCaStep.Confirm ?: return
        install(step.url) { russianStateCa.install() }
    }

    /** The files the user picked after the download failed. */
    fun importRuCa(uris: List<Uri>) {
        val step = mutableRuCaStep.value as? RuCaStep.Failed ?: return
        if (uris.isEmpty()) return
        install(step.url) {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull(::readSmallFile) }
            russianStateCa.import(files)
        }
    }

    fun dismissRuCa() {
        if (mutableRuCaStep.value !is RuCaStep.Installing) mutableRuCaStep.value = null
    }

    private fun install(url: String, installing: suspend () -> RussianStateCaResult) {
        mutableRuCaStep.value = RuCaStep.Installing(url)
        viewModelScope.launch {
            val result = installing()
            if (result == RussianStateCaResult.INSTALLED) {
                mutableRuCaStep.value = null
                setRuCa(url, allow = true)
            } else {
                mutableRuCaStep.value = RuCaStep.Failed(url, result)
            }
        }
    }

    private fun setRuCa(url: String, allow: Boolean) {
        viewModelScope.launch {
            repository.setAllowRuCa(listId, url, allow)?.let { errorChannel.send(it) }
        }
    }

    /** A picked file, if it is small enough to be a certificate. */
    private fun readSmallFile(uri: Uri): ByteArray? = try {
        // InputStream.readNBytes would do, but only from API 33.
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(BUFFER_BYTES)
            while (bytes.size() <= MAX_FILE_BYTES) {
                val read = input.read(chunk)
                if (read < 0) break
                bytes.write(chunk, 0, read)
            }
            bytes.toByteArray().takeIf { it.size <= MAX_FILE_BYTES }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun edit(operation: (List<ResourceEntry>) -> ListEditResult) {
        viewModelScope.launch {
            repository.editResourceList(listId, operation)?.let { errorChannel.send(it) }
        }
    }

    companion object {
        /** Certificates are a few kilobytes. */
        private const val MAX_FILE_BYTES = 64 * 1024
        private const val BUFFER_BYTES = 8 * 1024

        fun factory(listId: ResourceListId) = viewModelFactory {
            initializer {
                val container = appContainer()
                ResourceListEditorViewModel(
                    listId = listId,
                    repository = container.settingsRepository,
                    russianStateCa = container.russianStateCaStore,
                    checkController = container.checkController,
                    contentResolver = checkNotNull(this[APPLICATION_KEY]).contentResolver,
                )
            }
        }
    }
}
