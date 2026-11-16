package com.arcana.brain.core

import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a note came from (proposals/0002, phase 2): the run that wrote it and the chat that run belongs to. A run is one request of the user and everything
 * Arcana does to answer it (the worker, its tools, the Oracul and the other helpers, a skill). Stamped by code where notes are written, never by the model:
 * the model sees no ids and has no tool for this.
 */
data class Origin(val run: String, val chat: String) {
    companion object {
        /** A new run id, sortable by time: `run-` and the time in base 36, (zero-padded so the text sorts as the time does), then three random characters (two runs in the same millisecond differ). */
        fun newRun(chat: String, now: Long = System.currentTimeMillis(), random: java.util.Random = java.util.Random()): Origin {
            val tail = (0 until 3).map { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)] }.joinToString("")
            return Origin("run-" + now.toString(36).padStart(9, '0') + tail, chat)
        }
    }
}

/** What a run did to a note. */
enum class NoteAction(val id: String) { Created("created"), Appended("appended"), Properties("properties") }

/** One fact about a run. Each has its own id, so a record written twice is kept once. [at] is milliseconds since the epoch. */
@Serializable
sealed interface RunEvent {
    val id: String
    val run: String
    val at: Long

    /** A run began: [chat] it belongs to, the [request] that started it (first 200 characters of what the user asked) and the chat [model]. */
    @Serializable @SerialName("started")
    data class Started(override val id: String, override val run: String, override val at: Long, val chat: String, val request: String, val model: String) : RunEvent

    /** The run [action] a note, by its vault-relative [note] path at that time. */
    @Serializable @SerialName("note")
    data class NoteTouched(override val id: String, override val run: String, override val at: Long, val note: String, val action: String) : RunEvent
}

/** The notes one run touched, and how. */
data class RunNote(val note: String, val action: NoteAction)

/** A run as the fold of its events. */
data class RunInfo(val run: String, val chat: String, val request: String, val model: String, val startedAt: Long, val notes: List<RunNote>)

/**
 * The record of runs and the notes they touched: `.arcana/work/runs.jsonl` in the vault, beside the work logs, append-only (see [JsonlEventLog]). Two ways
 * to ask it a question, and both work for notes written before any item was linked to the run: which notes did this run touch ([notesOf]), and which
 * run wrote this note ([runOf]).
 *
 * A note also carries `origin` and `chat` in its own properties when a run created it (`Vault.create`), so the origin survives the note being moved or
 * renamed, which a path in a log does not; a note made before this existed simply has no origin ("unknown").
 */
class Provenance(private val log: JsonlEventLog<RunEvent>) {
    constructor(vault: Vault) : this(vault.root.resolve(".arcana/work"))
    constructor(dir: Path) : this(JsonlEventLog(dir.resolve("runs.jsonl"), RunEvent.serializer()) { it.id })

    /** Records that [origin] began, for [request] and [model]. Best effort: never throws, because losing a record must never fail the user's request. */
    fun started(origin: Origin, request: String, model: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            log.append(RunEvent.Started("start-${origin.run}", origin.run, now, origin.chat, request.trim().replace(Regex("\\s+"), " ").take(200), model))
        }
    }

    /** Records that [origin] [action] [note]. Best effort, like [started]; the same run doing the same to the same note is kept once. */
    fun touched(origin: Origin, note: String, action: NoteAction, now: Long = System.currentTimeMillis()) {
        runCatching {
            log.append(RunEvent.NoteTouched("note-${origin.run}-${action.id}-${note.hashCode().toUInt().toString(36)}", origin.run, now, note, action.id))
        }
    }

    /** Every run, oldest first. */
    fun runs(): List<RunInfo> {
        val events = log.events()
        val notes = events.filterIsInstance<RunEvent.NoteTouched>().groupBy { it.run }
        return events.filterIsInstance<RunEvent.Started>().sortedBy { it.at }.map { s ->
            RunInfo(s.run, s.chat, s.request, s.model, s.at, notes[s.run].orEmpty().sortedBy { it.at }.mapNotNull { n ->
                NoteAction.entries.firstOrNull { it.id == n.action }?.let { RunNote(n.note, it) }
            })
        }
    }

    /** What [run] did to notes (empty for a run that is not known). */
    fun notesOf(run: String): List<RunNote> = runs().firstOrNull { it.run == run }?.notes.orEmpty()

    /** The run that created [note] (a vault-relative path), or null when none did that Arcana knows of: a note made by hand or before this record existed. */
    fun runOf(note: String): RunInfo? = runs().lastOrNull { r -> r.notes.any { it.note == note && it.action == NoteAction.Created } }
}
