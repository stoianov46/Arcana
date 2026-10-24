package com.arcana.brain.app

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMappingTest {
    private fun press(codepoint: Int, shift: Boolean = false) = KeyboardEvent(
        codepoint = codepoint, shiftedCodepoint = -1, baseLayoutCodepoint = -1,
        modifiers = if (shift) KeyboardEvent.ModifierShift else 0, eventType = KeyboardEvent.EventTypePress, text = null,
    )

    @Test
    fun `a non-Latin key no longer crashes and arrives as the typed character`() {
        val method = Class.forName("com.jakewharton.mosaic.CompatKt").getDeclaredMethod("toKeyEventOrNull", KeyboardEvent::class.java)
        fun key(e: KeyboardEvent) = method.invoke(null, e) as KeyEvent?
        assertEquals("т", key(press(1090))?.key)
        assertEquals("Т", key(press(1090, shift = true))?.key)
        assertEquals("a", key(press(97))?.key)
        assertEquals("Enter", key(press(13))?.key)
        assertNull(key(press(57441)), "a Kitty functional key (a modifier) is ignored instead of throwing")
    }
}
