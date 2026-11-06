// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.stabilizer.Decision
import org.s0me0ne25.cellprobe.core.stabilizer.Observation
import org.s0me0ne25.cellprobe.core.stabilizer.Stabilizer
import org.s0me0ne25.cellprobe.core.stabilizer.StabilizerState
import java.io.InputStream
import java.io.OutputStream

/** The stabilizer state (spec §6), kept across restarts of the process. */
class StabilizerStore(private val dataStore: DataStore<StabilizerState>) {

    val state: Flow<StabilizerState> = dataStore.data

    /** Applies [observation] to the stored state and returns the decision; see [Stabilizer.observe]. */
    suspend fun observe(observation: Observation, silent: Set<NetworkStatus>): Decision {
        var decision: Decision? = null
        dataStore.updateData { state ->
            Stabilizer.observe(state, observation, silent).also { decision = it }.state
        }
        return checkNotNull(decision)
    }

    suspend fun snapshot(): StabilizerState = dataStore.data.first()

    /** Replaces the state (configuration import). */
    suspend fun replace(state: StabilizerState) {
        dataStore.updateData { state }
    }

    /** Forgets everything: the next check is a new baseline. */
    suspend fun reset() {
        dataStore.updateData { StabilizerState() }
    }
}

object StabilizerStateSerializer : Serializer<StabilizerState> {

    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: StabilizerState = StabilizerState()

    override suspend fun readFrom(input: InputStream): StabilizerState =
        try {
            json.decodeFromString(StabilizerState.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot parse the stabilizer state", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Cannot parse the stabilizer state", e)
        }

    override suspend fun writeTo(t: StabilizerState, output: OutputStream) {
        output.write(json.encodeToString(StabilizerState.serializer(), t).encodeToByteArray())
    }
}
