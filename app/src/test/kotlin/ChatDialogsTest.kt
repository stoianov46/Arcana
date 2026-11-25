import com.arcana.brain.app.screens.ArtifactsState
import com.arcana.brain.app.screens.ContextInspectorState
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ContextInspector
import com.idea.ink.input.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Chat dialogs that own their state (proposal 0007, phase 8): their keys, without the screen. */
class ChatDialogsTest {
    @Test
    fun theInspectorChoosesOpensAndClosesAPart() {
        val snap = ContextInspector.inspect(listOf(ChatMessage.system("sys"), ChatMessage.user("a"), ChatMessage.assistant("b"), ChatMessage.user("c")), null, "m", 0)
        val s = ContextInspectorState()
        var copied: String? = null
        assertTrue(s.handle(Key.Down, snap) { p, _ -> copied = p.title })
        assertEquals(1, s.index)
        s.handle(Key.Down, snap) { _, _ -> }; s.handle(Key.Down, snap) { _, _ -> }; s.handle(Key.Down, snap) { _, _ -> }
        assertEquals(snap.parts.lastIndex, s.index, "stops at the last part")
        s.handle(Key.Enter, snap) { _, _ -> }
        assertTrue(s.reading)
        s.handle(Key.Text('c'), snap) { p, _ -> copied = p.title }
        assertEquals(snap.parts.last().title, copied, "c copies the open part")
        assertTrue(s.handle(Key.Escape, snap) { _, _ -> })
        assertFalse(s.reading, "Esc goes back to the parts first")
        assertFalse(s.handle(Key.Escape, snap) { _, _ -> }, "then closes the inspector")
        s.open()
        assertEquals(0, s.index)
    }

    @Test
    fun theArtifactsListOpensByCursorOrNumberAndAnyOtherKeyCloses() {
        val s = ArtifactsState()
        val opened = ArrayList<Int>()
        s.handle(Key.Down, 3, opened::add); s.handle(Key.Down, 3, opened::add); s.handle(Key.Down, 3, opened::add)
        assertEquals(2, s.index)
        s.handle(Key.Enter, 3, opened::add); s.handle(Key.Text('1'), 3, opened::add)
        assertEquals(listOf(2, 0), opened)
        assertFalse(s.handle(Key.Text('x'), 3, opened::add))
    }
}
