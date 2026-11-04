package com.arcana.brain.core

/**
 * What Arcana itself writes into a report when the skill run that made it ends: `tokens`, `work_time` and `model`. A small model asked to copy
 * numbers from `chat_stats` and `elapsed_time` gets them wrong or skips the step, so the app, which already knows the run, writes them.
 *
 * - `tokens`: everything the run spent, every model and helper (planner, Oracul, judge…), from the usage records since the run started.
 * - `work_time`: wall-clock time from the start of the run to its final reply, as `5 min 12 s`. It includes the time you took to answer its questions.
 * - `model`: the chat model of the run, only when the note has none (`create_note` normally writes it).
 *
 * Only keys the note does not have are written, so a value the skill or you set is never changed, and the same call twice changes nothing.
 */
object ReportMeta {
    /** `5 min 12 s` for [millis] (never negative). */
    fun workTime(millis: Long): String = describeDuration((millis / 1000).coerceAtLeast(0))

    /**
     * Writes the missing keys into the report at [path] (vault-relative) and returns the keys it wrote. Does nothing, and returns an empty list, when the note
     * is not a `type: report` note, does not exist, or cannot be written. [tokens] of 0 or less is not written (nothing was recorded).
     */
    /**
     * Writes `backlog_id: <the line's marker>` and `backlog: "[[Research Backlog#^<marker>]]"` (a link to the line) into the report, or, for a line without a marker, `backlog: "<the line's text>"` at [path] when it is a `type: report` note without one: the link from a finished report to the research backlog line
     * it answers, which does not depend on how the model worded the report's own `question`. Returns true when it wrote it. Best effort: never throws.
     */
    fun stampBacklog(vault: Vault, path: String, backlog: String, id: String? = null): Boolean = runCatching {
        val text = backlog.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return@runCatching false
        val (block, _) = Vault.splitFrontmatter(vault.read(path))
        if (block.isEmpty()) return@runCatching false
        val props = Vault.parseProperties(Vault.propertyText(block))
        fun value(key: String) = props[key]?.firstOrNull()?.substringAfter(':', "")?.trim()?.trim('"', '\'').orEmpty()
        if (value("type") != "report" || value("backlog").isNotEmpty()) return@runCatching false
        // with a marker: the id and a link to the line (Obsidian shows the report among that line's backlinks); without: the line's text, which is what reconcile compares
        val lines = if (id.isNullOrBlank()) "backlog: \"${text.replace("\"", "'")}\"" else "backlog_id: $id\nbacklog: \"${ResearchBacklog.linkTo(id)}\""
        vault.setProperties(path, lines).isNotEmpty()
    }.getOrDefault(false)

    fun stamp(vault: Vault, path: String, tokens: Long, workMillis: Long, model: String): List<String> = runCatching {
        val (block, _) = Vault.splitFrontmatter(vault.read(path))
        if (block.isEmpty()) return@runCatching emptyList()
        val props = Vault.parseProperties(Vault.propertyText(block))
        fun value(key: String) = props[key]?.firstOrNull()?.substringAfter(':', "")?.trim()?.trim('"', '\'').orEmpty()
        if (value("type") != "report") return@runCatching emptyList()
        val lines = buildList {
            if (tokens > 0 && value("tokens").isEmpty()) add("tokens: $tokens")
            if (workMillis > 0 && value("work_time").isEmpty()) add("work_time: ${workTime(workMillis)}")
            if (model.isNotBlank() && value("model").isEmpty()) add("model: $model")
        }
        if (lines.isEmpty()) emptyList() else vault.setProperties(path, lines.joinToString("\n"))
    }.getOrDefault(emptyList())
}
