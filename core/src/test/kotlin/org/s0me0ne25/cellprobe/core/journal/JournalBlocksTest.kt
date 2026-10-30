// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType

class JournalBlocksTest {

    private fun entry(
        minute: Long,
        status: NetworkStatus = NetworkStatus.NORMAL,
        type: NetworkType? = NetworkType.MOBILE,
    ) = JournalEntry(timeMillis = minute * 60_000, status = status, networkType = type)

    @Test
    fun `consecutive entries with the same type and status merge`() {
        val blocks = JournalBlocks.of(listOf(entry(1), entry(4), entry(7)))

        assertEquals(1, blocks.size)
        with(blocks.single()) {
            assertEquals(60_000L, oldestMillis)
            assertEquals(7 * 60_000L, newestMillis)
            assertEquals(3, count)
        }
    }

    @Test
    fun `a different status or network type starts a new block`() {
        val blocks = JournalBlocks.of(
            listOf(
                entry(1),
                entry(2, status = NetworkStatus.WHITELIST),
                entry(3, status = NetworkStatus.WHITELIST, type = NetworkType.WIFI),
                entry(4, status = NetworkStatus.WHITELIST, type = NetworkType.WIFI),
            ),
        )

        assertEquals(listOf(1, 1, 2), blocks.map { it.count })
        assertEquals(listOf(NetworkType.MOBILE, NetworkType.MOBILE, NetworkType.WIFI), blocks.map { it.networkType })
    }

    @Test
    fun `the same status after another one is a separate block`() {
        val blocks = JournalBlocks.of(
            listOf(entry(1), entry(2, status = NetworkStatus.NO_INTERNET), entry(3)),
        )
        assertEquals(3, blocks.size)
    }

    @Test
    fun `standard blocking with and without VLESS is one block`() {
        val blocks = JournalBlocks.of(
            listOf(entry(1, status = NetworkStatus.NORMAL_NO_VLESS), entry(2, status = NetworkStatus.NORMAL)),
        )

        assertEquals(1, blocks.size)
        assertEquals(NetworkStatus.NORMAL, blocks.single().status)
    }

    @Test
    fun `newest first input keeps its order and takes the newest status`() {
        val blocks = JournalBlocks.of(
            listOf(
                entry(9, status = NetworkStatus.NORMAL_NO_VLESS),
                entry(8, status = NetworkStatus.NORMAL),
                entry(5, status = NetworkStatus.LEAK),
            ),
        )

        assertEquals(listOf(NetworkStatus.NORMAL_NO_VLESS, NetworkStatus.LEAK), blocks.map { it.status })
        assertEquals(8 * 60_000L, blocks.first().oldestMillis)
        assertEquals(9 * 60_000L, blocks.first().newestMillis)
    }

    @Test
    fun `one minute is detected by calendar minute`() {
        val sameMinute = JournalBlocks.of(
            listOf(
                JournalEntry(timeMillis = 60_000, status = NetworkStatus.NORMAL),
                JournalEntry(timeMillis = 119_999, status = NetworkStatus.NORMAL),
            ),
        ).single()
        assertTrue(sameMinute.withinOneMinute)

        // Two seconds apart, but across the minute boundary.
        val acrossMinutes = JournalBlocks.of(
            listOf(
                JournalEntry(timeMillis = 119_000, status = NetworkStatus.NORMAL),
                JournalEntry(timeMillis = 121_000, status = NetworkStatus.NORMAL),
            ),
        ).single()
        assertFalse(acrossMinutes.withinOneMinute)
    }

    @Test
    fun `no entries make no blocks`() {
        assertEquals(emptyList<JournalBlock>(), JournalBlocks.of(emptyList()))
    }
}
