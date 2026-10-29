// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.data.AppSettings
import org.s0me0ne25.cellprobe.data.AppSettingsSerializer
import org.s0me0ne25.cellprobe.data.JournalStore
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.data.TrafficStore
import org.s0me0ne25.cellprobe.data.TrafficTotalsSerializer
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.engine.ManualCheckRunner
import org.s0me0ne25.cellprobe.probe.ProbeClientFactory
import org.s0me0ne25.cellprobe.probe.TlsSetup
import org.s0me0ne25.cellprobe.probe.UserAgentProvider
import org.s0me0ne25.cellprobe.system.InstalledApps
import org.s0me0ne25.cellprobe.system.NetworkStateReader
import java.io.File

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

    val userAgentProvider: UserAgentProvider by lazy { UserAgentProvider(appContext) }

    /** Conscrypt is loaded on first use, from a background thread. */
    private val tlsSetup: TlsSetup by lazy { TlsSetup.conscrypt() }

    val probeClientFactory: ProbeClientFactory by lazy { ProbeClientFactory(tls = { tlsSetup }) }

    private val manualCheckRunner: ManualCheckRunner by lazy { ManualCheckRunner(probeClientFactory, userAgentProvider) }

    private val networkStateReader: NetworkStateReader by lazy { NetworkStateReader(appContext) }

    val installedApps: InstalledApps by lazy { InstalledApps(appContext) }

    val journalStore: JournalStore by lazy { JournalStore(File(appContext.filesDir, JOURNAL_FILE)) }

    val trafficStore: TrafficStore by lazy {
        val dataStore = DataStoreFactory.create(
            serializer = TrafficTotalsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { TrafficTotals() },
            produceFile = { appContext.dataStoreFile(TRAFFIC_FILE) },
        )
        TrafficStore(dataStore)
    }

    /** The check shared by all screens of the process. */
    val checkController: CheckController by lazy {
        CheckController(settingsRepository, networkStateReader, manualCheckRunner, journalStore, trafficStore)
    }

    private companion object {
        const val SETTINGS_FILE = "settings.json"
        const val TRAFFIC_FILE = "traffic.json"
        const val JOURNAL_FILE = "journal.jsonl"
    }
}
