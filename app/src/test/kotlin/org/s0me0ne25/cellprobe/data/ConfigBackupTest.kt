// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.certs.RussianStateCa
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringSettings
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.StoredResourceList
import org.s0me0ne25.cellprobe.core.vless.VlessEntry

class ConfigBackupTest {

    private fun certificates(vararg names: String) = names.flatMap { name ->
        RussianStateCa.parse(checkNotNull(javaClass.getResourceAsStream("/certs/$name.pem")).readBytes())
    }

    private val settings = AppSettings(
        resourceLists = mapOf(
            ResourceListId.PROVIDER to StoredResourceList(
                customized = true,
                entries = listOf(ResourceEntry("https://lk.example.ru", allowRuCa = true)),
            ),
            ResourceListId.DOMESTIC to StoredResourceList(ruCaHosts = setOf("ya.ru")),
        ),
        vlessEntries = listOf(VlessEntry(host = "203.0.113.5", port = 443, sni = "www.example.com", label = "Дом")),
        appRoles = mapOf(AppRole.VPN_CLIENT to "com.v2ray.ang"),
        monitoring = MonitoringSettings(enabled = true, screenOnIntervalSeconds = 30, trackSignalLoss = true),
        detailedJournal = true,
    )

    private val full = ConfigSnapshot(
        settings = settings,
        traffic = TrafficTotals(),
        russianStateCa = certificates("russian_trusted_root_ca", "russian_trusted_sub_ca"),
    )

    private fun decode(text: String) = ConfigBackup.decode(text)

    private fun snapshot(text: String) = assertInstanceOf(ConfigDecodeResult.Success::class.java, decode(text)).snapshot

    private fun error(text: String) = assertInstanceOf(ConfigDecodeResult.Failure::class.java, decode(text)).error

    @Test
    fun `everything survives the round trip`() {
        val text = ConfigBackup.encode(full, appVersion = "0.0.1", exportedAt = "2026-11-06T10:00:00Z")
        val back = snapshot(text)

        assertEquals(full.settings, back.settings)
        assertEquals(full.traffic, back.traffic)
        assertEquals(full.russianStateCa?.map { it.encoded.toList() }, back.russianStateCa?.map { it.encoded.toList() })
    }

    @Test
    fun `lists without the Russian state CA are written without its fields`() {
        val withFlags = settings.copy(
            resourceLists = settings.resourceLists + mapOf(
                ResourceListId.FOREIGN to StoredResourceList(ruCaHosts = setOf("google.com")),
                ResourceListId.BANNED to StoredResourceList(
                    customized = true,
                    entries = listOf(ResourceEntry("https://youtube.com", allowRuCa = true)),
                ),
            ),
        )
        val text = ConfigBackup.encode(ConfigSnapshot(settings = withFlags), appVersion = "0.0.1", exportedAt = "now")
        val lists = (Json.parseToJsonElement(text) as JsonObject)["settings"]!!.jsonObject["resourceLists"]!!.jsonObject

        assertEquals(listOf("customized", "entries"), lists["foreign"]!!.jsonObject.keys.toList())
        val banned = lists["banned"]!!.jsonObject
        assertEquals(listOf("customized", "entries"), banned.keys.toList())
        assertEquals(listOf("url", "marker"), banned["entries"]!!.jsonArray.single().jsonObject.keys.toList())
        // Lists 1 and provider pages keep them.
        assertEquals("true", lists["provider"]!!.jsonObject["entries"]!!.jsonArray.single().jsonObject["allowRuCa"].toString())
        assertEquals("[\"ya.ru\"]", lists["domestic"]!!.jsonObject["ruCaHosts"].toString())
    }

    @Test
    fun `a file cannot turn the Russian state CA on where it is not allowed`() {
        val text = """
            {"format": "cellprobe-config", "version": 1, "settings": {"resourceLists": {
                "foreign": {"ruCaHosts": ["google.com"]},
                "banned": {"customized": true, "entries": [{"url": "https://youtube.com", "allowRuCa": true}]},
                "provider": {"customized": true, "entries": [{"url": "https://lk.example.ru", "allowRuCa": true}]}
            }}}
        """.trimIndent()
        val lists = snapshot(text).settings!!.resourceLists

        assertEquals(StoredResourceList(), lists[ResourceListId.FOREIGN])
        assertEquals(listOf(ResourceEntry("https://youtube.com")), lists[ResourceListId.BANNED]?.entries)
        assertTrue(lists[ResourceListId.PROVIDER]!!.entries.single().allowRuCa)
    }

    @Test
    fun `the text is one JSON object, the header first`() {
        val text = ConfigBackup.encode(full, appVersion = "0.0.1", exportedAt = "2026-11-06T10:00:00Z")
        val root = Json.parseToJsonElement(text) as JsonObject
        assertEquals(
            listOf("format", "version", "appVersion", "exportedAt", "settings", "traffic", "russianStateCa"),
            root.keys.toList(),
        )
    }

    @Test
    fun `sections not in the file are left alone`() {
        val text = ConfigBackup.encode(ConfigSnapshot(settings = settings), appVersion = "0.0.1", exportedAt = "now")
        val back = snapshot(text)
        assertEquals(settings, back.settings)
        assertNull(back.traffic)
        assertNull(back.russianStateCa)
    }

    @Test
    fun `no certificates installed is an empty list, removing them on import`() {
        val text = ConfigBackup.encode(ConfigSnapshot(russianStateCa = emptyList()), appVersion = "0.0.1", exportedAt = "now")
        assertEquals(emptyList<Any>(), snapshot(text).russianStateCa)
        assertTrue(text.contains("\"russianStateCa\": ["))
    }

    @Test
    fun `other texts are not configurations`() {
        assertEquals(ConfigError.NotConfig, error("hello"))
        assertEquals(ConfigError.NotConfig, error("[1, 2]"))
        assertEquals(ConfigError.NotConfig, error("""{"format": "something-else", "version": 1}"""))
        assertEquals(ConfigError.NotConfig, error("""{"format": "cellprobe-config"}"""))
    }

    @Test
    fun `a newer format is refused`() {
        assertEquals(ConfigError.NewerFormat(2), error("""{"format": "cellprobe-config", "version": 2}"""))
    }

    @Test
    fun `a damaged section is named and nothing is taken`() {
        assertEquals(
            ConfigError.BadSection("settings"),
            error("""{"format": "cellprobe-config", "version": 1, "settings": {"monitoring": "loud"}}"""),
        )
        assertEquals(
            ConfigError.BadSection("traffic"),
            error("""{"format": "cellprobe-config", "version": 1, "traffic": {"dayBytes": "many"}}"""),
        )
        assertEquals(
            ConfigError.BadSection("russianStateCa"),
            error("""{"format": "cellprobe-config", "version": 1, "russianStateCa": ["not base64!"]}"""),
        )
    }

    @Test
    fun `unknown keys from later versions are ignored`() {
        val text = """{"format": "cellprobe-config", "version": 1, "theme": "DARK", "settings": {"detailedJournal": true, "future": 1}}"""
        assertEquals(true, snapshot(text).settings?.detailedJournal)
    }

    @Test
    fun `a root with another fingerprint is refused`() {
        val fake = ConfigBackup.encode(ConfigSnapshot(russianStateCa = certificates("fake_root")), appVersion = "0.0.1", exportedAt = "now")
        assertEquals(ConfigError.WrongRussianStateCa, error(fake))
    }

    @Test
    fun `the file name carries the date and the hash of the text`() {
        val name = ConfigBackup.fileName("{}", date = "2026-11-06")
        // SHA-256 of "{}" starts with 44136fa3.
        assertEquals("cellprobe-config-2026-11-06-44136fa3.json", name)
    }
}
