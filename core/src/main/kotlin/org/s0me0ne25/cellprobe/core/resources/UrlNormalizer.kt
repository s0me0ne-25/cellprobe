// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.resources

import org.s0me0ne25.cellprobe.core.net.Hosts
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

    private val schemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

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

        val (rawHost, rawPort) = Hosts.splitHostPort(authority)
            ?: return UrlParseResult.Invalid(UrlError.INVALID_HOST)
        val host = Hosts.normalize(rawHost) ?: return UrlParseResult.Invalid(UrlError.INVALID_HOST)
        val port = if (rawPort == null) {
            null
        } else {
            Hosts.parsePort(rawPort) ?: return UrlParseResult.Invalid(UrlError.INVALID_PORT)
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
        val host = Hosts.toDisplay(asciiHost)
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
        return canonicalUrl.replaceFirst("://$asciiHost", "://${Hosts.toDisplay(asciiHost)}")
    }
}
