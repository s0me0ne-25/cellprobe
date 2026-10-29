// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import java.nio.charset.Charset

/** Helpers for the beginning of a response body. */
object PageText {

    /** How much of the decoded body is read for markers and titles. */
    const val BODY_LIMIT_BYTES = 8 * 1024

    private const val MAX_TITLE_LENGTH = 120

    private val titleRegex = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val metaCharsetRegex = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9_\-:.]+)""", RegexOption.IGNORE_CASE)
    private val whitespaceRegex = Regex("""\s+""")
    private val entities = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ",
    )

    /**
     * Decodes body bytes: charset from the Content-Type header, then from a
     * `<meta charset>` in the bytes, then UTF-8. Malformed input is replaced.
     */
    fun decode(bytes: ByteArray, contentType: String?): String {
        val charset = charsetFromContentType(contentType)
            ?: charsetFromMeta(bytes)
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    fun charsetFromContentType(contentType: String?): Charset? {
        val name = contentType?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', '\'', ' ')
            ?: return null
        return charsetOrNull(name)
    }

    private fun charsetFromMeta(bytes: ByteArray): Charset? {
        val ascii = String(bytes, Charsets.ISO_8859_1)
        val name = metaCharsetRegex.find(ascii)?.groupValues?.get(1) ?: return null
        return charsetOrNull(name)
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Text of the first `<title>`, unescaped and trimmed, or null. */
    fun extractTitle(html: String): String? {
        val raw = titleRegex.find(html)?.groupValues?.get(1) ?: return null
        var title = raw
        entities.forEach { (entity, value) -> title = title.replace(entity, value, ignoreCase = true) }
        title = title.replace(whitespaceRegex, " ").trim()
        if (title.isEmpty()) return null
        return if (title.length > MAX_TITLE_LENGTH) title.take(MAX_TITLE_LENGTH).trimEnd() + "…" else title
    }

    /** Markers are matched case-insensitively. */
    fun containsMarker(text: String, marker: String): Boolean = text.contains(marker, ignoreCase = true)
}
