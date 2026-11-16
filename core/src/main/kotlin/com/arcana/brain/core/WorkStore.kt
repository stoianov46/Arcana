package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Where the events of one kind of item are kept. A port: the callers see only this, so the JSON Lines file can be replaced
 * (a database, say) without touching them.
 */
interface WorkLog {
    /** Adds [event]; false (and nothing written) when an event with the same id is already there. */
    fun append(event: WorkEvent): Boolean

    /** Every readable event, in the order they were written. */
    fun events(): List<WorkEvent>

    /** The items the events describe (see [WorkItems.fold]). */
    fun items(): List<WorkItem> = WorkItems.fold(events())
}

/**
 * An append-only JSON Lines file of events of type [E]: one event per line, only ever appended to, and each event has an id that is written once. A line
 * that cannot be read (a write cut short by a crash, an event type a newer Arcana wrote, a hand edit) is skipped and counted in [unreadable], never fatal
 * and never rewritten, so a newer version can still read it. A last line without its newline (a write cut short) does not swallow the next event: the
 * next append starts a new line. The one engine under the work log and the run log.
 */
class JsonlEventLog<E : Any>(private val path: Path, private val serializer: KSerializer<E>, private val idOf: (E) -> String) {
    private val lock = Any()
    private var known: MutableSet<String>? = null

    /** How many lines of the file could not be read the last time it was read. */
    @Volatile
    var unreadable: Int = 0
        private set

    /** Adds [event]; false (and nothing written) when an event with the same id is already there. */
    fun append(event: E): Boolean = synchronized(lock) {
        val ids = known ?: readAll().first.mapTo(HashSet()) { idOf(it) }.also { known = it }
        if (idOf(event) in ids) return false
        Files.createDirectories(path.parent)
        val line = json.encodeToString(serializer, event)
        val needsBreak = Files.isRegularFile(path) && Files.size(path) > 0 && !endsWithNewline()
        Files.writeString(path, (if (needsBreak) "\n" else "") + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        ids += idOf(event)
        true
    }

    /** Every readable event, in the order they were written. */
    fun events(): List<E> = synchronized(lock) {
        val (events, bad) = readAll()
        unreadable = bad
        known = events.mapTo(HashSet()) { idOf(it) }
        events
    }

    private fun readAll(): Pair<List<E>, Int> {
        if (!Files.isRegularFile(path)) return emptyList<E>() to 0
        var bad = 0
        val events = Files.readAllLines(path).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.decodeFromString(serializer, line) }.getOrElse { bad++; null }
        }
        return events to bad
    }

    private fun endsWithNewline(): Boolean = Files.newByteChannel(path).use { ch ->
        ch.position(ch.size() - 1)
        val one = java.nio.ByteBuffer.allocate(1)
        ch.read(one)
        one.get(0) == '\n'.code.toByte()
    }

    private companion object {
        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** A [WorkLog] in a JSON Lines file (see [JsonlEventLog]). */
class JsonlWorkLog(path: Path) : WorkLog {
    private val log = JsonlEventLog(path, WorkEvent.serializer()) { it.id }

    /** How many lines of the file could not be read the last time it was read. */
    val unreadable: Int get() = log.unreadable

    override fun append(event: WorkEvent): Boolean = log.append(event)

    override fun events(): List<WorkEvent> = log.events()
}

/**
 * The work log of one vault: one file, `.arcana/work/items.jsonl`, inside the vault so it travels and syncs with it. One file for every kind, because the
 * kind is part of an item's state (a `Retyped` event changes it), so an item that changes kind keeps its whole history in one place.
 *
 * The first version kept one file per kind (`question.jsonl`, written by the mirror of the research backlog, whose lines were research questions): it is
 * copied into the new file once, as research items, and renamed `question.jsonl.migrated`.
 */
class WorkStore(private val dir: Path) {
    constructor(vault: Vault) : this(vault.root.resolve(".arcana/work"))

    private val file = lazy { open() }

    /** The log of every kind. */
    fun log(): WorkLog = file.value

    /** The items, optionally of one kind only, in the order they were captured. */
    fun items(kind: WorkKind? = null): List<WorkItem> = log().items().filter { kind == null || it.kind == kind }

    private fun open(): WorkLog {
        val target = dir.resolve("items.jsonl")
        val legacy = dir.resolve("question.jsonl")
        val log = JsonlWorkLog(target)
        if (Files.isRegularFile(legacy) && !Files.exists(target)) {
            runCatching {
                for (e in JsonlWorkLog(legacy).events()) {
                    log.append(if (e is WorkEvent.Captured && e.kind == "question") e.copy(kind = WorkKind.Research.id) else e)
                }
                Files.move(legacy, dir.resolve("question.jsonl.migrated"))
            }
        }
        return log
    }
}
