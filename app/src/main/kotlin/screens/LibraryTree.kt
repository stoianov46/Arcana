package com.arcana.brain.app.screens

import com.arcana.brain.core.NoteInfo
import java.util.TreeMap

/** One row of the Library's tree: a folder that folds and unfolds, or a note. [depth] is how many folders deep it sits (0 = the vault root). */
internal sealed class LibraryRow {
    abstract val depth: Int

    /** A folder: [path] is its vault-relative path, [notes] the notes below it (all levels, in the current view), [folders] its direct subfolders. */
    class Folder(val path: String, val name: String, override val depth: Int, val expanded: Boolean, val notes: Int, val folders: Int) : LibraryRow()

    class Note(val info: NoteInfo, override val depth: Int) : LibraryRow()
}

/** `Skills/a/b.md` → `Skills`, `Skills/a`: the folders that must be open to see the note. */
internal fun ancestorsOf(path: String): List<String> {
    val parts = path.split('/').dropLast(1)
    return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
}

private class Node(val path: String, val name: String) {
    val folders = TreeMap<String, Node>(String.CASE_INSENSITIVE_ORDER)
    val notes = ArrayList<NoteInfo>()
    fun count(): Int = notes.size + folders.values.sumOf { it.count() }
}

/**
 * The tree of [notes] as rows to draw: in every folder the subfolders come first (A to Z), then its notes in the order [notes] has
 * (the view's own order: newest first, oldest first…). A folder is only listed when it holds a note of this view; its notes are only listed
 * when its path is in [expanded].
 */
internal fun libraryRows(notes: List<NoteInfo>, expanded: Set<String>): List<LibraryRow> {
    val root = Node("", "")
    for (note in notes) {
        var node = root
        for (part in note.path.split('/').dropLast(1)) {
            node = node.folders.getOrPut(part) { Node(if (node.path.isEmpty()) part else "${node.path}/$part", part) }
        }
        node.notes += note
    }
    val rows = ArrayList<LibraryRow>()
    fun walk(node: Node, depth: Int) {
        for (folder in node.folders.values) {
            val open = folder.path in expanded
            rows += LibraryRow.Folder(folder.path, folder.name, depth, open, folder.count(), folder.folders.size)
            if (open) walk(folder, depth + 1)
        }
        node.notes.forEach { rows += LibraryRow.Note(it, depth) }
    }
    walk(root, 0)
    return rows
}

/** Every folder path that has a note of this view below it (what "expand all" opens). */
internal fun allFolders(notes: List<NoteInfo>): Set<String> = notes.flatMap { ancestorsOf(it.path) }.toSet()
