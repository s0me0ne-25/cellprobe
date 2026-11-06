// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.s0me0ne25.cellprobe.core.certs.RussianStateCa
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.withoutRuCa
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The configuration of Cellprobe (spec §9.5). A null section is left as
 * it is on import; [russianStateCa] distinguishes "not in the file" (null)
 * from "not installed" (an empty list).
 */
data class ConfigSnapshot(
    val settings: AppSettings? = null,
    val traffic: TrafficTotals? = null,
    val russianStateCa: List<X509Certificate>? = null,
)

/** Why a configuration cannot be imported. */
sealed interface ConfigError {
    /** Not JSON, or not a Cellprobe configuration. */
    data object NotConfig : ConfigError

    /** Written by a newer Cellprobe in a format this one does not know. */
    data class NewerFormat(val version: Int) : ConfigError

    /** A section is damaged. */
    data class BadSection(val name: String) : ConfigError

    /** The certificates of the Russian state CA fail the pinned fingerprint. */
    data object WrongRussianStateCa : ConfigError
}

sealed interface ConfigDecodeResult {
    data class Success(val snapshot: ConfigSnapshot) : ConfigDecodeResult
    data class Failure(val error: ConfigError) : ConfigDecodeResult
}

/**
 * The text form of a [ConfigSnapshot]: one JSON object, readable and
 * editable by hand. Certificates are their DER encoding in Base64.
 */
object ConfigBackup {

    const val FORMAT = "cellprobe-config"
    const val VERSION = 1

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_TRAFFIC = "traffic"
    private const val KEY_RUSSIAN_STATE_CA = "russianStateCa"

    // Fields of AppSettings and StoredResourceList.
    private const val KEY_RESOURCE_LISTS = "resourceLists"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_RU_CA_HOSTS = "ruCaHosts"
    private const val KEY_ALLOW_RU_CA = "allowRuCa"

    private val pretty = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * @param appVersion and [exportedAt] are for the reader only and are
     *   not checked on import.
     */
    fun encode(snapshot: ConfigSnapshot, appVersion: String, exportedAt: String): String {
        val sections = buildMap<String, JsonElement> {
            put(KEY_FORMAT, JsonPrimitive(FORMAT))
            put(KEY_VERSION, JsonPrimitive(VERSION))
            put(KEY_APP_VERSION, JsonPrimitive(appVersion))
            put(KEY_EXPORTED_AT, JsonPrimitive(exportedAt))
            snapshot.settings?.let { put(KEY_SETTINGS, encodeSettings(it)) }
            snapshot.traffic?.let { put(KEY_TRAFFIC, pretty.encodeToJsonElement(TrafficTotals.serializer(), it)) }
            snapshot.russianStateCa?.let { certificates ->
                put(KEY_RUSSIAN_STATE_CA, JsonArray(certificates.map { JsonPrimitive(BASE64.encodeToString(it.encoded)) }))
            }
        }
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(sections)) + "\n"
    }

    fun decode(text: String): ConfigDecodeResult {
        val root = try {
            lenient.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return ConfigDecodeResult.Failure(ConfigError.NotConfig)
        if ((root[KEY_FORMAT] as? JsonPrimitive)?.contentOrNull != FORMAT) {
            return ConfigDecodeResult.Failure(ConfigError.NotConfig)
        }
        val version = (root[KEY_VERSION] as? JsonPrimitive)?.intOrNull
            ?: return ConfigDecodeResult.Failure(ConfigError.NotConfig)
        if (version > VERSION) return ConfigDecodeResult.Failure(ConfigError.NewerFormat(version))

        fun <T> section(key: String, serializer: KSerializer<T>): Result<T?> {
            val element = root[key] ?: return Result.success(null)
            return try {
                Result.success(lenient.decodeFromJsonElement(serializer, element))
            } catch (_: SerializationException) {
                Result.failure(BadSection(key))
            } catch (_: IllegalArgumentException) {
                Result.failure(BadSection(key))
            }
        }

        return try {
            ConfigDecodeResult.Success(
                ConfigSnapshot(
                    settings = section(KEY_SETTINGS, AppSettings.serializer()).getOrThrow()?.withRuCaOnlyWhereAllowed(),
                    traffic = section(KEY_TRAFFIC, TrafficTotals.serializer()).getOrThrow(),
                    russianStateCa = russianStateCa(root[KEY_RUSSIAN_STATE_CA]),
                ),
            )
        } catch (e: BadSection) {
            ConfigDecodeResult.Failure(ConfigError.BadSection(e.name))
        } catch (_: WrongCa) {
            ConfigDecodeResult.Failure(ConfigError.WrongRussianStateCa)
        }
    }

    /**
     * Lists that do not allow the Russian state CA (spec §2.3) are written
     * without its fields at all: nothing to edit by hand in a shared file.
     */
    private fun encodeSettings(settings: AppSettings): JsonElement {
        val encoded = pretty.encodeToJsonElement(AppSettings.serializer(), settings.withRuCaOnlyWhereAllowed()) as JsonObject
        val lists = encoded[KEY_RESOURCE_LISTS] as? JsonObject ?: return encoded
        val withoutRuCa = ResourceListId.entries.filterNot { it.allowsRuCa }.map(::listKey).toSet()
        val cleaned = lists.mapValues { (key, list) ->
            if (key in withoutRuCa && list is JsonObject) list.withoutRuCaFields() else list
        }
        return JsonObject(encoded + (KEY_RESOURCE_LISTS to JsonObject(cleaned)))
    }

    private fun JsonObject.withoutRuCaFields(): JsonObject {
        val entries = (this[KEY_ENTRIES] as? JsonArray)?.map { entry ->
            if (entry is JsonObject) JsonObject(entry - KEY_ALLOW_RU_CA) else entry
        }
        val cleaned = this - KEY_RU_CA_HOSTS
        return JsonObject(if (entries == null) cleaned else cleaned + (KEY_ENTRIES to JsonArray(entries)))
    }

    /** The key of [id] in the JSON map of lists: its serial name. */
    private fun listKey(id: ResourceListId): String =
        pretty.encodeToJsonElement(ResourceListId.serializer(), id).jsonPrimitive.content

    /**
     * Whatever a file says, a list that does not allow the Russian state
     * CA gets no flags: a shared configuration cannot turn it on there.
     */
    private fun AppSettings.withRuCaOnlyWhereAllowed(): AppSettings =
        copy(resourceLists = resourceLists.mapValues { (id, list) -> if (id.allowsRuCa) list else list.withoutRuCa() })

    /** The certificates, checked against the pinned fingerprint; null if not in the file. */
    private fun russianStateCa(element: JsonElement?): List<X509Certificate>? {
        if (element == null || element is JsonNull) return null
        val items = element as? JsonArray ?: throw BadSection(KEY_RUSSIAN_STATE_CA)
        if (items.isEmpty()) return emptyList()
        val certificates = try {
            items.flatMap { item ->
                val base64 = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BadSection(KEY_RUSSIAN_STATE_CA)
                RussianStateCa.parse(BASE64_DECODER.decode(base64))
            }
        } catch (_: IllegalArgumentException) {
            throw BadSection(KEY_RUSSIAN_STATE_CA)
        } catch (_: CertificateException) {
            throw BadSection(KEY_RUSSIAN_STATE_CA)
        }
        val verified = RussianStateCa.verify(certificates) as? RussianStateCa.Verification.Verified ?: throw WrongCa()
        return verified.all
    }

    /**
     * The file name offered when saving [text]:
     * `cellprobe-config-YYYY-MM-DD-<first 8 hex digits of its SHA-256>.json`.
     */
    fun fileName(text: String, date: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        val hash = digest.take(4).joinToString("") { "%02x".format(it) }
        return "cellprobe-config-$date-$hash.json"
    }

    private class BadSection(val name: String) : Exception()
    private class WrongCa : Exception()

    private val BASE64: Base64.Encoder = Base64.getEncoder()
    private val BASE64_DECODER: Base64.Decoder = Base64.getMimeDecoder()
}
