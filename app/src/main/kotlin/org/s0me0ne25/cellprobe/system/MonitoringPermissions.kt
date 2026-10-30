// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri

/** What background monitoring may need from the system (spec §9.3). */
enum class MonitoringPermission {
    /** API 33+: the status notification is shown. */
    NOTIFICATIONS,

    /** Doze and app standby do not hold the service back. */
    BATTERY,

    /** API 31+: checks run on time while the device sleeps. */
    EXACT_ALARMS,
}

/** Which [MonitoringPermission]s are granted, and how to ask for the others. */
class MonitoringPermissions(context: Context) {

    private val appContext = context.applicationContext

    fun isGranted(permission: MonitoringPermission): Boolean = when (permission) {
        MonitoringPermission.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        MonitoringPermission.BATTERY ->
            appContext.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        MonitoringPermission.EXACT_ALARMS -> canScheduleExactAlarms()
    }

    fun missing(): List<MonitoringPermission> = MonitoringPermission.entries.filterNot(::isGranted)

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.getSystemService<AlarmManager>()?.canScheduleExactAlarms() == true

    /** The notification settings of the app (API 26+), or its details screen. */
    fun notificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${appContext.packageName}".toUri())
        }

    /**
     * The system screen that grants [permission]; null for the notification
     * permission, which is asked for with a runtime request.
     */
    // Cellprobe is not distributed through Google Play: the direct request
    // for the battery exemption is allowed (spec §8).
    @SuppressLint("BatteryLife")
    fun requestIntent(permission: MonitoringPermission): Intent? {
        val packageUri = "package:${appContext.packageName}".toUri()
        return when (permission) {
            MonitoringPermission.NOTIFICATIONS -> null
            MonitoringPermission.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            MonitoringPermission.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                } else {
                    null
                }
        }
    }
}
