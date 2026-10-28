// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.core.vless

import org.s0me0ne25.cellprobe.core.resources.ListEditError

sealed interface VlessEditResult {
    data class Success(val entries: List<VlessEntry>) : VlessEditResult
    data class Failure(val error: ListEditError) : VlessEditResult
}

/**
 * Pure editing operations on the VLESS list. Entries are identified by
 * [VlessEntry.key]; the list may be empty.
 */
object VlessListEditing {

    fun add(current: List<VlessEntry>, entry: VlessEntry): VlessEditResult {
        if (current.any { it.key == entry.key }) return VlessEditResult.Failure(ListEditError.DUPLICATE)
        return VlessEditResult.Success(current + entry)
    }

    fun replace(current: List<VlessEntry>, oldKey: String, entry: VlessEntry): VlessEditResult {
        val index = current.indexOfFirst { it.key == oldKey }
        if (index < 0) return VlessEditResult.Failure(ListEditError.NOT_FOUND)
        val clash = current.withIndex().any { (i, e) -> i != index && e.key == entry.key }
        if (clash) return VlessEditResult.Failure(ListEditError.DUPLICATE)
        return VlessEditResult.Success(current.toMutableList().apply { set(index, entry) })
    }

    fun remove(current: List<VlessEntry>, key: String): VlessEditResult {
        if (current.none { it.key == key }) return VlessEditResult.Failure(ListEditError.NOT_FOUND)
        return VlessEditResult.Success(current.filterNot { it.key == key })
    }
}
