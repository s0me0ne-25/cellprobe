// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import java.io.IOException
import java.time.Instant

/**
 * Export and import of the configuration (spec §9.5): settings with the
 * lists, VLESS inbounds and unblocking apps, the check traffic and the
 * certificates of the Russian state CA, if installed. The log and the
 * stabilizer state are not part of it.
 *
 * @param onImported called with the imported settings, e.g. to start or
 *   stop monitoring to match them.
 */
class ConfigTransfer(
    private val settings: SettingsRepository,
    private val traffic: TrafficStore,
    private val russianStateCa: RussianStateCaStore,
    private val appVersion: String,
    private val onImported: suspend (AppSettings) -> Unit,
) {
    /** The whole configuration as text. */
    suspend fun export(): String {
        val snapshot = ConfigSnapshot(
            settings = settings.snapshot(),
            traffic = traffic.snapshot(),
            russianStateCa = russianStateCa.anchors().orEmpty(),
        )
        return ConfigBackup.encode(snapshot, appVersion, Instant.now().toString())
    }

    /**
     * Replaces what [text] contains; sections it lacks stay as they are.
     * Everything is checked before anything is written. Null on success.
     */
    suspend fun import(text: String): ConfigImportError? {
        val snapshot = when (val result = ConfigBackup.decode(text)) {
            is ConfigDecodeResult.Failure -> return ConfigImportError.Invalid(result.error)
            is ConfigDecodeResult.Success -> result.snapshot
        }
        return try {
            snapshot.russianStateCa?.let { certificates ->
                if (!russianStateCa.replace(certificates.ifEmpty { null })) return ConfigImportError.WriteFailed
            }
            snapshot.settings?.let { settings.replace(it) }
            snapshot.traffic?.let { traffic.replace(it) }
            onImported(settings.snapshot())
            null
        } catch (_: IOException) {
            ConfigImportError.WriteFailed
        }
    }
}

sealed interface ConfigImportError {
    data class Invalid(val error: ConfigError) : ConfigImportError

    /** The storage failed half way: what was written so far stays. */
    data object WriteFailed : ConfigImportError
}
