// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProviderPageLabelsTest {

    @Test
    fun `scheme, path and query are dropped, only www is removed`() {
        assertEquals(
            listOf("lk.megafon.ru", "mts.ru", "my.wifire.ru"),
            ProviderPageLabels.labels(
                listOf("https://lk.megafon.ru/", "https://www.mts.ru/personal?x=1", "https://my.wifire.ru/login#top"),
            ),
        )
    }

    @Test
    fun `pages on the same host get their first path segment`() {
        assertEquals(
            listOf("example.ru/mobile", "example.ru/home", "other.ru"),
            ProviderPageLabels.labels(
                listOf("https://example.ru/mobile/login", "https://www.example.ru/home", "https://other.ru/cabinet"),
            ),
        )
    }

    @Test
    fun `international domains are shown in Unicode, non-default ports are kept`() {
        assertEquals(
            listOf("личныйкабинет.рф", "lk.example.ru:8443"),
            ProviderPageLabels.labels(listOf("https://xn--80acmjcdhftf0dvczc.xn--p1ai/", "https://lk.example.ru:8443/")),
        )
    }
}
