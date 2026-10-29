// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

/** Traffic spent on checks, by day and month (spec §5.6). */
class TrafficStore(private val dataStore: DataStore<TrafficTotals>) {

    /** Stored totals; read them with [TrafficTotals.forDate] for the current date. */
    val totals: Flow<TrafficTotals> = dataStore.data

    suspend fun add(bytes: Long, today: LocalDate) {
        dataStore.updateData { it.add(bytes, today) }
    }
}

object TrafficTotalsSerializer : Serializer<TrafficTotals> {

    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: TrafficTotals = TrafficTotals()

    override suspend fun readFrom(input: InputStream): TrafficTotals =
        try {
            json.decodeFromString(TrafficTotals.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot parse traffic totals", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Cannot parse traffic totals", e)
        }

    override suspend fun writeTo(t: TrafficTotals, output: OutputStream) {
        output.write(json.encodeToString(TrafficTotals.serializer(), t).encodeToByteArray())
    }
}
