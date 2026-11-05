package com.arcana.brain.app

import com.arcana.brain.app.screens.Editor
import com.arcana.brain.app.screens.PlaceKind
import com.arcana.brain.app.screens.placeRows
import com.arcana.brain.app.screens.toggledPlaces
import com.arcana.brain.core.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The folder-list editor of Settings (Search in, Allowed folders): which rows it shows and what switching one off or on does to the stored list. */
class FolderListEditorTest {
    private val editor = Editor.Folders(
        "Search in", "x", { it.searchIn }, { c, v -> c.copy(searchIn = v) },
        specials = listOf("vault" to "your notes", "working" to "the Working folder"), check = { _, raw -> if (raw.startsWith("/nowhere")) "⚠ no such folder" else "searched" },
    )

    @Test
    fun theRowsAreTheSpecialsOnOrOffThenTheFoldersThenAddAFolder() {
        val rows = placeRows(AppConfig(searchIn = "vault; ~/Dev Resources; /nowhere/x"), editor)
        assertEquals(listOf("vault", "working", "~/Dev Resources", "/nowhere/x", ""), rows.map { it.token })
        assertEquals(listOf(PlaceKind.Special, PlaceKind.Special, PlaceKind.Folder, PlaceKind.Folder, PlaceKind.Add), rows.map { it.kind })
        assertEquals(listOf(true, false), rows.take(2).map { it.on }, "working is shown, switched off")
        assertEquals(listOf("searched", "⚠ no such folder"), rows.subList(2, 4).map { it.meta }, "each folder says what is wrong with it")
    }

    @Test
    fun switchingASpecialKeepsTheOrderAndTheFolders() {
        val rows = placeRows(AppConfig(searchIn = "vault; ~/Dev"), editor)
        val specials = editor.specials.map { it.first }
        assertEquals("vault; working; ~/Dev", toggledPlaces(specials, rows, "working"), "working on: in its place, before the folders")
        assertEquals("~/Dev", toggledPlaces(specials, rows, "vault"), "vault off: only the folder is left")
        val off = placeRows(AppConfig(searchIn = "~/Dev"), editor)
        assertEquals("vault; ~/Dev", toggledPlaces(specials, off, "vault"), "vault back on, in front")
    }

    @Test
    fun anAllowedFoldersListHasNoSpecialsAndOnlyFolders() {
        val allowed = Editor.Folders("Allowed folders", "x", { it.toolFolders }, { c, v -> c.copy(toolFolders = v) })
        val rows = placeRows(AppConfig(toolFolders = "~/; ~/Documents"), allowed)
        assertEquals(listOf("~/", "~/Documents", ""), rows.map { it.token })
        assertEquals(listOf(PlaceKind.Folder, PlaceKind.Folder, PlaceKind.Add), rows.map { it.kind })
    }
}
