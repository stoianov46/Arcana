package com.arcana.brain.app

import com.arcana.brain.app.design.DirectoryPickerState
import com.arcana.brain.app.input.Key
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The folder browser's search below the current folder (`f`), and the optional clear and type keys. */
class DirectoryPickerSearchTest {
    private fun t(c: Char) = Key.Text(c)

    /** root/a/deep/Arcana, root/b, root/.hidden/x, root/node_modules/y */
    private fun tree(): Path {
        val root = Files.createTempDirectory("dirsearch")
        for (p in listOf("a/deep/Arcana", "b", ".hidden/x", "node_modules/y")) Files.createDirectories(root.resolve(p))
        return root
    }

    private fun waitDone(p: DirectoryPickerState) { val end = System.currentTimeMillis() + 5000; while (p.scanning && System.currentTimeMillis() < end) Thread.sleep(10) }

    @Test
    fun fListsEveryFolderBelowByItsPathAndSkipsHiddenAndHeavyOnes() {
        val p = DirectoryPickerState(tree(), onChoose = {}, onCancel = {})
        p.handle(t('f'))
        waitDone(p)
        assertTrue(p.searching && p.filtering, "typing narrows at once")
        assertEquals(listOf("a", "a/deep", "a/deep/Arcana", "b"), p.entries.map { it.name }.sorted(), "hidden folders and node_modules are left out")
    }

    @Test
    fun typingNarrowsEnterOpensAndSpacePicksTheHighlightedFolder() {
        val root = tree()
        var chosen: Path? = null
        val p = DirectoryPickerState(root, onChoose = { chosen = it }, onCancel = {})
        p.handle(t('f')); waitDone(p)
        "arcana".forEach { p.handle(t(it)) }
        assertEquals(listOf("a/deep/Arcana"), p.picker.visible(p.entries) { it.name }.map { it.name })
        p.handle(Key.Enter)
        assertFalse(p.searching, "Enter opens the found folder and leaves the search")
        assertEquals(root.resolve("a/deep/Arcana").toRealPath(), p.dir.toRealPath())
        p.handle(t(' '))
        assertEquals(root.resolve("a/deep/Arcana").toRealPath(), chosen!!.toRealPath(), "Space then uses the folder that is shown")

        // from the search itself: Esc clears the filter, Space (not typing) picks the highlighted one
        val q = DirectoryPickerState(root, onChoose = { chosen = it }, onCancel = {})
        q.handle(t('f')); waitDone(q)
        q.handle(Key.Escape)
        assertTrue(q.searching && !q.filtering)
        q.handle(t(' '))
        assertEquals(root.resolve("a").toRealPath(), chosen!!.toRealPath())
    }

    @Test
    fun escGoesBackFromTheSearchBeforeItCancels() {
        var cancelled = false
        val p = DirectoryPickerState(tree(), onChoose = {}, onCancel = { cancelled = true })
        p.handle(t('f')); waitDone(p)
        p.handle(Key.Escape) // clears the filter
        p.handle(Key.Escape) // leaves the search
        assertFalse(p.searching); assertFalse(cancelled)
        p.handle(Key.Escape)
        assertTrue(cancelled)
    }

    @Test
    fun clearAndTypeKeysExistOnlyWhenGiven() {
        val plain = DirectoryPickerState(tree(), onChoose = {}, onCancel = {})
        assertFalse(plain.handle(t('x')) || plain.handle(t('e')))
        assertTrue(plain.hints().none { it.startsWith("x ") || it.startsWith("e ") })
        var cleared = false; var typed = false
        val p = DirectoryPickerState(tree(), onChoose = {}, onCancel = {}, onClear = { cleared = true }, clearLabel = "No working folder", onType = { typed = true })
        assertTrue(p.handle(t('x')) && p.handle(t('e')))
        assertTrue(cleared && typed)
        assertTrue("x  No working folder" in p.hints() && "e  type a path" in p.hints() && "f  search subfolders" in p.hints())
        assertNull(null)
    }
}
