// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.notify

/**
 * Each notification of Cellprobe is alone in a group of its own, with no
 * summary. Ungrouped notifications of one app are bundled by the system,
 * and a bundle shows on the lock screen only as "Cellprobe, 2
 * notifications"; a group of one without a summary shows as a notification
 * of its own, so the monitoring status and the last change both stay
 * readable there.
 */
object NotificationGroups {
    const val MONITORING = "monitoring"
    const val CHANGES = "changes"
}
