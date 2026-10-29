// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageTextTest {

    @Test
    fun `title is extracted, unescaped and squeezed`() {
        val html = "<html><head>\n<TITLE data-x=\"1\">\n  Яндекс &amp; Ко\n</TITLE></head>"
        assertEquals("Яндекс & Ко", PageText.extractTitle(html))
    }

    @Test
    fun `missing or empty title`() {
        assertNull(PageText.extractTitle("<html><body>no title</body></html>"))
        assertNull(PageText.extractTitle("<title>   </title>"))
    }

    @Test
    fun `long titles are shortened`() {
        val title = PageText.extractTitle("<title>${"a".repeat(500)}</title>")!!
        assertTrue(title.length <= 121)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `charset from content type`() {
        val bytes = "<title>Привет</title>".toByteArray(charset("windows-1251"))
        assertEquals("Привет", PageText.extractTitle(PageText.decode(bytes, "text/html; charset=windows-1251")))
    }

    @Test
    fun `charset from meta tag when the header has none`() {
        val bytes = "<meta charset=\"windows-1251\"><title>Привет</title>".toByteArray(charset("windows-1251"))
        assertEquals("Привет", PageText.extractTitle(PageText.decode(bytes, "text/html")))
    }

    @Test
    fun `unknown charset falls back to UTF-8`() {
        assertEquals("ok", PageText.decode("ok".toByteArray(), "text/html; charset=no-such-charset"))
    }

    @Test
    fun `markers match case-insensitively`() {
        assertTrue(PageText.containsMarker("<title>Yandex</title>", "yandex"))
        assertFalse(PageText.containsMarker("<title>Google</title>", "yandex"))
    }
}
