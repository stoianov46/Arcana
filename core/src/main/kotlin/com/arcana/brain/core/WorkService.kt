package com.arcana.brain.core

import java.time.LocalDate

/**
 * What the user (or a screen) does to work items, as commands. Each command appends an event and returns what the item is now; nothing edits an
 * item in place. A command on an item that does not exist, or one that would change nothing, writes nothing and says so (null / false).
 *
 * The note of the research backlog is still the truth for the items of the kind [WorkKind.Research] until phase 5 of proposals/0002 (`WorkMigration`
 * mirrors it into the log); every other kind lives in the log first and is shown in the vault by a generated note ([WorkNotes]).
 */
class WorkService(private val store: WorkStore, private val clock: () -> Long = System::currentTimeMillis, private val random: java.util.Random = java.util.Random()) {
    private val log get() = store.log()

    /** A new event id: unique per command, sortable by time. */
    private fun eventId(prefix: String): String = "$prefix-" + clock().toString(36).padStart(9, '0') + randomTail()

    private fun randomTail() = (0 until 4).map { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)] }.joinToString("")

    /** A new item id: `w-`, the time in base 36 (zero-padded, so ids sort by capture) and four random characters. */
    private fun itemId(): String = "w-" + clock().toString(36).padStart(9, '0') + randomTail()

    fun items(kind: WorkKind? = null): List<WorkItem> = store.items(kind)

    fun item(id: String): WorkItem? = store.items().firstOrNull { it.isKnownAs(id) }

    /** Adds an item of [kind] with [text] (blank text adds nothing: null). Returns the new item. */
    fun capture(kind: WorkKind, text: String, date: LocalDate = LocalDate.now()): WorkItem? {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        val id = itemId()
        log.append(WorkEvent.Captured("cap-$id", id, clock(), kind.id, clean, date.toString()))
        return item(id)
    }

    /** Rewords the item (blank or unchanged text writes nothing). */
    fun edit(id: String, text: String): WorkItem? {
        val item = item(id) ?: return null
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || clean == item.text) return item
        log.append(WorkEvent.Edited(eventId("edit"), item.id, clock(), clean))
        return item(id)
    }

    /** The user changed their mind about what the item is: same item, same history, another kind. */
    fun retype(id: String, kind: WorkKind): WorkItem? {
        val item = item(id) ?: return null
        if (item.kind == kind) return item
        log.append(WorkEvent.Retyped(eventId("type"), item.id, clock(), kind.id))
        return item(id)
    }

    /** Records what the item was made from; the same origin twice writes nothing. */
    fun source(id: String, origin: String): WorkItem? {
        val item = item(id) ?: return null
        if (item.origin == origin) return item
        log.append(WorkEvent.Sourced(eventId("source"), item.id, clock(), origin))
        return item(id)
    }

    /** Pins [skill] to this one item (blank unpins: the kind's default applies again). */
    fun pinSkill(id: String, skill: String): WorkItem? {
        val item = item(id) ?: return null
        if (item.skill == skill.trim()) return item
        log.append(WorkEvent.SkillPinned(eventId("skill"), item.id, clock(), skill.trim()))
        return item(id)
    }

    /** A run began working on the item. */
    fun linkRun(id: String, run: String): WorkItem? {
        val item = item(id) ?: return null
        if (run in item.runs) return item
        log.append(WorkEvent.RunLinked(eventId("run"), item.id, clock(), run))
        return item(id)
    }

    /** A note answers or supports the item. Several reports and answers may be linked; none replaces another. */
    fun linkNote(id: String, note: String, role: WorkRole): WorkItem? {
        val item = item(id) ?: return null
        if (WorkArtifact(note, role) in item.artifacts) return item
        log.append(WorkEvent.ArtifactLinked(eventId("art"), item.id, clock(), note, role.id))
        return item(id)
    }

    /** Closes the item by hand (a todo done, a question answered, an item given up). */
    fun close(id: String, reason: WorkClosing = WorkClosing.Answered): WorkItem? {
        val item = item(id) ?: return null
        if (item.closing == reason) return item
        log.append(WorkEvent.Closed(eventId("close"), item.id, clock(), reason.id))
        return item(id)
    }

    /** Undoes a close: the item is worked on again (and keeps every note linked to it). */
    fun reopen(id: String): WorkItem? {
        val item = item(id) ?: return null
        if (item.closing == null) return item
        log.append(WorkEvent.Reopened(eventId("open"), item.id, clock()))
        return item(id)
    }
}
