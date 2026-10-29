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

    /**
     * Entries, newest first; null while the journal is being read. Kept up
     * to date even while the screen is closed, so that it never opens with a
     * stale list that a fresh one replaces a moment later.
     */
    val entries: StateFlow<List<JournalEntry>?> = journal.entries
        .map { it.asReversed() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun clear() {
        viewModelScope.launch { journal.clear() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { JournalViewModel(appContainer().journalStore) }
        }
    }
}
