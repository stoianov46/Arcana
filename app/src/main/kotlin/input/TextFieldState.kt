package com.arcana.brain.app.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

private const val FIELD = "text"

/**
 * The text and caret of one input field, edited with [TextEdit]: typing inserts at the caret, ←/→ move it, Alt/Ctrl+←/→
 * jump by word, Home/End go to the line's ends, Shift+movement selects, Backspace/Delete/Ctrl+W/Ctrl+U/Ctrl+K remove
 * text. A pasted line break becomes a space unless the field is [multiline].
 */
class TextFieldState(initial: String = "", private val multiline: Boolean = false, private val max: Int = 20_000) {
    var text by mutableStateOf(initial)
        private set
    var caret by mutableStateOf(Caret())
        private set

    /** The caret position to draw, or -1 for "at the end". */
    val caretAt: Int get() = caret.forField(FIELD)

    /** The selected stretch, or null. */
    val selection: Selection? get() = caret.selectionFor(FIELD)

    val selectedText: String?
        get() = selection?.bounds(text.length)?.let { text.substring(it.first, it.last + 1) }

    fun set(value: String) {
        text = value
        caret = Caret()
    }

    fun clear() = set("")

    /** Applies [key] as a text field would; false when it is not an editing key (the caller then handles it). */
    fun onKey(key: Key): Boolean {
        val k = if (key == Key.PastedNewline && !multiline) Key.Text(' ') else key
        val (newText, newCaret) = TextEdit.apply(FIELD, text, caret, k) ?: return false
        if (newText.length <= max) {
            text = newText
            caret = newCaret
        }
        return true
    }
}

val LocalKeyDispatcher = compositionLocalOf<KeyDispatcher> { error("No KeyDispatcher provided") }

/**
 * Routes keyboard input to [handler] while this composable is on screen; keys it declines fall through to the global
 * shortcuts (screen switching, the key map). Only one screen shows at a time, so there is one handler.
 */
@Composable
fun KeyHandler(handler: (Key) -> Boolean) {
    val dispatcher = LocalKeyDispatcher.current
    val latest by rememberUpdatedState(handler)
    DisposableEffect(dispatcher) {
        dispatcher.screenHandler = { latest(it) }
        onDispose { dispatcher.screenHandler = null }
    }
}

/** The character of a typed key, or null for a special key. */
val Key.char: Char? get() = (this as? Key.Text)?.value
