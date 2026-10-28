// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EntryInputTest {

    @Test
    fun `builds an entry with a trimmed marker`() {
        val result = EntryInput.parse(" ya.ru ", "  Яндекс  ")
        assertEquals(EntryInputResult.Valid(ResourceEntry("https://ya.ru", "Яндекс")), result)
    }

    @Test
    fun `blank marker becomes null`() {
        val result = EntryInput.parse("ya.ru", "   ")
        assertEquals(EntryInputResult.Valid(ResourceEntry("https://ya.ru")), result)
    }

    @Test
    fun `reports url errors`() {
        val result = EntryInput.parse("http://ya.ru", "")
        assertEquals(EntryInputResult.Invalid(EntryInputError.InvalidUrl(UrlError.NOT_HTTPS)), result)
    }

    @Test
    fun `rejects too long markers`() {
        val result = EntryInput.parse("ya.ru", "x".repeat(EntryInput.MAX_MARKER_LENGTH + 1))
        assertEquals(EntryInputResult.Invalid(EntryInputError.MarkerTooLong), result)
    }

    @Test
    fun `new entries never trust the Russian CA`() {
        val result = EntryInput.parse("ya.ru", "") as EntryInputResult.Valid
        assertEquals(false, result.entry.allowRuCa)
    }
}
