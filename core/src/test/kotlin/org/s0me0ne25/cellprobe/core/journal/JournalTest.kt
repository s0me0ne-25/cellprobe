// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.journal

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import kotlin.time.Duration.Companion.minutes

class JournalTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `short journal is kept whole`() {
        assertEquals(listOf(1, 2, 3), JournalBuffer.trim(listOf(1, 2, 3)))
    }

    @Test
    fun `overflow drops the oldest entries and keeps the order`() {
        val entries = (1..JournalBuffer.CAPACITY + 50).toList()

        val trimmed = JournalBuffer.trim(entries)

        assertEquals(JournalBuffer.CAPACITY, trimmed.size)
        assertEquals(51, trimmed.first())
        assertEquals(JournalBuffer.CAPACITY + 50, trimmed.last())
    }

    @Test
    fun `file is compacted at twice the capacity`() {
        assertFalse(JournalBuffer.needsCompaction(JournalBuffer.COMPACT_AT - 1))
        assertTrue(JournalBuffer.needsCompaction(JournalBuffer.COMPACT_AT))
    }

    @Test
    fun `entry survives a round trip`() {
        val entry = JournalEntry(
            timeMillis = 1_793_270_000_000,
            status = NetworkStatus.VLESS_BLOCKED,
            networkType = NetworkType.MOBILE,
            trafficBytes = 123_456,
            lists = listOf(
                JournalList(
                    id = CheckListId.VLESS,
                    available = false,
                    retried = true,
                    probes = listOf(
                        JournalProbe("Home", ProbeCode.STALLED, 5_210, 200, 18_000, "SocketTimeoutException: timeout"),
                        JournalProbe("192.0.2.1:443", ProbeCode.TCP_TIMEOUT, 5_001),
                    ),
                ),
            ),
        )

        val line = json.encodeToString(JournalEntry.serializer(), entry)

        assertFalse('\n' in line, "one entry per line")
        assertEquals(entry, json.decodeFromString(JournalEntry.serializer(), line))
    }

    @Test
    fun `minimal and future entries load`() {
        val minimal = json.decodeFromString(JournalEntry.serializer(), """{"timeMillis":1,"status":"USER_OFFLINE"}""")
        assertEquals(JournalEntry(timeMillis = 1, status = NetworkStatus.USER_OFFLINE), minimal)

        val future = json.decodeFromString(JournalEntry.serializer(), """{"timeMillis":1,"status":"LEAK","newField":42}""")
        assertEquals(NetworkStatus.LEAK, future.status)
    }

    @Test
    fun `latest entry is restored only if it is recent`() {
        val entries = listOf(JournalEntry(1_000, NetworkStatus.WHITELIST), JournalEntry(600_000, NetworkStatus.NORMAL))
        val maxAge = 20.minutes

        assertEquals(entries.last(), JournalBuffer.latestWithin(entries, 600_000 + maxAge.inWholeMilliseconds, maxAge))
        assertEquals(null, JournalBuffer.latestWithin(entries, 600_001 + maxAge.inWholeMilliseconds, maxAge))
        assertEquals(entries.last(), JournalBuffer.latestWithin(entries, 600_000, maxAge))
        assertEquals(null, JournalBuffer.latestWithin(entries, 599_999, maxAge), "entry from the future")
        assertEquals(null, JournalBuffer.latestWithin(emptyList(), 600_000, maxAge))
    }
}
