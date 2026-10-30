package com.arcana.brain.app.input

import com.jakewharton.mosaic.layout.KeyEvent

/** Maps Mosaic's raw key event to the presentation layer's [Key]; null = not ours (e.g. Ctrl-C). */
fun KeyEvent.toKey(): Key? {
    if (ctrl || alt) {
        // Caret navigation in text fields: word jumps (Alt/Ctrl+arrows, with Shift they select), and Alt+A selects all
        // (Ctrl+A / Ctrl+E go to the start / end of the whole text, which many terminals send for Cmd+← / Cmd+→). Ctrl+C and Ctrl+B are handled before toKey() in CosmoApp.onKey.
        fun move(m: Key) = if (shift) Key.Select(m) else m
        return when {
            alt && key == "Escape" -> Key.MetaPrefix
            alt && key == "Enter" -> Key.PastedNewline // Alt+Enter: a line break in a field instead of sending
            ctrl && key.equals("j", ignoreCase = true) -> Key.PastedNewline // Ctrl+J: the same, in every terminal
            alt && key == "Backspace" -> Key.DeleteWord
            ctrl && key.equals("w", ignoreCase = true) -> Key.DeleteWord
            alt && key == "b" -> Key.WordLeft // Alt+B / Alt+F: how many terminals send Option+←/→
            alt && key == "f" -> Key.WordRight
            alt && key == "B" -> Key.Select(Key.WordLeft)
            alt && key == "F" -> Key.Select(Key.WordRight)
            key == "ArrowLeft" -> move(Key.WordLeft)
            key == "ArrowRight" -> move(Key.WordRight)
            key == "ArrowUp" && shift -> Key.Select(Key.Up)
            key == "ArrowDown" && shift -> Key.Select(Key.Down)
            key == "Home" && shift && !ctrl -> Key.Select(Key.Home)
            key == "End" && shift && !ctrl -> Key.Select(Key.End)
            alt && key.equals("a", ignoreCase = true) -> Key.SelectAll // Alt+A, and what Cmd+A is turned into
            ctrl && shift && key.equals("a", ignoreCase = true) -> Key.SelectAll
            key == "Home" && ctrl -> move(Key.TextStart) // Ctrl+Home, and what Cmd+← is turned into
            key == "End" && ctrl -> move(Key.TextEnd)
            ctrl && key.equals("a", ignoreCase = true) -> Key.TextStart // many terminals send this for Cmd+←
            ctrl && key.equals("u", ignoreCase = true) -> Key.DeleteToLineStart // iTerm's Cmd+Backspace
            ctrl && key.equals("k", ignoreCase = true) -> Key.DeleteToLineEnd
            ctrl && key.equals("e", ignoreCase = true) -> Key.TextEnd
            ctrl && key.equals("o", ignoreCase = true) -> Key.Browse
            ctrl && key.equals("t", ignoreCase = true) -> Key.Thinking
            ctrl && key.equals("s", ignoreCase = true) -> Key.Save
            ctrl && key.equals("x", ignoreCase = true) -> Key.Skip
            ctrl && key.equals("p", ignoreCase = true) -> Key.Palette
            ctrl && key.equals("n", ignoreCase = true) -> Key.Capture
            else -> null
        }
    }
    // Shift + a caret key selects while it moves.
    fun move(m: Key) = if (shift) Key.Select(m) else m
    return when (key) {
        "Tab" -> if (shift) Key.BackTab else Key.Tab
        "Enter" -> if (shift) Key.PastedNewline else Key.Enter // Shift+Enter (terminals that report it) is a line break
        "Escape" -> Key.Escape
        "Backspace" -> Key.Backspace
        "Delete" -> Key.Delete
        "Home" -> move(Key.Home)
        "End" -> move(Key.End)
        "ArrowUp" -> move(Key.Up)
        "ArrowDown" -> move(Key.Down)
        "ArrowLeft" -> move(Key.Left)
        "ArrowRight" -> move(Key.Right)
        "PageUp" -> Key.PageUp
        "PageDown" -> Key.PageDown
        else -> key.singleOrNull()?.let(Key::Text)
    }
}
