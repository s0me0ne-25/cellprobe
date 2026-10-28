// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.StoredResourceList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppSettingsSerializerTest {

    private fun write(settings: AppSettings): String = runBlocking {
        val output = ByteArrayOutputStream()
        AppSettingsSerializer.writeTo(settings, output)
        output.toString(Charsets.UTF_8.name())
    }

    private fun read(text: String): AppSettings = runBlocking {
        AppSettingsSerializer.readFrom(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `round trip keeps all lists`() {
        val settings = AppSettings(
            resourceLists = mapOf(
                ResourceListId.DOMESTIC to StoredResourceList(customized = false),
                ResourceListId.PROVIDER to StoredResourceList(
                    customized = true,
                    entries = listOf(ResourceEntry("https://lk.example", marker = "Баланс")),
                ),
            ),
        )
        assertEquals(settings, read(write(settings)))
    }

    @Test
    fun `empty file content is corruption, not a crash`() {
        assertThrows<CorruptionException> { read("") }
    }

    @Test
    fun `garbage is corruption`() {
        assertThrows<CorruptionException> { read("{not json") }
    }

    @Test
    fun `unknown fields from newer versions are ignored`() {
        val settings = read("""{"resourceLists":{},"someFutureField":42}""")
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `missing fields take defaults`() {
        assertEquals(AppSettings(), read("{}"))
    }
}
