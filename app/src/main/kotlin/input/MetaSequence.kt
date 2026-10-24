package com.arcana.brain.app.input

/**
 * Some terminals (IntelliJ's among them) send Option+Shift+arrow as `ESC ESC [ D`. Mosaic reads the two ESCs as an
 * Alt+Esc key ([Key.MetaPrefix]) and leaves `[` and `D` to arrive as typed letters, which would land in the text field.
 * This puts the sequence back together: after a [Key.MetaPrefix], `[` or `O`, optional `1;<modifier>` digits and a final
 * letter become the arrow they stand for (selecting by word when Alt or Ctrl is part of it, as for ←/→).
 */
class MetaSequence(private val windowNanos: Long = 150_000_000L) {
    sealed interface Result {
        /** Not part of a sequence: handle the key as usual. */
        data object Pass : Result

        /** Part of a sequence that is still being read. */
        data object Swallow : Result
        data class Emit(val key: Key) : Result
    }

    private var stage = Stage.Idle
    private var lastAt = 0L
    private val params = StringBuilder()

    private enum class Stage { Idle, Prefix, Bracket }

    fun feed(key: Key, now: Long): Result {
        if (stage != Stage.Idle && now - lastAt > windowNanos) reset()
        lastAt = now
        val ch = (key as? Key.Text)?.value
        return when (stage) {
            Stage.Idle -> if (key == Key.MetaPrefix) { stage = Stage.Prefix; Result.Swallow } else Result.Pass
            Stage.Prefix -> if (ch == '[' || ch == 'O') { stage = Stage.Bracket; params.clear(); Result.Swallow } else { reset(); feed(key, now) }
            Stage.Bracket -> when {
                ch != null && (ch.isDigit() || ch == ';') -> { params.append(ch); Result.Swallow }
                ch != null -> decode(ch).also { reset() }?.let { Result.Emit(it) } ?: Result.Swallow
                else -> { reset(); Result.Pass }
            }
        }
    }

    private fun reset() { stage = Stage.Idle; params.clear() }

    /** The key for final letter [letter] with the modifier number (xterm: 2 Shift, 3 Alt, 4 Shift+Alt, 5 Ctrl, 6 Shift+Ctrl…) read so far. */
    private fun decode(letter: Char): Key? {
        val modifier = params.toString().substringAfter(';', "").toIntOrNull()
        val bits = if (modifier == null) 0b11 else modifier - 1 // no modifier given: this is the Alt-prefixed form, which Shift also uses here
        val shift = bits and 1 != 0
        val wordy = bits and 0b110 != 0
        val cmd = bits and 0b1000 != 0 // Super (Cmd): ←/→ go to the start / end of the line
        fun move(plain: Key, word: Key = plain): Key {
            val m = if (wordy) word else plain
            return if (shift) Key.Select(m) else m
        }
        return when (letter) {
            'D' -> if (cmd) move(Key.TextStart) else move(Key.Left, Key.WordLeft)
            'C' -> if (cmd) move(Key.TextEnd) else move(Key.Right, Key.WordRight)
            'A' -> move(Key.Up)
            'B' -> move(Key.Down)
            'H' -> move(Key.Home)
            'F' -> move(Key.End)
            else -> null
        }
    }
}
