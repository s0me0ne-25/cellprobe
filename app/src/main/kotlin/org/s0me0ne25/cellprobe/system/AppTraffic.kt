// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.system

import android.net.TrafficStats
import android.os.Process

/**
 * Bytes sent and received by this app since boot, as counted by the system
 * at the IP level (the same source as the system's data usage screen).
 * Cellprobe makes no other network requests, so the difference around a
 * check is the traffic of that check.
 */
object AppTraffic {

    /** Total bytes, or null if the device does not report per-app traffic. */
    fun totalBytes(): Long? {
        val uid = Process.myUid()
        val received = TrafficStats.getUidRxBytes(uid)
        val sent = TrafficStats.getUidTxBytes(uid)
        if (received == TrafficStats.UNSUPPORTED.toLong() || sent == TrafficStats.UNSUPPORTED.toLong()) return null
        return received + sent
    }
}
