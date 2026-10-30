// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.core.journal.JournalBlock
import org.s0me0ne25.cellprobe.core.journal.JournalBlocks
import org.s0me0ne25.cellprobe.core.journal.JournalEntry
import org.s0me0ne25.cellprobe.data.JournalStore
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.ui.appContainer

/** What the journal screen shows, newest first. */
sealed interface JournalContent {
    val isEmpty: Boolean

    /** Every check, with its results on request. */
    data class Detailed(val entries: List<JournalEntry>) : JournalContent {
        override val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** Runs of equal results merged into one row each. */
    data class Compact(val blocks: List<JournalBlock>) : JournalContent {
        override val isEmpty: Boolean get() = blocks.isEmpty()
    }
}

class JournalViewModel(private val journal: JournalStore, settings: SettingsRepository) : ViewModel() {

    /**
     * The journal in the mode chosen in the settings; null while it is being
     * read. Kept up to date even while the screen is closed, so that it never
     * opens with a stale list that a fresh one replaces a moment later.
     */
    val content: StateFlow<JournalContent?> = combine(journal.entries, settings.detailedJournal) { entries, detailed ->
        val newestFirst = entries.asReversed()
        if (detailed) JournalContent.Detailed(newestFirst) else JournalContent.Compact(JournalBlocks.of(newestFirst))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun clear() {
        viewModelScope.launch { journal.clear() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                JournalViewModel(container.journalStore, container.settingsRepository)
            }
        }
    }
}
