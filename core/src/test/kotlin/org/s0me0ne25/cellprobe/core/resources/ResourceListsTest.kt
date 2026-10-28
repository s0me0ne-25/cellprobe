// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.resources

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResourceListsTest {

    private val domestic = ResourceListId.DOMESTIC
    private val domesticPreset = domestic.preset!!

    private fun ListEditResult.stored(): StoredResourceList {
        check(this is ListEditResult.Success) { "Expected success, got $this" }
        return stored
    }

    private fun ListEditResult.error(): ListEditError {
        check(this is ListEditResult.Failure) { "Expected failure, got $this" }
        return error
    }

    @Test
    fun `presets are canonical and unique`() {
        for (id in ResourceListId.entries) {
            val preset = id.preset ?: continue
            assertTrue(preset.isNotEmpty(), "$id preset is empty")
            assertEquals(preset.size, preset.map { it.url }.toSet().size, "$id preset has duplicates")
            for (entry in preset) {
                assertEquals(UrlParseResult.Valid(entry.url), UrlNormalizer.normalize(entry.url))
            }
        }
    }

    @Test
    fun `only the provider list has no preset`() {
        assertNull(ResourceListId.PROVIDER.preset)
        assertTrue(ResourceListId.PROVIDER.mayBeEmpty)
        assertFalse(ResourceListId.DOMESTIC.mayBeEmpty)
        assertFalse(ResourceListId.FOREIGN.mayBeEmpty)
        assertFalse(ResourceListId.BANNED.mayBeEmpty)
    }

    @Test
    fun `never stored list follows the preset`() {
        val state = (null as StoredResourceList?).effectiveState(domestic)
        assertEquals(domesticPreset, state.entries)
        assertFalse(state.customized)
    }

    @Test
    fun `non-customized list follows the current preset even if entries were stored`() {
        val stale = StoredResourceList(customized = false, entries = listOf(ResourceEntry("https://old.example")))
        assertEquals(domesticPreset, stale.effectiveState(domestic).entries)
    }

    @Test
    fun `customized list keeps its own entries`() {
        val own = listOf(ResourceEntry("https://own.example"))
        val state = StoredResourceList(customized = true, entries = own).effectiveState(domestic)
        assertEquals(own, state.entries)
        assertTrue(state.customized)
    }

    @Test
    fun `adding marks the list customized`() {
        val added = ResourceEntry("https://new.example")
        val stored = ResourceListEditing.add(domestic, domesticPreset, added).stored()
        assertTrue(stored.customized)
        assertEquals(domesticPreset + added, stored.entries)
    }

    @Test
    fun `adding a duplicate fails`() {
        val error = ResourceListEditing.add(domestic, domesticPreset, domesticPreset[0]).error()
        assertEquals(ListEditError.DUPLICATE, error)
    }

    @Test
    fun `replacing keeps the position`() {
        val replacement = ResourceEntry("https://new.example", "marker")
        val stored = ResourceListEditing.replace(domestic, domesticPreset, domesticPreset[1].url, replacement).stored()
        assertEquals(replacement, stored.entries[1])
        assertEquals(domesticPreset.size, stored.entries.size)
    }

    @Test
    fun `replacing with another entry's url fails`() {
        val error = ResourceListEditing
            .replace(domestic, domesticPreset, domesticPreset[0].url, domesticPreset[1])
            .error()
        assertEquals(ListEditError.DUPLICATE, error)
    }

    @Test
    fun `replacing an entry with itself is allowed`() {
        val withMarker = domesticPreset[0].copy(marker = "yandex")
        val stored = ResourceListEditing.replace(domestic, domesticPreset, withMarker.url, withMarker).stored()
        assertEquals(withMarker, stored.entries[0])
    }

    @Test
    fun `replacing a missing entry fails`() {
        val error = ResourceListEditing
            .replace(domestic, domesticPreset, "https://missing.example", ResourceEntry("https://x.example"))
            .error()
        assertEquals(ListEditError.NOT_FOUND, error)
    }

    @Test
    fun `removing the last entry of a list with a preset fails`() {
        val single = listOf(ResourceEntry("https://only.example"))
        val error = ResourceListEditing.remove(domestic, single, single[0].url).error()
        assertEquals(ListEditError.WOULD_BE_EMPTY, error)
    }

    @Test
    fun `removing the last provider entry is allowed`() {
        val single = listOf(ResourceEntry("https://lk.example"))
        val stored = ResourceListEditing.remove(ResourceListId.PROVIDER, single, single[0].url).stored()
        assertEquals(emptyList<ResourceEntry>(), stored.entries)
    }

    @Test
    fun `removing a missing entry fails`() {
        val error = ResourceListEditing.remove(domestic, domesticPreset, "https://missing.example").error()
        assertEquals(ListEditError.NOT_FOUND, error)
    }

    @Test
    fun `editing back to the preset returns to following the preset`() {
        val added = ResourceEntry("https://new.example")
        val edited = ResourceListEditing.add(domestic, domesticPreset, added).stored()
        val reverted = ResourceListEditing
            .remove(domestic, edited.effectiveState(domestic).entries, added.url)
            .stored()
        assertEquals(StoredResourceList(customized = false), reverted)
    }

    @Test
    fun `reset to preset`() {
        assertEquals(StoredResourceList(customized = false), ResourceListEditing.resetToPreset(domestic).stored())
        assertEquals(ListEditError.NO_PRESET, ResourceListEditing.resetToPreset(ResourceListId.PROVIDER).error())
    }

    @Test
    fun `stored format uses stable names`() {
        val json = Json { encodeDefaults = true }
        val original = mapOf(
            ResourceListId.PROVIDER to StoredResourceList(
                customized = true,
                entries = listOf(ResourceEntry("https://lk.example", "Баланс")),
            ),
        )
        val text = json.encodeToString(original)
        assertEquals(
            """{"provider":{"customized":true,"entries":[{"url":"https://lk.example","marker":"Баланс","allowRuCa":false}]}}""",
            text,
        )
        assertEquals(original, json.decodeFromString<Map<ResourceListId, StoredResourceList>>(text))
    }
}
