package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What can happen during a session. The names are stored, so they must not change. */
object SessionKind {
    const val Asked = "asked"
    const val Task = "task"
    const val Created = "created"
    const val Appended = "appended"
    const val Replaced = "replaced"
    const val Renamed = "renamed"
    const val Deleted = "deleted"
    const val Saved = "saved"
    const val Captured = "captured"
    const val Processed = "processed"
    const val Attached = "attached"
    const val Tool = "tool"
    const val Session = "session"
}

/** One thing that happened: when, what kind, a short description, and the vault note it concerns (if any). */
@Serializable
data class SessionEvent(val time: Long, val kind: String, val text: String, val path: String? = null)

/** A session as it is kept on disk: it starts when Arcana opens (or when you start a new one) and is written as it goes. */
@Serializable
data class StoredSession(
    val id: String,
    val started: Long,
    val ended: Long? = null,
    /** The vault note the session was saved to as a summary or log; null while it has not been saved. */
    val savedAs: String? = null,
    val events: List<SessionEvent> = emptyList(),
) {
    val durationMillis: Long get() = (ended ?: events.lastOrNull()?.time ?: started) - started
}

/** Sessions live as one JSON file each in a `sessions` folder next to the settings. */
class SessionStore(val dir: Path = defaultDir()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(session: StoredSession) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${session.id}.json"), json.encodeToString(StoredSession.serializer(), session))
    }

    /** Every stored session, the most recent first. */
    fun list(): List<StoredSession> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { it.toList() }
            .filter { it.fileName.toString().endsWith(".json") }
            .mapNotNull { runCatching { json.decodeFromString<StoredSession>(Files.readString(it)) }.getOrNull() }
            .sortedByDescending { it.started }
    }

    /** Keeps the [keep] most recent sessions and deletes the rest. */
    fun prune(keep: Int) {
        list().drop(keep.coerceAtLeast(1)).forEach { Files.deleteIfExists(dir.resolve("${it.id}.json")) }
    }

    companion object {
        fun defaultDir(): Path = ConfigStore.defaultPath().resolveSibling("sessions")
    }
}

/** Numbers about a session, counted from its events. */
data class SessionStats(val questions: Int, val tasks: Int, val created: Int, val changed: Int, val deleted: Int, val pictures: Int, val captured: Int = 0, val filed: Int = 0) {
    val total: Int get() = questions + tasks + created + changed + deleted + pictures + captured + filed

    /** "2 questions · 1 AI task · 1 note created · 3 thoughts captured": only what happened, with the right plural. */
    fun describe(): String {
        fun part(n: Int, one: String, many: String) = if (n == 0) null else "$n ${if (n == 1) one else many}"
        return listOfNotNull(
            part(questions, "question", "questions"),
            part(tasks, "AI task", "AI tasks"),
            part(captured, "thought captured", "thoughts captured"),
            part(filed, "inbox note filed", "inbox notes filed"),
            part(created, "note created", "notes created"),
            part(changed, "note changed", "notes changed"),
            part(deleted, "note trashed", "notes trashed"),
            part(pictures, "attachment", "attachments"),
        ).joinToString(" · ").ifEmpty { "no activity yet" }
    }
}

object SessionReport {
    fun stats(events: List<SessionEvent>) = SessionStats(
        questions = events.count { it.kind == SessionKind.Asked },
        tasks = events.count { it.kind == SessionKind.Task },
        created = events.count { it.kind == SessionKind.Created || (it.kind == SessionKind.Saved && it.path != null) },
        captured = events.count { it.kind == SessionKind.Captured },
        filed = events.count { it.kind == SessionKind.Processed },
        changed = events.count { it.kind == SessionKind.Appended || it.kind == SessionKind.Replaced || it.kind == SessionKind.Renamed },
        deleted = events.count { it.kind == SessionKind.Deleted },
        pictures = events.count { it.kind == SessionKind.Attached },
    )

    /** "1h 12m", "5m", "40s". */
    fun duration(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = clock.format(Instant.ofEpochMilli(millis).atZone(zone))

    private fun label(kind: String) = when (kind) {
        SessionKind.Asked -> "Asked"
        SessionKind.Task -> "AI task"
        SessionKind.Created -> "Created"
        SessionKind.Appended -> "Added to"
        SessionKind.Replaced -> "Replaced"
        SessionKind.Renamed -> "Renamed"
        SessionKind.Deleted -> "Moved to trash"
        SessionKind.Saved -> "Saved"
        SessionKind.Captured -> "Captured"
        SessionKind.Processed -> "Filed"
        SessionKind.Attached -> "Attached"
        SessionKind.Tool -> "Used tool"
        else -> "Session"
    }

    /** The activity as a Markdown list; notes become `[[wikilinks]]`, so Obsidian links the session to what it touched. */
    fun activityMarkdown(events: List<SessionEvent>, zone: ZoneId = ZoneId.systemDefault()): String =
        events.joinToString("\n") { e ->
            val link = e.path?.takeIf { it.endsWith(".md") }?.let { " [[${it.removeSuffix(".md")}]]" } ?: ""
            "- ${clock(e.time, zone)} **${label(e.kind)}**: ${e.text}$link"
        }

    /** The whole session note: a short header, the model's [summary] (may be empty for a plain log) and the activity log. */
    fun note(session: StoredSession, summary: String, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val s = stats(session.events)
        appendLine("**Session** ${clock(session.started, zone)}, ${duration(session.durationMillis)}: ${s.describe()}.")
        if (summary.isNotBlank()) {
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine(summary.trim())
        }
        appendLine()
        appendLine("## Activity")
        appendLine()
        append(activityMarkdown(session.events, zone))
    }

    fun title(session: StoredSession, zone: ZoneId = ZoneId.systemDefault()): String =
        "Session " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm").format(Instant.ofEpochMilli(session.started).atZone(zone))
}
