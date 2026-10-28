// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.vless

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.net.IDN

/**
 * Sample links mimic the formats exported by popular clients. All values
 * are fake: documentation IP ranges, random UUIDs and keys.
 */
class VlessInputTest {

    private fun valid(input: String): VlessEntry {
        val result = VlessInput.parse(input)
        check(result is VlessParseResult.Valid) { "Expected valid for '$input', got $result" }
        return result.entry
    }

    private fun error(input: String): VlessError {
        val result = VlessInput.parse(input)
        check(result is VlessParseResult.Invalid) { "Expected invalid for '$input', got $result" }
        return result.error
    }

    @Test
    fun `v2rayNG reality link`() {
        val entry = valid(V2RAYNG_REALITY)
        assertEquals(VlessEntry("203.0.113.5", 443, "www.microsoft.com", "chrome", "🇳🇱 NL-1"), entry)
    }

    @Test
    fun `Hiddify link with IPv6 host and spiderX path`() {
        val entry = valid(HIDDIFY_IPV6)
        assertEquals(VlessEntry("[2001:db8::7]", 8443, "dl.google.com", "firefox", "Hiddify v6"), entry)
    }

    @Test
    fun `NekoBox tls grpc link without fingerprint`() {
        val entry = valid(NEKOBOX_TLS_GRPC)
        assertEquals(VlessEntry("proxy.example.net", 443, "proxy.example.net", null, "Neko gRPC"), entry)
    }

    @Test
    fun `Happ link with slash before the query`() {
        val entry = valid(HAPP_SLASH)
        assertEquals(VlessEntry("198.51.100.20", 2053, "www.yahoo.com", "safari", "Happ RU"), entry)
    }

    @Test
    fun `plus in the label is a space, encoded plus is a plus`() {
        assertEquals("a b+c", valid("vless://$UUID@203.0.113.9:443?security=tls&sni=a.example.com#a+b%2Bc").label)
    }

    @ParameterizedTest
    @MethodSource("allSamples")
    fun `secrets never reach the parsed entry`(link: String) {
        val entry = valid(link)
        val serialized = Json.encodeToString(VlessEntry.serializer(), entry) + entry.toString() + entry.toManualLine()
        for (secret in SECRETS) {
            assertFalse(serialized.contains(secret, ignoreCase = true), "Leaked '$secret' from $link")
        }
    }

    @Test
    fun `text around the link is ignored`() {
        val entry = valid("old text $V2RAYNG_REALITY trailing")
        assertEquals("203.0.113.5", entry.host)
    }

    @Test
    fun `tls link without sni and with domain host uses the host`() {
        val entry = valid("vless://$UUID@Proxy.Example.ORG:443?security=tls&type=ws&path=%2Fws#x")
        assertEquals("proxy.example.org", entry.sni)
    }

    @Test
    fun `security value is case-insensitive`() {
        assertEquals(443, valid("vless://$UUID@203.0.113.9:443?security=REALITY&sni=a.example.com").port)
        assertEquals(443, valid("vless://$UUID@203.0.113.9:443?security=Tls&sni=a.example.com").port)
    }

    @Test
    fun `peer is accepted as an sni alias`() {
        assertEquals("cdn.example.com", valid("vless://$UUID@203.0.113.9:443?security=tls&peer=cdn.example.com").sni)
    }

    @Test
    fun `missing port defaults to 443`() {
        assertEquals(443, valid("vless://$UUID@203.0.113.9?security=reality&sni=a.example.com").port)
    }

    @Test
    fun `unknown fingerprint is dropped`() {
        assertNull(valid("vless://$UUID@203.0.113.9:443?security=reality&sni=a.example.com&fp=%3Cscript%3E").fingerprint)
    }

    @Test
    fun `internationalized sni is stored as punycode and shown in unicode`() {
        val entry = valid("vless://$UUID@203.0.113.9:443?security=tls&sni=%D0%BF%D1%80%D0%B8%D0%BC%D0%B5%D1%80.%D1%80%D1%84")
        assertEquals(IDN.toASCII("пример.рф"), entry.sni)
        assertEquals("пример.рф", entry.displaySni)
    }

    @Test
    fun `long labels from links are truncated`() {
        val entry = valid("vless://$UUID@203.0.113.9:443?security=tls&sni=a.example.com#" + "x".repeat(100))
        assertEquals(VlessInput.MAX_LABEL_LENGTH, entry.label!!.length)
    }

    @Test
    fun `control characters are removed from labels`() {
        assertEquals("ab", valid("vless://$UUID@203.0.113.9:443?security=tls&sni=a.example.com#a%0Ab").label)
    }

    @ParameterizedTest
    @CsvSource(
        "'', EMPTY",
        "trojan://pass@203.0.113.9:443?sni=a.example.com, UNSUPPORTED_SCHEME",
        "ss://YWVzLTI1Ni1nY206cGFzcw@203.0.113.9:8388, UNSUPPORTED_SCHEME",
        "'vless://a@1.2.3.4?security=tls&sni=a.example.com vless://b@1.2.3.5?security=tls&sni=a.example.com', MULTIPLE_LINKS",
        "vless://203.0.113.9:443?security=tls&sni=a.example.com, MALFORMED",
        "://broken, MALFORMED",
        "vless://id@999.1.1.1:443?security=tls&sni=a.example.com, INVALID_HOST",
        "vless://id@203.0.113.9:0?security=tls&sni=a.example.com, INVALID_PORT",
        "vless://id@203.0.113.9:443?security=reality, MISSING_SNI",
        "vless://id@203.0.113.9:443?security=reality&sni=1.1.1.1, INVALID_SNI",
        "vless://id@203.0.113.9:443?security=none&sni=a.example.com, UNSUPPORTED_SECURITY",
        "vless://id@203.0.113.9:443?security=xtls&sni=a.example.com, UNSUPPORTED_SECURITY",
        "vless://id@203.0.113.9:443?security=foobar&sni=a.example.com, UNSUPPORTED_SECURITY",
        "vless://id@203.0.113.9:443?security=&sni=a.example.com, UNSUPPORTED_SECURITY",
        "vless://id@203.0.113.9:443?sni=a.example.com, UNSUPPORTED_SECURITY",
        "vless://id@203.0.113.9:443?security=tls&type=kcp&sni=a.example.com, UNSUPPORTED_TRANSPORT",
        "vless://id@203.0.113.9:443?security=tls&type=quic&sni=a.example.com, UNSUPPORTED_TRANSPORT",
    )
    fun `invalid links`(input: String, expected: VlessError) {
        assertEquals(expected, error(input))
    }

    @ParameterizedTest
    @CsvSource(
        "'203.0.113.5:443 www.microsoft.com chrome', 203.0.113.5, 443, www.microsoft.com, chrome",
        "'  203.0.113.5:443   www.microsoft.com  ', 203.0.113.5, 443, www.microsoft.com, ",
        "'[2001:DB8::1]:8443 a.example.com', '[2001:db8::1]', 8443, a.example.com, ",
        "'proxy.example.com:443', proxy.example.com, 443, proxy.example.com, ",
        "'203.0.113.5:443 a.example.com Firefox', 203.0.113.5, 443, a.example.com, firefox",
    )
    fun `manual lines`(input: String, host: String, port: Int, sni: String, fp: String?) {
        assertEquals(VlessEntry(host, port, sni, fp), valid(input))
    }

    @ParameterizedTest
    @CsvSource(
        "'203.0.113.5 a.example.com', INVALID_PORT",
        "'203.0.113.5:443', MISSING_SNI",
        "'203.0.113.5:443 a.example.com chrome extra', MALFORMED",
        "'203.0.113.5:443 a.example.com <bad>', INVALID_FINGERPRINT",
        "'localhost:443 a.example.com', INVALID_HOST",
    )
    fun `invalid manual lines`(input: String, expected: VlessError) {
        assertEquals(expected, error(input))
    }

    @Test
    fun `manual line round trip`() {
        for (link in allSamples()) {
            val entry = valid(link)
            assertEquals(entry.copy(label = null), valid(entry.toManualLine()))
        }
    }

    @Test
    fun `form label wins over the link fragment`() {
        val result = VlessInput.fromForm(V2RAYNG_REALITY, "  Home  ") as VlessParseResult.Valid
        assertEquals("Home", result.entry.label)
    }

    @Test
    fun `blank form label keeps the link fragment`() {
        val result = VlessInput.fromForm(V2RAYNG_REALITY, " ") as VlessParseResult.Valid
        assertEquals("🇳🇱 NL-1", result.entry.label)
    }

    @Test
    fun `too long form label is rejected`() {
        val result = VlessInput.fromForm("203.0.113.5:443 a.example.com", "y".repeat(VlessInput.MAX_LABEL_LENGTH + 1))
        assertEquals(VlessParseResult.Invalid(VlessError.LABEL_TOO_LONG), result)
    }

    companion object {
        private const val UUID = "5f8a6b1e-1c2d-4e3f-9a0b-c1d2e3f4a5b6"
        private const val PBK = "Zx9fQm3k7Lp2Vb8Nw4Rt6Yh1Uj5Ik0Ol9Pq3As7Df2G"
        private const val SID = "6ba85179e30d4fc2"
        private const val SERVICE = "grpcSvcName42"
        private val SECRETS = listOf(UUID, PBK, SID, SERVICE, "xtls-rprx-vision")

        private const val V2RAYNG_REALITY =
            "vless://$UUID@203.0.113.5:443?encryption=none&flow=xtls-rprx-vision&security=reality" +
                "&sni=www.microsoft.com&fp=chrome&pbk=$PBK&sid=$SID&type=tcp&headerType=none" +
                "#%F0%9F%87%B3%F0%9F%87%B1%20NL-1"

        private const val HIDDIFY_IPV6 =
            "vless://$UUID@[2001:db8::7]:8443?type=tcp&security=reality&pbk=$PBK&fp=firefox" +
                "&sni=dl.google.com&sid=$SID&spx=%2F&flow=xtls-rprx-vision#Hiddify%20v6"

        private const val NEKOBOX_TLS_GRPC =
            "vless://$UUID@proxy.example.net:443?type=grpc&serviceName=$SERVICE&security=tls" +
                "&sni=proxy.example.net&alpn=h2#Neko%20gRPC"

        private const val HAPP_SLASH =
            "vless://$UUID@198.51.100.20:2053/?type=tcp&security=reality&pbk=$PBK&fp=safari" +
                "&sni=www.yahoo.com&sid=$SID&spx=%2F&flow=xtls-rprx-vision#Happ+RU"

        @JvmStatic
        fun allSamples() = listOf(V2RAYNG_REALITY, HIDDIFY_IPV6, NEKOBOX_TLS_GRPC, HAPP_SLASH)
    }
}
