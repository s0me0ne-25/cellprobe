// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.lists

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
import org.s0me0ne25.cellprobe.core.resources.ListEditResult
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListEditing
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.ui.appContainer

class ResourceListEditorViewModel(
    private val listId: ResourceListId,
    private val repository: SettingsRepository,
) : ViewModel() {

    /** Null until the settings are loaded. */
    val state: StateFlow<ResourceListState?> = repository.resourceList(listId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val errorChannel = Channel<ListEditError>(Channel.BUFFERED)

    /** One-shot edit errors to show to the user. */
    val errors: Flow<ListEditError> = errorChannel.receiveAsFlow()

    fun add(entry: ResourceEntry) = edit { ResourceListEditing.add(listId, it, entry) }

    fun replace(oldUrl: String, entry: ResourceEntry) =
        edit { ResourceListEditing.replace(listId, it, oldUrl, entry) }

    fun remove(url: String) = edit { ResourceListEditing.remove(listId, it, url) }

    fun resetToPreset() = edit { ResourceListEditing.resetToPreset(listId) }

    private fun edit(operation: (List<ResourceEntry>) -> ListEditResult) {
        viewModelScope.launch {
            repository.editResourceList(listId, operation)?.let { errorChannel.send(it) }
        }
    }

    companion object {
        fun factory(listId: ResourceListId) = viewModelFactory {
            initializer { ResourceListEditorViewModel(listId, appContainer().settingsRepository) }
        }
    }
}
