// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.resources

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HTTPS resource lists. The VLESS list is modelled separately.
 * Serial names are part of the stored settings format: never change them.
 */
@Serializable
enum class ResourceListId {
    /** List 1: never blocked, checks that there is a connection at all. */
    @SerialName("domestic")
    DOMESTIC,

    /** List 2: blocked only in whitelist mode. */
    @SerialName("foreign")
    FOREIGN,

    /** List 4: known to be blocked. */
    @SerialName("banned")
    BANNED,

    /** Provider account pages; checked when nothing else is reachable. */
    @SerialName("provider")
    PROVIDER;

    /** Built-in entries, or null for lists without a preset. */
    val preset: List<ResourceEntry>?
        get() = Presets.forList(this)

    /** Lists with a preset must never be saved empty. */
    val mayBeEmpty: Boolean
        get() = preset == null
}

object Presets {

    private val domestic = entries(
        "https://ya.ru",
        "https://mail.ru",
        "https://vk.com",
        "https://max.ru",
        "https://avito.ru",
    )

    private val foreign = entries(
        "https://google.com",
        "https://apple.com",
        "https://microsoft.com",
    )

    private val banned = entries(
        "https://youtube.com",
        "https://meduza.io",
        "https://instagram.com",
    )

    fun forList(id: ResourceListId): List<ResourceEntry>? = when (id) {
        ResourceListId.DOMESTIC -> domestic
        ResourceListId.FOREIGN -> foreign
        ResourceListId.BANNED -> banned
        ResourceListId.PROVIDER -> null
    }

    private fun entries(vararg urls: String) = urls.map { ResourceEntry(url = it) }
}

/**
 * Persisted state of one list.
 *
 * While [customized] is false the list follows the preset of the running
 * app version and [entries] is not used, so preset updates reach users who
 * never edited the list.
 */
@Serializable
data class StoredResourceList(
    val customized: Boolean = false,
    val entries: List<ResourceEntry> = emptyList(),
)

/** What the UI and the probes see for one list. */
data class ResourceListState(
    val id: ResourceListId,
    val entries: List<ResourceEntry>,
    /** False while a list with a preset still follows that preset. */
    val customized: Boolean,
)

fun StoredResourceList?.effectiveState(id: ResourceListId): ResourceListState {
    val preset = id.preset
    return if (preset != null && (this == null || !customized)) {
        ResourceListState(id, preset, customized = false)
    } else {
        ResourceListState(id, this?.entries.orEmpty(), customized = preset != null)
    }
}

enum class ListEditError {
    DUPLICATE,
    WOULD_BE_EMPTY,
    NOT_FOUND,
    NO_PRESET,
}

sealed interface ListEditResult {
    data class Success(val stored: StoredResourceList) : ListEditResult
    data class Failure(val error: ListEditError) : ListEditResult
}

/**
 * Pure editing operations. Each takes the currently effective entries and
 * returns the new persisted state. Entries are identified by their URL.
 */
object ResourceListEditing {

    fun add(id: ResourceListId, current: List<ResourceEntry>, entry: ResourceEntry): ListEditResult {
        if (current.any { it.url == entry.url }) return ListEditResult.Failure(ListEditError.DUPLICATE)
        return store(id, current + entry)
    }

    fun replace(
        id: ResourceListId,
        current: List<ResourceEntry>,
        oldUrl: String,
        entry: ResourceEntry,
    ): ListEditResult {
        val index = current.indexOfFirst { it.url == oldUrl }
        if (index < 0) return ListEditResult.Failure(ListEditError.NOT_FOUND)
        if (current.anyIndexed { i, e -> i != index && e.url == entry.url }) {
            return ListEditResult.Failure(ListEditError.DUPLICATE)
        }
        return store(id, current.toMutableList().apply { set(index, entry) })
    }

    fun remove(id: ResourceListId, current: List<ResourceEntry>, url: String): ListEditResult {
        if (current.none { it.url == url }) return ListEditResult.Failure(ListEditError.NOT_FOUND)
        val remaining = current.filterNot { it.url == url }
        if (remaining.isEmpty() && !id.mayBeEmpty) {
            return ListEditResult.Failure(ListEditError.WOULD_BE_EMPTY)
        }
        return store(id, remaining)
    }

    fun resetToPreset(id: ResourceListId): ListEditResult {
        if (id.preset == null) return ListEditResult.Failure(ListEditError.NO_PRESET)
        return ListEditResult.Success(StoredResourceList(customized = false))
    }

    /** A list edited back to exactly its preset returns to following the preset. */
    private fun store(id: ResourceListId, entries: List<ResourceEntry>): ListEditResult {
        val preset = id.preset
        val stored = if (preset != null && entries == preset) {
            StoredResourceList(customized = false)
        } else {
            StoredResourceList(customized = true, entries = entries)
        }
        return ListEditResult.Success(stored)
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        forEachIndexed { i, e -> if (predicate(i, e)) return true }
        return false
    }
}
