package com.arcana.brain.core

import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the model proposes for one inbox note, as it arrives (raw, before it is checked against the vault). */
@Serializable
data class RawProposal(
    val title: String = "",
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val folder: String = "",
)

/** A proposal that has been checked against the vault: real notes, tidy tags, an existing folder. */
data class InboxPlan(
    val title: String,
    val folder: String,
    val tags: List<String>,
    /** Titles of notes to link to, all of which exist. */
    val links: List<String>,
    val summary: String,
)

/** The outcome of processing one note: where it is now, and where its previous version was kept. */
data class ProcessedNote(val path: String, val backup: String)

object InboxProcessing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** The messages that ask for a proposal as one JSON object, from what the vault already contains. */
    fun prompt(config: AppConfig, title: String, text: String, tagsInUse: List<String>, noteTitles: List<String>, folders: List<String>): List<ChatMessage> = listOf(
        ChatMessage.system(config.systemPrompt),
        ChatMessage.user(
            "You help file a new note into an Obsidian vault. Reply with ONE JSON object and nothing else, with these keys:\n" +
                "- \"title\": a short, clear title for the note, in the note's own language (no slashes or colons)\n" +
                "- \"summary\": one sentence saying what the note is about\n" +
                "- \"tags\": up to 4 tags without the # sign, preferably from the existing tags\n" +
                "- \"links\": up to 4 titles of existing notes it relates to, copied exactly from the list\n" +
                "- \"folder\": the best existing folder for it, copied exactly from the list, or \"\" if none fits\n\n" +
                "Existing tags: ${tagsInUse.take(40).joinToString(", ").ifEmpty { "(none)" }}\n" +
                "Existing notes: ${noteTitles.take(80).joinToString("; ").ifEmpty { "(none)" }}\n" +
                "Existing folders: ${folders.take(40).joinToString("; ").ifEmpty { "(none)" }}\n\n" +
                "The note \"$title\":\n---\n${text.take(6000)}",
        ),
    )

    /**
     * Reads the model's reply and checks it against the vault: the JSON object is found even when the model wrapped it in
     * text, links must be real notes ([noteTitles] by title), the folder must be one of [folders], tags are tidied.
     * Returns null when no usable object is in the reply.
     */
    fun parse(reply: String, fallbackTitle: String, noteTitles: Collection<String>, folders: Collection<String>): InboxPlan? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val raw = runCatching { json.decodeFromString<RawProposal>(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        val byLower = noteTitles.associateBy { it.lowercase() }
        val folderByLower = folders.associateBy { it.trim('/').lowercase() }
        return InboxPlan(
            title = Vault.sanitizeTitle(raw.title).takeUnless { it == "Untitled" && raw.title.isBlank() } ?: fallbackTitle,
            folder = folderByLower[raw.folder.trim('/').lowercase()] ?: "",
            tags = raw.tags.map { it.trim().removePrefix("#").replace(Regex("\\s+"), "-") }.filter { it.isNotEmpty() }.distinct().take(6),
            links = raw.links.mapNotNull { byLower[it.trim().removePrefix("[[").removeSuffix("]]").lowercase()] }.distinct().take(6),
            summary = raw.summary.trim().replace('\n', ' '),
        )
    }
}

/** [front] (a frontmatter block, or empty) with [tags] merged into its `tags:` line; a block is created when there is none. */
fun mergeFrontmatterTags(front: String, tags: List<String>): String {
    if (tags.isEmpty()) return front
    if (front.isBlank()) return "---\ntags: [${tags.joinToString(", ")}]\n---\n\n"
    val lines = front.trimEnd('\n').lines()
    val close = lines.indexOfLast { it.trim() == "---" }.takeIf { it > 0 } ?: return front
    val middle = lines.subList(1, close).toMutableList()
    val existing = VaultIndex.frontmatterTags(front)
    val at = middle.indexOfFirst { it.trimStart().startsWith("tags:") || it.trimStart().startsWith("tag:") }
    if (at >= 0) {
        // the old line goes, together with a block list that follows it
        var end = at + 1
        while (end < middle.size && middle[end].trimStart().startsWith("-")) end++
        repeat(end - at) { middle.removeAt(at) }
    }
    middle += "tags: [${(existing + tags).distinct().joinToString(", ")}]"
    return "---\n" + middle.joinToString("\n") + "\n---\n\n"
}

/**
 * Applies [plan] to the inbox note [path]: tags go into its frontmatter, the summary becomes a callout at the top, links to
 * related notes are added under a "Related" heading, and the note is renamed and moved to the folder. The version before
 * is first copied into the vault's `.trash`, so nothing is lost. With [keepName] the file name stays (when other notes link
 * to it by name, renaming would break those links).
 */
fun Vault.processInboxNote(path: String, plan: InboxPlan, keepName: Boolean = false): ProcessedNote {
    val file = root.resolve(path).normalize()
    if (!file.startsWith(root) || !Files.isRegularFile(file)) throw VaultException("Note does not exist: $path")
    val old = Files.readString(file)
    val trash = root.resolve(".trash")
    Files.createDirectories(trash)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
    val backup = trash.resolve("${file.name.removeSuffix(".md")} (backup $stamp).md")
    Files.writeString(backup, old)

    val (front, body) = Vault.splitFrontmatter(old)
    var text = body.trim()
    if (plan.summary.isNotBlank() && "[!summary]" !in text) text = "> [!summary] ${plan.summary.trim()}\n\n$text"
    val missing = plan.links.filter { "[[$it]]" !in text && "[[$it|" !in text }
    if (missing.isNotEmpty()) text += "\n\n## Related\n\n" + missing.joinToString("\n") { "- [[$it]]" }
    val content = mergeFrontmatterTags(front, plan.tags) + text + "\n"

    val name = if (keepName) file.name.removeSuffix(".md") else Vault.sanitizeTitle(plan.title)
    val dir = if (plan.folder.isBlank()) file.parent else root.resolve(plan.folder.trim('/')).normalize().also {
        if (!it.startsWith(root)) throw VaultException("Folder escapes the vault: ${plan.folder}")
    }
    Files.createDirectories(dir)
    var target = dir.resolve("$name.md")
    var n = 2
    while (Files.exists(target) && target != file) target = dir.resolve("$name $n.md").also { n++ }
    Files.writeString(target, content)
    if (target != file) Files.delete(file)
    return ProcessedNote(target.relativeTo(root).toString().replace('\\', '/'), backup.relativeTo(root).toString().replace('\\', '/'))
}
