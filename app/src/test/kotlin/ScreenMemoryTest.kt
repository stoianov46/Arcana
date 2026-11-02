package com.arcana.brain.app

import com.arcana.brain.app.design.PickerState
import com.arcana.brain.app.design.ScreenMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** A screen's state outlives its tab: the same key gives the same holder on the next visit, another key (or another vault) starts clean. */
class ScreenMemoryTest {
    @Test
    fun aKeyGivesTheSameHolderOnEveryVisit() {
        val memory = ScreenMemory()
        var made = 0
        val first = memory.keep("library.picker@/vault") { made++; PickerState() }
        first.index = 7
        val again = memory.keep("library.picker@/vault") { made++; PickerState() }
        assertSame(first, again)
        assertEquals(7, again.index)
        assertEquals(1, made, "the holder is made once")
    }

    @Test
    fun anotherKeyOrVaultStartsClean() {
        val memory = ScreenMemory()
        memory.keep("library.picker@/a") { PickerState() }.index = 3
        val other = memory.keep("library.picker@/b") { PickerState() }
        assertNotSame(memory.keep("library.picker@/a") { PickerState() }, other)
        assertEquals(0, other.index)
        assertEquals(0, memory.keep("library.tags@/a") { PickerState() }.index)
    }
}
