package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path

/** A file or note one of the model's tools wrote, changed, moved or deleted in this chat: [path], what happened ([action]), the [tool] and when. */
data class Artifact(val path: String, val action: String, val tool: String, val at: Long)

/** Which tool calls leave something behind (see the Chat status bar pill and `/artifacts`). */
object Artifacts {
    /**
     * The artifact a finished tool call produced, or null when it produced none (a read, a search, a command, a failed call). [arg] reads a string argument of the call;
     * [result] is the tool's answer, which names the path of a created note.
     */
    fun of(tool: String, arg: (String) -> String, result: String, at: Long): Artifact? {
        if (result.startsWith("Error:")) return null
        fun art(path: String, action: String) = path.trim().takeIf { it.isNotEmpty() }?.let { Artifact(it, action, tool, at) }
        return when (tool) {
            "create_note" -> art(result.removePrefix("Created ").substringBefore(" ("), "created")
            "write_file" -> art(arg("path"), if (result.startsWith("Replaced")) "replaced" else "created")
            "edit_file" -> art(arg("path"), "edited")
            "append_to_note" -> art(arg("path"), "appended")
            "set_properties" -> art(arg("path"), "properties set")
            "add_tags" -> art(arg("path"), "tagged")
            "delete_path" -> art(arg("path"), "deleted")
            "move_path" -> art(arg("to"), "moved")
            else -> null
        }
    }

    /** One file of the list: its newest [artifact] and how many tool calls touched it ([times]). */
    data class Entry(val artifact: Artifact, val times: Int)

    /** The list for the screen: one entry per path (several calls on one file count once), newest first; the action is the last one done to it. */
    fun entries(list: List<Artifact>): List<Entry> =
        list.asReversed().groupBy { it.path }.values.map { Entry(it.first(), it.size) }

    /**
     * The path to hand to the Library when [a] is a note it can open: a Markdown file that is still there and lies inside the vault at [root]
     * (the path may be relative to the vault, `~/…` or absolute). Null for anything else (a deleted note, a file outside the vault, a non-note).
     */
    fun libraryNote(a: Artifact, root: Path): String? = runCatching {
        if (a.action == "deleted" || !a.path.endsWith(".md", ignoreCase = true)) return null
        val home = Path.of(System.getProperty("user.home"))
        val file = when {
            a.path.startsWith("~/") -> home.resolve(a.path.removePrefix("~/"))
            else -> root.resolve(a.path)
        }.toAbsolutePath().normalize()
        val base = root.toAbsolutePath().normalize()
        if (!file.startsWith(base) || !Files.isRegularFile(file)) return null
        if (base.relativize(file).any { it.toString().startsWith(".") }) return null
        base.relativize(file).joinToString("/")
    }.getOrNull()

    /** One line of the list: `created  Notes/Plan.md  (create_note)`. */
    fun line(a: Artifact): String = "${a.action.padEnd(14)} ${a.path}"

    /** The pill text for the status bar: `✦ 3 changed` (several calls on one path count once). */
    fun pill(list: List<Artifact>, plain: Boolean): String? =
        list.map { it.path }.distinct().size.takeIf { it > 0 }?.let { "${if (plain) "*" else "✦"} $it changed" }
}
