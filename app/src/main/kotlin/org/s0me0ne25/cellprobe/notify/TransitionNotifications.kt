// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.s0me0ne25.cellprobe.MainActivity
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.core.NetworkStatus
import org.s0me0ne25.cellprobe.core.stabilizer.Notice
import org.s0me0ne25.cellprobe.core.stabilizer.Volume
import org.s0me0ne25.cellprobe.ui.descriptionRes
import org.s0me0ne25.cellprobe.ui.labelRes
import org.s0me0ne25.cellprobe.ui.titleRes

/**
 * Notifications about status changes (spec §7.1, §7.2): one at a time
 * under one ID, each replacing the previous one. Loud ones go to the
 * "Important changes" channel, the others to "Status changes"; silent
 * updates alert no one. The lock screen shows only the status.
 */
class TransitionNotifications(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    fun show(notice: Notice) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        createChannels()
        manager.notify(ID, build(notice))
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

    private fun build(notice: Notice): Notification {
        val (title, text) = when (notice) {
            is Notice.StatusChanged -> appContext.getString(notice.status.titleRes()) to notice.status.description()
            is Notice.Unstable -> appContext.getString(R.string.notification_unstable_title) to
                appContext.getString(R.string.notification_unstable_text, statusList(notice.statuses))
            is Notice.Stabilized -> appContext.getString(
                R.string.notification_stabilized_title,
                appContext.getString(notice.status.titleRes()),
            ) to notice.status.description()
        }
        val networkType = when (notice) {
            is Notice.StatusChanged -> notice.networkType
            is Notice.Stabilized -> notice.networkType
            is Notice.Unstable -> null
        }
        val loud = notice.volume == Volume.LOUD
        val channel = if (loud) CHANNEL_LOUD else CHANNEL_NORMAL
        val priority = if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        // The lock screen gets the title alone (spec §7.2).
        val public = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setPriority(priority)
            .build()

        return NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(text?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setSubText(networkType?.let { appContext.getString(it.labelRes()) })
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(NotificationGroups.CHANGES)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            // API 24-25 have no channels: sound and vibration come with the notification.
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSilent(notice.volume == Volume.SILENT)
            .build()
    }

    private fun NetworkStatus.description(): String? = descriptionRes()?.let(appContext::getString)

    /** "“A” and “B”", "“A”, “B” and “C”". */
    private fun statusList(statuses: List<NetworkStatus>): String {
        val titles = statuses.map { appContext.getString(R.string.quoted, appContext.getString(it.titleRes())) }
        if (titles.size < 2) return titles.joinToString()
        return titles.dropLast(1).joinToString(", ") + appContext.getString(R.string.list_last_separator) + titles.last()
    }

    // Spec §7.3 actions come in step 11; until then the app opens.
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val ID = 2
        const val CHANNEL_LOUD = "changes_important"
        const val CHANNEL_NORMAL = "changes"
    }
}
