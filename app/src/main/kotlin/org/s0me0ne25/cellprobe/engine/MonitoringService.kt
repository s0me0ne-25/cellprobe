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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.CellprobeApplication
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringLoop
import org.s0me0ne25.cellprobe.notify.ServiceNotification
import org.s0me0ne25.cellprobe.system.screenOnStates

/**
 * Carries background monitoring (spec §8): a foreground service of type
 * `specialUse` with the status notification, running checks in a loop at
 * the screen-on or screen-off interval. A check also starts when the screen
 * goes on. The check itself is [CheckController]'s, the same as a manual
 * one; this service only decides when.
 *
 * Until exact alarms arrive (step 9) the loop runs on coroutine delays,
 * which Doze may stretch.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as CellprobeApplication).container
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
            MonitoringLoop.run(
                interval = interval,
                extraChecks = screenOn.drop(1).filter { it }.map { },
                nowMillis = SystemClock::elapsedRealtime,
                check = { controller.start().join() },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts or stops the service to match the monitoring switch. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, MonitoringService::class.java)
            if (enabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
