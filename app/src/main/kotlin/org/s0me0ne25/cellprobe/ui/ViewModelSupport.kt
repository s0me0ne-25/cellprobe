// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import org.s0me0ne25.cellprobe.AppContainer
import org.s0me0ne25.cellprobe.CellprobeApplication

/** Access to the dependency container from ViewModel factories. */
fun CreationExtras.appContainer(): AppContainer =
    (checkNotNull(this[APPLICATION_KEY]) as CellprobeApplication).container
