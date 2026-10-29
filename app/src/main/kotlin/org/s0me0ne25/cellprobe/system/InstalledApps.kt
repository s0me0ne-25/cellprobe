// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap

/** An app that can be launched from the home screen. */
data class LaunchableApp(val packageName: String, val label: String)

/**
 * Apps visible through the `<queries>` entry for `MAIN/LAUNCHER`.
 * Every call does package manager I/O: keep them off the main thread.
 */
class InstalledApps(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val icons = LruCache<String, Bitmap>(ICON_CACHE_SIZE)

    /** Launchable apps except Cellprobe itself, sorted by label. */
    fun launchable(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != appContext.packageName }
            .mapNotNull { packageName -> label(packageName)?.let { LaunchableApp(packageName, it) } }
            .sortedBy { it.label.lowercase() }
    }

    /** User-visible name, or null if the app is not installed. */
    fun label(packageName: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Intent that opens the app as its launcher icon does, or null if it cannot be launched. */
    fun launchIntent(packageName: String): Intent? = packageManager.getLaunchIntentForPackage(packageName)

    /** Launcher icon scaled to [sizePx], or null if the app is not installed. */
    fun icon(packageName: String, sizePx: Int): Bitmap? {
        val key = "$packageName@$sizePx"
        icons.get(key)?.let { return it }
        val drawable = try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return drawable.toBitmap(sizePx, sizePx).also { icons.put(key, it) }
    }

    private companion object {
        const val ICON_CACHE_SIZE = 64
    }
}
