// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.core.journal.JournalEntry
import org.s0me0ne25.cellprobe.data.JournalStore
import org.s0me0ne25.cellprobe.ui.appContainer

class JournalViewModel(private val journal: JournalStore) : ViewModel() {

    /** Entries, newest first; null while the journal is being read. */
    val entries: StateFlow<List<JournalEntry>?> = journal.entries
        .map { it.asReversed() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun clear() {
        viewModelScope.launch { journal.clear() }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer { JournalViewModel(appContainer().journalStore) }
        }
    }
}
