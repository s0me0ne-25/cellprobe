// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import org.s0me0ne25.cellprobe.R

/** Emoji used as button icons; rendered by the system emoji font. */
object Emoji {
    const val EDIT = "✏️"
    const val DELETE = "❌"
}

/**
 * Icon button showing an emoji. The emoji itself is hidden from
 * accessibility services so that [contentDescription] is read instead
 * of the emoji name.
 */
@Composable
fun EmojiButton(
    emoji: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(text = emoji, fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.action_back),
        )
    }
}
