// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.core.resources.ResourceListId

/** Lists probed in one check cycle, in display order. */
enum class CheckListId(
    /**
     * Whether the first reachable entry cancels the others. Provider pages
     * and VLESS inbounds are all probed: each one's state is shown.
     */
    val stopOnFirstReachable: Boolean,
) {
    DOMESTIC(stopOnFirstReachable = true),
    FOREIGN(stopOnFirstReachable = true),
    VLESS(stopOnFirstReachable = false),
    BANNED(stopOnFirstReachable = true),
    PROVIDER(stopOnFirstReachable = false),
    ;

    companion object {
        fun of(id: ResourceListId): CheckListId = when (id) {
            ResourceListId.DOMESTIC -> DOMESTIC
            ResourceListId.FOREIGN -> FOREIGN
            ResourceListId.BANNED -> BANNED
            ResourceListId.PROVIDER -> PROVIDER
        }
    }
}

/** Probes a single list entry. Implementations must be cancellable. */
fun interface EntryProber<in E> {
    suspend fun probe(entry: E): ProbeOutcome
}

data class EntryResult<out E>(val entry: E, val outcome: ProbeOutcome)

data class ListProbeResult<out E>(
    val listId: CheckListId,
    /** Results of the last round, in list order. */
    val results: List<EntryResult<E>>,
    /** True if the first round found nothing and the list was probed again. */
    val retried: Boolean,
) {
    /** OR logic: the list is available if any of its entries is reachable. */
    val available: Boolean
        get() = results.any { it.outcome.code.reachable }
}

/** Progress callbacks for the UI. Called from the probing coroutines. */
interface ListProbeListener {
    fun onRoundStarted(listId: CheckListId, round: Int) {}
    fun onEntryFinished(listId: CheckListId, index: Int, outcome: ProbeOutcome) {}
}

/**
 * Probes one list according to the spec:
 * - all entries in parallel;
 * - where [CheckListId.stopOnFirstReachable] is set, the first reachable
 *   entry cancels the rest, which are reported as [ProbeCode.SKIPPED];
 * - if no entry is reachable, the whole list is probed once more.
 */
object ListProbing {

    suspend fun <E> probeList(
        listId: CheckListId,
        entries: List<E>,
        prober: EntryProber<E>,
        listener: ListProbeListener = object : ListProbeListener {},
    ): ListProbeResult<E> {
        if (entries.isEmpty()) return ListProbeResult(listId, emptyList(), retried = false)

        listener.onRoundStarted(listId, round = 1)
        val first = runRound(listId, entries, prober, listener)
        if (first.any { it.outcome.code.reachable }) return ListProbeResult(listId, first, retried = false)

        listener.onRoundStarted(listId, round = 2)
        val second = runRound(listId, entries, prober, listener)
        return ListProbeResult(listId, second, retried = true)
    }

    private suspend fun <E> runRound(
        listId: CheckListId,
        entries: List<E>,
        prober: EntryProber<E>,
        listener: ListProbeListener,
    ): List<EntryResult<E>> {
        val outcomes = arrayOfNulls<ProbeOutcome>(entries.size)

        coroutineScope {
            // Jobs are created lazily and started together, so that a fast
            // success always sees all its siblings and can cancel them.
            val jobs = ArrayList<Job>(entries.size)
            entries.forEachIndexed { index, entry ->
                jobs += launch(start = CoroutineStart.LAZY) {
                    val outcome = prober.probe(entry)
                    outcomes[index] = outcome
                    listener.onEntryFinished(listId, index, outcome)
                    if (listId.stopOnFirstReachable && outcome.code.reachable) {
                        jobs.forEachIndexed { other, job -> if (other != index) job.cancel() }
                    }
                }
            }
            jobs.forEach { it.start() }
        }

        return entries.mapIndexed { index, entry ->
            val outcome = outcomes[index] ?: ProbeOutcome(ProbeCode.SKIPPED, durationMillis = 0).also {
                listener.onEntryFinished(listId, index, it)
            }
            EntryResult(entry, outcome)
        }
    }
}
