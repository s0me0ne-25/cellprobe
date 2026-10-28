// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.StoredResourceList
import java.io.InputStream
import java.io.OutputStream

/**
 * Root of the persisted settings. Stored as JSON; new fields must have
 * defaults so older files keep loading.
 */
@Serializable
data class AppSettings(
    val resourceLists: Map<ResourceListId, StoredResourceList> = emptyMap(),
)

object AppSettingsSerializer : Serializer<AppSettings> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings =
        try {
            json.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot parse settings", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Cannot parse settings", e)
        }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(json.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}
