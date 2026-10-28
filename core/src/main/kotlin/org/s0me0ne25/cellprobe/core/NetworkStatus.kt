// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core

/**
 * Network status as determined by the classifier.
 *
 * [severity] ranks how restrictive the filtering is: a higher value means a
 * stricter filter. `null` means the status is outside the severity scale
 * (a configuration problem or a user action rather than a filter mode).
 */
enum class NetworkStatus(val severity: Int?) {
    /** At least one VLESS entry is reachable. */
    NORMAL(0),

    /** No VLESS entries configured, but the network is not in whitelist mode. */
    NORMAL_NO_VLESS(0),

    /** Whitelist mode is off, yet every VLESS entry is blocked. */
    VLESS_BLOCKED(1),

    /** Only resources from the never-blocked list are reachable. */
    WHITELIST(2),

    /** Only the provider's account page is reachable. */
    PAY(3),

    /** The system reports a captive portal on the active network. */
    AUTH_REQUIRED(3),

    /** Nothing is reachable. */
    NO_INTERNET(4),

    /** Mobile data is enabled but there is no cellular service. */
    NO_SIGNAL(4),

    /** Cellular service is present but the data session does not come up. */
    VOICE_ONLY(4),

    /** A banned resource is reachable: traffic is proxied or not excluded. */
    LEAK(null),

    /** Airplane mode or mobile data switched off by the user. */
    USER_OFFLINE(null);

    /**
     * Returns true if this status is stricter than [other].
     * Statuses outside the severity scale are never stricter or softer.
     */
    fun isStricterThan(other: NetworkStatus): Boolean {
        val mine = severity ?: return false
        val theirs = other.severity ?: return false
        return mine > theirs
    }
}
