package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.relativeTo

data class Note(val path: String, val modified: Long) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".md")
}

data class SearchHit(val note: Note, val score: Int, val excerpt: String)

class VaultException(message: String) : Exception(message)

class Vault(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()

    init {
        if (!Files.isDirectory(this.root)) throw VaultException("Vault folder does not exist: ${this.root}")
    }

    fun notes(): List<Note> = Files.walk(root).use { paths ->
        paths
            .filter { Files.isRegularFile(it) && it.extension == "md" && !isHidden(it) }
            .map { Note(it.relativeTo(root).toString().replace('\\', '/'), Files.getLastModifiedTime(it).toMillis()) }
            .toList()
    }.sortedByDescending { it.modified }

    fun read(note: String): String = Files.readString(resolve(note))

    fun create(folder: String, title: String, body: String, model: String): Note {
        val dir = if (folder.isBlank()) root else resolve(folder.trim('/'))
        Files.createDirectories(dir)
        val base = sanitizeTitle(title)
        var file = dir.resolve("$base.md")
        var n = 2
        while (Files.exists(file)) file = dir.resolve("$base $n.md").also { n++ }
        val created = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        val frontmatter = "---\ncreated: $created\ngenerator: arcana\nmodel: $model\ntags: [arcana]\n---\n\n"
        Files.writeString(file, frontmatter + body.trim() + "\n")
        return Note(file.relativeTo(root).toString().replace('\\', '/'), Files.getLastModifiedTime(file).toMillis())
    }

    /** Adds [body] to the end of [note], under a `## [heading]` line unless [heading] is null. */
    fun append(note: String, heading: String?, body: String) {
        val file = resolve(note)
        val existing = Files.readString(file)
        val separator = if (existing.endsWith("\n")) "\n" else "\n\n"
        val title = if (heading == null) "" else "## $heading\n\n"
        Files.writeString(file, existing + separator + title + body.trim() + "\n")
    }

    /**
     * Copies the picture [source] into an `attachments` folder inside [folder] (the vault root when blank) and returns the
     * file name to embed it with (`![[name]]`). A name that is taken becomes `name 2.png`.
     */
    fun copyAttachment(folder: String, source: Path): String {
        val dir = (if (folder.isBlank()) root else resolve(folder.trim('/'))).resolve("attachments")
        Files.createDirectories(dir)
        val name = source.fileName.toString()
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var target = dir.resolve(name)
        var n = 2
        while (Files.exists(target)) target = dir.resolve(if (ext.isEmpty()) "$base $n" else "$base $n.$ext").also { n++ }
        Files.copy(source, target)
        return target.fileName.toString()
    }

    /**
     * Replaces the text of [note] with [body], keeping its frontmatter (the `---` block at the top). The old version is
     * first copied into the vault's `.trash` folder, so it can always be brought back. Returns the backup's path.
     */
    fun replace(note: String, body: String): String {
        val file = resolve(note)
        if (!Files.isRegularFile(file)) throw VaultException("Note does not exist: $note")
        val old = Files.readString(file)
        val dir = root.resolve(".trash")
        Files.createDirectories(dir)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
        val backup = dir.resolve("${file.name.removeSuffix(".md")} (backup $stamp).md")
        Files.writeString(backup, old)
        val (front, _) = splitFrontmatter(old)
        // A model that was shown the whole note often hands the frontmatter back; the note's own is kept, so drop that copy.
        val newBody = splitFrontmatter(body.trim() + "\n").second
        Files.writeString(file, front + newBody.trim() + "\n")
        return backup.relativeTo(root).toString().replace('\\', '/')
    }

    /** Moves [note] into the vault's `.trash` folder (Obsidian's own trash), so a deletion can be undone. Returns the new path. */
    fun trash(note: String): String {
        val file = resolve(note)
        if (!Files.isRegularFile(file)) throw VaultException("Note does not exist: $note")
        val dir = root.resolve(".trash")
        Files.createDirectories(dir)
        val base = file.name.removeSuffix(".md")
        var target = dir.resolve("$base.md")
        var n = 2
        while (Files.exists(target)) target = dir.resolve("$base $n.md").also { n++ }
        Files.move(file, target)
        return target.relativeTo(root).toString().replace('\\', '/')
    }

    fun search(query: String, limit: Int, excerptChars: Int): List<SearchHit> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        return notes().mapNotNull { note ->
            val text = runCatching { read(note.path) }.getOrNull() ?: return@mapNotNull null
            val lowerText = text.lowercase()
            val lowerTitle = note.title.lowercase()
            var score = 0
            for (term in terms) {
                if (term in lowerTitle) score += 10
                score += minOf(countOccurrences(lowerText, term), 10)
            }
            if (score == 0) null else SearchHit(note, score, excerpt(text, lowerText, terms, excerptChars))
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun excerpt(text: String, lowerText: String, terms: List<String>, chars: Int): String {
        val first = terms.mapNotNull { lowerText.indexOf(it).takeIf { i -> i >= 0 } }.minOrNull() ?: 0
        val start = (first - chars / 4).coerceAtLeast(0)
        return text.substring(start, minOf(text.length, start + chars))
    }

    private fun resolve(relative: String): Path {
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root)) throw VaultException("Path escapes the vault: $relative")
        return path
    }

    private fun isHidden(path: Path): Boolean =
        path.relativeTo(root).any { it.name.startsWith(".") }

    companion object {
        private val invalidTitleChars = Regex("""[\\/:*?"<>|#^\[\]]""")
        private val stopWords = setOf("the", "and", "for", "with", "that", "this", "what", "how", "are", "was", "about")

        fun sanitizeTitle(title: String): String =
            title.replace(invalidTitleChars, " ").replace(Regex("\\s+"), " ").trim().take(80).trim()
                .ifEmpty { "Untitled" }

        /** The frontmatter block of [text] (with its closing line and a blank line after it) and the rest; the block is empty when there is none. */
        fun splitFrontmatter(text: String): Pair<String, String> {
            if (!text.startsWith("---\n")) return "" to text
            val end = text.indexOf("\n---", 3)
            if (end < 0) return "" to text
            val closing = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it + 1 }
            return text.substring(0, closing).trimEnd('\n') + "\n\n" to text.substring(closing).trimStart('\n')
        }

        fun tokenize(query: String): List<String> =
            query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 && it !in stopWords }
                .distinct()

        private fun countOccurrences(text: String, term: String): Int {
            var count = 0
            var i = text.indexOf(term)
            while (i >= 0) {
                count++
                i = text.indexOf(term, i + term.length)
            }
            return count
        }
    }
}

/** Things AI can do to an existing note. */
enum class NoteTask { Summarize, Improve, Continue, Translate, ActionItems, Connect, Custom }

object Prompts {
    fun summarize(config: AppConfig, noteTitle: String, noteText: String): List<ChatMessage> = listOf(
        ChatMessage.system(config.systemPrompt),
        ChatMessage.user("${config.summaryPrompt}\n\nNote title: $noteTitle\n\n---\n$noteText"),
    )

    fun ask(config: AppConfig, question: String, hits: List<SearchHit>): List<ChatMessage> {
        val context = if (hits.isEmpty()) {
            "No matching notes were found in the vault."
        } else {
            hits.joinToString("\n\n") { "### [[${it.note.title}]]\n${it.excerpt}" }
        }
        return listOf(
            ChatMessage.system(
                config.systemPrompt + " Answer using the vault excerpts below. " +
                    "Cite the notes you used as [[Note title]]. If the excerpts do not contain the answer, say so.",
            ),
            ChatMessage.user("Vault excerpts:\n\n$context\n\nQuestion: $question"),
        )
    }

    /** The messages that ask the model to do [task] to one note; [input] is the language, or the instruction for [NoteTask.Custom]. */
    fun noteTask(config: AppConfig, task: NoteTask, title: String, text: String, input: String = ""): List<ChatMessage> {
        val instruction = when (task) {
            NoteTask.Summarize -> config.summaryPrompt
            NoteTask.Improve ->
                "Improve the following note: fix spelling and grammar, make it clearer and better structured, and keep the author's meaning, " +
                    "language and Markdown formatting. Return the complete improved note and nothing else."
            NoteTask.Continue ->
                "Continue writing the following note in the same style, language and format. Return only the new text to add after it, nothing else."
            NoteTask.Translate ->
                "Translate the following note into ${input.ifBlank { "English" }}. Keep the Markdown formatting, links and code unchanged. " +
                    "Return only the translation."
            NoteTask.ActionItems ->
                "Extract every task and action item from the following note as a Markdown checklist (- [ ] task). Return only the checklist."
            NoteTask.Connect ->
                "Connect the following note to the rest of my vault. $input\n\n" +
                    "Suggest which of the existing tags fit (you may propose at most two new ones), and which of the candidate notes it should link to, " +
                    "with one short reason each. Use only tags and notes from those lists, links written as [[Note title]]. " +
                    "Return Markdown with two headings: **Tags** (a line of #tags) and **Links** (a bullet per link)."
            NoteTask.Custom -> "$input\n\nApply this to the note below. Return the result in Markdown only."
        }
        return listOf(
            ChatMessage.system(config.systemPrompt),
            ChatMessage.user("$instruction\n\nNote title: $title\n\n---\n$text"),
        )
    }

    /**
     * The messages a chat sends to the model.
     *
     * Each of your messages carries the text of the notes attached to it ([noteTexts], by vault path), and the latest one
     * also carries the excerpts found for it when the vault is being searched ([hits]; null = not searching). The model is
     * told to cite the notes it used as `[[Note title]]`.
     */
    fun chatMessages(config: AppConfig, history: List<ChatMessage>, hits: List<SearchHit>?, noteTexts: Map<String, String>): List<ChatMessage> {
        val system = config.systemPrompt + if (hits == null) "" else {
            " Answer using the vault excerpts given with the latest question. Cite the notes you used as [[Note title]]. " +
                "If the excerpts do not contain the answer, say so."
        }
        val window = history.takeLast(config.chatHistory)
        val lastUser = window.indexOfLast { it.role == "user" }
        val converted = window.mapIndexed { i, m ->
            if (m.role != "user") return@mapIndexed m
            var content = m.content
            for (name in m.noteNames) {
                val title = name.substringAfterLast('/').removeSuffix(".md")
                content += "\n\n---\nAttached note “$title” ($name):\n${noteTexts[name] ?: "(this note could not be read)"}"
            }
            if (hits != null && i == lastUser) {
                val context = if (hits.isEmpty()) "No matching notes were found in the vault." else hits.joinToString("\n\n") { "### [[${it.note.title}]]\n${it.excerpt}" }
                content = "Vault excerpts:\n\n$context\n\nQuestion: $content"
            }
            m.copy(content = content, noteNames = emptyList())
        }
        return listOf(ChatMessage.system(system)) + converted
    }

    /**
     * Asks the model to write up a session: what was done, what was decided or learned, and what is still open. It gets the
     * activity log and, for each chat of the session, its title and a trimmed transcript ([chats]).
     */
    fun sessionSummary(config: AppConfig, activity: String, chats: List<Pair<String, String>>): List<ChatMessage> {
        val transcripts = if (chats.isEmpty()) "" else chats.joinToString("\n\n") { (title, text) -> "### Chat: $title\n${text.take(6000)}" }
        return listOf(
            ChatMessage.system(config.systemPrompt),
            ChatMessage.user(
                "Write notes on the working session below, for my Obsidian vault. Use these headings: **What I did**, **What I learned or decided**, " +
                    "**Open questions**, **Next steps**. Be concrete and brief (bullet points), and keep my language. Mention notes as [[Note title]]. " +
                    "Return Markdown only.\n\n## Activity log\n$activity" + if (transcripts.isEmpty()) "" else "\n\n## Chats\n$transcripts",
            ),
        )
    }

    fun compose(config: AppConfig, request: String): List<ChatMessage> = listOf(
        ChatMessage.system(config.systemPrompt + " Write the note body in Markdown, without frontmatter."),
        ChatMessage.user(request),
    )
}
