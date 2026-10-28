// SPDX-License-Identifier: GPL-3.0-or-later

package org.s0me0ne25.cellprobe.ui.components

import android.annotation.SuppressLint
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Asks the keyboard not to learn from or suggest anything typed into text
 * fields inside [content]: proxy addresses must not end up in the keyboard's
 * dictionary or suggestion history.
 */
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = NoLearningInterceptor, content = content)
}

private object NoLearningInterceptor : PlatformTextInputInterceptor {

    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val modified = object : PlatformTextInputMethodRequest {
            // IME_FLAG_NO_PERSONALIZED_LEARNING is API 26; it is a plain bit
            // in EditorInfo, so older keyboards simply ignore it.
            @SuppressLint("InlinedApi")
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val connection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                return connection
            }
        }
        nextHandler.startInputMethod(modified)
    }
}
