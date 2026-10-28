// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.s0me0ne25.cellprobe.ui.CellprobeRoot
import org.s0me0ne25.cellprobe.ui.theme.CellprobeTheme

class MainActivity : ComponentActivity() {

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
