// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.AppContainer
import org.s0me0ne25.cellprobe.CellprobeApplication
import org.s0me0ne25.cellprobe.system.MonitoringPermissions
import kotlin.time.Duration.Companion.seconds

/**
 * Wakes the device for the next monitoring check (spec §8): an exact
 * allow-while-idle alarm if the user granted exact alarms, an inexact one
 * otherwise. Times are in [android.os.SystemClock.elapsedRealtime].
 *
 * Doze still rations allow-while-idle alarms per app, and an inexact one
 * may come minutes late: the loop recomputes its pause on every wake-up.
 */
class MonitoringAlarm(context: Context, private val permissions: MonitoringPermissions) {

    private val appContext = context.applicationContext
    private val alarmManager = checkNotNull(appContext.getSystemService<AlarmManager>())

    private val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(appContext, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Replaces the pending alarm with one at [elapsedRealtimeMillis]. */
    fun set(elapsedRealtimeMillis: Long) {
        if (permissions.canScheduleExactAlarms()) {
            try {
                AlarmManagerCompat.setExactAndAllowWhileIdle(
                    alarmManager,
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedRealtimeMillis,
                    pendingIntent,
                )
                return
            } catch (_: SecurityException) {
                // The permission was revoked a moment ago: fall back to an inexact alarm.
            }
        }
        AlarmManagerCompat.setAndAllowWhileIdle(
            alarmManager,
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            elapsedRealtimeMillis,
            pendingIntent,
        )
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent)
    }
}

/**
 * The wake-up alarm: keeps the CPU awake until the monitoring loop starts
 * its check, and brings the service back if the system had stopped it.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        container.monitoringWakeLock.acquire(ALARM_WAKE_LOCK_TIMEOUT.inWholeMilliseconds)
        container.monitoringWakeUps.tryEmit(Unit)
        if (!MonitoringService.running) restartIfEnabled(context, container)
    }

    private companion object {
        /** Enough for the loop to pick the alarm up; the check then holds the wake lock itself. */
        val ALARM_WAKE_LOCK_TIMEOUT = 10.seconds
    }
}

/** Brings monitoring back after a reboot or an app update, if it was on. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        restartIfEnabled(context, context.container)
    }
}

private val Context.container: AppContainer get() = (applicationContext as CellprobeApplication).container

/** Starts the monitoring service if the user has monitoring on. */
private fun BroadcastReceiver.restartIfEnabled(context: Context, container: AppContainer) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Main.immediate).launch {
        try {
            if (container.settingsRepository.monitoring.first().enabled) {
                MonitoringService.sync(context.applicationContext, enabled = true)
            }
        } finally {
            pending.finish()
        }
    }
}
