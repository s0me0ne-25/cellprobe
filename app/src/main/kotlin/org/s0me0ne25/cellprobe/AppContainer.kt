// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe

import android.content.Context
import android.os.PowerManager
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.core.stabilizer.StabilizerState
import org.s0me0ne25.cellprobe.data.AppSettings
import org.s0me0ne25.cellprobe.data.AppSettingsSerializer
import org.s0me0ne25.cellprobe.data.JournalStore
import org.s0me0ne25.cellprobe.data.RussianStateCaStore
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.data.StabilizerStateSerializer
import org.s0me0ne25.cellprobe.data.StabilizerStore
import org.s0me0ne25.cellprobe.data.TrafficStore
import org.s0me0ne25.cellprobe.data.TrafficTotalsSerializer
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.engine.ManualCheckRunner
import org.s0me0ne25.cellprobe.engine.MonitoringAlarm
import org.s0me0ne25.cellprobe.engine.StatusTracker
import org.s0me0ne25.cellprobe.notify.TransitionNotifications
import org.s0me0ne25.cellprobe.probe.ProbeClientFactory
import org.s0me0ne25.cellprobe.probe.TlsSetup
import org.s0me0ne25.cellprobe.probe.UserAgentProvider
import org.s0me0ne25.cellprobe.system.InstalledApps
import org.s0me0ne25.cellprobe.system.MonitoringPermissions
import org.s0me0ne25.cellprobe.system.NetworkStateReader
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    private val manualCheckRunner: ManualCheckRunner by lazy {
        ManualCheckRunner(probeClientFactory, userAgentProvider, russianStateCaStore)
    }

    /** Process-wide background work that outlives any screen. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Plain HTTPS downloads: the platform TLS and trust, redirects followed. */
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    /** Certificates of the Russian state CA for entries that trust it (spec §2.3); never backed up. */
    val russianStateCaStore: RussianStateCaStore by lazy {
        RussianStateCaStore(File(appContext.noBackupFilesDir, RUSSIAN_STATE_CA_FILE), ::download)
    }

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body.source()
            if (body.request(MAX_DOWNLOAD_BYTES + 1)) throw IOException("Too large")
            body.readByteArray()
        }
    }

    /**
     * Removes the certificates of the Russian state CA once no entry trusts
     * them (spec §2.3): turned off, edited, removed or reset to the preset.
     */
    fun startRussianStateCaCleanup() {
        applicationScope.launch {
            settingsRepository.anyAllowsRuCa.filter { !it }.collect { russianStateCaStore.delete() }
        }
    }

    val networkStateReader: NetworkStateReader by lazy { NetworkStateReader(appContext) }

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

    private val stabilizerStore: StabilizerStore by lazy {
        val dataStore = DataStoreFactory.create(
            serializer = StabilizerStateSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { StabilizerState() },
            produceFile = { appContext.dataStoreFile(STABILIZER_FILE) },
        )
        StabilizerStore(dataStore)
    }

    val transitionNotifications: TransitionNotifications by lazy { TransitionNotifications(appContext) }

    val statusTracker: StatusTracker by lazy {
        StatusTracker(stabilizerStore, settingsRepository, transitionNotifications)
    }

    /** The check shared by all screens of the process. */
    val checkController: CheckController by lazy {
        CheckController(settingsRepository, networkStateReader, manualCheckRunner, journalStore, trafficStore, statusTracker)
    }

    val monitoringPermissions: MonitoringPermissions by lazy { MonitoringPermissions(appContext) }

    val monitoringAlarm: MonitoringAlarm by lazy { MonitoringAlarm(appContext, monitoringPermissions) }

    /** Wake-up alarms, from [org.s0me0ne25.cellprobe.engine.AlarmReceiver] to the monitoring loop. */
    val monitoringWakeUps = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Keeps the CPU awake from a wake-up event (an alarm, a network change)
     * until the monitoring loop has started the check, which then holds a
     * wake lock of its own. Not reference-counted: each acquire only moves
     * the timeout.
     */
    val monitoringWakeLock: PowerManager.WakeLock by lazy { newWakeLock(WAKE_LOCK_TAG) }

    /** A partial wake lock that is not reference-counted. */
    fun newWakeLock(tag: String): PowerManager.WakeLock =
        checkNotNull(appContext.getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            .apply { setReferenceCounted(false) }

    private companion object {
        const val WAKE_LOCK_TAG = "cellprobe:wakeup"
        const val SETTINGS_FILE = "settings.json"
        const val TRAFFIC_FILE = "traffic.json"
        const val JOURNAL_FILE = "journal.jsonl"
        const val STABILIZER_FILE = "stabilizer.json"
        const val RUSSIAN_STATE_CA_FILE = "russian_state_ca.pem"
        const val DOWNLOAD_TIMEOUT_SECONDS = 30L

        /** The certificates are a few kilobytes; anything much larger is not them. */
        const val MAX_DOWNLOAD_BYTES = 64L * 1024
    }
}
