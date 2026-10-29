// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.s0me0ne25.cellprobe.core.probe.ListProbeListener
import org.s0me0ne25.cellprobe.core.probe.ListProbeResult
import org.s0me0ne25.cellprobe.core.probe.ListProbing
import org.s0me0ne25.cellprobe.core.probe.WebViewHeaders
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.probe.HttpsProber
import org.s0me0ne25.cellprobe.probe.ProbeClientFactory
import org.s0me0ne25.cellprobe.probe.UserAgentProvider

/**
 * One manual check of the HTTPS resource lists, started by the user.
 * Reads page bodies so that the results show page titles.
 */
class ManualCheckRunner(
    private val clientFactory: ProbeClientFactory,
    private val userAgentProvider: UserAgentProvider,
) {

    suspend fun run(lists: List<ResourceListState>, listener: ListProbeListener): List<ListProbeResult> {
        val userAgent = userAgentProvider.userAgent()
        val headers = WebViewHeaders.navigation(userAgent, UserAgentProvider.systemLanguageTags())
        // Creating the client loads Conscrypt's native library: keep it off the main thread.
        val client = withContext(Dispatchers.IO) { clientFactory.create() }
        val prober = HttpsProber(client, headers, readBody = true)
        return try {
            coroutineScope {
                lists.map { list ->
                    async { ListProbing.probeList(list.id, list.entries, prober, listener) }
                }.awaitAll()
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }
}
