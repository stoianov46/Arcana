package com.arcana.brain.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The model of the work log (proposals/0002): an item has an immutable id, and everything that happens to it is an event
 * in an append-only log. What an item *is* now (its text, its state, its runs and reports) is never stored: it is the
 * fold of its events ([WorkItems.fold]).
 *
 * Phase 1 only mirrors the research backlog note into this log ([WorkMigration]); nothing reads it for behaviour yet.
 */
enum class WorkKind(val id: String, val title: String) {
    /** The default: a plain note, no skill, nothing to answer. */
    Thought("thought", "Thoughts"),

    /** Wants an answer, usually in chat, kept as a short answer note that the work can continue from later. */
    Question("question", "Questions"),

    /** Wants a full report from a skill (dev research, a comparison…); answered when a linked report is done. */
    Research("research", "Research"),

    /** A task: open, in progress, done. */
    Todo("todo", "Todos");

    companion object {
        fun of(id: String): WorkKind? = entries.firstOrNull { it.id == id }
    }
}

/** Where an item stands. Derived, never stored: see [WorkItem.state]. */
enum class WorkState { Open, InProgress, Answered, Abandoned }

/** Why an item was closed. */
enum class WorkClosing(val id: String) {
    /** Somebody (the user by hand, or a finished report) answered it. */
    Answered("answered"),
    Abandoned("abandoned"),

    /** Folded into another item. */
    Merged("merged"),
}

/** The role a linked note plays for an item: only a [Report] (research) or an [Answer] (question) can answer it. */
enum class WorkRole(val id: String) { Report("report"), Answer("answer"), Source("source"), Draft("draft") }

/**
 * One thing that happened to one item. Every event has its own [id], so reading a log twice, or merging the logs of two
 * machines, is a union by id: nothing is applied twice and nothing is overwritten. [at] is milliseconds since the epoch.
 * [item] is the id of the item it belongs to.
 */
@Serializable
sealed interface WorkEvent {
    val id: String
    val item: String
    val at: Long

    @Serializable @SerialName("captured")
    data class Captured(override val id: String, override val item: String, override val at: Long, val kind: String, val text: String, val date: String) : WorkEvent

    @Serializable @SerialName("edited")
    data class Edited(override val id: String, override val item: String, override val at: Long, val text: String) : WorkEvent

    /** Another id the same item is known by (a marker the backlog note gained later); lets a line be found again. */
    @Serializable @SerialName("aliased")
    data class Aliased(override val id: String, override val item: String, override val at: Long, val alias: String) : WorkEvent

    @Serializable @SerialName("run")
    data class RunLinked(override val id: String, override val item: String, override val at: Long, val run: String) : WorkEvent

    /** A note linked to the item. Phase 1 names it by its title as the backlog note wrote it (`[[title]]`). */
    @Serializable @SerialName("artifact")
    data class ArtifactLinked(override val id: String, override val item: String, override val at: Long, val note: String, val role: String) : WorkEvent

    /** The user changed their mind about what the item is (research to question, todo to thought…): same id, same history, another kind. */
    @Serializable @SerialName("retyped")
    data class Retyped(override val id: String, override val item: String, override val at: Long, val kind: String) : WorkEvent

    /** The skill pinned to this one item (blank: none, the kind's default applies). */
    @Serializable @SerialName("skill")
    data class SkillPinned(override val id: String, override val item: String, override val at: Long, val skill: String) : WorkEvent

    @Serializable @SerialName("closed")
    data class Closed(override val id: String, override val item: String, override val at: Long, val reason: String) : WorkEvent

    @Serializable @SerialName("reopened")
    data class Reopened(override val id: String, override val item: String, override val at: Long) : WorkEvent
}

/** A note linked to an item, and the part it plays. */
data class WorkArtifact(val note: String, val role: WorkRole)

/**
 * An item as the fold of its events.
 *
 * @property id the immutable id (a backlog marker such as `q-0611-1650` when the line had one, else made from the line)
 * @property aliases other ids the item is known by
 * @property closing why it was closed by hand or by a report, or null while it is not closed
 */
data class WorkItem(
    val id: String,
    val kind: WorkKind,
    val text: String,
    val date: String,
    val capturedAt: Long,
    val aliases: Set<String> = emptySet(),
    val runs: List<String> = emptyList(),
    val artifacts: List<WorkArtifact> = emptyList(),
    val closing: WorkClosing? = null,
    /** The skill pinned to this item, or blank. */
    val skill: String = "",
) {
    /**
     * [doneNotes]: the notes that are finished reports (`status: done`); only the vault knows them, so the caller passes them in.
     * An item is *answered* when it was closed as answered, or when a linked `report` is in [doneNotes]; a linked note that is not done
     * (a draft, a run that failed) leaves it *in progress*.
     */
    fun state(doneNotes: Set<String> = emptySet()): WorkState = when {
        closing == WorkClosing.Abandoned || closing == WorkClosing.Merged -> WorkState.Abandoned
        closing == WorkClosing.Answered || artifacts.any { (it.role == WorkRole.Report || it.role == WorkRole.Answer) && it.note in doneNotes } -> WorkState.Answered
        runs.isNotEmpty() || artifacts.isNotEmpty() -> WorkState.InProgress
        else -> WorkState.Open
    }

    /** True when [key] is this item's id or one of its aliases. */
    fun isKnownAs(key: String): Boolean = key == id || key in aliases
}

object WorkItems {
    /**
     * The items a list of events describes. Events are de-duplicated by id; captures come first, then the rest in the order of their time (then their id), so the result
     * is the same however the events were read, read twice, or merged from two logs. An event for an item that was never captured is ignored
     * (its capture may sit in another log that has not been merged yet); the order of the result is the order of capture.
     */
    fun fold(events: Iterable<WorkEvent>): List<WorkItem> {
        // captures first, whatever their time: an event stamped earlier than its item's capture (a machine with a slow clock) must not be lost
        val ordered = events.distinctBy { it.id }.sortedWith(compareBy<WorkEvent>({ it !is WorkEvent.Captured }, { it.at }, { it.id }))
        val items = LinkedHashMap<String, WorkItem>()
        // alias -> the item id it stands for, so a later event may name the item by either
        val byAlias = HashMap<String, String>()
        for (e in ordered) {
            if (e is WorkEvent.Captured) {
                val kind = WorkKind.of(e.kind) ?: continue
                if (e.item !in items) items[e.item] = WorkItem(e.item, kind, e.text, e.date, e.at)
                continue
            }
            val key = byAlias[e.item] ?: e.item
            val item = items[key] ?: continue
            items[key] = when (e) {
                is WorkEvent.Edited -> item.copy(text = e.text)
                is WorkEvent.Retyped -> WorkKind.of(e.kind)?.let { item.copy(kind = it) } ?: item
                is WorkEvent.SkillPinned -> item.copy(skill = e.skill.trim())
                is WorkEvent.Aliased -> { byAlias[e.alias] = key; item.copy(aliases = item.aliases + e.alias) }
                is WorkEvent.RunLinked -> if (e.run in item.runs) item else item.copy(runs = item.runs + e.run)
                is WorkEvent.ArtifactLinked -> {
                    val role = WorkRole.entries.firstOrNull { it.id == e.role } ?: WorkRole.Source
                    val link = WorkArtifact(e.note, role)
                    if (link in item.artifacts) item else item.copy(artifacts = item.artifacts + link)
                }
                is WorkEvent.Closed -> WorkClosing.entries.firstOrNull { it.id == e.reason }?.let { item.copy(closing = it) } ?: item
                is WorkEvent.Reopened -> item.copy(closing = null)
                is WorkEvent.Captured -> item
            }
        }
        return items.values.toList()
    }
}
