// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.notify

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.actions.AppRole
import org.s0me0ne25.cellprobe.core.actions.StatusActions
import org.s0me0ne25.cellprobe.core.stabilizer.Notice
import org.s0me0ne25.cellprobe.core.stabilizer.Volume
import org.s0me0ne25.cellprobe.engine.ProviderPage
import org.s0me0ne25.cellprobe.ui.descriptionRes
import org.s0me0ne25.cellprobe.ui.labelRes
import org.s0me0ne25.cellprobe.ui.titleRes

/**
 * Notifications about status changes (spec §7.1, §7.2): one at a time
 * under one ID, each replacing the previous one. Loud ones go to the
 * "Important changes" channel, the others to "Status changes"; silent
 * updates alert no one. The lock screen shows only the status.
 *
 * A tap acts on the status (spec §7.3): the app of its role, the provider
 * pages window, a settings screen; for UNSTABLE, on the strictest status of
 * the window. "VLESS blocked" has buttons for both of its apps, "Pay for
 * your connection" lists the provider pages (spec §7.4) and has a button for
 * each, if there are at most three.
 */
class TransitionNotifications(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /** @param pages the provider pages, marked by the check that found [notice]. */
    fun show(notice: Notice, pages: List<ProviderPage>) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        createChannels()
        manager.notify(ID, build(notice, pages))
    }

    fun cancel() {
        manager.cancel(ID)
    }

    private fun createChannels() {
        val loud = NotificationChannelCompat.Builder(CHANNEL_LOUD, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(appContext.getString(R.string.channel_changes_important))
            .setDescription(appContext.getString(R.string.channel_changes_important_description))
            .build()
        val normal = NotificationChannelCompat.Builder(CHANNEL_NORMAL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(appContext.getString(R.string.channel_changes))
            .setDescription(appContext.getString(R.string.channel_changes_description))
            .build()
        manager.createNotificationChannelsCompat(listOf(loud, normal))
    }

    private fun build(notice: Notice, pages: List<ProviderPage>): Notification {
        val (title, text) = when (notice) {
            is Notice.StatusChanged -> appContext.getString(notice.status.titleRes()) to notice.status.description()
            is Notice.Unstable -> appContext.getString(R.string.notification_unstable_title) to
                appContext.getString(R.string.notification_unstable_text, statusList(notice.statuses))
            is Notice.Stabilized -> appContext.getString(
                R.string.notification_stabilized_title,
                appContext.getString(notice.status.titleRes()),
            ) to notice.status.description()
        }
        // What a tap acts on, and whether the status gets its own buttons and page list.
        val (actedOn, actedNetwork, full) = when (notice) {
            is Notice.StatusChanged -> Triple(notice.status, notice.networkType, true)
            is Notice.Stabilized -> Triple(notice.status, notice.networkType, true)
            is Notice.Unstable -> Triple(notice.strictest, notice.networkType, false)
        }
        val actions = StatusActions.forStatus(actedOn, actedNetwork)
        val loud = notice.volume == Volume.LOUD
        val channel = if (loud) CHANNEL_LOUD else CHANNEL_NORMAL
        val priority = if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        // The lock screen gets the title alone (spec §7.2).
        val public = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setPriority(priority)
            .build()

        val showPages = full && actedOn == NetworkStatus.PAY && pages.isNotEmpty()
        val style = if (showPages) {
            NotificationCompat.InboxStyle().also { inbox ->
                pages.forEach { page -> inbox.addLine(if (page.responded) "✅ ${page.label}" else page.label) }
            }
        } else {
            NotificationCompat.BigTextStyle().bigText(text)
        }
        val builder = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setSubText(actedNetwork?.let { appContext.getString(it.labelRes()) })
            .setContentIntent(NotificationTarget.of(actions.firstOrNull()).pendingIntent(appContext, REQUEST_CONTENT))
            .setAutoCancel(true)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(NotificationGroups.CHANGES)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            // API 24-25 have no channels: sound and vibration come with the notification.
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSilent(notice.volume == Volume.SILENT)
        if (full) buttons(actedOn, pages).forEachIndexed { index, (label, target) ->
            builder.addAction(0, label, target.pendingIntent(appContext, REQUEST_BUTTON + index))
        }
        return builder.build()
    }

    /** Buttons of [status] (spec §7.3, §7.4): label and target. */
    private fun buttons(status: NetworkStatus, pages: List<ProviderPage>): List<Pair<String, NotificationTarget>> =
        when (status) {
            NetworkStatus.VLESS_BLOCKED -> listOf(
                appContext.getString(R.string.notification_action_i2p) to NotificationTarget.Role(AppRole.I2P),
                appContext.getString(R.string.notification_action_vpn_client) to NotificationTarget.Role(AppRole.VPN_CLIENT),
            )
            NetworkStatus.PAY ->
                if (pages.size <= MAX_PAGE_BUTTONS) pages.map { it.label to NotificationTarget.Url(it.url) } else emptyList()
            else -> emptyList()
        }

    private fun NetworkStatus.description(): String = appContext.getString(descriptionRes())

    /** "“A” and “B”", "“A”, “B” and “C”". */
    private fun statusList(statuses: List<NetworkStatus>): String {
        val titles = statuses.map { appContext.getString(R.string.quoted, appContext.getString(it.titleRes())) }
        if (titles.size < 2) return titles.joinToString()
        return titles.dropLast(1).joinToString(", ") + appContext.getString(R.string.list_last_separator) + titles.last()
    }

    private companion object {
        const val ID = 2
        const val REQUEST_CONTENT = 0
        const val REQUEST_BUTTON = 1

        /** With more pages, the buttons would not fit: the window lists them all. */
        const val MAX_PAGE_BUTTONS = 3
        const val CHANNEL_LOUD = "changes_important"
        const val CHANNEL_NORMAL = "changes"
    }
}
