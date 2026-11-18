package com.arcana.brain.app.design

import com.idea.ink.ui.*
import com.idea.ink.input.*
import com.idea.ink.theme.*

import com.idea.ink.input.Key
import com.idea.ink.ui.TextFieldState

/**
 * Keys for a field that carries [attachments]: Ctrl+O opens the file browser, and Backspace on an empty field takes the last
 * attachment back. Returns true when the key was used. [notify] shows what happened (text, isError).
 */
fun handleAttachmentKey(attachments: Attachments, field: TextFieldState, key: Key, notify: (String, Boolean) -> Unit): Boolean = when {
    key == Key.Browse -> { attachments.open(notify); true }
    key == Key.Backspace && field.text.isEmpty() -> attachments.removeLast()
    else -> false
}
