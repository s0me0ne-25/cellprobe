// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.monitoring.MonitoringSettings
import org.s0me0ne25.cellprobe.core.resources.ListEditError
import org.s0me0ne25.cellprobe.core.resources.ListEditResult
import org.s0me0ne25.cellprobe.core.resources.ResourceEntry
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.core.resources.ResourceListState
import org.s0me0ne25.cellprobe.core.resources.effectiveState
import org.s0me0ne25.cellprobe.core.vless.VlessEditResult
import org.s0me0ne25.cellprobe.core.vless.VlessEntry

class SettingsRepository(private val dataStore: DataStore<AppSettings>) {

    /** Effective state of every HTTPS resource list, in [ResourceListId] order. */
    val resourceLists: Flow<List<ResourceListState>> = dataStore.data
        .map { settings -> ResourceListId.entries.map { settings.resourceLists[it].effectiveState(it) } }
        .distinctUntilChanged()

    fun resourceList(id: ResourceListId): Flow<ResourceListState> = dataStore.data
        .map { it.resourceLists[id].effectiveState(id) }
        .distinctUntilChanged()

    val vlessEntries: Flow<List<VlessEntry>> = dataStore.data
        .map { it.vlessEntries }
        .distinctUntilChanged()

    val monitoring: Flow<MonitoringSettings> = dataStore.data
        .map { it.monitoring }
        .distinctUntilChanged()

    suspend fun updateMonitoring(transform: (MonitoringSettings) -> MonitoringSettings) {
        dataStore.updateData { it.copy(monitoring = transform(it.monitoring)) }
    }

    /** Package name of the app assigned to each role. */
    val appRoles: Flow<Map<AppRole, String>> = dataStore.data
        .map { it.appRoles }
        .distinctUntilChanged()

    /** Assigns [packageName] to [role], or clears the role if it is null. */
    suspend fun setAppRole(role: AppRole, packageName: String?) {
        dataStore.updateData { settings ->
            val roles = if (packageName == null) settings.appRoles - role else settings.appRoles + (role to packageName)
            settings.copy(appRoles = roles)
        }
    }

    /**
     * Applies [edit] to the VLESS list atomically.
     * Returns the edit error, or null on success.
     */
    suspend fun editVlessEntries(edit: (List<VlessEntry>) -> VlessEditResult): ListEditError? {
        var error: ListEditError? = null
        dataStore.updateData { settings ->
            when (val result = edit(settings.vlessEntries)) {
                is VlessEditResult.Success -> settings.copy(vlessEntries = result.entries)
                is VlessEditResult.Failure -> {
                    error = result.error
                    settings
                }
            }
        }
        return error
    }

    /**
     * Applies [edit] to the current effective entries of list [id] atomically.
     * Returns the edit error, or null on success.
     */
    suspend fun editResourceList(
        id: ResourceListId,
        edit: (List<ResourceEntry>) -> ListEditResult,
    ): ListEditError? {
        var error: ListEditError? = null
        dataStore.updateData { settings ->
            val current = settings.resourceLists[id].effectiveState(id).entries
            when (val result = edit(current)) {
                is ListEditResult.Success ->
                    settings.copy(resourceLists = settings.resourceLists + (id to result.stored))
                is ListEditResult.Failure -> {
                    error = result.error
                    settings
                }
            }
        }
        return error
    }
}
