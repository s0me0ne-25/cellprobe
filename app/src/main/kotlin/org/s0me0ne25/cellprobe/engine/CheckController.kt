// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.core.ListAvailability
import org.s0me0ne25.cellprobe.core.NetworkClassifier
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ListProbeListener
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.system.NetworkStateReader

/** One entry of a list; [outcome] is null while it is being probed. */
data class EntryRow(val name: String, val outcome: ProbeOutcome?)

data class ListSection(
    val id: CheckListId,
    val rows: List<EntryRow>,
    /** 1, or 2 when the whole list is being probed again. */
    val round: Int,
    /** Null until the list is finished. */
    val available: Boolean?,
)

/** Outcome of a finished check. */
data class CheckResult(
    val status: NetworkStatus,
    /** Transport of the active network, null if there was none. */
    val networkType: NetworkType?,
    val finishedAtMillis: Long,
    /** False when the status followed from the system state and no list was probed. */
    val probed: Boolean,
)

data class CheckState(
    val running: Boolean = false,
    /** Per-resource progress and results of the current or last check. */
    val sections: List<ListSection> = emptyList(),
    /** Result of the last finished check; kept while a new one runs. */
    val result: CheckResult? = null,
    /** The last check could not run at all (e.g. the TLS library failed to load). */
    val failed: Boolean = false,
)

/**
 * Runs a full check on request: the system state first (spec §3), then the
 * lists if needed (spec §5), then the classifier (spec §4). One check at a
 * time; the state is shared by every screen of the process.
 */
class CheckController(
    private val settings: SettingsRepository,
    private val networkStateReader: NetworkStateReader,
    private val runner: ManualCheckRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(CheckState())
    val state: StateFlow<CheckState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Starts a check unless one is already running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runCheck() }
    }

    private suspend fun runCheck() {
        mutableState.update { it.copy(running = true, sections = emptyList(), failed = false) }
        try {
            val networkState = withContext(Dispatchers.IO) { networkStateReader.read() }
            if (!NetworkClassifier.needsProbes(networkState)) {
                finish(NetworkClassifier.classify(networkState, null), networkState.activeNetwork, probed = false)
                return
            }

            val lists = settings.resourceLists.first()
            val vlessEntries = settings.vlessEntries.first()
            val names = lists.associate { list -> CheckListId.of(list.id) to list.entries.map { it.displayName } } +
                (CheckListId.VLESS to vlessEntries.map { it.label ?: it.address })
            mutableState.update { state ->
                state.copy(
                    sections = CheckListId.entries.map { id ->
                        val rows = names[id].orEmpty().map { EntryRow(it, outcome = null) }
                        ListSection(id = id, rows = rows, round = 1, available = if (rows.isEmpty()) false else null)
                    },
                )
            }

            val results = runner.run(lists, vlessEntries, progressListener)
            results.forEach { result -> updateSection(result.listId) { it.copy(available = result.available) } }
            val status = NetworkClassifier.classify(networkState, ListAvailability.of(results))
            finish(status, networkState.activeNetwork, probed = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(failed = true) }
        } finally {
            mutableState.update { it.copy(running = false) }
        }
    }

    private fun finish(status: NetworkStatus, networkType: NetworkType?, probed: Boolean) {
        val result = CheckResult(status, networkType, clock(), probed)
        mutableState.update { it.copy(result = result) }
    }

    private val progressListener = object : ListProbeListener {
        override fun onRoundStarted(listId: CheckListId, round: Int) {
            updateSection(listId) { section ->
                section.copy(round = round, rows = section.rows.map { it.copy(outcome = null) })
            }
        }

        override fun onEntryFinished(listId: CheckListId, index: Int, outcome: ProbeOutcome) {
            updateSection(listId) { section ->
                section.copy(rows = section.rows.toMutableList().also { it[index] = it[index].copy(outcome = outcome) })
            }
        }
    }

    private fun updateSection(id: CheckListId, transform: (ListSection) -> ListSection) {
        mutableState.update { state ->
            state.copy(sections = state.sections.map { if (it.id == id) transform(it) else it })
        }
    }
}
