package com.arcana.brain.core

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

    /** One line of the list: `created  Notes/Plan.md  (create_note)`. */
    fun line(a: Artifact): String = "${a.action.padEnd(14)} ${a.path}"

    /** The pill text for the status bar: `✦ 3 changed` (several calls on one path count once). */
    fun pill(list: List<Artifact>, plain: Boolean): String? =
        list.map { it.path }.distinct().size.takeIf { it > 0 }?.let { "${if (plain) "*" else "✦"} $it changed" }
}
