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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.core.ConnectivityWatch
import org.s0me0ne25.cellprobe.core.ListAvailability
import org.s0me0ne25.cellprobe.core.NetworkClassifier
import org.s0me0ne25.cellprobe.core.NetworkState
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.journal.JournalEntry
import org.s0me0ne25.cellprobe.core.journal.JournalList
import org.s0me0ne25.cellprobe.core.journal.JournalProbe
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ListProbeListener
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome
import org.s0me0ne25.cellprobe.data.JournalStore
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.data.TrafficStore
import org.s0me0ne25.cellprobe.system.AppTraffic
import org.s0me0ne25.cellprobe.system.NetworkStateReader
import java.io.IOException
import java.time.LocalDate

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
    /** The network went away mid-check; the status follows from the system state. */
    val interrupted: Boolean = false,
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
 * lists if needed (spec §5), then the classifier (spec §4). The system state
 * is followed while the lists are probed: if the network goes away, the
 * probing stops and the status follows from the system state. One check at
 * a time; the state is shared by every screen of the process.
 */
class CheckController(
    private val settings: SettingsRepository,
    private val networkStateReader: NetworkStateReader,
    private val runner: ManualCheckRunner,
    private val journal: JournalStore,
    private val traffic: TrafficStore,
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
            val trafficBefore = AppTraffic.totalBytes()
            val networkState = readNetworkState()
            if (!NetworkClassifier.needsProbes(networkState)) {
                finish(NetworkClassifier.classify(networkState, null), networkState, probed = false, trafficBefore)
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

            val outcome = ConnectivityWatch.runUntilLoss(
                work = { runner.run(lists, vlessEntries, progressListener) },
                loss = {
                    ConnectivityWatch.awaitLoss(
                        readings = networkStateReader.changes().map { readNetworkState() },
                        readAgain = ::readNetworkState,
                    )
                },
            )
            when (outcome) {
                is ConnectivityWatch.Outcome.Completed -> {
                    val results = outcome.value
                    results.forEach { result -> updateSection(result.listId) { it.copy(available = result.available) } }
                    val status = NetworkClassifier.classify(networkState, ListAvailability.of(results))
                    finish(status, networkState, probed = true, trafficBefore)
                }
                is ConnectivityWatch.Outcome.Interrupted -> {
                    // Results of the requests cut short mean nothing: drop them.
                    mutableState.update { it.copy(sections = emptyList()) }
                    val lost = outcome.state
                    finish(NetworkClassifier.classify(lost, null), lost, probed = false, trafficBefore, interrupted = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(failed = true) }
        } finally {
            mutableState.update { it.copy(running = false) }
        }
    }

    private suspend fun readNetworkState(): NetworkState = withContext(Dispatchers.IO) { networkStateReader.read() }

    private suspend fun finish(
        status: NetworkStatus,
        networkState: NetworkState,
        probed: Boolean,
        trafficBefore: Long?,
        interrupted: Boolean = false,
    ) {
        val result = CheckResult(status, networkState.activeNetwork, clock(), probed, interrupted)
        val sections = mutableState.value.sections
        mutableState.update { it.copy(result = result) }

        // A reboot resets the system counters: a negative delta is dropped.
        val trafficBytes = trafficBefore?.let { before -> AppTraffic.totalBytes()?.minus(before)?.takeIf { it >= 0 } }
        try {
            if (trafficBytes != null) traffic.add(trafficBytes, LocalDate.now())
            journal.append(journalEntry(result, trafficBytes, sections))
        } catch (_: IOException) {
            // The check itself succeeded; a full disk must not turn it into a failure.
        }
    }

    private fun journalEntry(result: CheckResult, trafficBytes: Long?, sections: List<ListSection>) = JournalEntry(
        timeMillis = result.finishedAtMillis,
        status = result.status,
        networkType = result.networkType,
        trafficBytes = trafficBytes,
        interrupted = result.interrupted,
        lists = sections.map { section ->
            JournalList(
                id = section.id,
                available = section.available == true,
                retried = section.round > 1,
                probes = section.rows.mapNotNull { row ->
                    row.outcome?.let { outcome ->
                        JournalProbe(
                            name = row.name,
                            code = outcome.code,
                            millis = outcome.durationMillis,
                            httpStatus = outcome.httpStatus,
                            receivedBytes = outcome.receivedBytes,
                            error = outcome.errorDetail,
                        )
                    }
                },
            )
        },
    )

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
