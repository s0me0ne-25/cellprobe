// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Xiaomi firmware (MIUI, HyperOS) stops an app on "clear memory" in the
 * recents even with the battery exemption, and its alarms with it (spec
 * §9.3). Two vendor settings help, and neither can be read by an app:
 * locking the app in the recents keeps it running, and autostart lets the
 * next wake-up alarm bring monitoring back.
 */
object XiaomiBackground {

    /** Redmi and POCO devices report Xiaomi as the manufacturer too. */
    fun applies(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /**
     * Opens the autostart list of the security app, or the app details
     * screen, which on MIUI also has the autostart switch. Returns false if
     * neither could be opened.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(ComponentName(SECURITY_CENTER, AUTOSTART_ACTIVITY)),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        )
        return intents.any { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                // Not exported on some firmware versions.
                false
            }
        }
    }

    private const val SECURITY_CENTER = "com.miui.securitycenter"
    private const val AUTOSTART_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"
}
