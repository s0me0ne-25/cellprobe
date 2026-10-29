// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.core.actions.StatusAction.LaunchRole
import org.s0me0ne25.cellprobe.core.actions.StatusAction.OpenSettings

class StatusActionsTest {

    private fun actions(status: NetworkStatus, type: NetworkType? = NetworkType.MOBILE) =
        StatusActions.forStatus(status, type)

    @Test
    fun `table of the spec`() {
        assertEquals(listOf(LaunchRole(AppRole.VPN_CLIENT)), actions(NetworkStatus.LEAK))
        assertEquals(listOf(LaunchRole(AppRole.VPN_CLIENT)), actions(NetworkStatus.NORMAL))
        assertEquals(listOf(LaunchRole(AppRole.VPN_CLIENT)), actions(NetworkStatus.NORMAL_NO_VLESS))
        assertEquals(listOf(LaunchRole(AppRole.I2P), LaunchRole(AppRole.VPN_CLIENT)), actions(NetworkStatus.VLESS_BLOCKED))
        assertEquals(listOf(LaunchRole(AppRole.OLCRTC)), actions(NetworkStatus.WHITELIST))
        assertEquals(listOf(StatusAction.ProviderPages), actions(NetworkStatus.PAY))
        assertEquals(emptyList<StatusAction>(), actions(NetworkStatus.USER_OFFLINE, null))
    }

    @Test
    fun `settings follow the network type`() {
        for (status in listOf(NetworkStatus.AUTH_REQUIRED, NetworkStatus.NO_INTERNET, NetworkStatus.NO_SIGNAL, NetworkStatus.VOICE_ONLY)) {
            assertEquals(listOf(OpenSettings(SettingsScreen.WIFI)), actions(status, NetworkType.WIFI), "$status")
            assertEquals(listOf(OpenSettings(SettingsScreen.MOBILE_NETWORK)), actions(status, NetworkType.MOBILE), "$status")
            assertEquals(listOf(OpenSettings(SettingsScreen.NETWORK)), actions(status, NetworkType.ETHERNET), "$status")
            assertEquals(listOf(OpenSettings(SettingsScreen.NETWORK)), actions(status, null), "$status")
        }
    }

    @Test
    fun `every status is covered`() {
        for (status in NetworkStatus.entries) actions(status)
    }
}
