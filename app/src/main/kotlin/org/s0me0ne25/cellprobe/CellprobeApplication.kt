// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe

import android.app.Application

class CellprobeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
