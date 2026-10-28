// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import org.s0me0ne25.cellprobe.data.AppSettings
import org.s0me0ne25.cellprobe.data.AppSettingsSerializer
import org.s0me0ne25.cellprobe.data.SettingsRepository

/**
 * Manual dependency container, one per process.
 * Lives in [CellprobeApplication]; everything else gets dependencies from here.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository by lazy {
        // DataStore requires a single instance per file within a process.
        val dataStore = DataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
            produceFile = { appContext.dataStoreFile(SETTINGS_FILE) },
        )
        SettingsRepository(dataStore)
    }

    private companion object {
        const val SETTINGS_FILE = "settings.json"
    }
}
