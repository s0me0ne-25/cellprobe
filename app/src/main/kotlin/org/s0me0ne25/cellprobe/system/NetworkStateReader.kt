// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import org.s0me0ne25.cellprobe.core.NetworkState
import org.s0me0ne25.cellprobe.core.NetworkType

/**
 * Reads the system signals of spec §3: the active network and its
 * transport, the captive portal flag, airplane mode and mobile data.
 * No network requests and no dangerous permissions.
 */
class NetworkStateReader(context: Context) {

    private val appContext = context.applicationContext
    private val connectivity = checkNotNull(appContext.getSystemService<ConnectivityManager>())

    fun read(): NetworkState {
        // The default network of this app: the physical network when
        // Cellprobe is excluded from the VPN, the VPN otherwise (its
        // capabilities then also carry the underlying transport).
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        return NetworkState(
            networkId = network?.networkHandle,
            activeNetwork = capabilities?.let(::transportOf),
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            airplaneMode = Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0,
            mobileDataEnabled = mobileDataEnabled(),
        )
    }

    /**
     * Emits on subscription and whenever the default network of this app
     * appears, goes away or changes its capabilities (e.g. a captive portal
     * is detected); the state itself is read with [read].
     */
    fun changes(): Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(Unit)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }
        .onStart { emit(Unit) }
        .conflate()

    private fun transportOf(capabilities: NetworkCapabilities): NetworkType = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
        else -> NetworkType.OTHER
    }

    /** Mobile data switch of the SIM chosen for data; false without a SIM. */
    private fun mobileDataEnabled(): Boolean {
        val subscription = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telephony = appContext.getSystemService<TelephonyManager>()?.createForSubscriptionId(subscription)
            try {
                // Allowed with ACCESS_NETWORK_STATE since API 26.
                if (telephony != null) return telephony.isDataEnabled
            } catch (_: SecurityException) {
                // Some vendor builds still demand READ_PHONE_STATE: fall back to the setting.
            }
        }
        val resolver = appContext.contentResolver
        val perSubscription = Settings.Global.getInt(resolver, "$MOBILE_DATA_SETTING$subscription", -1)
        if (perSubscription != -1) return perSubscription != 0
        return Settings.Global.getInt(resolver, MOBILE_DATA_SETTING, 1) != 0
    }

    private companion object {
        /** Hidden `Settings.Global.MOBILE_DATA`, the only source on API 24–25. */
        const val MOBILE_DATA_SETTING = "mobile_data"
    }
}
