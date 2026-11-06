package com.arcana.brain.core

import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 1 of proposals/0002: mirrors the research backlog note into the work log, without changing the note. The note is still what the
 * user sees and what [ResearchBacklog] reads and writes; this keeps the log equal to it, so that later phases can take the log as the truth.
 *
 * Every event it writes has an id made from what it describes, so running [sync] again (on every start, after any edit) writes nothing new.
 *
 * What it can and cannot see: a line is the same item when it has the same marker (`^q-0611-1650`), or the same text and date. A line without a
 * marker whose text was edited by hand looks like a new item (the old one is reported by [discrepancies] as gone from the note); Brain gives a line
 * its marker the first time a research is started from it, and from then on it is found by that.
 */
object WorkMigration {
    /** The lines of the backlog note are research questions: the kind Research. */
    private val kind = WorkKind.Research

    /** An id for a line that has no marker: stable for the same text and date, so the same line is the same item on every run. */
    internal fun legacyId(text: String, date: String): String = "legacy-" + hash("${ResearchBacklog.normalize(text)}|$date").take(10)

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun epoch(date: String): Long = runCatching { LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0L)

    /** The title of a `[[link]]` as the backlog line wrote it. */
    private fun title(link: String): String = link.trim().removePrefix("[[").removeSuffix("]]").substringBefore('|').trim()

    /**
     * Brings the log of questions in line with the backlog note. Returns how many events it wrote (0 when the log already matched).
     * Never writes to the note; a note that does not exist writes nothing.
     */
    fun sync(vault: Vault, store: WorkStore, now: Long = System.currentTimeMillis()): Int {
        val lines = ResearchBacklog.items(vault)
        if (lines.isEmpty()) return 0
        val log = store.log()
        var written = 0
        fun add(e: WorkEvent) { if (log.append(e)) written++ }

        for (line in lines) {
            val legacy = legacyId(line.question, line.date)
            // the log is read again for each line: an item captured a moment ago is found, and so is its count of closings
            val events = log.events()
            val item = WorkItems.fold(events).firstOrNull { it.isKnownAs(line.id ?: "") || it.isKnownAs(legacy) }
            if (item == null) {
                val id = line.id ?: legacy
                add(WorkEvent.Captured("cap-$id", id, epoch(line.date), kind.id, line.question, line.date))
                if (line.done) closeAnswered(::add, id, 1, now, line)
                continue
            }
            if (line.id != null && !item.isKnownAs(line.id)) add(WorkEvent.Aliased("alias-${item.id}-${line.id}", item.id, now, line.id))
            if (line.question != item.text) add(WorkEvent.Edited("edit-${item.id}-${hash(line.question).take(10)}", item.id, now, line.question))
            val closings = events.count { it is WorkEvent.Closed && it.item == item.id }
            val reopenings = events.count { it is WorkEvent.Reopened && it.item == item.id }
            if (line.done && item.closing == null) closeAnswered(::add, item.id, closings + 1, now, line)
            else if (line.done) line.link?.let { add(link(item.id, now, it)) }
            else if (item.closing == WorkClosing.Answered) add(WorkEvent.Reopened("reopen-${item.id}-${reopenings + 1}", item.id, now))
        }
        return written
    }

    private fun closeAnswered(add: (WorkEvent) -> Unit, id: String, nth: Int, now: Long, line: BacklogItem) {
        line.link?.let { add(link(id, now, it)) }
        add(WorkEvent.Closed("close-$id-$nth", id, now, WorkClosing.Answered.id))
    }

    private fun link(id: String, now: Long, link: String): WorkEvent {
        val note = title(link)
        return WorkEvent.ArtifactLinked("art-$id-${hash(note).take(10)}", id, now, note, WorkRole.Report.id)
    }

    /**
     * Where the log and the note disagree, in words: an item that is no longer a line of the note, a ticked line whose item is not closed, an open line
     * whose item was closed as answered. Empty when they agree (as they do right after [sync], apart from lines that were deleted from the note).
     */
    fun discrepancies(vault: Vault, store: WorkStore): List<String> {
        val items = store.items(kind)
        val lines = ResearchBacklog.items(vault)
        val problems = ArrayList<String>()
        for (item in items) {
            val line = lines.firstOrNull { it.id?.let(item::isKnownAs) == true || item.isKnownAs(legacyId(it.question, it.date)) }
            when {
                line == null -> problems += "gone from the note: ${item.text}"
                line.done && item.closing == null -> problems += "ticked in the note, not closed in the log: ${item.text}"
                !line.done && item.closing == WorkClosing.Answered -> problems += "open in the note, closed in the log: ${item.text}"
            }
        }
        return problems
    }
}
