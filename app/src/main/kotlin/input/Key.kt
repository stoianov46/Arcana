package com.arcana.brain.app.input

/** Terminal-library-agnostic key model; the UI layer maps Mosaic key events onto it. */
sealed interface Key {
    data class Text(val value: Char) : Key
    data object Enter : Key
    data object Escape : Key
    data object Tab : Key
    data object BackTab : Key
    data object Backspace : Key
    data object Delete : Key
    data object Home : Key
    data object End : Key
    data object Up : Key
    data object Down : Key
    data object Left : Key
    data object Right : Key
    /** Caret to the previous / next word boundary (Alt+← / Ctrl+←, and their Right counterparts). */
    data object WordLeft : Key
    data object WordRight : Key
    /** A caret move made with Shift held: the text between where it started and where it ends becomes selected. [move] is Left, Right, Up, Down, Home, End, WordLeft or WordRight. */
    data class Select(val move: Key) : Key
    /** Select all the text of the field (Alt+A, Cmd+A, Ctrl+Shift+A). */
    data object SelectAll : Key
    /** Skip the item being reviewed (Ctrl+X). */
    data object Skip : Key
    /** Open the command palette (Ctrl+P): every action by name, searchable. */
    data object Palette : Key
    /** Capture a thought from anywhere (Ctrl+N): it goes to today's daily note. */
    data object Capture : Key
    /** Save what is on screen (Ctrl+S); works even while a text field has the keyboard. */
    data object Save : Key
    /** Change the size of the Chat's thinking pane (Ctrl+T): it steps through a few heights. */
    data object Thinking : Key
    /** Choose the reasoning effort of the next message from what the model offers (Ctrl+G, the same as `/think`). */
    data object Effort : Key
    /** Open the file browser of the field being edited (Ctrl+O). */
    data object Browse : Key
    /** Delete the word before the caret (Alt+Backspace, Ctrl+W). */
    data object DeleteWord : Key
    /** An Alt+Esc event: the start of an `ESC ESC [ …` arrow sequence some terminals send (see [MetaSequence]); never a command. */
    data object MetaPrefix : Key
    /** The very start / end of the whole text, across lines (Cmd+←/→, Ctrl+A / Ctrl+E, Ctrl+Home / Ctrl+End); Home / End stay on the line. */
    data object TextStart : Key
    data object TextEnd : Key
    /** Delete from the caret back to the start of its line / on to the end of it (Ctrl+U, Ctrl+K; iTerm sends Ctrl+U for Cmd+Backspace). */
    data object DeleteToLineStart : Key
    data object DeleteToLineEnd : Key
    data object PageUp : Key
    data object PageDown : Key

    /** An Enter that arrived as part of a paste (right after another key), so it is a line break in the text, not a command. */
    data object PastedNewline : Key
}

/** Up arrow, or k (vim-style) on layouts where the dispatcher passes the Latin letter. */
fun Key.isUp(): Boolean = this == Key.Up || (this as? Key.Text)?.value == 'k'

/** Down arrow, or j. */
fun Key.isDown(): Boolean = this == Key.Down || (this as? Key.Text)?.value == 'j'

/** The index of the row above in a list, stopping at the first. */
fun Int.up(): Int = (this - 1).coerceAtLeast(0)

/** The index of the row below in a list of [size] rows, stopping at the last. */
fun Int.down(size: Int): Int = (this + 1).coerceAtMost((size - 1).coerceAtLeast(0))
