// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.journal

import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType

/**
 * Consecutive journal entries with the same network type and status, shown
 * as one row of the compact journal (spec §9.4).
 *
 * @property oldestMillis time of the oldest check of the block.
 * @property newestMillis time of the newest check of the block.
 * @property status status of the newest check.
 * @property trafficBytes traffic of the whole block; null if no check of it
 *   had its traffic reported.
 * @property count number of checks in the block.
 */
data class JournalBlock(
    val oldestMillis: Long,
    val newestMillis: Long,
    val status: NetworkStatus,
    val networkType: NetworkType?,
    val trafficBytes: Long?,
    val count: Int,
) {
    /**
     * The whole block falls within one calendar minute, so one time is
     * shown instead of a range. Time zones are offset from UTC by whole
     * minutes, so minutes since the epoch are calendar minutes anywhere.
     */
    val withinOneMinute: Boolean
        get() = Math.floorDiv(oldestMillis, MILLIS_PER_MINUTE) == Math.floorDiv(newestMillis, MILLIS_PER_MINUTE)

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/** Groups journal entries into the rows of the compact journal (spec §9.4). */
object JournalBlocks {

    /**
     * Merges every run of consecutive [entries] with the same network type
     * and status into one [JournalBlock]; the blocks keep the order of
     * [entries], whichever direction it is.
     *
     * Standard blocking with and without VLESS entries configured
     * ([NetworkStatus.NORMAL], [NetworkStatus.NORMAL_NO_VLESS]) counts as one
     * status here: for the user it is the same network mode.
     */
    fun of(entries: List<JournalEntry>): List<JournalBlock> {
        val blocks = mutableListOf<MutableList<JournalEntry>>()
        for (entry in entries) {
            val last = blocks.lastOrNull()?.last()
            if (last != null && sameBlock(last, entry)) {
                blocks.last() += entry
            } else {
                blocks += mutableListOf(entry)
            }
        }
        return blocks.map(::toBlock)
    }

    private fun sameBlock(a: JournalEntry, b: JournalEntry): Boolean =
        a.networkType == b.networkType && group(a.status) == group(b.status)

    private fun group(status: NetworkStatus): NetworkStatus =
        if (status == NetworkStatus.NORMAL_NO_VLESS) NetworkStatus.NORMAL else status

    private fun toBlock(entries: List<JournalEntry>): JournalBlock {
        val newest = entries.maxBy { it.timeMillis }
        val reported = entries.mapNotNull { it.trafficBytes }
        return JournalBlock(
            oldestMillis = entries.minOf { it.timeMillis },
            newestMillis = newest.timeMillis,
            status = newest.status,
            networkType = newest.networkType,
            trafficBytes = if (reported.isEmpty()) null else reported.sum(),
            count = entries.size,
        )
    }
}
