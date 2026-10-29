// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.probe

import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * User-Agent of the system WebView, cached until the WebView package is
 * updated (on API 24-25 the version is unknown, so once per process).
 */
class UserAgentProvider(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var cached: Pair<Long, String>? = null

    suspend fun userAgent(): String = mutex.withLock {
        val version = webViewVersion()
        cached?.let { (cachedVersion, userAgent) -> if (cachedVersion == version) return userAgent }

        // Loading the WebView provider is safest on the main thread; it
        // happens once per process or WebView update.
        val userAgent = withContext(Dispatchers.Main) {
            runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull()
        } ?: FALLBACK_USER_AGENT
        cached = version to userAgent
        userAgent
    }

    private fun webViewVersion(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return UNKNOWN_VERSION
        val info = WebView.getCurrentWebViewPackage() ?: return UNKNOWN_VERSION
        return PackageInfoCompat.getLongVersionCode(info)
    }

    companion object {
        private const val UNKNOWN_VERSION = -1L

        /** Used only when no WebView is installed or it fails to load. */
        private val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; K; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/141.0.0.0 Mobile Safari/537.36"

        /** System language preferences as BCP 47 tags, most preferred first. */
        fun systemLanguageTags(): List<String> =
            LocaleList.getDefault().toLanguageTags().split(',').filter { it.isNotBlank() }
    }
}
