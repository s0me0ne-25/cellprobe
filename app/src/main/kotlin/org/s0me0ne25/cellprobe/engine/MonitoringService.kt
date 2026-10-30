// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.AppContainer
import org.s0me0ne25.cellprobe.CellprobeApplication
import org.s0me0ne25.cellprobe.core.NetworkState
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringLoop
import org.s0me0ne25.cellprobe.core.monitoring.NetworkEvents
import org.s0me0ne25.cellprobe.notify.ServiceNotification
import org.s0me0ne25.cellprobe.system.screenOnStates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Carries background monitoring (spec §8): a foreground service of type
 * `specialUse` with the status notification, running checks in a loop at
 * the screen-on or screen-off interval. The check itself is
 * [CheckController]'s, the same as a manual one; this service only decides
 * when.
 *
 * The end of each pause is set as a wake-up alarm ([MonitoringAlarm]), and
 * a partial wake lock is held through the check, so checks go on while the
 * device sleeps. Extra checks start when the screen goes on and when the
 * default network changes ([NetworkEvents]).
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private val checkWakeLock by lazy { container.newWakeLock(CHECK_WAKE_LOCK_TAG) }

    override fun onCreate() {
        super.onCreate()
        running = true
        container = (application as CellprobeApplication).container
        ServiceNotification.createChannel(this)
        ServiceCompat.startForeground(
            this,
            ServiceNotification.ID,
            ServiceNotification.build(this, container.checkController.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )

        val controller = container.checkController
        val settings = container.settingsRepository

        // Keep the notification in step with the shared check state.
        scope.launch {
            controller.state.collect { state ->
                ServiceNotification.update(this@MonitoringService, state)
            }
        }

        // Stop when monitoring is switched off.
        scope.launch {
            settings.monitoring.filter { !it.enabled }.first()
            stopSelf()
        }

        scope.launch {
            val screenOn = screenOnStates().stateIn(scope, SharingStarted.Eagerly, true)
            val interval = combine(settings.monitoring, screenOn) { monitoring, on -> monitoring.interval(on) }
                .distinctUntilChanged()
                .stateIn(scope)
            val networkChecks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            launch { networkExtraChecks(networkChecks) }

            MonitoringLoop.run(
                interval = interval,
                extraChecks = merge(screenOn.drop(1).filter { it }.map { }, networkChecks),
                nowMillis = SystemClock::elapsedRealtime,
                onPause = container.monitoringAlarm::set,
                wakeUps = container.monitoringWakeUps,
                check = {
                    checkWakeLock.acquire(CHECK_WAKE_LOCK_TIMEOUT.inWholeMilliseconds)
                    try {
                        controller.start().join()
                    } finally {
                        if (checkWakeLock.isHeld) checkWakeLock.release()
                    }
                },
            )
        }
    }

    /**
     * Requests an extra check when the default network changes, is lost or
     * comes back, once it has settled; a newer change restarts the wait.
     */
    private suspend fun networkExtraChecks(requests: MutableSharedFlow<Unit>) {
        val reader = container.networkStateReader
        val states: Flow<NetworkState> = reader.changes().map { reader.read() }.flowOn(Dispatchers.IO)
        states
            .runningFold(null as Pair<NetworkState?, NetworkState>?) { last, current -> last?.second to current }
            .filterNotNull()
            .map { (previous, current) -> previous?.let { NetworkEvents.extraCheckDelay(it, current) } }
            .filterNotNull()
            .collectLatest { wait ->
                if (wait > Duration.ZERO) {
                    // The device may doze off while the network settles.
                    container.monitoringWakeLock.acquire((wait + SETTLE_WAKE_LOCK_MARGIN).inWholeMilliseconds)
                    delay(wait)
                }
                requests.tryEmit(Unit)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        scope.cancel()
        container.monitoringAlarm.cancel()
        if (checkWakeLock.isHeld) checkWakeLock.release()
        container.monitoringWakeLock.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHECK_WAKE_LOCK_TAG = "cellprobe:check"

        /** A check takes up to about a minute; the wake lock never outlives it by much. */
        private val CHECK_WAKE_LOCK_TIMEOUT = 3.minutes

        /** Time for the loop to start the extra check after the network has settled. */
        private val SETTLE_WAKE_LOCK_MARGIN = 10.seconds

        /** The service is running in this process. */
        @Volatile
        var running: Boolean = false
            private set

        /** Starts or stops the service to match the monitoring switch. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, MonitoringService::class.java)
            if (!enabled) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: IllegalStateException) {
                // API 31+: the system does not allow a start from the background
                // right now (ForegroundServiceStartNotAllowedException); the next
                // app start or wake-up alarm tries again.
            }
        }
    }
}
