package com.arcana.brain.core

import java.nio.file.Files

/** One thought Capture wrote into a daily thought note: its day, time and text, and the note it is in. */
data class ThoughtLine(val date: String, val time: String, val text: String, val path: String)

/**
 * The thoughts of the daily thought notes (`Thoughts/thought-2026-11-06.md`, a line `- 16:50 text` per thought), newest first, for the Brain screen's list.
 * A thought that carries a kind prefix (`todo: …`, `? …`) is listed too: it is also an item of that kind, and this list is the plain record of what was captured.
 */
object Thoughts {
    private val LINE = Regex("""^- (\d{1,2}:\d{2}) (.+)$""")
    private val DAY = Regex("""(\d{4}-\d{2}-\d{2})""")

    /** The thoughts of one note's [text] (a note of [path]); lines that are not thoughts are skipped. */
    fun parse(path: String, text: String): List<ThoughtLine> {
        val date = DAY.find(path.substringAfterLast('/'))?.groupValues?.get(1) ?: return emptyList()
        return text.lineSequence().mapNotNull { LINE.find(it.trim())?.let { m -> ThoughtLine(date, m.groupValues[1], m.groupValues[2].trim(), path) } }.toList().asReversed()
    }

    /** The newest [max] thoughts of the daily notes in the folder of [daily], from at most the last [days] notes. Reads files; empty when the folder is missing. */
    fun recent(vault: Vault, daily: DailyNotes, days: Int = 14, max: Int = 200): List<ThoughtLine> {
        val folder = vault.root.resolve(daily.folder.trim('/'))
        if (!Files.isDirectory(folder)) return emptyList()
        val files = Files.list(folder).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("thought-") && it.fileName.toString().endsWith(".md") }.toList() }
            .sortedByDescending { it.fileName.toString() }.take(days)
        return files.flatMap { f -> parse(vault.root.relativize(f).toString().replace('\\', '/'), runCatching { Files.readString(f) }.getOrDefault("")) }.take(max)
    }
}
