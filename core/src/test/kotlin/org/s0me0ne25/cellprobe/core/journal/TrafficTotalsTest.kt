// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TrafficTotalsTest {

    private val oct29 = LocalDate.of(2026, 10, 29)

    @Test
    fun `bytes add up within a day`() {
        val totals = TrafficTotals().add(1_000, oct29).add(500, oct29)
        assertEquals(TrafficTotals("2026-10-29", 1_500, "2026-10", 1_500), totals)
    }

    @Test
    fun `new day starts the day counter and keeps the month`() {
        val totals = TrafficTotals().add(1_000, oct29).add(200, oct29.plusDays(1))
        assertEquals(TrafficTotals("2026-10-30", 200, "2026-10", 1_200), totals)
    }

    @Test
    fun `new month starts both counters`() {
        val totals = TrafficTotals().add(1_000, oct29).add(300, LocalDate.of(2026, 11, 1))
        assertEquals(TrafficTotals("2026-11-01", 300, "2026-11", 300), totals)
    }

    @Test
    fun `stale totals read as zero`() {
        val totals = TrafficTotals().add(1_000, oct29)
        assertEquals(TrafficTotals("2026-10-30", 0, "2026-10", 1_000), totals.forDate(oct29.plusDays(1)))
        assertEquals(TrafficTotals("2027-10-29", 0, "2027-10", 0), totals.forDate(oct29.plusYears(1)))
    }

    @Test
    fun `negative deltas are ignored`() {
        assertEquals(0, TrafficTotals().add(-5, oct29).dayBytes)
    }
}
