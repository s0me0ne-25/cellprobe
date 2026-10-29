// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.actions

import org.s0me0ne25.cellprobe.core.net.Hosts
import java.net.URI
import java.net.URISyntaxException

/**
 * Short labels of provider account pages for buttons and notifications
 * (spec §7.4): the host without scheme, path and query, with only `www.`
 * removed; when two pages share a host, the first path segment is added.
 */
object ProviderPageLabels {

    fun labels(urls: List<String>): List<String> {
        val parsed = urls.map(::parse)
        val hostCounts = parsed.groupingBy { it.host }.eachCount()
        return parsed.map { page ->
            val segment = page.firstSegment
            if (hostCounts.getValue(page.host) > 1 && segment != null) "${page.host}/$segment" else page.host
        }
    }

    private class Page(val host: String, val firstSegment: String?)

    private fun parse(url: String): Page {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return Page(url, null)
        }
        val rawHost = uri.host ?: return Page(url, null)
        val host = Hosts.toDisplay(rawHost.lowercase()).removePrefix("www.")
        val port = if (uri.port != -1 && uri.port != DEFAULT_HTTPS_PORT) ":${uri.port}" else ""
        val segment = uri.path.orEmpty().split('/').firstOrNull { it.isNotEmpty() }
        return Page(host + port, segment)
    }

    private const val DEFAULT_HTTPS_PORT = 443
}
