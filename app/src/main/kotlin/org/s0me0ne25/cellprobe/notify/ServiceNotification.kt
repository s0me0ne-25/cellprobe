// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.s0me0ne25.cellprobe.MainActivity
import org.s0me0ne25.cellprobe.R
import org.s0me0ne25.cellprobe.engine.CheckState
import org.s0me0ne25.cellprobe.ui.labelRes
import org.s0me0ne25.cellprobe.ui.titleRes
import java.util.Date

/**
 * The persistent notification of the monitoring service (spec §7.2): the
 * current status, network type and time of the last check. It holds no
 * addresses or labels, so it may show in full on the lock screen.
 */
object ServiceNotification {

    const val ID = 1
    private const val CHANNEL_ID = "service"

    /** Creates the "Service" channel on API 26+; on older versions the priority is set per notification. */
    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
            .setName(context.getString(R.string.channel_service))
            .setDescription(context.getString(R.string.channel_service_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Replaces the shown notification; without the notification permission (API 33+) nothing is shown. */
    fun update(context: Context, state: CheckState) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(ID, build(context, state))
    }

    fun build(context: Context, state: CheckState): Notification {
        val result = state.result
        val title = when {
            result != null -> context.getString(result.status.titleRes())
            state.running -> context.getString(R.string.main_status_checking)
            else -> context.getString(R.string.notification_no_checks)
        }
        val text = result?.let {
            val time = DateFormat.getTimeFormat(context).format(Date(it.finishedAtMillis))
            context.getString(R.string.main_status_details, context.getString(it.networkType.labelRes()), time)
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
