// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

/**
 * Request headers of a top-level navigation in Android WebView, so that
 * probes look like a page opened in an embedded browser.
 */
object WebViewHeaders {

    private val chromeVersionRegex = Regex("""Chrome/(\d+)""")

    /** Headers in the order the browser sends them; `Host` is added by the client. */
    fun navigation(userAgent: String, languageTags: List<String>): List<Pair<String, String>> {
        val major = chromeMajorVersion(userAgent)
        return buildList {
            if (major != null) {
                add("sec-ch-ua" to secChUa(major))
                add("sec-ch-ua-mobile" to "?1")
                add("sec-ch-ua-platform" to "\"Android\"")
            }
            add("Upgrade-Insecure-Requests" to "1")
            add("User-Agent" to userAgent)
            add("Accept" to ACCEPT)
            add("Sec-Fetch-Site" to "none")
            add("Sec-Fetch-Mode" to "navigate")
            add("Sec-Fetch-User" to "?1")
            add("Sec-Fetch-Dest" to "document")
            add("Accept-Encoding" to ACCEPT_ENCODING)
            add("Accept-Language" to acceptLanguage(languageTags))
            add("Priority" to "u=0, i")
        }
    }

    fun chromeMajorVersion(userAgent: String): Int? =
        chromeVersionRegex.find(userAgent)?.groupValues?.get(1)?.toIntOrNull()

    fun secChUa(major: Int): String =
        "\"Chromium\";v=\"$major\", \"Android WebView\";v=\"$major\", \"Not?A_Brand\";v=\"99\""

    /**
     * Chrome's Accept-Language: each tag followed by its base language if
     * that is not listed yet, quality decreasing by 0.1 down to 0.1.
     * `["ru-RU", "en-US"]` becomes `ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7`.
     */
    fun acceptLanguage(languageTags: List<String>): String {
        val expanded = LinkedHashSet<String>()
        for (tag in languageTags.map { it.trim() }.filter { it.isNotEmpty() && it != "und" }) {
            expanded += tag
            val base = tag.substringBefore('-')
            if (base != tag && languageTags.none { it.equals(base, ignoreCase = true) }) expanded += base
        }
        if (expanded.isEmpty()) expanded += "en-US"
        return expanded.mapIndexed { index, tag ->
            if (index == 0) tag else "$tag;q=${qualityAt(index)}"
        }.joinToString(",")
    }

    /** 0.9, 0.8, ... with a floor of 0.1, formatted without locale effects. */
    private fun qualityAt(index: Int): String = "0.${(10 - index).coerceAtLeast(1)}"

    const val ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8," +
            "application/signed-exchange;v=b3;q=0.7"

    /** Every encoding listed here must be decodable by the prober. */
    const val ACCEPT_ENCODING = "gzip, deflate, br, zstd"
}
