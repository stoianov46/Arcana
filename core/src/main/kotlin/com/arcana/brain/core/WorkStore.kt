package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
 * A [WorkLog] in a JSON Lines file: one event per line, only ever appended to. A line that cannot be read (a write cut short by a crash,
 * an event type a newer Arcana wrote, a hand edit) is skipped and counted in [unreadable], never fatal and never rewritten, so a newer
 * version can still read it. A last line without its newline (a write cut short) does not swallow the next event: the next append starts a new line.
 */
class JsonlWorkLog(private val path: Path) : WorkLog {
    private val lock = Any()
    private var known: MutableSet<String>? = null

    /** How many lines of the file could not be read the last time it was read. */
    @Volatile
    var unreadable: Int = 0
        private set

    override fun append(event: WorkEvent): Boolean = synchronized(lock) {
        val ids = known ?: readAll().first.mapTo(HashSet()) { it.id }.also { known = it }
        if (event.id in ids) return false
        Files.createDirectories(path.parent)
        val line = json.encodeToString(WorkEvent.serializer(), event)
        val needsBreak = Files.isRegularFile(path) && Files.size(path) > 0 && !endsWithNewline()
        Files.writeString(path, (if (needsBreak) "\n" else "") + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        ids += event.id
        true
    }

    override fun events(): List<WorkEvent> = synchronized(lock) {
        val (events, bad) = readAll()
        unreadable = bad
        known = events.mapTo(HashSet()) { it.id }
        events
    }

    private fun readAll(): Pair<List<WorkEvent>, Int> {
        if (!Files.isRegularFile(path)) return emptyList<WorkEvent>() to 0
        var bad = 0
        val events = Files.readAllLines(path).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.decodeFromString(WorkEvent.serializer(), line) }.getOrElse { bad++; null }
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

/**
 * The logs of one vault: `.arcana/work/<kind>.jsonl`, inside the vault so they travel and sync with it.
 */
class WorkStore(private val dir: Path) {
    constructor(vault: Vault) : this(vault.root.resolve(".arcana/work"))

    private val logs = HashMap<WorkKind, WorkLog>()

    fun log(kind: WorkKind): WorkLog = synchronized(logs) { logs.getOrPut(kind) { JsonlWorkLog(dir.resolve("${kind.id}.jsonl")) } }
}
