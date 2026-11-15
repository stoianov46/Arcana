package com.arcana.brain.core

/**
 * A prompt the user wrote once and runs again and again: one note in the vault's [PromptTemplates.FOLDER] folder (so it is edited in Obsidian too).
 * The note's text is the prompt; `description` (a property) is the one line shown in the list; `skill` (a property, a skill's name) is a skill that is attached to the
 * chat the template runs in (blank = none). A `{{name}}` in the text is a blank to fill when it runs.
 */
data class PromptTemplate(val path: String, val name: String, val description: String, val body: String, val skill: String = "") {
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
        return PromptTemplate(path, name, description.replace(Regex("\\s+"), " ").trim().take(120), body, skill)
    }

    /** Every template in [FOLDER] (and its sub-folders), by name; an unreadable note is left out. */
    fun list(vault: Vault): List<PromptTemplate> =
        vault.notes().filter { it.path.startsWith("$FOLDER/") }
            .mapNotNull { runCatching { parse(it.path, vault.read(it.path)) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    /** The text of a note for [body] (and an optional one-line [description]): the properties block, then the prompt. */
    fun noteText(body: String, description: String = "", skill: String = ""): String =
        "---\ntype: prompt\n" + (if (description.isBlank()) "" else "description: ${description.replace('\n', ' ').trim()}\n") + (if (skill.isBlank()) "" else "skill: ${skill.trim().removePrefix("/")}\n") + "---\n" + body.trim() + "\n"

    /** Attaches the skill [skill] to the template at [path] (its `skill` property); a blank name removes it. Nothing else in the note changes. */
    fun setSkill(vault: Vault, path: String, skill: String) {
        vault.setProperties(path, "skill: ${skill.trim().removePrefix("/")}")
    }

    /** Makes a new template named [name] (a number is added when the name is taken); returns its path. */
    fun create(vault: Vault, name: String, body: String = "", description: String = "", skill: String = ""): String {
        require(name.isNotBlank()) { "Give the template a name." }
        val note = vault.create(FOLDER, name, "", model = "")
        vault.write(note.path, noteText(body, description, skill))
        return note.path
    }
}
