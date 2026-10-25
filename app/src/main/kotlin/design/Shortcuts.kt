package com.arcana.brain.app.design

/**
 * Where shortcut labels are written. Arcana's shortcuts are Ctrl chords (Ctrl+P commands, Ctrl+N capture, Ctrl+O attach…): they reach
 * a terminal app unchanged everywhere, while Cmd chords are claimed by the terminal or macOS (Print, New window…). Every label goes
 * through [label], so the way shortcuts are shown can change in one place.
 */
object Shortcuts {
    fun label(text: String): String = text
}
