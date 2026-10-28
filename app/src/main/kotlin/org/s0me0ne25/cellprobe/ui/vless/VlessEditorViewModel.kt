// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.vless

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.core.resources.ListEditError
import org.s0me0ne25.cellprobe.core.vless.VlessEditResult
import org.s0me0ne25.cellprobe.core.vless.VlessEntry
import org.s0me0ne25.cellprobe.core.vless.VlessListEditing
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.ui.appContainer

class VlessEditorViewModel(private val repository: SettingsRepository) : ViewModel() {

    /** Null until the settings are loaded. */
    val entries: StateFlow<List<VlessEntry>?> = repository.vlessEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val errorChannel = Channel<ListEditError>(Channel.BUFFERED)

    /** One-shot edit errors to show to the user. */
    val errors: Flow<ListEditError> = errorChannel.receiveAsFlow()

    fun add(entry: VlessEntry) = edit { VlessListEditing.add(it, entry) }

    fun replace(oldKey: String, entry: VlessEntry) = edit { VlessListEditing.replace(it, oldKey, entry) }

    fun remove(key: String) = edit { VlessListEditing.remove(it, key) }

    private fun edit(operation: (List<VlessEntry>) -> VlessEditResult) {
        viewModelScope.launch {
            repository.editVlessEntries(operation)?.let { errorChannel.send(it) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { VlessEditorViewModel(appContainer().settingsRepository) }
        }
    }
}
