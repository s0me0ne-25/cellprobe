// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.s0me0ne25.cellprobe.MainActivity
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.actions.SettingsScreen
import org.s0me0ne25.cellprobe.core.actions.StatusAction
import org.s0me0ne25.cellprobe.ui.pages.ProviderPagesActivity

/** Where a tap on a notification or on one of its buttons leads (spec §7.3). */
sealed interface NotificationTarget {
    data class Role(val role: AppRole) : NotificationTarget
    data class Settings(val screen: SettingsScreen) : NotificationTarget
    data class Url(val url: String) : NotificationTarget

    /** The Cellprobe window with the provider account pages. */
    data object ProviderPagesWindow : NotificationTarget

    /** Cellprobe itself. */
    data object App : NotificationTarget

    companion object {
        fun of(action: StatusAction?): NotificationTarget = when (action) {
            is StatusAction.LaunchRole -> Role(action.role)
            is StatusAction.OpenSettings -> Settings(action.screen)
            StatusAction.ProviderPages -> ProviderPagesWindow
            null -> App
        }
    }
}

/**
 * The intent of [target] for a notification. Apps, settings screens and
 * pages go through [NotificationActionActivity]: what to open is decided
 * at the tap, so an app assigned or removed meanwhile is taken into
 * account.
 */
fun NotificationTarget.pendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = when (this) {
        is NotificationTarget.Role -> NotificationActionActivity.intent(context).putExtra(EXTRA_ROLE, role.name)
        is NotificationTarget.Settings -> NotificationActionActivity.intent(context).putExtra(EXTRA_SETTINGS, screen.name)
        is NotificationTarget.Url -> NotificationActionActivity.intent(context).putExtra(EXTRA_URL, url)
        NotificationTarget.ProviderPagesWindow -> ProviderPagesActivity.intent(context)
        NotificationTarget.App -> Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

internal const val EXTRA_ROLE = "role"
internal const val EXTRA_SETTINGS = "settings"
internal const val EXTRA_URL = "url"
