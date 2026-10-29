// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.s0me0ne25.cellprobe.core.journal.TrafficTotals
import org.s0me0ne25.cellprobe.data.TrafficStore
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.engine.CheckState
import org.s0me0ne25.cellprobe.ui.appContainer

class MainViewModel(
    private val controller: CheckController,
    traffic: TrafficStore,
) : ViewModel() {

    val state: StateFlow<CheckState> = controller.state

    /** Stored totals; read them for the current date. */
    val traffic: StateFlow<TrafficTotals> = traffic.totals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TrafficTotals())

    fun checkNow() = controller.start()

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MainViewModel(container.checkController, container.trafficStore)
            }
        }
    }
}
