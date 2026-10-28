// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.vless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.resources.ListEditError

class VlessListEditingTest {

    private val a = VlessEntry("203.0.113.1", 443, "a.example.com", "chrome", "A")
    private val b = VlessEntry("203.0.113.2", 443, "b.example.com")

    private fun VlessEditResult.entries(): List<VlessEntry> {
        check(this is VlessEditResult.Success) { "Expected success, got $this" }
        return entries
    }

    private fun VlessEditResult.error(): ListEditError {
        check(this is VlessEditResult.Failure) { "Expected failure, got $this" }
        return error
    }

    @Test
    fun `add appends`() {
        assertEquals(listOf(a, b), VlessListEditing.add(listOf(a), b).entries())
    }

    @Test
    fun `same server with another label is a duplicate`() {
        assertEquals(ListEditError.DUPLICATE, VlessListEditing.add(listOf(a), a.copy(label = "other", fingerprint = null)).error())
    }

    @Test
    fun `replace keeps position and allows relabeling`() {
        val relabeled = a.copy(label = "New")
        assertEquals(listOf(relabeled, b), VlessListEditing.replace(listOf(a, b), a.key, relabeled).entries())
    }

    @Test
    fun `replace onto another server is a duplicate`() {
        assertEquals(ListEditError.DUPLICATE, VlessListEditing.replace(listOf(a, b), a.key, b).error())
    }

    @Test
    fun `remove may empty the list`() {
        assertEquals(emptyList<VlessEntry>(), VlessListEditing.remove(listOf(a), a.key).entries())
    }

    @Test
    fun `missing entries are reported`() {
        assertEquals(ListEditError.NOT_FOUND, VlessListEditing.remove(listOf(a), b.key).error())
        assertEquals(ListEditError.NOT_FOUND, VlessListEditing.replace(listOf(a), b.key, b).error())
    }
}
