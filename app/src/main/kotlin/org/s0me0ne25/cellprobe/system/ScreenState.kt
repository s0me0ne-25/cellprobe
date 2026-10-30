// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the screen is on (interactive), now and on every change. */
fun Context.screenOnStates(): Flow<Boolean> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> trySend(true)
                Intent.ACTION_SCREEN_OFF -> trySend(false)
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
    }
    // Protected system broadcasts: no export flag needed.
    registerReceiver(receiver, filter)
    trySend(getSystemService<PowerManager>()?.isInteractive != false)
    awaitClose { unregisterReceiver(receiver) }
}.distinctUntilChanged()
