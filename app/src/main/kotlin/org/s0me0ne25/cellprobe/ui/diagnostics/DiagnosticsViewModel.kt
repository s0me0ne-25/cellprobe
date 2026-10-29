// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.core.probe.EntryResult
import org.s0me0ne25.cellprobe.core.probe.ListProbeListener
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.engine.ManualCheckRunner
import org.s0me0ne25.cellprobe.ui.appContainer

/** One entry of a list; [outcome] is null while it is being probed. */
data class EntryRow(val entry: ResourceEntry, val outcome: ProbeOutcome?)

data class ListSection(
    val id: ResourceListId,
    val rows: List<EntryRow>,
    /** 1, or 2 when the whole list is being probed again. */
    val round: Int,
    /** Null until the list is finished. */
    val available: Boolean?,
)

data class DiagnosticsUiState(
    val running: Boolean = false,
    val sections: List<ListSection> = emptyList(),
    /** The check could not run at all (e.g. the TLS library failed to load). */
    val failed: Boolean = false,
)

class DiagnosticsViewModel(
    private val settings: SettingsRepository,
    private val runner: ManualCheckRunner,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val lists = settings.resourceLists.first()
            mutableState.value = DiagnosticsUiState(
                running = true,
                sections = lists.map { list ->
                    ListSection(
                        id = list.id,
                        rows = list.entries.map { EntryRow(it, outcome = null) },
                        round = 1,
                        available = if (list.entries.isEmpty()) false else null,
                    )
                },
            )
            try {
                val results = runner.run(lists, progressListener)
                results.forEach { result ->
                    updateSection(result.listId) { it.copy(available = result.available) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            } finally {
                mutableState.update { it.copy(running = false) }
            }
        }
    }

    private val progressListener = object : ListProbeListener {
        override fun onRoundStarted(listId: ResourceListId, round: Int) {
            updateSection(listId) { section ->
                section.copy(round = round, rows = section.rows.map { it.copy(outcome = null) })
            }
        }

        override fun onEntryFinished(listId: ResourceListId, index: Int, result: EntryResult) {
            updateSection(listId) { section ->
                section.copy(rows = section.rows.toMutableList().also { it[index] = EntryRow(result.entry, result.outcome) })
            }
        }
    }

    private fun updateSection(id: ResourceListId, transform: (ListSection) -> ListSection) {
        mutableState.update { state ->
            state.copy(sections = state.sections.map { if (it.id == id) transform(it) else it })
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                DiagnosticsViewModel(container.settingsRepository, container.manualCheckRunner)
            }
        }
    }
}
