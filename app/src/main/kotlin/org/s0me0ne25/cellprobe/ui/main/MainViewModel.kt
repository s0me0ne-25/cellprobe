// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.core.resources.ResourceListId
import org.s0me0ne25.cellprobe.data.SettingsRepository
import org.s0me0ne25.cellprobe.data.TrafficStore
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.engine.CheckState
import org.s0me0ne25.cellprobe.system.InstalledApps
import org.s0me0ne25.cellprobe.ui.appContainer

/** The app assigned to a role; [label] is null if it is no longer installed. */
data class RoleApp(val packageName: String, val label: String?)

class MainViewModel(
    private val controller: CheckController,
    traffic: TrafficStore,
    settings: SettingsRepository,
    private val installedApps: InstalledApps,
) : ViewModel() {

    val state: StateFlow<CheckState> = controller.state

    /** Stored totals; read them for the current date. */
    val traffic: StateFlow<TrafficTotals> = traffic.totals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TrafficTotals())

    /** Bumped when the screen comes back, to notice apps installed or removed meanwhile. */
    private val appsChanged = MutableStateFlow(0)

    val roles: StateFlow<Map<AppRole, RoleApp>> = combine(settings.appRoles, appsChanged) { roles, _ ->
        roles.mapValues { (_, packageName) -> RoleApp(packageName, installedApps.label(packageName)) }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    /** URLs of the provider account pages, in list order. */
    val providerPages: StateFlow<List<String>> = settings.resourceList(ResourceListId.PROVIDER)
        .map { list -> list.entries.map { it.url } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun checkNow() = controller.start()

    fun refreshApps() {
        appsChanged.value++
    }

    /** Intent that launches the app of [role], or null if it cannot be launched. */
    fun launchIntent(role: AppRole) = roles.value[role]?.let { installedApps.launchIntent(it.packageName) }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MainViewModel(
                    container.checkController,
                    container.trafficStore,
                    container.settingsRepository,
                    container.installedApps,
                )
            }
        }
    }
}
