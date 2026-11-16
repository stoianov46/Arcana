package com.arcana.brain.app

import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyboardLayout
import com.arcana.brain.app.input.MetaSequence
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.toKey
import com.jakewharton.mosaic.layout.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun TextFieldState.type(text: String) = text.forEach { onKey(Key.Text(it)) }

class InputTest {
    @Test
    fun typingInsertsAtTheCaretAndArrowsMoveIt() {
        val f = TextFieldState()
        f.type("helo")
        f.onKey(Key.Left)
        f.type("l")
        assertEquals("hello", f.text)
        f.onKey(Key.Home)
        f.type(">")
        f.onKey(Key.End)
        f.type("!")
        assertEquals(">hello!", f.text)
    }

    @Test
    fun wordJumpsAndWordDelete() {
        val f = TextFieldState()
        f.type("one two three")
        f.onKey(Key.WordLeft)
        f.type("X")
        assertEquals("one two Xthree", f.text)
        f.onKey(Key.DeleteWord)
        assertEquals("one two three", f.text)
        f.onKey(Key.TextEnd)
        f.onKey(Key.DeleteWord)
        assertEquals("one two ", f.text)
    }

    @Test
    fun shiftSelectionIsReplacedByTyping() {
        val f = TextFieldState()
        f.type("hello world")
        f.onKey(Key.Select(Key.WordLeft))
        assertEquals("world", f.selectedText)
        f.type("there")
        assertEquals("hello there", f.text)
        f.onKey(Key.SelectAll)
        assertEquals("hello there", f.selectedText)
        f.onKey(Key.Backspace)
        assertEquals("", f.text)
    }

    @Test
    fun deleteToLineStartAndEnd() {
        val f = TextFieldState()
        f.type("abc def")
        f.onKey(Key.WordLeft)
        f.onKey(Key.DeleteToLineEnd)
        assertEquals("abc ", f.text)
        f.onKey(Key.DeleteToLineStart)
        assertEquals("", f.text)
    }

    @Test
    fun pastedNewlineIsASpaceInOneLineFieldsAndABreakInMultiline() {
        val one = TextFieldState()
        one.type("a")
        one.onKey(Key.PastedNewline)
        one.type("b")
        assertEquals("a b", one.text)
        val many = TextFieldState(multiline = true)
        many.type("a")
        many.onKey(Key.PastedNewline)
        many.type("b")
        assertEquals("a\nb", many.text)
    }

    @Test
    fun keysThatAreNotEditingKeysAreNotConsumed() {
        val f = TextFieldState()
        assertFalse(f.onKey(Key.Enter))
        assertFalse(f.onKey(Key.Escape))
        assertFalse(f.onKey(Key.Up), "Up on a single line belongs to the screen (scrolling)")
        assertFalse(f.onKey(Key.Tab))
    }

    @Test
    fun mosaicKeyEventsMapOntoTheKeyModel() {
        assertEquals(Key.Enter, KeyEvent("Enter").toKey())
        assertEquals(Key.BackTab, KeyEvent("Tab", shift = true).toKey())
        assertEquals(Key.Select(Key.Right), KeyEvent("ArrowRight", shift = true).toKey())
        assertEquals(Key.WordLeft, KeyEvent("ArrowLeft", alt = true).toKey())
        assertEquals(Key.WordRight, KeyEvent("ArrowRight", ctrl = true).toKey())
        assertEquals(Key.DeleteWord, KeyEvent("Backspace", alt = true).toKey())
        assertEquals(Key.DeleteWord, KeyEvent("w", ctrl = true).toKey())
        assertEquals(Key.DeleteToLineStart, KeyEvent("u", ctrl = true).toKey())
        assertEquals(Key.DeleteToLineEnd, KeyEvent("k", ctrl = true).toKey())
        assertEquals(Key.TextStart, KeyEvent("a", ctrl = true).toKey())
        assertEquals(Key.TextEnd, KeyEvent("e", ctrl = true).toKey())
        assertEquals(Key.SelectAll, KeyEvent("a", alt = true).toKey())
        assertEquals(Key.PageDown, KeyEvent("PageDown").toKey())
        assertEquals(Key.Text('x'), KeyEvent("x").toKey())
        assertNull(KeyEvent("c", ctrl = true).toKey(), "Ctrl+C is handled by the shell, never typed")
    }

    @Test
    fun cyrillicLettersMapToTheirQwertyKeys() {
        assertEquals('n', KeyboardLayout.latinFor('т'))
        assertEquals('S', KeyboardLayout.latinFor('І'))
        assertNull(KeyboardLayout.latinFor('я'), "z is never a control, so it has no mapping")
    }

    @Test
    fun escapeEscapeBracketSequencesBecomeArrows() {
        val meta = MetaSequence()
        assertEquals(MetaSequence.Result.Swallow, meta.feed(Key.MetaPrefix, 0))
        assertEquals(MetaSequence.Result.Swallow, meta.feed(Key.Text('['), 1))
        assertEquals(MetaSequence.Result.Emit(Key.Select(Key.WordLeft)), meta.feed(Key.Text('D'), 2))
        assertEquals(MetaSequence.Result.Pass, meta.feed(Key.Text('x'), 3))
    }

    @Test
    fun noControlUsesTheLettersZ() {
        val offenders = java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (Regex("'[zZ]'").containsMatchIn(code) && !file.name.contains("KeyboardLayout")) "${file.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue(offenders.isEmpty(), "z / Z must not be used as a control: $offenders")
    }
}
