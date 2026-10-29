// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListId

/** Probes a single HTTPS resource. Implementations must be cancellable. */
fun interface EntryProber {
    suspend fun probe(entry: ResourceEntry): ProbeOutcome
}

data class EntryResult(val entry: ResourceEntry, val outcome: ProbeOutcome)

data class ListProbeResult(
    val listId: ResourceListId,
    /** Results of the last round, in list order. */
    val results: List<EntryResult>,
    /** True if the first round found nothing and the list was probed again. */
    val retried: Boolean,
) {
    /** OR logic: the list is available if any of its entries is reachable. */
    val available: Boolean
        get() = results.any { it.outcome.code.reachable }
}

/** Progress callbacks for the UI. Called from the probing coroutines. */
interface ListProbeListener {
    fun onRoundStarted(listId: ResourceListId, round: Int) {}
    fun onEntryFinished(listId: ResourceListId, index: Int, result: EntryResult) {}
}

/**
 * Probes one list according to the spec:
 * - all entries in parallel;
 * - for lists 1, 2, 4 the first reachable entry cancels the rest, which
 *   are reported as [ProbeCode.SKIPPED];
 * - provider pages are all probed, so that each responding one is known;
 * - if no entry is reachable, the whole list is probed once more.
 */
object ListProbing {

    suspend fun probeList(
        listId: ResourceListId,
        entries: List<ResourceEntry>,
        prober: EntryProber,
        listener: ListProbeListener = object : ListProbeListener {},
    ): ListProbeResult {
        if (entries.isEmpty()) return ListProbeResult(listId, emptyList(), retried = false)

        listener.onRoundStarted(listId, round = 1)
        val first = runRound(listId, entries, prober, listener)
        if (first.any { it.outcome.code.reachable }) return ListProbeResult(listId, first, retried = false)

        listener.onRoundStarted(listId, round = 2)
        val second = runRound(listId, entries, prober, listener)
        return ListProbeResult(listId, second, retried = true)
    }

    private suspend fun runRound(
        listId: ResourceListId,
        entries: List<ResourceEntry>,
        prober: EntryProber,
        listener: ListProbeListener,
    ): List<EntryResult> {
        val stopOnFirst = listId != ResourceListId.PROVIDER
        val results = arrayOfNulls<EntryResult>(entries.size)

        coroutineScope {
            // Jobs are created lazily and started together, so that a fast
            // success always sees all its siblings and can cancel them.
            val jobs = ArrayList<Job>(entries.size)
            entries.forEachIndexed { index, entry ->
                jobs += launch(start = CoroutineStart.LAZY) {
                    val result = EntryResult(entry, prober.probe(entry))
                    results[index] = result
                    listener.onEntryFinished(listId, index, result)
                    if (stopOnFirst && result.outcome.code.reachable) {
                        jobs.forEachIndexed { other, job -> if (other != index) job.cancel() }
                    }
                }
            }
            jobs.forEach { it.start() }
        }

        return entries.mapIndexed { index, entry ->
            results[index] ?: EntryResult(entry, ProbeOutcome(ProbeCode.SKIPPED, durationMillis = 0)).also {
                listener.onEntryFinished(listId, index, it)
            }
        }
    }
}
