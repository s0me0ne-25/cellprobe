// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.notify

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.s0me0ne25.cellprobe.CellprobeApplication
import org.s0me0ne25.cellprobe.MainActivity
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.actions.SettingsScreen
import org.s0me0ne25.cellprobe.ui.apps.openSettings
import org.s0me0ne25.cellprobe.ui.apps.openUrl
import org.s0me0ne25.cellprobe.ui.apps.tryStart

/**
 * An invisible activity between a notification and what it opens (spec
 * §7.3): the app of a role, a settings screen, a page. An activity, not a
 * receiver: since Android 12 only an activity may start another one from a
 * notification tap. If the role has no app, or the app does not start,
 * Cellprobe opens with the choice of the app.
 */
class NotificationActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            handle(intent)
            finish()
        }
    }

    private suspend fun handle(intent: Intent) {
        val role = intent.getStringExtra(EXTRA_ROLE)?.let { name -> AppRole.entries.firstOrNull { it.name == name } }
        val screen = intent.getStringExtra(EXTRA_SETTINGS)?.let { name -> SettingsScreen.entries.firstOrNull { it.name == name } }
        val url = intent.getStringExtra(EXTRA_URL)
        when {
            role != null -> if (!launchRole(role)) startActivity(MainActivity.pickRoleIntent(this, role))
            screen != null -> openSettings(screen)
            url != null -> if (!openUrl(url)) startActivity(MainActivity.pickRoleIntent(this, role = null))
        }
    }

    private suspend fun launchRole(role: AppRole): Boolean {
        val container = (application as CellprobeApplication).container
        val packageName = container.settingsRepository.appRoles.first()[role] ?: return false
        val launch = container.installedApps.launchIntent(packageName) ?: return false
        return tryStart(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, NotificationActionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
