// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.vless

import org.s0me0ne25.cellprobe.core.net.Hosts
import java.io.ByteArrayOutputStream

enum class VlessError {
    EMPTY,

    /** A link of another scheme (trojan://, ss://, vmess://, ...). */
    UNSUPPORTED_SCHEME,

    /** More than one link in one input. */
    MULTIPLE_LINKS,
    MALFORMED,
    INVALID_HOST,
    INVALID_PORT,

    /** The host is an IP address and no SNI is given. */
    MISSING_SNI,

    /** The SNI is not a valid domain name. */
    INVALID_SNI,
    INVALID_FINGERPRINT,

    /**
     * `security` is missing or is anything other than `reality` or `tls`
     * (`none`, the legacy `xtls` removed in Xray 1.8, ...).
     */
    UNSUPPORTED_SECURITY,

    /** UDP-based transport (mKCP, QUIC): a TCP probe cannot check it. */
    UNSUPPORTED_TRANSPORT,
    LABEL_TOO_LONG,
}

sealed interface VlessParseResult {
    data class Valid(val entry: VlessEntry) : VlessParseResult
    data class Invalid(val error: VlessError) : VlessParseResult
}

/**
 * Parses user input into a [VlessEntry].
 *
 * Accepted input:
 * - text containing exactly one `vless://` link; text around the link is ignored;
 * - a manual line `host:port sni [fp]`; for a domain host the SNI may be omitted.
 *
 * Only host, port, SNI, fingerprint and the label (link fragment) are extracted.
 * Everything else in the link, including the UUID and Reality keys, is dropped.
 */
object VlessInput {

    const val MAX_LABEL_LENGTH = 64
    private const val DEFAULT_PORT = 443
    private const val SCHEME = "vless://"

    private val linkRegex = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S*""")
    private val whitespaceRegex = Regex("""\s+""")
    private val fingerprintRegex = Regex("^[a-z0-9_-]{1,32}$")
    private val supportedSecurities = setOf("reality", "tls")
    private val udpTransports = setOf("kcp", "mkcp", "quic")

    /** True if [text] looks like it contains a link and must not be kept as typed. */
    fun containsLink(text: CharSequence): Boolean = text.contains("://")

    fun parse(text: String): VlessParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return invalid(VlessError.EMPTY)

        val links = linkRegex.findAll(trimmed).map { it.value }.toList()
        if (links.isEmpty()) {
            return if (containsLink(trimmed)) invalid(VlessError.MALFORMED) else parseManualLine(trimmed)
        }
        if (links.any { !it.startsWith(SCHEME, ignoreCase = true) }) return invalid(VlessError.UNSUPPORTED_SCHEME)
        if (links.size > 1) return invalid(VlessError.MULTIPLE_LINKS)
        return parseLink(links.single())
    }

    /**
     * Builds an entry from the editor form: the server line (link or manual
     * line) and a separately typed label. A non-blank [rawLabel] wins over the
     * link fragment.
     */
    fun fromForm(serverLine: String, rawLabel: String): VlessParseResult {
        val parsed = parse(serverLine)
        if (parsed !is VlessParseResult.Valid) return parsed
        if (rawLabel.isBlank()) return parsed
        val label = cleanLabel(rawLabel)
        if (label != null && label.codePointCount(0, label.length) > MAX_LABEL_LENGTH) {
            return invalid(VlessError.LABEL_TOO_LONG)
        }
        return VlessParseResult.Valid(parsed.entry.copy(label = label))
    }

    private fun parseLink(link: String): VlessParseResult {
        val body = link.substring(SCHEME.length)
        val fragment = body.substringAfter('#', missingDelimiterValue = "")
        val beforeFragment = body.substringBefore('#')
        val query = beforeFragment.substringAfter('?', missingDelimiterValue = "")
        // Some clients put a slash before the query: vless://id@host:port/?type=...
        val authority = beforeFragment.substringBefore('?').substringBefore('/')

        if ('@' !in authority) return invalid(VlessError.MALFORMED)
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return invalid(VlessError.INVALID_HOST)

        val (rawHost, rawPort) = Hosts.splitHostPort(hostPort) ?: return invalid(VlessError.INVALID_HOST)
        val host = Hosts.normalize(rawHost) ?: return invalid(VlessError.INVALID_HOST)
        val port = if (rawPort == null) DEFAULT_PORT else Hosts.parsePort(rawPort) ?: return invalid(VlessError.INVALID_PORT)

        val params = parseQuery(query)
        if (params["security"]?.trim()?.lowercase() !in supportedSecurities) {
            return invalid(VlessError.UNSUPPORTED_SECURITY)
        }
        if (params["type"]?.lowercase() in udpTransports) return invalid(VlessError.UNSUPPORTED_TRANSPORT)

        val rawSni = listOf("sni", "peer", "servername").firstNotNullOfOrNull { params[it]?.trim()?.ifEmpty { null } }
        val sni = when {
            rawSni != null -> normalizeSni(rawSni) ?: return invalid(VlessError.INVALID_SNI)
            !Hosts.isIpLiteral(host) -> host
            else -> return invalid(VlessError.MISSING_SNI)
        }

        // An unknown fingerprint in a link is dropped rather than rejecting the whole link.
        val fingerprint = params["fp"]?.trim()?.lowercase()?.takeIf { fingerprintRegex.matches(it) }
        // Clients such as v2rayNG form-encode the label: '+' is a space there, '%2B' a plus.
        val label = cleanLabel(percentDecode(fragment.replace('+', ' ')))
            ?.let { truncateCodePoints(it, MAX_LABEL_LENGTH) }

        return VlessParseResult.Valid(VlessEntry(host, port, sni, fingerprint, label))
    }

    private fun parseManualLine(line: String): VlessParseResult {
        val tokens = line.split(whitespaceRegex)
        if (tokens.size > 3) return invalid(VlessError.MALFORMED)

        val (rawHost, rawPort) = Hosts.splitHostPort(tokens[0]) ?: return invalid(VlessError.INVALID_HOST)
        val host = Hosts.normalize(rawHost) ?: return invalid(VlessError.INVALID_HOST)
        val port = rawPort?.let { Hosts.parsePort(it) } ?: return invalid(VlessError.INVALID_PORT)

        val sni = when {
            tokens.size >= 2 -> normalizeSni(tokens[1]) ?: return invalid(VlessError.INVALID_SNI)
            !Hosts.isIpLiteral(host) -> host
            else -> return invalid(VlessError.MISSING_SNI)
        }

        val fingerprint = tokens.getOrNull(2)?.lowercase()?.let {
            if (fingerprintRegex.matches(it)) it else return invalid(VlessError.INVALID_FINGERPRINT)
        }
        return VlessParseResult.Valid(VlessEntry(host, port, sni, fingerprint))
    }

    /** SNI must be a domain name, not an IP literal. */
    private fun normalizeSni(raw: String): String? =
        Hosts.normalize(raw)?.takeUnless { Hosts.isIpLiteral(it) }

    /** First value per key, keys lowercased, values percent-decoded. */
    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = percentDecode(pair.substringBefore('=')).lowercase()
            val value = percentDecode(pair.substringAfter('=', missingDelimiterValue = ""))
            result.putIfAbsent(key, value)
        }
        return result
    }

    /**
     * Percent-decoding as UTF-8; `+` is left as is. Broken escapes are kept
     * as they are.
     */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val high = Character.digit(text[i + 1], 16)
                val low = Character.digit(text[i + 2], 16)
                if (high >= 0 && low >= 0) {
                    bytes.write(high * 16 + low)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    /** Drops control characters and surrounding whitespace; blank becomes null. */
    private fun cleanLabel(raw: String): String? =
        raw.filterNot { Character.isISOControl(it) }.trim().ifEmpty { null }

    private fun truncateCodePoints(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max)).trimEnd()
    }

    private fun invalid(error: VlessError) = VlessParseResult.Invalid(error)
}
