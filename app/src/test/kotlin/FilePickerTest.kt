package com.arcana.brain.app

import com.arcana.brain.app.design.FilePickerState
import com.arcana.brain.app.input.Key
import com.arcana.brain.core.ImageFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePickerTest {
    private fun t(c: Char) = Key.Text(c)

    /** A folder with: sub/ (holding deep.png), a.png, b.jpg, c.webp, notes.txt, .hidden.png */
    private fun tree(): Path {
        val dir = Files.createTempDirectory("pick")
        Files.createDirectories(dir.resolve("sub"))
        Files.write(dir.resolve("sub/deep.png"), byteArrayOf(1))
        for (name in listOf("a.png", "b.jpg", "c.webp", "notes.txt", ".hidden.png")) Files.write(dir.resolve(name), byteArrayOf(1, 2, 3))
        return dir
    }

    private class Result {
        var done: List<Path>? = null
        var cancelled = false
        var folder: Path? = null
    }

    private fun picker(dir: Path, limit: Int = 5, result: Result = Result()) = FilePickerState(
        dir, ImageFiles::isImage, limit,
        onDone = { result.done = it }, onCancel = { result.cancelled = true }, onFolder = { result.folder = it },
    )

    @Test
    fun listsParentFoldersThenOnlyImages() {
        val p = picker(tree())
        assertEquals(listOf("..", "sub", "a.png", "b.jpg", "c.webp"), p.entries.map { it.name }, "hidden files and non-images are left out")
        p.handle(t('.'))
        assertTrue(p.entries.any { it.name == ".hidden.png" }, "'.' shows hidden files")
    }

    @Test
    fun tickingAndChoosingWithTab() {
        val result = Result()
        val dir = tree()
        val p = picker(dir, limit = 2, result = result)
        p.handle(Key.Down); p.handle(Key.Down)           // on a.png (.. and sub come first)
        p.handle(t(' '))
        p.handle(Key.Down); p.handle(t(' '))             // b.jpg
        assertEquals(listOf("a.png", "b.jpg"), p.checked.map { it.fileName.toString() })
        p.handle(Key.Down); p.handle(t(' '))             // c.webp: over the limit
        assertEquals(2, p.checked.size)
        assertTrue(p.notice!!.contains("at most 2"))
        p.handle(Key.Tab)
        assertEquals(listOf("a.png", "b.jpg"), result.done?.map { it.fileName.toString() })
        assertEquals(dir.toAbsolutePath().normalize(), result.folder, "the folder is remembered")
    }

    @Test
    fun enterOnAFileChoosesItTogetherWithTheTickedOnes() {
        val result = Result()
        val p = picker(tree(), result = result)
        p.handle(Key.Down); p.handle(Key.Down); p.handle(t(' '))   // tick a.png
        p.handle(Key.Down)                                          // b.jpg
        p.handle(Key.Enter)
        assertEquals(listOf("a.png", "b.jpg"), result.done?.map { it.fileName.toString() })
    }

    @Test
    fun enterOnAFolderOpensItAndLeftGoesBackUp() {
        val dir = tree()
        val p = picker(dir)
        p.handle(Key.Down)                                          // sub
        p.handle(Key.Enter)
        assertEquals(dir.resolve("sub").toAbsolutePath().normalize(), p.dir)
        assertEquals(listOf("..", "deep.png"), p.entries.map { it.name })
        p.handle(Key.Left)
        assertEquals(dir.toAbsolutePath().normalize(), p.dir)
        assertEquals("sub", p.entries[p.picker.index].name, "the folder we came from is highlighted")
    }

    @Test
    fun tabWithNothingTickedExplainsInsteadOfChoosing() {
        val result = Result()
        val p = picker(tree(), result = result)
        p.handle(Key.Tab)
        assertNull(result.done)
        assertTrue(p.notice!!.contains("Tick files"))
    }

    @Test
    fun escapeCancelsAndSpaceTypesIntoTheFilterWhileFiltering() {
        val result = Result()
        val p = picker(tree(), result = result)
        p.handle(t('/'))
        assertTrue(p.filtering)
        p.handle(t('j'))
        assertEquals(listOf("b.jpg"), p.picker.visible(p.entries) { e -> e.name }.map { it.name })
        assertFalse(p.checked.isNotEmpty())
        p.handle(Key.Escape)                                        // clears the filter first
        assertFalse(result.cancelled)
        p.handle(Key.Escape)
        assertTrue(result.cancelled)
    }
}
