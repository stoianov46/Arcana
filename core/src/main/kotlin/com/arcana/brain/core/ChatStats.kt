package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/** The pseudo-tool that tells the model how much the current chat has used; the chat answers it (see [ChatStats.describe]). */
const val CHAT_STATS = "chat_stats"

val CHAT_STATS_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", CHAT_STATS)
        put("description", "Get the statistics of this chat so far: the model, tokens in and out, estimated cost, chat time (first to last message), active time (without long pauses), time spent generating, how many tool calls, how full the context window is, and which other models were consulted (the Oracul: second opinions, comparisons, plans, takeovers, and the tokens each model spent). Use it when the user asks how much this chat used or cost, or to decide whether to wrap up.")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {}
            putJsonArray("required") {}
        }
    }
}

/** What the current chat has used, worded for the model. */
object ChatStats {
    /** The features that belong to the other models, in the order they are listed: the Oracul (opinions, answers, takeovers, stepping in), the judge, the planner. */
    private val ORACUL_FEATURES = listOf("Oracul", "Judge", "Analyst", "Planner", "Controller")

    /**
     * Token spend per model and in total, from the Usage records: [session] (since Arcana started, every chat and feature) broken down by model with
     * what each model was used for, then the total with the estimated cost; and how many times the Oracul, the judge and the planner were called in all
     * the history ([all], which Arcana keeps in its usage file), so these counts are not lost when Arcana restarts.
     */
    internal fun spendLines(session: List<UsageRecord>, all: List<UsageRecord>, priceIn: Int, priceOut: Int): List<String> = buildList {
        fun tokens(t: UsageTotals) = "${UsageStats.compact(t.promptTokens)} read + ${UsageStats.compact(t.completionTokens)} generated = ${UsageStats.compact(t.totalTokens)}"
        fun cost(records: List<UsageRecord>) = if (priceIn == 0 && priceOut == 0) "" else ", est. $" + "%.2f".format(UsageStats.estimatedCost(records, priceIn, priceOut))
        if (session.isNotEmpty()) {
            add("Token spend by model (since Arcana started, all chats and features):")
            for ((model, records) in session.groupBy { it.model }.entries.sortedByDescending { e -> e.value.sumOf { it.totalTokens.toLong() } }) {
                val uses = records.groupingBy { it.feature }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" }
                add("  $model: ${tokens(UsageStats.totals(records))} tokens in ${records.size} request${if (records.size == 1) "" else "s"} ($uses)${cost(records)}")
            }
            add("  Total: ${tokens(UsageStats.totals(session))} tokens in ${session.size} requests${cost(session)}")
        }
        val calls = ORACUL_FEATURES.mapNotNull { feature -> all.filter { it.feature == feature }.takeIf { it.isNotEmpty() }?.let { feature to it } }
        if (calls.isNotEmpty()) {
            add("Calls to the other models, all time (kept in the usage history): " + calls.joinToString("; ") { (feature, records) ->
                val sessionCount = session.count { it.feature == feature }
                "$feature ${records.size} request${if (records.size == 1) "" else "s"} (${UsageStats.compact(UsageStats.totals(records).totalTokens)} tokens, $sessionCount this session)"
            })
        }
    }

    /**
     * What the other models did in this chat: [setup] says how the Oracul is set (empty = not mentioned), the blocks in [messages] say what it was asked
     * for (second opinions and comparisons, plans, takeovers: each names its model). What the models spent is in [spendLines].
     */
    internal fun oraculLines(messages: List<ChatMessage>, setup: String): List<String> {
        val opinions = messages.filter { it.role == "user" && it.content.startsWith(Oracul.MARK) }
        val comparisons = messages.filter { it.role == "user" && it.content.startsWith(Oracul.MARK_COMPARE) }
        val plans = messages.filter { Oracul.isPlan(it) }
        val analyses = messages.filter { Oracul.isAnalysis(it) }
        val controls = messages.filter { Oracul.isControl(it) }
        val takeovers = messages.filter { it.role == "assistant" && it.content.startsWith(Oracul.TAKEOVER_MARK) }
        fun models(list: List<ChatMessage>, name: (ChatMessage) -> String) = list.map(name).groupingBy { it }.eachCount().entries.joinToString(", ") { if (it.value > 1) "${it.key} ×${it.value}" else it.key }
        return buildList {
            if (setup.isNotEmpty()) add("Oracul (other models): $setup")
            if (opinions.isNotEmpty()) add("Second opinions in this chat: ${opinions.size} (${models(opinions) { it.content.substringBefore(']').substringAfter("· ").trim() }})")
            if (comparisons.isNotEmpty()) add("Comparisons in this chat: ${comparisons.size} (${models(comparisons) { Oracul.title(it).substringAfter("judged by ").trim() }} judged)")
            if (controls.isNotEmpty()) add("Verdicts of a controller model: ${controls.size} (${controls.count { it.content.contains("Verdict: redo") }} asked for a redo)")
            if (analyses.isNotEmpty()) add("Analyses written by an analyst model: ${analyses.size} (${models(analyses) { it.content.substringBefore(']').substringAfter("· ").trim() }})")
            if (plans.isNotEmpty()) add("Plans written by a planner model: ${plans.size} (${models(plans) { it.content.substringBefore(']').substringAfter("· ").trim() }})")
            if (takeovers.isNotEmpty()) add("Turns the Oracul took over after the main model failed: ${takeovers.size} (${models(takeovers) { it.content.substringBefore(" took over").substringAfter(Oracul.GLYPH + " ").trim() }})")
            if (isEmpty()) add("Oracul: no other model has been consulted in this chat.")
        }
    }

    /** A pause between two messages longer than this is left out of the active time (lunch, the night). */
    const val PAUSE_LIMIT_MILLIS = 30 * 60 * 1000L

    /** The time from the first to the last message, and the same without the long pauses; null when fewer than two messages carry a time (an older chat). */
    fun spans(messages: List<ChatMessage>): Pair<Long, Long>? {
        val times = messages.map { it.at }.filter { it > 0 }
        if (times.size < 2) return null
        val total = (times.max() - times.min()).coerceAtLeast(0)
        val active = times.sorted().zipWithNext { a, b -> (b - a).takeIf { it <= PAUSE_LIMIT_MILLIS } ?: 0L }.sum()
        return total to active
    }

    /**
     * [messages] are the whole chat; [contextLimit] the context window in use (0 when unknown); the prices are the estimate per million tokens
     * from the settings; [sessionMillis] how long Arcana has been running.
     */
    fun describe(messages: List<ChatMessage>, model: String, contextLimit: Int, priceIn: Int, priceOut: Int, sessionMillis: Long, oraculSetup: String = "", sessionUsage: List<UsageRecord> = emptyList(), allUsage: List<UsageRecord> = emptyList()): String {
        val replies = messages.filter { it.role == "assistant" }
        val stats = replies.mapNotNull { it.stats }
        val prompt = stats.sumOf { it.promptTokens.toLong() }
        val generated = stats.sumOf { it.completionTokens.toLong() }
        val millis = stats.sumOf { it.totalMillis }
        val calls = replies.flatMap { it.toolCalls }.groupingBy { it.function.name }.eachCount().entries.sortedByDescending { it.value }
        val cost = prompt * priceIn / 1_000_000.0 + generated * priceOut / 1_000_000.0
        val last = stats.lastOrNull { it.promptTokens > 0 }
        return buildString {
            appendLine("Model: $model")
            appendLine("Messages: ${messages.size} (${messages.count { it.role == "user" && !Oracul.isOpinion(it) && !Compaction.isSummary(it) }} from the user, ${replies.size} replies, ${messages.count { it.role == "tool" }} tool results)")
            appendLine("Tokens: ${UsageStats.compact(prompt)} read (prompt, every request counted) + ${UsageStats.compact(generated)} generated = ${UsageStats.compact(prompt + generated)}")
            spans(messages)?.let { (total, active) ->
                appendLine("Chat time: ${describeDuration(total / 1000)} (first to last message, pauses included)")
                appendLine("Active time: ${describeDuration(active / 1000)} (pauses over ${PAUSE_LIMIT_MILLIS / 60_000} min left out)")
            }
            appendLine("Time spent generating: ${describeDuration(millis / 1000)}" + (if (generated > 0 && millis > 0) " (${"%.1f".format(generated * 1000.0 / millis)} tokens/s)" else ""))
            appendLine("Estimated cost: " + (if (priceIn == 0 && priceOut == 0) "none set (local model, free)" else "$${"%.4f".format(cost)} at $$priceIn / $$priceOut per million tokens in / out"))
            appendLine("Tool calls: ${calls.sumOf { it.value }}" + (if (calls.isEmpty()) "" else " (" + calls.joinToString { "${it.key} ×${it.value}" } + ")"))
            if (last != null && contextLimit > 0) {
                val used = last.promptTokens + last.completionTokens
                appendLine("Context window: the last request used ${UsageStats.compact(used.toLong())} of ${UsageStats.contextSize(contextLimit.toLong())} tokens (${"%.0f".format(used * 100.0 / contextLimit)}%)")
            } else appendLine("Context window: " + (if (contextLimit > 0) "${UsageStats.contextSize(contextLimit.toLong())} tokens, no reply measured yet" else "size unknown"))
            spendLines(sessionUsage, allUsage, priceIn, priceOut).forEach { appendLine(it) }
            oraculLines(messages, oraculSetup).forEach { appendLine(it) }
            append("Arcana has been running for ${describeDuration(sessionMillis / 1000)}.")
        }
    }
}
