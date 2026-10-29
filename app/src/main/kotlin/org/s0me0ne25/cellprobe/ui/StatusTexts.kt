// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui

import androidx.annotation.StringRes
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.NetworkType
import org.s0me0ne25.cellprobe.ui.theme.StatusTone

/** UI texts and tones for network statuses and network types (glossary). */

/** Short status name, shown large. */
@StringRes
fun NetworkStatus.titleRes(): Int = when (this) {
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

/** What the status means and what to do, shown under the title; null where not written yet. */
@StringRes
fun NetworkStatus.descriptionRes(): Int? = when (this) {
    NetworkStatus.NORMAL -> R.string.status_normal_description
    NetworkStatus.NORMAL_NO_VLESS -> R.string.status_normal_no_vless_description
    NetworkStatus.VLESS_BLOCKED -> R.string.status_vless_blocked_description
    NetworkStatus.WHITELIST -> R.string.status_whitelist_description
    NetworkStatus.PAY -> R.string.status_pay_description
    NetworkStatus.AUTH_REQUIRED -> R.string.status_auth_required_description
    NetworkStatus.NO_INTERNET -> R.string.status_no_internet_description
    NetworkStatus.LEAK -> R.string.status_leak_description
    NetworkStatus.USER_OFFLINE -> R.string.status_user_offline_description
    // Detected only from a later step on; texts follow with the detection.
    NetworkStatus.NO_SIGNAL, NetworkStatus.VOICE_ONLY -> null
}

fun NetworkStatus.tone(): StatusTone = when (this) {
    NetworkStatus.NORMAL, NetworkStatus.NORMAL_NO_VLESS -> StatusTone.NORMAL
    NetworkStatus.VLESS_BLOCKED -> StatusTone.WARNING
    NetworkStatus.WHITELIST,
    NetworkStatus.PAY,
    NetworkStatus.AUTH_REQUIRED,
    NetworkStatus.NO_INTERNET,
    NetworkStatus.NO_SIGNAL,
    NetworkStatus.VOICE_ONLY,
    -> StatusTone.ALERT
    NetworkStatus.LEAK -> StatusTone.FREE
    NetworkStatus.USER_OFFLINE -> StatusTone.NEUTRAL
}

@StringRes
fun NetworkType?.labelRes(): Int = when (this) {
    NetworkType.WIFI -> R.string.network_type_wifi
    NetworkType.MOBILE -> R.string.network_type_mobile
    NetworkType.ETHERNET -> R.string.network_type_ethernet
    NetworkType.OTHER -> R.string.network_type_other
    null -> R.string.network_type_none
}
