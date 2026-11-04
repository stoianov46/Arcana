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
 * @property id the line's stable marker (`^k3x9qz` at its end, an Obsidian block id): a report started from the line carries it as `backlog_id`, so the two stay linked when the question text is edited; null for a line written by hand
 */
data class BacklogItem(val line: Int, val question: String, val date: String, val done: Boolean, val link: String?, val id: String? = null)

/**
 * The research backlog: the vault note `Research/Research Backlog.md`, a checklist of questions to research. A thought captured with a `? ` or `research: `
 * prefix is added here ([capturedQuestion], [add]); the Brain screen lists the open ones and starts the research; a finished report whose `question:` (or `backlog:`) property
 * is the same text ticks its line by itself ([reconcile]). The note is plain Markdown the user can edit; only lines of the form
 * `- [ ] question (YYYY-MM-DD)` and `- [x] question (YYYY-MM-DD) -> [[report]]` are read (`→`, which a note editor may turn `->` into, counts as the arrow).
 */
object ResearchBacklog {
    const val PATH = "Research/Research Backlog.md"
    const val TITLE = "Research Backlog"
    private const val FOOTER = "Back to [[Home]]"
    private val LINE = Regex("""^- \[([ xX])] (.*?) \((\d{4}-\d{2}-\d{2})\)(?: (?:->|→) (\[\[.+]]))?(?: \^([A-Za-z0-9-]+))?\s*$""")
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

    /**
     * A new marker for a line, readable and sorted by time: `q-` and the day and month of the line's date, then the hour and minute it is made (`q-0411-1548`, written `^q-0411-1548`
     * at the end of the line, an Obsidian block id). A clash with a marker already in the note (same minute, or the same day a year ago) gets a letter: `q-0411-1548b`.
     */
    internal fun newId(date: LocalDate, taken: Set<String>, now: java.time.LocalTime = java.time.LocalTime.now()): String {
        val base = "q-%02d%02d-%02d%02d".format(date.dayOfMonth, date.monthValue, now.hour, now.minute)
        if (base !in taken) return base
        return ('b'..'z').map { base + it }.firstOrNull { it !in taken } ?: (base + System.nanoTime() % 100000)
    }

    /** The markers in use in [lines]. */
    private fun markers(lines: List<String>): Set<String> = lines.mapNotNull { LINE.find(it)?.groups?.get(5)?.value }.toSet()

    /** The link to a marker, for a report's `backlog` property: `[[Research Backlog#^q-0411-1548]]` opens that very line in Obsidian and lists the report among its backlinks. */
    fun linkTo(id: String): String = "[[$TITLE#^$id]]"

    /**
     * The marker of [item], written into its line first when it has none (a line typed by hand or captured before markers existed). The Brain screen calls it when it starts a research,
     * so the report can carry it. Returns null when the line is gone.
     */
    fun ensureId(vault: Vault, item: BacklogItem): String? {
        item.id?.let { return it }
        val file = vault.root.resolve(PATH)
        if (!Files.isRegularFile(file)) return null
        val lines = Files.readAllLines(file).toMutableList()
        fun matches(i: Int) = i in lines.indices && LINE.find(lines[i])?.let { normalize(it.groupValues[2]) == normalize(item.question) && it.groups[5] == null } == true
        val at = if (matches(item.line)) item.line else lines.indices.firstOrNull { matches(it) } ?: return null
        val id = newId(runCatching { LocalDate.parse(LINE.find(lines[at])!!.groupValues[3]) }.getOrDefault(LocalDate.now()), markers(lines))
        lines[at] = lines[at].trimEnd() + " ^$id"
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return id
    }

    /** A question as it is compared: lower case, spaces collapsed, a trailing `?` or `.` dropped. */
    fun normalize(question: String): String = question.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('?', '.', ' ')

    /** All lines of the note, done and open, in the note's order; empty when it does not exist. */
    fun items(vault: Vault): List<BacklogItem> {
        val file = vault.root.resolve(PATH)
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file).mapIndexedNotNull { i, l ->
            LINE.find(l)?.let { m -> BacklogItem(i, m.groupValues[2], m.groupValues[3], m.groupValues[1] != " ", m.groups[4]?.value, m.groups[5]?.value) }
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
        val line = "- [ ] $q ($date) ^${newId(date, if (Files.exists(file)) markers(Files.readAllLines(file)) else emptySet())}"
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
        lines[at] = "- [x] ${m.groupValues[2]} (${m.groupValues[3]})" + (clean?.let { " -> [[$it]]" } ?: "") + (m.groups[5]?.value?.let { " ^$it" } ?: "")
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
            // a report answers a line when its `question` says the same, or when Arcana recorded that it was started from this very line (`backlog`)
            // by the marker first (it survives an edited question), then by what the report says it answers
            val report = done.firstOrNull { item.id != null && it.backlogId == item.id }
                ?: done.firstOrNull { normalize(it.question) == normalize(item.question) || (it.backlog.isNotBlank() && normalize(it.backlog) == normalize(item.question)) } ?: continue
            if (tick(vault, item, report.title)) ticked++
        }
        return ticked
    }
}
