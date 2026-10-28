// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.vless

import kotlinx.serialization.Serializable
import org.s0me0ne25.cellprobe.core.net.Hosts

/**
 * A user's VLESS inbound, reduced to what probing needs.
 * UUID, keys, short ids and other secrets of the original link are never stored.
 *
 * @property host canonical host of the proxy server, see [Hosts.normalize].
 * @property sni canonical domain presented in the TLS ClientHello.
 * @property fingerprint uTLS fingerprint name from the link (`fp`), if any.
 * @property label user-visible name, taken from the link fragment or typed in.
 */
@Serializable
data class VlessEntry(
    val host: String,
    val port: Int,
    val sni: String,
    val fingerprint: String? = null,
    val label: String? = null,
) {
    /** `host:port` for display, with Unicode domains. */
    val address: String
        get() = "${Hosts.toDisplay(host)}:$port"

    val displaySni: String
        get() = Hosts.toDisplay(sni)

    /** Identity of the server within the list; the label and fingerprint are not part of it. */
    val key: String
        get() = "$host|$port|$sni"

    /** Editable single-line form `host:port sni [fp]`, accepted back by [VlessInput.parse]. */
    fun toManualLine(): String = listOfNotNull(address, displaySni, fingerprint).joinToString(" ")
}
