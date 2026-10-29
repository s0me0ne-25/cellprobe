// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebViewHeadersTest {

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 14; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"

    @Test
    fun `chrome major version from the WebView user agent`() {
        assertEquals(141, WebViewHeaders.chromeMajorVersion(webViewUa))
        assertNull(WebViewHeaders.chromeMajorVersion("Dalvik/2.1.0 (Linux; U; Android 14)"))
    }

    @Test
    fun `accept-language expands base languages with decreasing quality`() {
        assertEquals("ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7", WebViewHeaders.acceptLanguage(listOf("ru-RU", "en-US")))
        assertEquals("en-GB,en;q=0.9", WebViewHeaders.acceptLanguage(listOf("en-GB")))
        assertEquals("en-US", WebViewHeaders.acceptLanguage(emptyList()))
    }

    @Test
    fun `base language listed explicitly is not duplicated`() {
        assertEquals("ru-RU,ru;q=0.9,en;q=0.8", WebViewHeaders.acceptLanguage(listOf("ru-RU", "en")))
        assertEquals("de-DE,de;q=0.9", WebViewHeaders.acceptLanguage(listOf("de-DE", "de")))
    }

    @Test
    fun `quality never drops below 0_1`() {
        val tags = (1..12).map { "x$it-AA" }
        assertFalse(WebViewHeaders.acceptLanguage(tags).contains("q=0.0"))
    }

    @Test
    fun `navigation headers carry client hints matching the user agent`() {
        val headers = WebViewHeaders.navigation(webViewUa, listOf("ru-RU")).toMap()
        assertEquals(webViewUa, headers["User-Agent"])
        assertEquals("\"Chromium\";v=\"141\", \"Android WebView\";v=\"141\", \"Not?A_Brand\";v=\"99\"", headers["sec-ch-ua"])
        assertEquals("?1", headers["sec-ch-ua-mobile"])
        assertEquals("gzip, deflate, br, zstd", headers["Accept-Encoding"])
        assertEquals("navigate", headers["Sec-Fetch-Mode"])
    }

    @Test
    fun `no client hints without a Chrome version`() {
        val headers = WebViewHeaders.navigation("SomethingElse/1.0", listOf("en-US")).toMap()
        assertNull(headers["sec-ch-ua"])
    }
}
