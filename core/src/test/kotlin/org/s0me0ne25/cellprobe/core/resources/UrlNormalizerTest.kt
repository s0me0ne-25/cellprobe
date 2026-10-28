// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.IDN

class UrlNormalizerTest {

    private fun valid(input: String): String {
        val result = UrlNormalizer.normalize(input)
        check(result is UrlParseResult.Valid) { "Expected valid for '$input', got $result" }
        return result.url
    }

    private fun error(input: String): UrlError {
        val result = UrlNormalizer.normalize(input)
        check(result is UrlParseResult.Invalid) { "Expected invalid for '$input', got $result" }
        return result.error
    }

    @ParameterizedTest
    @CsvSource(
        "ya.ru, https://ya.ru",
        "https://ya.ru, https://ya.ru",
        "HTTPS://YA.RU/, https://ya.ru",
        "'  ya.ru  ', https://ya.ru",
        "ya.ru., https://ya.ru",
        "https://ya.ru:443/, https://ya.ru",
        "https://ya.ru:8443, https://ya.ru:8443",
        "lk.example.com/account?tab=1#top, https://lk.example.com/account?tab=1",
        "example.com/?a=1, https://example.com/?a=1",
        "1.2.3.4, https://1.2.3.4",
        "[2001:DB8::1]:8443/x, https://[2001:db8::1]:8443/x",
    )
    fun `normalizes valid input`(input: String, expected: String) {
        assertEquals(expected, valid(input))
    }

    @Test
    fun `converts internationalized domains to punycode`() {
        val expectedHost = IDN.toASCII("госуслуги.рф")
        assertEquals("https://$expectedHost/lk", valid("госуслуги.рф/lk"))
    }

    @Test
    fun `rejects empty input`() {
        assertEquals(UrlError.EMPTY, error("   "))
    }

    @ParameterizedTest
    @CsvSource("http://ya.ru", "ftp://ya.ru", "vless://uuid@1.2.3.4:443")
    fun `rejects schemes other than https`(input: String) {
        assertEquals(UrlError.NOT_HTTPS, error(input))
    }

    @Test
    fun `rejects credentials in the address`() {
        assertEquals(UrlError.CREDENTIALS_NOT_ALLOWED, error("https://user:pass@ya.ru"))
    }

    @ParameterizedTest
    @CsvSource("localhost", "a..b", "https://", "999.1.1.1", "1.2.3", "2001:db8::1", "[2001:db8::1", "-bad-.ru")
    fun `rejects invalid hosts`(input: String) {
        assertEquals(UrlError.INVALID_HOST, error(input))
    }

    @ParameterizedTest
    @CsvSource("ya.ru:0", "ya.ru:65536", "ya.ru:abc")
    fun `rejects invalid ports`(input: String) {
        assertEquals(UrlError.INVALID_PORT, error(input))
    }

    @Test
    fun `rejects malformed paths`() {
        assertEquals(UrlError.MALFORMED, error("ya.ru/with space"))
    }

    @Test
    fun `editable form shows unicode host and normalizes back`() {
        val canonical = valid("госуслуги.рф:8443/lk?x=1")
        val editable = UrlNormalizer.editableForm(canonical)
        assertEquals("https://госуслуги.рф:8443/lk?x=1", editable)
        assertEquals(canonical, valid(editable))
        assertEquals("https://[2001:db8::1]", UrlNormalizer.editableForm("https://[2001:db8::1]"))
    }

    @Test
    fun `display name shows unicode host, port and path`() {
        assertEquals("ya.ru", UrlNormalizer.displayName("https://ya.ru"))
        assertEquals("госуслуги.рф/lk", UrlNormalizer.displayName(valid("госуслуги.рф/lk?x=1")))
        assertEquals("example.com:8443", UrlNormalizer.displayName("https://example.com:8443"))
        assertEquals("[2001:db8::1]", UrlNormalizer.displayName("https://[2001:db8::1]"))
    }
}
