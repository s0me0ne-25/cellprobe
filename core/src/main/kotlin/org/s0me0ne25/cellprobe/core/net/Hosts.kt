// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.net

import java.net.IDN

/**
 * Host name and authority parsing shared by URL and proxy entry parsers.
 *
 * Canonical host forms: lowercase ASCII domain (IDN converted to punycode),
 * dotted IPv4, or bracketed lowercase IPv6 such as `[2001:db8::1]`.
 */
object Hosts {

    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    private val numericHostRegex = Regex("^[0-9.]+$")
    private val ipv6Regex = Regex("^[0-9a-fA-F:.]+$")

    /**
     * Splits `host[:port]`. The host keeps IPv6 brackets.
     * Returns null for an unbracketed IPv6 literal or broken brackets.
     */
    fun splitHostPort(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            return when {
                rest.isEmpty() -> host to null
                rest.startsWith(":") -> host to rest.substring(1)
                else -> null
            }
        }
        return when (authority.count { it == ':' }) {
            0 -> authority to null
            1 -> authority.substringBefore(':') to authority.substringAfter(':')
            else -> null
        }
    }

    /** Returns the canonical form of [rawHost], or null if it is not a valid host. */
    fun normalize(rawHost: String): String? {
        if (rawHost.startsWith("[")) {
            val literal = rawHost.removePrefix("[").removeSuffix("]")
            return if (':' in literal && ipv6Regex.matches(literal)) "[${literal.lowercase()}]" else null
        }

        val host = rawHost.removeSuffix(".")
        if (host.isEmpty()) return null

        if (numericHostRegex.matches(host)) {
            return host.takeIf { isValidIpv4(it) }
        }

        val ascii = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (ascii.length > MAX_HOST_LENGTH || '.' !in ascii) return null
        val labels = ascii.split('.')
        if (labels.any { it.isEmpty() || it.length > MAX_LABEL_LENGTH }) return null
        return ascii
    }

    /** Parses a port number in the valid TCP range, or returns null. */
    fun parsePort(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in 1..65535 }

    /** True for canonical IPv4 and bracketed IPv6 literals. */
    fun isIpLiteral(canonicalHost: String): Boolean =
        canonicalHost.startsWith("[") || numericHostRegex.matches(canonicalHost)

    /** Unicode form of a canonical host, for display and editing. */
    fun toDisplay(canonicalHost: String): String =
        if (isIpLiteral(canonicalHost)) canonicalHost else IDN.toUnicode(canonicalHost)

    private fun isValidIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && (part.toIntOrNull() ?: -1) in 0..255
        }
    }
}
