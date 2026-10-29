// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.actions

import kotlinx.serialization.Serializable
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType

/**
 * Apps the user assigns to statuses (spec §9.2); one app may take several
 * roles. Declared, and shown in the settings, in order of growing severity
 * of the filtering they are meant for. Stored by name, so reordering is safe.
 */
@Serializable
enum class AppRole { VPN_CLIENT, I2P, OLCRTC }

/** System settings screens a status may lead to. */
enum class SettingsScreen { WIFI, MOBILE_NETWORK, NETWORK }

/** What the user is offered for a status (spec §7.3). */
sealed interface StatusAction {
    /** Launch the app assigned to [role]. */
    data class LaunchRole(val role: AppRole) : StatusAction

    /** Open one of the provider account pages in the browser. */
    data object ProviderPages : StatusAction

    data class OpenSettings(val screen: SettingsScreen) : StatusAction
}

object StatusActions {

    /**
     * Actions for [status] on a network of [networkType], the main one first.
     * Empty when there is nothing to offer: the user turned the network off.
     */
    fun forStatus(status: NetworkStatus, networkType: NetworkType?): List<StatusAction> = when (status) {
        NetworkStatus.LEAK, NetworkStatus.NORMAL, NetworkStatus.NORMAL_NO_VLESS ->
            listOf(StatusAction.LaunchRole(AppRole.VPN_CLIENT))
        NetworkStatus.VLESS_BLOCKED ->
            listOf(StatusAction.LaunchRole(AppRole.I2P), StatusAction.LaunchRole(AppRole.VPN_CLIENT))
        NetworkStatus.WHITELIST -> listOf(StatusAction.LaunchRole(AppRole.OLCRTC))
        NetworkStatus.PAY -> listOf(StatusAction.ProviderPages)
        NetworkStatus.AUTH_REQUIRED,
        NetworkStatus.NO_INTERNET,
        NetworkStatus.NO_SIGNAL,
        NetworkStatus.VOICE_ONLY,
        -> listOf(StatusAction.OpenSettings(settingsFor(networkType)))
        NetworkStatus.USER_OFFLINE -> emptyList()
    }

    /** Wi-Fi settings on Wi-Fi, mobile network settings on mobile data, general network settings otherwise. */
    private fun settingsFor(networkType: NetworkType?): SettingsScreen = when (networkType) {
        NetworkType.WIFI -> SettingsScreen.WIFI
        NetworkType.MOBILE -> SettingsScreen.MOBILE_NETWORK
        NetworkType.ETHERNET, NetworkType.OTHER, null -> SettingsScreen.NETWORK
    }
}
