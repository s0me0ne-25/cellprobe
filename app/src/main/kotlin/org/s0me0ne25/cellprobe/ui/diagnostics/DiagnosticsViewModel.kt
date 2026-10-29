// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.engine.CheckState
import org.s0me0ne25.cellprobe.ui.appContainer

/** Per-resource view of the check shared with the main screen. */
class DiagnosticsViewModel(private val controller: CheckController) : ViewModel() {

    val state: StateFlow<CheckState> = controller.state

    fun start() = controller.start()

    companion object {
        val Factory = viewModelFactory {
            initializer { DiagnosticsViewModel(appContainer().checkController) }
        }
    }
}
