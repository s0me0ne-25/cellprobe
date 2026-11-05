// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.core.actions.AppRole
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

    /** A role whose app the user is to choose, asked for by a notification. */
    private val pickRoleRequest = MutableStateFlow<AppRole?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity has handled its intent already.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val pickRole by pickRoleRequest.collectAsState()
            CellprobeTheme {
                CellprobeRoot(pickRole = pickRole, onPickRoleShown = { pickRoleRequest.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val role = intent.getStringExtra(EXTRA_PICK_ROLE) ?: return
        pickRoleRequest.value = AppRole.entries.firstOrNull { it.name == role }
    }

    companion object {
        private const val EXTRA_PICK_ROLE = "pick_role"

        /**
         * Opens Cellprobe on the choice of the app for [role] (spec §7.3: a
         * notification for a role without an app), or just opens it.
         */
        fun pickRoleIntent(context: Context, role: AppRole?): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (role != null) putExtra(EXTRA_PICK_ROLE, role.name) }
    }
}
