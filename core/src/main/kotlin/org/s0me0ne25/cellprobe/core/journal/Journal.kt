// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.journal

import kotlinx.serialization.Serializable
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import kotlin.time.Duration

/**
 * One finished check in the local journal (spec §9.4).
 *
 * Holds no secrets: VLESS inbounds are named by their label, or by
 * `host:port` when they have none; UUIDs and keys never reach the journal
 * because they are never stored at all.
 *
 * New fields must have defaults so older journals keep loading.
 */
@Serializable
data class JournalEntry(
    val timeMillis: Long,
    val status: NetworkStatus,
    /** Transport of the active network, null if there was none. */
    val networkType: NetworkType? = null,
    /** Bytes sent and received by the check, if the system reports them. */
    val trafficBytes: Long? = null,
    /** Empty when the status followed from the system state alone. */
    val lists: List<JournalList> = emptyList(),
    /** The network went away mid-check; the status follows from the system state. */
    val interrupted: Boolean = false,
)

@Serializable
data class JournalList(
    val id: CheckListId,
    val available: Boolean,
    /** The whole list was probed a second time. */
    val retried: Boolean = false,
    val probes: List<JournalProbe> = emptyList(),
)

@Serializable
data class JournalProbe(
    val name: String,
    val code: ProbeCode,
    val millis: Long,
    val httpStatus: Int? = null,
    val receivedBytes: Long? = null,
    /** Technical description of the error that ended the probe. */
    val error: String? = null,
)

/**
 * Ring buffer rules of the journal. The store appends one line per check
 * and rewrites the file only now and then, keeping the newest [CAPACITY]
 * entries: appending is cheap, a full rewrite on every check is not.
 */
object JournalBuffer {

    /** Entries kept, roughly ten hours of checks every three minutes. */
    const val CAPACITY = 200

    /** The stored file is rewritten once it holds this many lines. */
    const val COMPACT_AT = CAPACITY * 2

    /** The newest [capacity] of [entries], oldest first. */
    fun <T> trim(entries: List<T>, capacity: Int = CAPACITY): List<T> =
        if (entries.size <= capacity) entries else entries.subList(entries.size - capacity, entries.size)

    fun needsCompaction(storedLines: Int): Boolean = storedLines >= COMPACT_AT

    /**
     * The newest of [entries] (oldest first) if it was made at most [maxAge]
     * before [nowMillis]; null if there is none or it is older. An entry
     * from the future (the clock was set back) does not count as recent.
     */
    fun latestWithin(entries: List<JournalEntry>, nowMillis: Long, maxAge: Duration): JournalEntry? {
        val latest = entries.lastOrNull() ?: return null
        val age = nowMillis - latest.timeMillis
        return latest.takeIf { age in 0..maxAge.inWholeMilliseconds }
    }
}
