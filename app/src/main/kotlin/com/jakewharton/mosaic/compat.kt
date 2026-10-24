package com.jakewharton.mosaic

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent

/**
 * Replacement for Mosaic 0.18.0's own `CompatKt` (same package and class name, and this app's jar comes first on the
 * classpath, so the JVM loads this one).
 *
 * The original throws `UnsupportedOperationException` for every key it cannot name, which kills the whole app the
 * moment a terminal using the Kitty keyboard protocol reports a letter from a non-Latin layout (Cyrillic `т` is
 * codepoint 1090). This version keeps the original's key names, turns any other printable character into itself so it
 * can be typed, and ignores keys that are neither (instead of crashing)
 * .
 *
 * Nothing in this app calls it: Mosaic's own code does, by the JVM class name `com.jakewharton.mosaic.CompatKt`, so the
 * package, file name and signature must not change (KeyMappingTest looks it up by that name).
 *
 * Upstream: https://github.com/JakeWharton/mosaic/issues/972 (open; checked against 0.18.0, the latest release, and its
 * main branch still throws). A fix is proposed in https://github.com/JakeWharton/mosaic/pull/1176. Once a Mosaic release
 * contains one, delete this file and the non-Latin test in KeyMappingTest.
 */
@Suppress("unused") // called by Mosaic itself, see above
internal fun KeyboardEvent.toKeyEventOrNull(): KeyEvent? {
	if (eventType != KeyboardEvent.EventTypePress) {
		return null
	}

	val key = when (val codepoint = codepoint) {
		9 -> "Tab"
		13 -> "Enter"
		27 -> "Escape"
		in 32..126 -> codepoint.toChar().toString()
		127 -> "Backspace"
		57350 -> "ArrowLeft"
		57351 -> "ArrowRight"
		57352 -> "ArrowUp"
		57353 -> "ArrowDown"
		57348 -> "Insert"
		57349 -> "Delete"
		57354 -> "PageUp"
		57355 -> "PageDown"
		57356 -> "Home"
		57357 -> "End"
		in 57364..57398 -> "F" + (codepoint - 57363)
		else -> printableOrNull(codepoint, shift) ?: return null
	}

	// Cmd (Super) arrives from terminals that report it (Kitty protocol, or a profile that maps Cmd+arrows). KeyEvent has no
	// field for it, so it becomes the key it means in a text field: Cmd+←/→ = Ctrl+Home/End (start / end of the text), Cmd+A = Alt+A (select all) and
	// Cmd+Backspace = Alt+Backspace (delete the word).
	if (`super`) {
		when (key) {
			"ArrowLeft" -> return KeyEvent("Home", alt = false, ctrl = true, shift = shift) // Ctrl+Home: the start of the whole text
			"ArrowRight" -> return KeyEvent("End", alt = false, ctrl = true, shift = shift)
			"a", "A" -> return KeyEvent("a", alt = true, ctrl = false, shift = false)
			"Backspace" -> return KeyEvent("Backspace", alt = true, ctrl = false, shift = false)
		}
	}

	return KeyEvent(
		key = key,
		alt = alt,
		ctrl = ctrl,
		shift = shift,
	)
}

/** The typed character for a non-ASCII key (Kitty reports the unshifted letter, so Shift upper-cases it). */
private fun printableOrNull(codepoint: Int, shift: Boolean): String? {
	// Kitty's private-use range holds its functional keys (media keys, modifiers, ...); they are not text.
	if (codepoint !in 0xA0..0xFFFF || codepoint in 0xE000..0xF8FF) return null
	val char = codepoint.toChar()
	if (Character.isISOControl(char) || Character.isSurrogate(char)) return null
	return (if (shift && Character.isLetter(char)) Character.toUpperCase(char) else char).toString()
}
