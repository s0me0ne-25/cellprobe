// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Color groups of network statuses on the main screen. */
enum class StatusTone {
    /** Nothing to judge: the user turned the network off. */
    NEUTRAL,

    /** Usual filtering, the recommended tools work. */
    NORMAL,

    /** Part of the tools is blocked. */
    WARNING,

    /** Strict filtering or no working connection. */
    ALERT,

    /** No filtering seen. */
    FREE,
}

/**
 * Text color of [tone]. Yellow and green are not part of the Material
 * scheme; their shades keep text readable on light and dark surfaces.
 */
@Composable
fun StatusTone.color(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
        StatusTone.NORMAL -> MaterialTheme.colorScheme.primary
        StatusTone.WARNING -> if (dark) Color(0xFFF2C94C) else Color(0xFF8A6A00)
        StatusTone.ALERT -> MaterialTheme.colorScheme.error
        StatusTone.FREE -> if (dark) Color(0xFF7BC67E) else Color(0xFF2E7D32)
    }
}
