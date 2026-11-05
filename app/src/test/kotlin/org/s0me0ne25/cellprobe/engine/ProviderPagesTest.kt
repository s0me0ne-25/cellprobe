// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.probe.CheckListId
import org.s0me0ne25.cellprobe.core.probe.ProbeCode
import org.s0me0ne25.cellprobe.core.probe.ProbeOutcome

class ProviderPagesTest {

    private val urls = listOf("https://lk.example.ru/", "https://www.pay.example.com/cabinet")

    private fun section(vararg codes: ProbeCode?) = ListSection(
        id = CheckListId.PROVIDER,
        rows = codes.mapIndexed { index, code -> EntryRow("page $index", code?.let { ProbeOutcome(it, 100) }) },
        round = 1,
        available = null,
    )

    @Test
    fun `pages are labelled and marked by the last check`() {
        val pages = ProviderPages.of(urls, listOf(section(ProbeCode.TCP_TIMEOUT, ProbeCode.OK)))
        assertEquals(
            listOf(
                ProviderPage(urls[0], "lk.example.ru", responded = false),
                ProviderPage(urls[1], "pay.example.com", responded = true),
            ),
            pages,
        )
    }

    @Test
    fun `nothing is marked without a check or after the list changed`() {
        assertEquals(listOf(false, false), ProviderPages.of(urls, emptyList()).map { it.responded })
        assertEquals(listOf(false, false), ProviderPages.of(urls, listOf(section(ProbeCode.OK))).map { it.responded })
    }

    @Test
    fun `a page still being probed is not marked`() {
        assertEquals(listOf(true, false), ProviderPages.of(urls, listOf(section(ProbeCode.OK, null))).map { it.responded })
    }
}
