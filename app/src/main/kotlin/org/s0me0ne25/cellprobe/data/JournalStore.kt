// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.s0me0ne25.cellprobe.core.journal.JournalBuffer
import org.s0me0ne25.cellprobe.core.journal.JournalEntry
import java.io.File

/**
 * The check journal: a ring buffer of [JournalBuffer.CAPACITY] entries in a
 * JSON Lines file. Each check appends one line; the file is rewritten with
 * the newest entries only when it reaches [JournalBuffer.COMPACT_AT] lines,
 * so frequent background checks do not rewrite it every time. Lines that
 * cannot be read (an interrupted write, an older format) are skipped.
 */
class JournalStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val mutex = Mutex()

    /** Null until the file has been read. */
    private val loaded = MutableStateFlow<List<JournalEntry>?>(null)
    private var storedLines = 0

    /** Entries, oldest first. */
    val entries: Flow<List<JournalEntry>> = flow {
        mutex.withLock { ensureLoaded() }
        emitAll(loaded.filterNotNull())
    }

    suspend fun append(entry: JournalEntry) = mutex.withLock {
        val current = ensureLoaded()
        val updated = JournalBuffer.trim(current + entry)
        withContext(io) {
            if (JournalBuffer.needsCompaction(storedLines + 1)) {
                rewrite(updated)
            } else {
                file.appendText(json.encodeToString(JournalEntry.serializer(), entry) + "\n")
                storedLines++
            }
        }
        loaded.value = updated
    }

    suspend fun clear() = mutex.withLock {
        withContext(io) { file.delete() }
        storedLines = 0
        loaded.value = emptyList()
    }

    private suspend fun ensureLoaded(): List<JournalEntry> {
        loaded.value?.let { return it }
        val entries = withContext(io) { read() }
        loaded.value = entries
        return entries
    }

    private fun read(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        storedLines = lines.size
        val entries = lines.mapNotNull { line ->
            try {
                json.decodeFromString(JournalEntry.serializer(), line)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return JournalBuffer.trim(entries)
    }

    /** Replaces the file atomically with [entries]. */
    private fun rewrite(entries: List<JournalEntry>) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(entries.joinToString("") { json.encodeToString(JournalEntry.serializer(), it) + "\n" })
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "Cannot replace the journal file" }
        }
        storedLines = entries.size
    }
}
