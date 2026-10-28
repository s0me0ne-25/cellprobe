// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.resources

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException

/** Reasons why a user-entered address cannot be used as a probe target. */
enum class UrlError {
    EMPTY,
    NOT_HTTPS,
    CREDENTIALS_NOT_ALLOWED,
    INVALID_HOST,
    INVALID_PORT,
    MALFORMED,
}

sealed interface UrlParseResult {
    /** [url] is in canonical form, see [UrlNormalizer.normalize]. */
    data class Valid(val url: String) : UrlParseResult
    data class Invalid(val error: UrlError) : UrlParseResult
}

/**
 * Turns user input into a canonical https URL.
 *
 * Canonical form: `https://` + lowercase ASCII host (IDN converted to
 * punycode) + port unless it is 443 + path and query as entered.
 * The fragment is dropped, a lone trailing `/` is dropped.
 * Input without a scheme is treated as https.
 */
object UrlNormalizer {

    private const val HTTPS_PORT = 443
    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    private val schemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    private val numericHostRegex = Regex("^[0-9.]+$")
    private val ipv6Regex = Regex("^[0-9a-fA-F:.]+$")

    fun normalize(input: String): UrlParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return UrlParseResult.Invalid(UrlError.EMPTY)

        val schemeMatch = schemeRegex.find(trimmed)
        val afterScheme = if (schemeMatch != null) {
            if (!schemeMatch.groupValues[1].equals("https", ignoreCase = true)) {
                return UrlParseResult.Invalid(UrlError.NOT_HTTPS)
            }
            trimmed.substring(schemeMatch.range.last + 1)
        } else {
            trimmed
        }

        val withoutFragment = afterScheme.substringBefore('#')
        val authorityEnd = withoutFragment.indexOfFirst { it == '/' || it == '?' }
            .let { if (it < 0) withoutFragment.length else it }
        val authority = withoutFragment.substring(0, authorityEnd)
        var tail = withoutFragment.substring(authorityEnd)

        if (authority.isEmpty()) return UrlParseResult.Invalid(UrlError.INVALID_HOST)
        if ('@' in authority) return UrlParseResult.Invalid(UrlError.CREDENTIALS_NOT_ALLOWED)

        val (rawHost, rawPort) = splitHostPort(authority)
            ?: return UrlParseResult.Invalid(UrlError.INVALID_HOST)
        val host = normalizeHost(rawHost) ?: return UrlParseResult.Invalid(UrlError.INVALID_HOST)
        val port = if (rawPort == null) {
            null
        } else {
            rawPort.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return UrlParseResult.Invalid(UrlError.INVALID_PORT)
        }

        if (tail == "/") tail = ""
        val portSuffix = if (port == null || port == HTTPS_PORT) "" else ":$port"
        val candidate = "https://$host$portSuffix$tail"

        return try {
            URI(candidate)
            UrlParseResult.Valid(candidate)
        } catch (_: URISyntaxException) {
            UrlParseResult.Invalid(UrlError.MALFORMED)
        }
    }

    /**
     * Human-readable form of a canonical URL: Unicode host, then port and
     * path if present. The query is omitted.
     */
    fun displayName(canonicalUrl: String): String {
        val uri = try {
            URI(canonicalUrl)
        } catch (_: URISyntaxException) {
            return canonicalUrl
        }
        val asciiHost = uri.host ?: return canonicalUrl
        val host = if (asciiHost.startsWith("[")) asciiHost else IDN.toUnicode(asciiHost)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path.orEmpty().takeIf { it != "/" }.orEmpty()
        return host + port + path
    }

    /**
     * Canonical URL with the host converted back to Unicode, for editing.
     * Normalizing the result yields the same canonical URL again.
     */
    fun editableForm(canonicalUrl: String): String {
        val asciiHost = try {
            URI(canonicalUrl).host
        } catch (_: URISyntaxException) {
            null
        } ?: return canonicalUrl
        if (asciiHost.startsWith("[")) return canonicalUrl
        return canonicalUrl.replaceFirst("://$asciiHost", "://${IDN.toUnicode(asciiHost)}")
    }

    /** Splits `host[:port]`; returns null for an unbracketed IPv6 literal. */
    private fun splitHostPort(authority: String): Pair<String, String?>? {
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

    private fun normalizeHost(rawHost: String): String? {
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

    private fun isValidIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && (part.toIntOrNull() ?: -1) in 0..255
        }
    }
}
