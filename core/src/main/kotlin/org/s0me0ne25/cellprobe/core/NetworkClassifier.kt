// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core

import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ListProbeResult

/** Transport of the active network. */
enum class NetworkType { WIFI, MOBILE, ETHERNET, OTHER }

/** Registration of the data SIM in the cellular network, as the system reports it. */
enum class CellularService {
    /** Registered: calls work. */
    IN_SERVICE,

    /** Not registered anywhere: no coverage. */
    OUT_OF_SERVICE,

    /** Emergency calls only: no network of the operator. */
    EMERGENCY_ONLY,

    /** The radio is off. */
    POWER_OFF,
}

/**
 * System signals read before a check, without network requests (spec §3).
 *
 * @property activeNetwork transport of the active network, or null if there
 *   is none.
 * @property captivePortal the system flags the active network as a captive portal.
 * @property airplaneMode airplane mode is on.
 * @property mobileDataEnabled mobile data is switched on for the data SIM.
 * @property networkId opaque number of the active network connection,
 *   new on every (re)connection; tells a network change from a
 *   capabilities update. Kept in memory only.
 * @property cellularService registration of the data SIM, null if unknown.
 * @property roaming the data SIM is roaming.
 * @property dataRoamingEnabled mobile data is allowed in roaming.
 */
data class NetworkState(
    val activeNetwork: NetworkType?,
    val captivePortal: Boolean = false,
    val airplaneMode: Boolean = false,
    val mobileDataEnabled: Boolean = true,
    val networkId: Long? = null,
    val cellularService: CellularService? = null,
    val roaming: Boolean = false,
    val dataRoamingEnabled: Boolean = true,
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
        if (state.activeNetwork == null) return classifyWithoutNetwork(state)
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

    /**
     * No active network (spec §3): turned off by the user, or the cellular
     * network is lost (NO_SIGNAL) or carries calls but no data (VOICE_ONLY).
     * Roaming with data roaming off is the user's choice, not VOICE_ONLY.
     * Without the registration state it is NO_INTERNET.
     */
    private fun classifyWithoutNetwork(state: NetworkState): NetworkStatus {
        // Without a SIM or with data off there is nothing to monitor.
        if (state.airplaneMode || !state.mobileDataEnabled) return NetworkStatus.USER_OFFLINE
        return when (state.cellularService) {
            CellularService.OUT_OF_SERVICE, CellularService.EMERGENCY_ONLY -> NetworkStatus.NO_SIGNAL
            CellularService.IN_SERVICE ->
                if (state.roaming && !state.dataRoamingEnabled) NetworkStatus.USER_OFFLINE else NetworkStatus.VOICE_ONLY
            // The radio off without airplane mode is rare and says nothing about filtering.
            CellularService.POWER_OFF, null -> NetworkStatus.NO_INTERNET
        }
    }
}
