// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow
import org.s0me0ne25.cellprobe.engine.CheckController
import org.s0me0ne25.cellprobe.engine.CheckState
import org.s0me0ne25.cellprobe.ui.appContainer

class MainViewModel(private val controller: CheckController) : ViewModel() {

    val state: StateFlow<CheckState> = controller.state

    fun checkNow() = controller.start()

    companion object {
        val Factory = viewModelFactory {
            initializer { MainViewModel(appContainer().checkController) }
        }
    }
}
