package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.arcana.brain.core.ToolBox.Companion.READ_LIMIT
import com.arcana.brain.core.ToolBox.Companion.LIST_LIMIT
import com.arcana.brain.core.ToolBox.Companion.FIND_LIMIT
import com.arcana.brain.core.ToolBox.Companion.GREP_LIMIT
import com.arcana.brain.core.ToolBox.Companion.GREP_LINE
import com.arcana.brain.core.ToolBox.Companion.RANGE_LINES
import com.arcana.brain.core.ToolBox.Companion.HIDDEN_SKIP
import com.arcana.brain.core.ToolBox.Companion.TREE_LIMIT
import com.arcana.brain.core.ToolBox.Companion.TREE_DEPTH
import com.arcana.brain.core.ToolBox.Companion.FIND_DEPTH
import com.arcana.brain.core.ToolBox.Companion.FIND_VISIT_LIMIT
import com.arcana.brain.core.ToolBox.Companion.FIND_SECONDS
import com.arcana.brain.core.ToolBox.Companion.FIND_SKIP
import com.arcana.brain.core.ToolBox.Companion.PREVIEW_LINES
import com.arcana.brain.core.ToolBox.Companion.PROJECT_FILES
import com.arcana.brain.core.ToolBox.Companion.PROJECT_NOTES_LIMIT
import com.arcana.brain.core.ToolBox.Companion.PROJECT_NOISE
import com.arcana.brain.core.ToolBox.Companion.MAX_DIFF_LINES
import com.arcana.brain.core.ToolBox.Companion.MAX_DIFF_BYTES
import com.arcana.brain.core.ToolBox.Companion.PROTECTED
import com.arcana.brain.core.ToolBox.Companion.FILE_TOOLS
import com.arcana.brain.core.ToolBox.Companion.schema
import com.arcana.brain.core.ToolBox.Companion.bool
import com.arcana.brain.core.ToolBox.Companion.str
import com.arcana.brain.core.ToolBox.Companion.int

// current_time, wait and elapsed_time, and the time helpers they use. Split out of Tools.kt (proposal 0007, phase 3): extension functions of ToolBox, which offers them in `specs`.

internal fun ToolBox.timeTool() = ToolSpec(
    "current_time", "Get the current date and time (and the weekday and time zone), here or in another time zone. Use it whenever the date or time matters; never guess it.",
    schema("timezone" to ("string" to "Optional IANA time zone such as Europe/Kyiv or Asia/Tokyo; default: this computer's time zone")),
    ToolRisk.Read, { it.str("timezone") },
) { a ->
    val zone = a.str("timezone").trim().takeIf { it.isNotEmpty() }?.let {
        try { java.time.ZoneId.of(it) } catch (e: Exception) { throw IllegalArgumentException("\"$it\" is not a time zone name. Use a name like Europe/Kyiv, America/New_York or UTC.") }
    } ?: java.time.ZoneId.systemDefault()
    describeNow(java.time.ZonedDateTime.now(zone))
}

/** Lets a model sit out a rate limit (for example 5 to 7 requests a minute) instead of hammering an API; Esc stops it at once. */
internal fun ToolBox.waitTool() = ToolSpec(
    "wait", "Pause for a number of seconds (1 to $MAX_WAIT_SECONDS), for example until a rate limit resets, then continue. Say why in reason. For a longer wait call it again.",
    schema(
        "seconds" to ("integer" to "How long to wait, 1 to $MAX_WAIT_SECONDS"),
        "reason" to ("string" to "Why you wait (optional), for example: CoinGecko rate limit"),
        required = listOf("seconds"),
    ),
    ToolRisk.Read, { "${it.int("seconds") ?: "?"} s" + it.str("reason").let { r -> if (r.isEmpty()) "" else " - $r" } },
) { a ->
    val asked = a.int("seconds") ?: throw IllegalArgumentException("Say how many seconds to wait (1 to $MAX_WAIT_SECONDS).")
    val seconds = asked.coerceIn(1, MAX_WAIT_SECONDS)
    Thread.sleep(seconds * 1000L)
    "Waited $seconds s" + (if (asked > MAX_WAIT_SECONDS) " (the most one call waits; call wait again for more)" else "") + ". " + describeNow(java.time.ZonedDateTime.now())
}

/** Time spent between two moments: models cannot subtract clock times reliably, and every long task (a skill run, a download) wants it. */
internal fun ToolBox.elapsedTool() = ToolSpec(
    "elapsed_time", "Calculate how much time has passed since a moment (or between two moments), for example how long a task took. Note the start with current_time first, then call this with it as `from`. It also tells the exact end time. Never subtract times yourself.",
    schema(
        "from" to ("string" to "The start: an ISO time such as 2026-10-26T18:05:00 (this computer's time zone) or with an offset (2026-10-26T18:05:00+03:00), or Unix seconds"),
        "to" to ("string" to "The end, same formats (default: now)"),
        required = listOf("from"),
    ),
    ToolRisk.Read, { it.str("from") },
) { a ->
    val zone = java.time.ZoneId.systemDefault()
    val from = parseMoment(a.str("from"), zone)
    val to = a.str("to").trim().takeIf { it.isNotEmpty() }?.let { parseMoment(it, zone) } ?: java.time.Instant.now()
    describeElapsed(from, to, zone)
}

/** `Sunday 25 October 2026, 21:07:45 (Europe/Kyiv, UTC+03:00); ISO 2026-10-25T21:07:45+03:00; Unix 1792962465`. */
fun describeNow(now: java.time.ZonedDateTime): String {
    val day = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm:ss", java.util.Locale.ENGLISH))
    return "$day (${now.zone}, UTC${now.offset.id.replace("Z", "+00:00")}); ISO ${now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)}; Unix ${now.toEpochSecond()}"
}

/** Seconds as `45 s`, `5 min 12 s`, `1 h 2 min 3 s` or `2 d 3 h 4 min 5 s`. */
fun describeDuration(totalSeconds: Long): String {
    val d = totalSeconds / 86400
    val h = totalSeconds % 86400 / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return buildList {
        if (d > 0) add("$d d")
        if (d > 0 || h > 0) add("$h h")
        if (d > 0 || h > 0 || m > 0) add("$m min")
        add("$s s")
    }.joinToString(" ").let { text -> text.replace(Regex("^(0 [a-z]+ )+(?=\\d)"), "").ifEmpty { "0 s" } }
}

/**
 * A moment as a model writes it: ISO with an offset or `Z` (`2026-10-26T18:05:00+03:00`), a local date-time with `T` or a space
 * (read in [zone], this computer's time zone), or Unix seconds or milliseconds.
 */
fun parseMoment(text: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): java.time.Instant {
    val t = text.trim()
    if (Regex("""\d{9,13}""").matches(t)) return t.toLong().let { if (it >= 1_000_000_000_000L) java.time.Instant.ofEpochMilli(it) else java.time.Instant.ofEpochSecond(it) }
    runCatching { return java.time.OffsetDateTime.parse(t).toInstant() }
    runCatching { return java.time.LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant() }
    throw IllegalArgumentException("\"$t\" is not a time. Use an ISO time like 2026-10-26T18:05:00 (or with an offset like +03:00), or Unix seconds.")
}

/** The answer of `elapsed_time`: labelled values the model can copy as they are. */
fun describeElapsed(from: java.time.Instant, to: java.time.Instant, zone: java.time.ZoneId): String {
    val seconds = to.epochSecond - from.epochSecond
    require(seconds >= 0) { "The end is before the start." }
    val local = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    return "total_work_time=${describeDuration(seconds)}\nseconds=$seconds\nfrom=${java.time.ZonedDateTime.ofInstant(from, zone).format(local)}\nto=${java.time.ZonedDateTime.ofInstant(to, zone).format(local)}"
}

/** The longest a single `wait` call sleeps. */
const val MAX_WAIT_SECONDS = 120
