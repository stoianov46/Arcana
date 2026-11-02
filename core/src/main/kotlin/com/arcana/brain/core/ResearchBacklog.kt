package com.arcana.brain.core

import java.nio.file.Files
import java.time.LocalDate

/**
 * One line of the research backlog.
 *
 * @property line the line's index in the note (0-based), valid until the note changes
 * @property question the question, without the prefix a capture typed and without the date
 * @property date `YYYY-MM-DD` the question was captured
 * @property done the box is ticked
 * @property link the `[[report]]` it was answered by, as written, or null
 */
data class BacklogItem(val line: Int, val question: String, val date: String, val done: Boolean, val link: String?)

/**
 * The research backlog: the vault note `Research/Research Backlog.md`, a checklist of questions to research. A thought captured with a `? ` or `research: `
 * prefix is added here ([capturedQuestion], [add]); the Brain screen lists the open ones and starts the research; a finished report whose `question:` property
 * is the same text ticks its line by itself ([reconcile]). The note is plain Markdown the user can edit; only lines of the form
 * `- [ ] question (YYYY-MM-DD)` and `- [x] question (YYYY-MM-DD) -> [[report]]` are read.
 */
object ResearchBacklog {
    const val PATH = "Research/Research Backlog.md"
    const val TITLE = "Research Backlog"
    private const val FOOTER = "Back to [[Home]]"
    private val LINE = Regex("""^- \[([ xX])] (.*?) \((\d{4}-\d{2}-\d{2})\)(?: -> (\[\[.+]]))?\s*$""")
    private val PREFIX = Regex("""^(\?|research:)\s+""", RegexOption.IGNORE_CASE)

    private fun header() = "---\ntype: backlog\ntags: [index, research]\n---\n\n# $TITLE\n\n" +
        "Questions to research, one per line. A thought captured with `? ` or `research: ` in front lands here (`Ctrl+N`); a finished report whose `question:` " +
        "property is the same text ticks its line; start one from the Brain screen (`b`).\n\n"

    /** The question of a captured thought, when it starts with `? ` or `research: ` (any case); null for an ordinary thought or an empty question. */
    fun capturedQuestion(thought: String): String? {
        val t = thought.trim()
        val m = PREFIX.find(t) ?: return null
        return t.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }
    }

    /** A question as it is compared: lower case, spaces collapsed, a trailing `?` or `.` dropped. */
    fun normalize(question: String): String = question.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('?', '.', ' ')

    /** All lines of the note, done and open, in the note's order; empty when it does not exist. */
    fun items(vault: Vault): List<BacklogItem> {
        val file = vault.root.resolve(PATH)
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file).mapIndexedNotNull { i, l ->
            LINE.find(l)?.let { m -> BacklogItem(i, m.groupValues[2], m.groupValues[3], m.groupValues[1] != " ", m.groups[4]?.value) }
        }
    }

    /** The questions not yet answered, in the order of the note (oldest first). */
    fun open(vault: Vault): List<BacklogItem> = items(vault).filter { !it.done }

    /**
     * Adds `- [ ] [question] ([date])` above the closing "Back to" line, creating the note when it is missing. A question that is already there (open or done,
     * compared by [normalize]) is not added twice. Returns true when a line was added; throws on an I/O error (the caller treats the backlog as best effort).
     */
    fun add(vault: Vault, question: String, date: LocalDate = LocalDate.now()): Boolean {
        val q = question.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return false
        val file = vault.root.resolve(PATH)
        val line = "- [ ] $q ($date)"
        if (!Files.exists(file)) {
            Files.createDirectories(file.parent)
            Files.writeString(file, header() + line + "\n\n" + FOOTER + "\n")
            return true
        }
        if (items(vault).any { normalize(it.question) == normalize(q) }) return false
        val text = Files.readString(file)
        val at = text.lastIndexOf("\n$FOOTER")
        val body = (if (at >= 0) text.substring(0, at) else text).trimEnd('\n')
        val tail = if (at >= 0) text.substring(at).trimStart('\n') else FOOTER + "\n"
        Files.writeString(file, "$body\n$line\n\n$tail")
        return true
    }

    /**
     * Ticks [item]: `- [ ]` becomes `- [x]` and, with a [link] (a note title), ` -> [[link]]` is added. The line is found by its index, else by its question,
     * so a note edited in between still works. Returns false when the line is gone or already ticked.
     */
    fun tick(vault: Vault, item: BacklogItem, link: String? = null): Boolean {
        val file = vault.root.resolve(PATH)
        if (!Files.isRegularFile(file)) return false
        val lines = Files.readAllLines(file).toMutableList()
        fun matches(i: Int) = i in lines.indices && LINE.find(lines[i])?.let { it.groupValues[1] == " " && normalize(it.groupValues[2]) == normalize(item.question) } == true
        val at = if (matches(item.line)) item.line else lines.indices.firstOrNull { matches(it) } ?: return false
        val m = LINE.find(lines[at])!!
        val clean = link?.trim()?.removePrefix("[[")?.removeSuffix("]]")?.takeIf { it.isNotEmpty() }
        lines[at] = "- [x] ${m.groupValues[2]} (${m.groupValues[3]})" + (clean?.let { " -> [[$it]]" } ?: "")
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return true
    }

    /**
     * Ticks every open item that a finished report answers: a note with `type: report`, `status: done` and a `question:` property equal to the item's question
     * ([normalize]: trimmed, any case). The newest such report is linked. Returns how many items were ticked.
     */
    fun reconcile(vault: Vault): Int {
        val open = open(vault)
        if (open.isEmpty()) return 0
        val done = Reports.scan(vault).filter { it.status.equals("done", ignoreCase = true) && it.question.isNotBlank() } // newest first
        var ticked = 0
        // ticking changes no line index (a line stays a line), so the indexes of the other items stay valid
        for (item in open) {
            val report = done.firstOrNull { normalize(it.question) == normalize(item.question) } ?: continue
            if (tick(vault, item, report.title)) ticked++
        }
        return ticked
    }
}
