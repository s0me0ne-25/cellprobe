// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.journal.JournalBuffer
import org.s0me0ne25.cellprobe.core.journal.JournalEntry
import java.io.File

class JournalStoreTest {

    @TempDir
    lateinit var dir: File

    private val file by lazy { File(dir, "journal.jsonl") }

    private fun store() = JournalStore(file, Dispatchers.Unconfined)

    private fun entry(time: Long) = JournalEntry(timeMillis = time, status = NetworkStatus.NORMAL)

    private fun lines() = file.readLines().filter { it.isNotBlank() }

    @Test
    fun `missing file is an empty journal`() = runBlocking {
        assertEquals(emptyList<JournalEntry>(), store().entries.first())
    }

    @Test
    fun `entries are appended and read back in order`() = runBlocking {
        val store = store()
        store.append(entry(1))
        store.append(entry(2))

        assertEquals(listOf(entry(1), entry(2)), store.entries.first())
        assertEquals(listOf(entry(1), entry(2)), store().entries.first())
        assertEquals(2, lines().size)
    }

    @Test
    fun `only the newest entries are kept and the file is compacted`() = runBlocking {
        val store = store()
        val total = JournalBuffer.COMPACT_AT + 10
        for (time in 1L..total) store.append(entry(time))

        val entries = store.entries.first()
        assertEquals(JournalBuffer.CAPACITY, entries.size)
        assertEquals(total - JournalBuffer.CAPACITY + 1L, entries.first().timeMillis)
        assertEquals(total.toLong(), entries.last().timeMillis)
        assertTrue(lines().size < JournalBuffer.COMPACT_AT, "lines: ${lines().size}")
        assertEquals(entries, store().entries.first())
    }

    @Test
    fun `unreadable lines are skipped`() = runBlocking {
        file.writeText("""{"timeMillis":1,"status":"NORMAL"}""" + "\n{broken\n" + """{"timeMillis":2,"status":"UNKNOWN_STATUS"}""" + "\n")

        assertEquals(listOf(entry(1)), store().entries.first())
    }

    @Test
    fun `clear deletes the file`() = runBlocking {
        val store = store()
        store.append(entry(1))
        store.clear()

        assertEquals(emptyList<JournalEntry>(), store.entries.first())
        assertFalse(file.exists())
        store.append(entry(2))
        assertEquals(listOf(entry(2)), store().entries.first())
    }
}
