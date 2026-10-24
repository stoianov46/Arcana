package com.arcana.brain.app.input

/**
 * Where the cursor is in the text field being edited. It is remembered together with the field's name, so moving to
 * another field puts the cursor back at the end of that field's text, and programmatic changes (clearing a prompt after
 * a launch) leave it at the end.
 */
data class Caret(val field: String = "", val pos: Int = END, val anchor: Int = NONE) {
    /** The cursor position in [field]'s [text], clamped; the end of the text unless the cursor was moved there. */
    fun at(field: String, text: String): Int = if (this.field == field && pos in 0..text.length) pos else text.length

    /** The other end of the selection in [field]'s [text], or null when nothing is selected. */
    fun anchorAt(field: String, text: String): Int? =
        anchor.takeIf { this.field == field && it in 0..text.length && it != at(field, text) }

    /** For drawing: the position to show, or -1 for "at the end". */
    fun forField(field: String): Int = if (this.field == field && pos >= 0) pos else END

    /** For drawing: the selection in [field] as the editor stored it (the renderer clamps it to the text it draws), or null. */
    fun selectionFor(field: String): Selection? = if (this.field == field && anchor >= 0) Selection(anchor, pos) else null

    companion object {
        const val END = -1
        const val NONE = -1
    }
}

/** A selected stretch of a text field: [anchor] is where it started, [caret] where the cursor is now ([Caret.END] = the end). */
data class Selection(val anchor: Int, val caret: Int) {
    /** The selected range [start, end) in a text of [length] characters, or null when it is empty. */
    fun bounds(length: Int): IntRange? {
        val a = anchor.coerceIn(0, length)
        val c = if (caret < 0) length else caret.coerceIn(0, length)
        return if (a == c) null else minOf(a, c) until maxOf(a, c)
    }
}

object TextEdit {
    /** The longest label a user can give a job (the Workspace list has little room for it). */
    const val MAX_LABEL = 40

    /** The longest branch name a user can type (git itself allows 255 bytes per ref name). */
    const val MAX_BRANCH = 255

    /**
     * Like [apply] for a one-line field of at most [max] characters: a pasted line break becomes a space and Enter / Tab
     * are left to the caller.
     */
    fun applyLine(field: String, text: String, caret: Caret, key: Key, max: Int = MAX_LABEL): Pair<String, Caret>? {
        val k = if (key == Key.PastedNewline) Key.Text(' ') else key
        val (t, c) = apply(field, text, caret, k) ?: return null
        return if (t.length > max) null else t to c
    }

    /**
     * Applies [key] to [text] as a text field would: typing inserts at the cursor, Backspace and Delete remove around
     * it, Left / Right / Home / End move it.
     *
     * @return the new text and cursor, or null when [key] is not an editing key (the caller then handles it).
     */
    fun apply(field: String, text: String, caret: Caret, key: Key): Pair<String, Caret>? {
        val c = caret.at(field, text)
        val a = caret.anchorAt(field, text)
        val from = if (a == null) c else minOf(a, c)
        val to = if (a == null) c else maxOf(a, c)
        fun result(newText: String, newPos: Int, newAnchor: Int = Caret.NONE) =
            newText to Caret(field, if (newPos >= newText.length) Caret.END else newPos, if (newAnchor == newPos) Caret.NONE else newAnchor)
        /** Replaces the selection (or just inserts at the cursor) with [insert]. */
        fun replace(insert: String) = result(text.substring(0, from) + insert + text.substring(to), from + insert.length)
        return when (key) {
            is Key.Text -> if (key.value.code < 0x20) null else replace(key.value.toString())
            Key.PastedNewline -> replace("\n")
            Key.Backspace -> if (a != null) replace("") else if (c == 0) result(text, 0) else result(text.removeRange(c - 1, c), c - 1)
            Key.Delete -> if (a != null) replace("") else if (c >= text.length) result(text, c) else result(text.removeRange(c, c + 1), c)
            Key.Left -> result(text, if (a != null) from else c.up())
            Key.Right -> result(text, if (a != null) to else (c + 1).coerceAtMost(text.length))
            Key.DeleteWord -> if (a != null) replace("") else wordLeft(text, c).let { w -> result(text.removeRange(w, c), w) }
            Key.WordLeft -> result(text, wordLeft(text, c))
            Key.WordRight -> result(text, wordRight(text, c))
            Key.TextStart -> result(text, 0)
            Key.TextEnd -> result(text, text.length)
            Key.DeleteToLineStart -> if (a != null) replace("") else lineStart(text, c).let { s -> if (s == c) null else result(text.removeRange(s, c), s) }
            Key.DeleteToLineEnd -> if (a != null) replace("") else lineEnd(text, c).let { e -> if (e > c) result(text.removeRange(c, e), c) else if (c < text.length) result(text.removeRange(c, c + 1), c) else null }
            Key.Home -> result(text, lineStart(text, c))
            Key.End -> result(text, lineEnd(text, c))
            // Up / Down move between the lines of a multi-line text; on the first / last line they are not ours (null).
            Key.Up -> lineUp(text, c)?.let { result(text, it) }
            Key.Down -> lineDown(text, c)?.let { result(text, it) }
            Key.SelectAll -> if (text.isEmpty()) result(text, 0) else result(text, text.length, 0)
            is Key.Select -> {
                val target = when (key.move) {
                    Key.Left -> c.up()
                    Key.Right -> (c + 1).coerceAtMost(text.length)
                    Key.WordLeft -> wordLeft(text, c)
                    Key.WordRight -> wordRight(text, c)
                    Key.Home -> lineStart(text, c)
                    Key.TextStart -> 0
                    Key.TextEnd -> text.length
                    Key.End -> lineEnd(text, c)
                    Key.Up -> lineUp(text, c) ?: 0
                    Key.Down -> lineDown(text, c) ?: text.length
                    else -> return null
                }
                result(text, target, a ?: c)
            }
            else -> null
        }
    }

    /** Where the line holding [pos] starts. */
    private fun lineStart(text: String, pos: Int): Int = text.lastIndexOf('\n', pos - 1).let { if (pos == 0) 0 else it + 1 }

    /** Where the line holding [pos] ends (before its line break, or the end of the text). */
    private fun lineEnd(text: String, pos: Int): Int = text.indexOf('\n', pos).let { if (it < 0) text.length else it }

    /** The same column one line up (or the end of that line when it is shorter); null on the first line. */
    private fun lineUp(text: String, pos: Int): Int? {
        val start = lineStart(text, pos)
        if (start == 0) return null
        val prevStart = lineStart(text, start - 1)
        return prevStart + minOf(pos - start, start - 1 - prevStart)
    }

    /** The same column one line down (or the end of that line when it is shorter); null on the last line. */
    private fun lineDown(text: String, pos: Int): Int? {
        val end = lineEnd(text, pos)
        if (end >= text.length) return null
        val nextStart = end + 1
        return nextStart + minOf(pos - lineStart(text, pos), lineEnd(text, nextStart) - nextStart)
    }

    /** The start of the word at or before [pos]: skip whitespace to the left, then the run of non-whitespace. */
    private fun wordLeft(text: String, pos: Int): Int {
        var i = pos
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    /** The end of the word at or after [pos]: skip whitespace to the right, then the run of non-whitespace. */
    private fun wordRight(text: String, pos: Int): Int {
        var i = pos
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }
}
