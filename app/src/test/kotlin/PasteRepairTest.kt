package com.arcana.brain.app

import com.arcana.brain.app.input.ClipboardReader
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/** The terminal delivers a paste as key events; Mosaic keeps only 64 of a burst, and the clipboard is typed in to make up for what was dropped. */
class PasteRepairTest {
    private val text = (1..12).joinToString("\n") { "line $it: the quick brown fox ${"x".repeat(it)} jumps" }

    private fun typed(clipboard: String, send: (KeyDispatcher) -> Unit): String {
        val out = StringBuilder()
        val dispatcher = KeyDispatcher(globalHandler = { false }, clipboard = ClipboardReader { clipboard })
        dispatcher.screenHandler = { key ->
            when (key) {
                is Key.Text -> out.append(key.value)
                Key.Backspace -> if (out.isNotEmpty()) out.deleteCharAt(out.lastIndex)
                Key.PastedNewline, Key.Enter -> out.append('\n')
                else -> Unit
            }
            true
        }
        send(dispatcher)
        return out.toString()
    }

    private fun events(s: String) = s.map { if (it == '\n') Key.Enter else Key.Text(it) }

    @Test
    fun `a paste that lost its beginning is typed in whole`() {
        val all = events(text)
        assertEquals(text, typed(text) { d -> all.takeLast(64).forEach { d.dispatch(it) } })
    }

    @Test
    fun `a paste that lost its middle is typed in whole`() {
        val all = events(text)
        assertEquals(text, typed(text) { d -> (all.take(11) + all.takeLast(64)).forEach { d.dispatch(it) } })
    }

    @Test
    fun `a short paste arrives as it is`() {
        val short = "hello\nworld"
        assertEquals(short, typed(short) { d -> events(short).forEach { d.dispatch(it) } })
    }
}
