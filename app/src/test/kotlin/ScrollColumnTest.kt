import com.arcana.brain.app.design.ScrollState
import com.arcana.brain.app.input.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollColumnTest {
    private fun state(count: Int, shown: Int, maxFirst: Int, first: Int = 0) = ScrollState().also {
        it.count = count; it.shown = shown; it.maxFirst = maxFirst; it.first = first
    }

    @Test
    fun `nothing to scroll leaves keys alone`() {
        val s = state(count = 5, shown = 5, maxFirst = 0)
        assertFalse(s.scrollable)
        assertFalse(s.handle(Key.Down))
    }

    @Test
    fun `arrows and pages move within the ends`() {
        val s = state(count = 20, shown = 8, maxFirst = 12)
        assertTrue(s.handle(Key.Down)); assertEquals(1, s.first)
        assertTrue(s.handle(Key.PageDown)); assertEquals(8, s.first)
        assertTrue(s.handle(Key.PageDown)); assertEquals(12, s.first)
        assertTrue(s.handle(Key.End)); assertEquals(12, s.first)
        assertTrue(s.handle(Key.PageUp)); assertEquals(5, s.first)
        assertTrue(s.handle(Key.Home)); assertEquals(0, s.first)
        assertTrue(s.handle(Key.Up)); assertEquals(0, s.first)
    }
}
