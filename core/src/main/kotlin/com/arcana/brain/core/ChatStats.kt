package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/** The chat tool that tells the model how much the current chat has used; the chat answers it (see [ChatStats.describe]). */
const val CHAT_STATS = "chat_stats"
/** The same statistics as JSON, for a model that wants to analyse them (see [ChatStats.describeJson]). */
const val CHAT_STATS_JSON = "chat_stats_json"

val CHAT_STATS_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", CHAT_STATS)
        put("description", "Get the statistics of this chat so far: the model, tokens in and out, estimated cost, chat time (first to last message), active time (without long pauses), time spent generating, how many tool calls, how full the context window is, compaction (how many times this chat was compacted and how many tokens are left before auto-compact), and which other models were consulted (the Oracul: second opinions, comparisons, plans, takeovers, and the tokens each model spent). Use it when the user asks how much this chat used or cost, or to decide whether to wrap up.")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {}
            putJsonArray("required") {}
        }
    }
}

val CHAT_STATS_JSON_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", CHAT_STATS_JSON)
        put("description", "The same statistics as chat_stats, but as JSON with fixed field names (times in seconds, cost in dollars): model, messages, tokens, time, cost, tool calls, context use, compaction, Oracul use, and token spend per model. Use it when you need to read, compare or calculate with the numbers (a report property, a table); use chat_stats to tell the user in words.")
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

    /**
     * How this chat stands with compaction, for `chat_stats`: [used] tokens of the context in use now (null before the first reply; [estimated] after a compaction, until the next reply
     * says), auto-compact at [autoPercent] % of the window (0 = off). The number of compactions and the size of the latest summary come from the messages themselves.
     */
    data class CompactionState(val autoPercent: Int, val used: Int?, val estimated: Boolean = false)

    /** The compaction facts in fixed fields: shared by [compactionLine] and the JSON. [window] is the context window (0 = unknown). */
    class CompactionFacts(val count: Int, val lastSummaryTokens: Int, val autoPercent: Int, val threshold: Int?, val used: Int?, val estimated: Boolean, val left: Int?, val due: Boolean)

    fun compactionFacts(messages: List<ChatMessage>, window: Int, state: CompactionState): CompactionFacts {
        val summaries = messages.filter { Compaction.isSummary(it) }
        val lastTokens = summaries.lastOrNull()?.let { Compaction.tokens(Compaction.summaryText(it)) } ?: 0
        val threshold = if (state.autoPercent > 0 && window > 0) (window.toLong() * state.autoPercent / 100).toInt() else null
        val left = if (threshold != null && state.used != null) (threshold - state.used).coerceAtLeast(0) else null
        return CompactionFacts(summaries.size, lastTokens, state.autoPercent, threshold, state.used, state.estimated, left, left == 0)
    }

    /** `Compaction: compacted 2 times (latest summary ~1.2k tokens); auto-compact at 80% (~105k tokens): ~42k tokens left before it`. */
    fun compactionLine(f: CompactionFacts): String {
        val done = if (f.count == 0) "not compacted yet" else "compacted ${f.count} time${if (f.count == 1) "" else "s"} (latest summary ~${UsageStats.compact(f.lastSummaryTokens.toLong())} tokens; the model is sent it instead of the messages above it)"
        val auto = when {
            f.autoPercent <= 0 -> "auto-compact off (only /compact does it)"
            f.threshold == null -> "auto-compact at ${f.autoPercent}% of the window (its size is not known yet)"
            f.used == null -> "auto-compact at ${f.autoPercent}% (~${UsageStats.compact(f.threshold.toLong())} tokens), no reply measured yet"
            f.due -> "auto-compact at ${f.autoPercent}% (~${UsageStats.compact(f.threshold.toLong())} tokens): due, the next message compacts first"
            else -> "auto-compact at ${f.autoPercent}% (~${UsageStats.compact(f.threshold.toLong())} tokens): ${if (f.estimated) "~" else ""}${UsageStats.compact(f.left!!.toLong())} tokens left before it"
        }
        return "Compaction: $done; $auto"
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
    fun describe(messages: List<ChatMessage>, model: String, contextLimit: Int, priceIn: Int, priceOut: Int, sessionMillis: Long, oraculSetup: String = "", sessionUsage: List<UsageRecord> = emptyList(), allUsage: List<UsageRecord> = emptyList(), compaction: CompactionState? = null): String {
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
            compaction?.let { appendLine(compactionLine(compactionFacts(messages, contextLimit, it))) }
            spendLines(sessionUsage, allUsage, priceIn, priceOut).forEach { appendLine(it) }
            oraculLines(messages, oraculSetup).forEach { appendLine(it) }
            append("Arcana has been running for ${describeDuration(sessionMillis / 1000)}.")
        }
    }

    /**
     * The same numbers as [describe], as JSON for a model that wants to analyse them (the chat tool `chat_stats_json`). Every time is in seconds,
     * the cost in dollars; the Oracul and the token spend per model are included when they are known.
     */
    fun describeJson(messages: List<ChatMessage>, model: String, contextLimit: Int, priceIn: Int, priceOut: Int, sessionMillis: Long = 0, oraculSetup: String = "", sessionUsage: List<UsageRecord> = emptyList(), allUsage: List<UsageRecord> = emptyList(), compaction: CompactionState? = null): String {
        val replies = messages.filter { it.role == "assistant" }
        val stats = replies.mapNotNull { it.stats }
        val prompt = stats.sumOf { it.promptTokens.toLong() }
        val generated = stats.sumOf { it.completionTokens.toLong() }
        val millis = stats.sumOf { it.totalMillis }
        val calls = replies.flatMap { it.toolCalls }.groupingBy { it.function.name }.eachCount().entries.sortedByDescending { it.value }
        val cost = prompt * priceIn / 1_000_000.0 + generated * priceOut / 1_000_000.0
        val last = stats.lastOrNull { it.promptTokens > 0 }
        val span = spans(messages)
        fun seconds(ms: Long) = ms / 1000.0
        return buildJsonObject {
            put("model", model)
            putJsonObject("messages") {
                put("total", messages.size)
                put("user", messages.count { it.role == "user" && !Oracul.isOpinion(it) && !Compaction.isSummary(it) })
                put("replies", replies.size)
                put("tool_results", messages.count { it.role == "tool" })
            }
            putJsonObject("tokens") {
                put("prompt", prompt)
                put("completion", generated)
                put("total", prompt + generated)
            }
            putJsonObject("time") {
                // null when the chat is from before messages carried a time
                put("chat_seconds", span?.let { seconds(it.first) })
                put("active_seconds", span?.let { seconds(it.second) })
                put("pause_limit_seconds", PAUSE_LIMIT_MILLIS / 1000)
                put("generation_seconds", seconds(millis))
                put("tokens_per_second", if (generated > 0 && millis > 0) generated * 1000.0 / millis else 0.0)
            }
            putJsonObject("cost") {
                put("estimated_dollars", cost)
                put("price_in_per_million", priceIn)
                put("price_out_per_million", priceOut)
                put("free", priceIn == 0 && priceOut == 0)
            }
            putJsonObject("tools") {
                put("total_calls", calls.sumOf { it.value })
                putJsonObject("by_tool") { calls.forEach { (name, count) -> put(name, count) } }
            }
            putJsonObject("context") {
                put("limit", contextLimit)
                if (last != null && contextLimit > 0) {
                    val used = last.promptTokens + last.completionTokens
                    put("last_request_used", used)
                    put("percent_used", used * 100.0 / contextLimit)
                } else put("last_request_used", null as Int?)
            }
            if (compaction != null) putJsonObject("compaction") {
                val f = compactionFacts(messages, contextLimit, compaction)
                put("count", f.count)
                put("last_summary_tokens", f.lastSummaryTokens)
                put("auto_percent", f.autoPercent)
                put("auto_threshold_tokens", f.threshold)
                put("used_tokens", f.used)
                put("used_is_estimate", f.estimated)
                put("tokens_until_auto", f.left)
                put("due", f.due)
            }
            putJsonObject("oracul") {
                put("setup", oraculSetup)
                put("second_opinions", messages.count { it.role == "user" && it.content.startsWith(Oracul.MARK) })
                put("comparisons", messages.count { it.role == "user" && it.content.startsWith(Oracul.MARK_COMPARE) })
                put("plans", messages.count { Oracul.isPlan(it) })
                put("analyses", messages.count { Oracul.isAnalysis(it) })
                put("controller_verdicts", messages.count { Oracul.isControl(it) })
                put("takeovers", messages.count { it.role == "assistant" && it.content.startsWith(Oracul.TAKEOVER_MARK) })
            }
            putJsonObject("spend_since_start") {
                fun totals(t: UsageTotals, records: List<UsageRecord>, into: kotlinx.serialization.json.JsonObjectBuilder) {
                    into.put("prompt_tokens", t.promptTokens)
                    into.put("completion_tokens", t.completionTokens)
                    into.put("total_tokens", t.totalTokens)
                    into.put("requests", records.size)
                    into.put("estimated_dollars", UsageStats.estimatedCost(records, priceIn, priceOut))
                }
                putJsonArray("by_model") {
                    for ((m, records) in sessionUsage.groupBy { it.model }.entries.sortedByDescending { e -> e.value.sumOf { it.totalTokens.toLong() } }) {
                        add(buildJsonObject {
                            put("model", m)
                            totals(UsageStats.totals(records), records, this)
                            put("used_for", buildJsonObject { records.groupingBy { it.feature }.eachCount().forEach { (f, n) -> put(f, n) } })
                        })
                    }
                }
                putJsonObject("total") { totals(UsageStats.totals(sessionUsage), sessionUsage, this) }
            }
            putJsonObject("other_models_all_time") {
                for (feature in ORACUL_FEATURES) {
                    val records = allUsage.filter { it.feature == feature }
                    if (records.isEmpty()) continue
                    putJsonObject(feature) {
                        put("requests", records.size)
                        put("tokens", UsageStats.totals(records).totalTokens)
                        put("this_session", sessionUsage.count { it.feature == feature })
                    }
                }
            }
            put("arcana_running_seconds", seconds(sessionMillis))
        }.toString()
    }
}
