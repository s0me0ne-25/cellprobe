// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui

import androidx.annotation.StringRes
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType

/** UI texts for network statuses and network types (spec §4, glossary). */

@StringRes
fun NetworkStatus.textRes(): Int = when (this) {
    NetworkStatus.NORMAL -> R.string.status_normal
    NetworkStatus.NORMAL_NO_VLESS -> R.string.status_normal_no_vless
    NetworkStatus.VLESS_BLOCKED -> R.string.status_vless_blocked
    NetworkStatus.WHITELIST -> R.string.status_whitelist
    NetworkStatus.PAY -> R.string.status_pay
    NetworkStatus.AUTH_REQUIRED -> R.string.status_auth_required
    NetworkStatus.NO_INTERNET -> R.string.status_no_internet
    NetworkStatus.NO_SIGNAL -> R.string.status_no_signal
    NetworkStatus.VOICE_ONLY -> R.string.status_voice_only
    NetworkStatus.LEAK -> R.string.status_leak
    NetworkStatus.USER_OFFLINE -> R.string.status_user_offline
}

@StringRes
fun NetworkType?.labelRes(): Int = when (this) {
    NetworkType.WIFI -> R.string.network_type_wifi
    NetworkType.MOBILE -> R.string.network_type_mobile
    NetworkType.ETHERNET -> R.string.network_type_ethernet
    NetworkType.OTHER -> R.string.network_type_other
    null -> R.string.network_type_none
}
