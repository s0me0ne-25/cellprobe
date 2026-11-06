// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.system

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.s0me0ne25.cellprobe.core.CellularService

/**
 * Registration of the data SIM in the cellular network (spec §3), kept up
 * to date by a service state listener. Listening to the service state
 * needs no permission on any Android version (only location details are
 * withheld), so READ_PHONE_STATE is never asked for.
 *
 * The listener follows the SIM chosen for mobile data: when it changes, the
 * next [read] moves the listener over.
 */
class CellularStateReader(context: Context) {

    /** The registration and whether the SIM is roaming. */
    data class Snapshot(val service: CellularService, val roaming: Boolean)

    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService<TelephonyManager>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var subscription: Int? = null

    @Volatile
    private var latest: Snapshot? = null

    /** Stops the current listener; touched on the main thread only. */
    private var stopListening: (() -> Unit)? = null

    /** The latest state of the current data SIM, or null while unknown (no SIM, no answer yet). */
    fun read(): Snapshot? {
        val current = SubscriptionManager.getDefaultDataSubscriptionId()
        if (current != subscription) {
            subscription = current
            latest = null
            mainHandler.post { listen(current) }
        }
        return latest
    }

    private fun listen(target: Int) {
        // A newer data SIM was asked for meanwhile.
        if (target != subscription) return
        stopListening?.invoke()
        stopListening = null
        if (target == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        val manager = telephony?.createForSubscriptionId(target) ?: return
        val onState = { state: ServiceState ->
            if (subscription == target) latest = Snapshot(state.state.toService(), state.roaming)
        }
        stopListening = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listenWithCallback(manager, onState) else listenLegacy(manager, onState)
        } catch (_: SecurityException) {
            // A vendor build that wants a permission after all: the status stays NO_INTERNET.
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listenWithCallback(manager: TelephonyManager, onState: (ServiceState) -> Unit): () -> Unit {
        val callback = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
            override fun onServiceStateChanged(serviceState: ServiceState) = onState(serviceState)
        }
        manager.registerTelephonyCallback(ContextCompat.getMainExecutor(appContext), callback)
        return { manager.unregisterTelephonyCallback(callback) }
    }

    // The only way before API 31; created on the main thread, which has a looper.
    @Suppress("DEPRECATION")
    private fun listenLegacy(manager: TelephonyManager, onState: (ServiceState) -> Unit): () -> Unit {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onServiceStateChanged(serviceState: ServiceState?) {
                serviceState?.let(onState)
            }
        }
        manager.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE)
        return { manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    private fun Int.toService(): CellularService = when (this) {
        ServiceState.STATE_IN_SERVICE -> CellularService.IN_SERVICE
        ServiceState.STATE_EMERGENCY_ONLY -> CellularService.EMERGENCY_ONLY
        ServiceState.STATE_POWER_OFF -> CellularService.POWER_OFF
        else -> CellularService.OUT_OF_SERVICE
    }
}
