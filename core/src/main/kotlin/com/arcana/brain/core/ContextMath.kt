package com.arcana.brain.core

/*
 * How full the context window is and which window counts: plain functions over the chat, shared by the session (auto-compact, chat_stats) and the
 * screens (the meters, the status pane). They lived in screens/Chat.kt, which made the session depend on a screen (proposal 0007, phase 1).
 */

/** The window the gauge counts against: what was asked for, but never more than the model supports (when that is known). */
fun effectiveContext(asked: Int, modelMax: Int, loaded: Int = 0): Int = when {
    asked > 0 && modelMax > 0 -> minOf(asked, modelMax)
    asked > 0 -> asked
    // "Model default": Ollama runs with its own window, usually much smaller than what the model could do; once it has loaded the model it says which
    loaded > 0 -> loaded
    else -> modelMax
}

/** The values of [model] `think` as the setting names them: `default`, then `off`, `on` and levels, only what the model lists. */
fun thinkOptions(values: List<String>): List<String> =
    if (values.isEmpty()) listOf("default") else listOf("default") + values.map { when (it) { "false" -> "off"; "true" -> "on"; else -> it } }.distinct()

/**
 * Tokens the context holds: the last reply's prompt plus its answer as Ollama counted them, or an estimate of what the model is sent now (every message it reads, as it reads it,
 * plus [overhead]: the system prompt and the tool definitions; see [Compaction.tokens]) when that is larger: after a compaction the counts are stale, and some Ollama versions count
 * only the part of the prompt they did not have in their cache. The flag says it is an estimate.
 */
fun contextTokens(messages: List<ChatMessage>, overhead: Int = 0): Pair<Int, Boolean>? {
    val statsAt = messages.indexOfLast { (it.stats?.promptTokens ?: 0) > 0 }
    if (statsAt < 0) return null
    val estimate = Compaction.forModel(messages).sumOf { Compaction.messageTokens(it) } + overhead
    // the counts of the last reply are stale when a summary came after it, or was inserted before it while that reply was made from the whole chat (a manual or automatic compaction)
    val summaryAt = messages.indexOfLast { Compaction.isSummary(it) }
    if (summaryAt > statsAt || (summaryAt >= 0 && statsAt < messages[summaryAt].contextAt)) return estimate to true
    val stats = messages[statsAt].stats!!
    val counted = stats.promptTokens + stats.completionTokens
    // well above what Ollama counted: its count left part of the prompt out, the estimate is nearer the truth
    return if (overhead > 0 && estimate > counted * 6 / 5) estimate to true else counted to false
}

/** How full the model's context was after the last reply (its prompt plus its answer) as a share and a `13.6k/131k` text (`~` after a compaction: an estimate); null before the first reply or when the size is unknown. */
fun contextUsage(messages: List<ChatMessage>, limit: Int, overhead: Int = 0, ahead: Int = 0): Pair<Double, String>? {
    val (counted, estimated) = contextTokens(messages, overhead) ?: return null
    // [ahead]: what the next request will add that is not in the chat yet (the excerpts and notes of the message about to be sent)
    val used = counted + ahead
    if (limit <= 0) return null
    return (used.toDouble() / limit).coerceIn(0.0, 1.0) to "${if (estimated) "~" else ""}${UsageStats.compact(used.toLong())}/${UsageStats.contextSize(limit.toLong())}"
}
