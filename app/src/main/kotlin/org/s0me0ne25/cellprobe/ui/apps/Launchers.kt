// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.actions.SettingsScreen

/** Starting other apps and system screens for status actions (spec §7.3). */

/** Opens [screen]; falls back to the general settings where a vendor build lacks it. */
fun Context.openSettings(screen: SettingsScreen) {
    val action = when (screen) {
        SettingsScreen.WIFI -> Settings.ACTION_WIFI_SETTINGS
        // The mobile network screen; its action name is historical.
        SettingsScreen.MOBILE_NETWORK -> Settings.ACTION_DATA_ROAMING_SETTINGS
        SettingsScreen.NETWORK -> Settings.ACTION_WIRELESS_SETTINGS
    }
    if (!tryStart(Intent(action))) tryStart(Intent(Settings.ACTION_SETTINGS))
}

/** Opens [url] in the default browser or lets the user choose one. Returns false if nothing can open it. */
fun Context.openUrl(url: String): Boolean = tryStart(Intent(Intent.ACTION_VIEW, url.toUri()))

/** Starts [intent]; returns false if no activity can handle it. */
fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@StringRes
fun AppRole.titleRes(): Int = when (this) {
    AppRole.VPN_CLIENT -> R.string.role_vpn_client
    AppRole.OLCRTC -> R.string.role_olcrtc
    AppRole.I2P -> R.string.role_i2p
}

@StringRes
fun SettingsScreen.actionRes(): Int = when (this) {
    SettingsScreen.WIFI -> R.string.action_open_wifi_settings
    SettingsScreen.MOBILE_NETWORK -> R.string.action_open_mobile_settings
    SettingsScreen.NETWORK -> R.string.action_open_network_settings
}
