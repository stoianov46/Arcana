package com.arcana.brain.core

/**
 * A prompt the user wrote once and runs again and again: one note in the vault's [PromptTemplates.FOLDER] folder (so it is edited in Obsidian too).
 * The note's text is the prompt; `description` (a property) is the one line shown in the list; `skill` (a property, a skill's name) is a skill and `notes` (a property, a list of note paths) are notes that are attached to the
 * chat the template runs in (blank = none). A `{{name}}` in the text is a blank to fill when it runs.
 */
data class PromptTemplate(val path: String, val name: String, val description: String, val body: String, val skill: String = "", val notes: List<String> = emptyList()) {
    /** The blanks to fill, in the order they first appear, each once. */
    val placeholders: List<String> get() = PromptTemplates.placeholders(body)
}

/** The prompt templates of the vault: reading them, the blanks in them, and writing a new one. */
object PromptTemplates {
    /** The vault folder that holds the templates (a visible one, so they can be edited in Obsidian). */
    const val FOLDER = "Prompts"

    private val PLACEHOLDER = Regex("""\{\{\s*([^{}\n]+?)\s*\}\}""")

    /** The blanks in [body] (`{{topic}}`), in order of appearance, each once. */
    fun placeholders(body: String): List<String> = PLACEHOLDER.findAll(body).map { it.groupValues[1] }.distinct().toList()

    /** [body] with each blank replaced by its value; a blank without a value stays as written. */
    fun fill(body: String, values: Map<String, String>): String = PLACEHOLDER.replace(body) { m -> values[m.groupValues[1]] ?: m.value }

    /** One template from the text of its note: the prompt is the text after the properties block. */
    fun parse(path: String, text: String): PromptTemplate {
        val name = path.substringAfterLast('/').removeSuffix(".md")
        val body = Vault.splitFrontmatter(text).second.trim()
        val description = Skills.frontmatter(text)["description"].orEmpty().ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
        val skill = Skills.frontmatter(text)["skill"].orEmpty().trim().removePrefix("/")
        return PromptTemplate(path, name, description.replace(Regex("\\s+"), " ").trim().take(120), body, skill, parseNotes(Skills.frontmatter(text)["notes"].orEmpty()))
    }

    /** Every template in [FOLDER] (and its sub-folders), by name; an unreadable note is left out. */
    fun list(vault: Vault): List<PromptTemplate> =
        vault.notes().filter { it.path.startsWith("$FOLDER/") }
            .mapNotNull { runCatching { parse(it.path, vault.read(it.path)) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    /** The text of a note for [body] (and an optional one-line [description]): the properties block, then the prompt. */
    fun noteText(body: String, description: String = "", skill: String = ""): String =
        "---\ntype: prompt\n" + (if (description.isBlank()) "" else "description: ${description.replace('\n', ' ').trim()}\n") + (if (skill.isBlank()) "" else "skill: ${skill.trim().removePrefix("/")}\n") + "---\n" + body.trim() + "\n"

    /** The note paths of a `notes:` value: `[A.md, B/C.md]` (brackets, quotes and `[[links]]` are dropped), in order, each once. */
    fun parseNotes(value: String): List<String> =
        value.trim().removePrefix("[").removeSuffix("]").split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'").removePrefix("[[").removeSuffix("]]").trim() }.filter { it.isNotEmpty() }.distinct()

    private fun edit(text: String, change: (LinkedHashMap<String, List<String>>) -> Unit): String {
        val (block, rest) = Vault.splitFrontmatter(text)
        val properties = if (block.isEmpty()) LinkedHashMap() else Vault.parseProperties(Vault.propertyText(block))
        change(properties)
        return if (properties.isEmpty()) rest.trimStart('\n') else Vault.renderProperties(properties) + rest
    }

    /** The text of a template note with its skill set to [skill] (blank removes the property); the prompt and the other properties stay. For the editor, which holds the text before it is saved. */
    fun withSkill(text: String, skill: String): String = edit(text) { p -> if (skill.isBlank()) p.remove("skill") else p["skill"] = listOf("skill: ${skill.trim().removePrefix("/")}") }

    /** The text with [path] added to its `notes` property (once). */
    fun withNote(text: String, path: String): String = edit(text) { p ->
        val now = parseNotes(p["notes"]?.firstOrNull()?.substringAfter(':').orEmpty())
        if (path !in now) p["notes"] = listOf("notes: [" + (now + path).joinToString(", ") + "]")
    }

    /** The text with the skill and the notes removed. */
    fun withoutAttachments(text: String): String = edit(text) { p -> p.remove("skill"); p.remove("notes") }

    /** Attaches the skill [skill] to the template at [path] (its `skill` property); a blank name removes it. Nothing else in the note changes. */
    fun setSkill(vault: Vault, path: String, skill: String) {
        vault.write(path, withSkill(vault.read(path), skill))
    }

    /** Adds the note [note] to the template at [path] (its `notes` property). */
    fun addNote(vault: Vault, path: String, note: String) {
        vault.write(path, withNote(vault.read(path), note))
    }

    /** Removes the skill and the notes of the template at [path]. */
    fun clearAttachments(vault: Vault, path: String) {
        vault.write(path, withoutAttachments(vault.read(path)))
    }

    /** Makes a new template named [name] (a number is added when the name is taken); returns its path. */
    fun create(vault: Vault, name: String, body: String = "", description: String = "", skill: String = ""): String {
        require(name.isNotBlank()) { "Give the template a name." }
        val note = vault.create(FOLDER, name, "", model = "")
        vault.write(note.path, noteText(body, description, skill))
        return note.path
    }
}
