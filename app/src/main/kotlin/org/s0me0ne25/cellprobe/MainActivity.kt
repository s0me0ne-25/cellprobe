// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.engine.MonitoringService
import org.s0me0ne25.cellprobe.ui.CellprobeRoot
import org.s0me0ne25.cellprobe.ui.theme.CellprobeTheme

class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        // Brings monitoring back if its process was killed: a foreground
        // service may be started from here, while the activity is visible.
        val container = (application as CellprobeApplication).container
        lifecycleScope.launch {
            if (container.settingsRepository.monitoring.first().enabled) MonitoringService.sync(this@MainActivity, enabled = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CellprobeTheme {
                CellprobeRoot()
            }
        }
    }
}
