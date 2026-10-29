// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core

import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ListProbeResult

/** Transport of the active network. */
enum class NetworkType { WIFI, MOBILE, ETHERNET, OTHER }

/**
 * System signals read before a check, without network requests (spec §3).
 *
 * @property activeNetwork transport of the active network, or null if there
 *   is none.
 * @property captivePortal the system flags the active network as a captive portal.
 * @property airplaneMode airplane mode is on.
 * @property mobileDataEnabled mobile data is switched on for the data SIM.
 */
data class NetworkState(
    val activeNetwork: NetworkType?,
    val captivePortal: Boolean = false,
    val airplaneMode: Boolean = false,
    val mobileDataEnabled: Boolean = true,
)

/**
 * Reachability of each list for the classifier: true if any entry
 * responded, null if the list is empty.
 */
data class ListAvailability(
    val domestic: Boolean,
    val foreign: Boolean,
    val vless: Boolean?,
    val banned: Boolean,
    val provider: Boolean?,
) {
    companion object {
        fun of(results: List<ListProbeResult<*>>): ListAvailability {
            val byId = results.associateBy { it.listId }
            fun available(id: CheckListId) = byId[id]?.available ?: false
            fun availableOrEmpty(id: CheckListId) = byId[id]?.takeIf { it.results.isNotEmpty() }?.available
            return ListAvailability(
                domestic = available(CheckListId.DOMESTIC),
                foreign = available(CheckListId.FOREIGN),
                vless = availableOrEmpty(CheckListId.VLESS),
                banned = available(CheckListId.BANNED),
                provider = availableOrEmpty(CheckListId.PROVIDER),
            )
        }
    }
}

/**
 * The classifier of spec §4: `(network state, list results) → status`.
 * The first matching rule wins.
 */
object NetworkClassifier {

    /**
     * Whether the resource lists must be probed. Without an active network
     * or behind a captive portal the status follows from [state] alone.
     */
    fun needsProbes(state: NetworkState): Boolean = state.activeNetwork != null && !state.captivePortal

    /**
     * @param lists results of the lists; required when [needsProbes] is true,
     *   ignored otherwise.
     */
    fun classify(state: NetworkState, lists: ListAvailability?): NetworkStatus {
        if (state.activeNetwork == null) {
            // Without a SIM or with data off there is nothing to monitor.
            // Physical loss of service (NO_SIGNAL, VOICE_ONLY) is told apart
            // from a dead connection in a later step; until then it is
            // NO_INTERNET.
            return if (state.airplaneMode || !state.mobileDataEnabled) NetworkStatus.USER_OFFLINE else NetworkStatus.NO_INTERNET
        }
        if (state.captivePortal) return NetworkStatus.AUTH_REQUIRED

        requireNotNull(lists) { "List results are required for an active network without a captive portal" }
        return when {
            lists.banned -> NetworkStatus.LEAK
            lists.vless == true -> NetworkStatus.NORMAL
            lists.foreign -> if (lists.vless == null) NetworkStatus.NORMAL_NO_VLESS else NetworkStatus.VLESS_BLOCKED
            lists.domestic -> NetworkStatus.WHITELIST
            lists.provider == true -> NetworkStatus.PAY
            else -> NetworkStatus.NO_INTERNET
        }
    }
}
