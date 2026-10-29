// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.journal

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/**
 * Traffic spent on checks today and this month (spec §5.6), in local time.
 * Dates are ISO strings: `2026-10-29` and `2026-10`.
 */
@Serializable
data class TrafficTotals(
    val day: String = "",
    val dayBytes: Long = 0,
    val month: String = "",
    val monthBytes: Long = 0,
) {
    /** Adds [bytes] spent [today], starting a new day or month when it has changed. */
    fun add(bytes: Long, today: LocalDate): TrafficTotals {
        val current = forDate(today)
        val added = bytes.coerceAtLeast(0)
        return current.copy(dayBytes = current.dayBytes + added, monthBytes = current.monthBytes + added)
    }

    /** The totals as of [today]: counters of a past day or month read as zero. */
    fun forDate(today: LocalDate): TrafficTotals {
        val dayKey = today.toString()
        val monthKey = YearMonth.from(today).toString()
        return TrafficTotals(
            day = dayKey,
            dayBytes = if (day == dayKey) dayBytes else 0,
            month = monthKey,
            monthBytes = if (month == monthKey) monthBytes else 0,
        )
    }
}
