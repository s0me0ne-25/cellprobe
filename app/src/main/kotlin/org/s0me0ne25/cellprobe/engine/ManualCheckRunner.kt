// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ListProbeListener
import org.s0me0ne25.cellprobe.core.probe.ListProbeResult
import org.s0me0ne25.cellprobe.core.probe.ListProbing
import org.s0me0ne25.cellprobe.core.probe.WebViewHeaders
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.core.vless.VlessEntry
import org.s0me0ne25.cellprobe.probe.HttpsProber
import org.s0me0ne25.cellprobe.probe.ProbeClientFactory
import org.s0me0ne25.cellprobe.probe.UserAgentProvider
import org.s0me0ne25.cellprobe.probe.VlessProber

/**
 * One manual check of all lists, started by the user.
 * Reads page bodies so that the results show page titles.
 */
class ManualCheckRunner(
    private val clientFactory: ProbeClientFactory,
    private val userAgentProvider: UserAgentProvider,
) {

    suspend fun run(
        lists: List<ResourceListState>,
        vlessEntries: List<VlessEntry>,
        listener: ListProbeListener,
    ): List<ListProbeResult<*>> {
        val userAgent = userAgentProvider.userAgent()
        val headers = WebViewHeaders.navigation(userAgent, UserAgentProvider.systemLanguageTags())
        // Creating the client loads Conscrypt's native library: keep it off the main thread.
        val client = withContext(Dispatchers.IO) { clientFactory.create() }
        val httpsProber = HttpsProber(client, headers, readBody = true)
        val vlessProber = VlessProber(headers, clientFor = { dns, sockets -> clientFactory.inboundClient(client, dns, sockets) })
        return try {
            coroutineScope {
                val https = lists.map { list ->
                    async { ListProbing.probeList(CheckListId.of(list.id), list.entries, httpsProber, listener) }
                }
                val vless = async { ListProbing.probeList(CheckListId.VLESS, vlessEntries, vlessProber, listener) }
                (https + vless).awaitAll()
            }
        } finally {
            // Closing TLS connections is network I/O; the caller may be on the main thread.
            withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }
}
