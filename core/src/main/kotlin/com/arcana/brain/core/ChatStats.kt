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
        put("description", "Get the statistics of this chat so far: the model, tokens in and out, estimated cost, time spent generating, how many tool calls, how full the context window is. Use it when the user asks how much this chat used or cost, or to decide whether to wrap up.")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {}
            putJsonArray("required") {}
        }
    }
}

/** What the current chat has used, worded for the model. */
object ChatStats {
    /**
     * [messages] are the whole chat; [contextLimit] the context window in use (0 when unknown); the prices are the estimate per million tokens
     * from the settings; [sessionMillis] how long Arcana has been running.
     */
    fun describe(messages: List<ChatMessage>, model: String, contextLimit: Int, priceIn: Int, priceOut: Int, sessionMillis: Long): String {
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
            appendLine("Messages: ${messages.size} (${messages.count { it.role == "user" }} from the user, ${replies.size} replies, ${messages.count { it.role == "tool" }} tool results)")
            appendLine("Tokens: ${UsageStats.compact(prompt)} read (prompt, every request counted) + ${UsageStats.compact(generated)} generated = ${UsageStats.compact(prompt + generated)}")
            appendLine("Time spent generating: ${describeDuration(millis / 1000)}" + (if (generated > 0 && millis > 0) " (${"%.1f".format(generated * 1000.0 / millis)} tokens/s)" else ""))
            appendLine("Estimated cost: " + (if (priceIn == 0 && priceOut == 0) "none set (local model, free)" else "$${"%.4f".format(cost)} at $$priceIn / $$priceOut per million tokens in / out"))
            appendLine("Tool calls: ${calls.sumOf { it.value }}" + (if (calls.isEmpty()) "" else " (" + calls.joinToString { "${it.key} ×${it.value}" } + ")"))
            if (last != null && contextLimit > 0) {
                val used = last.promptTokens + last.completionTokens
                appendLine("Context window: the last request used ${UsageStats.compact(used.toLong())} of ${UsageStats.contextSize(contextLimit.toLong())} tokens (${"%.0f".format(used * 100.0 / contextLimit)}%)")
            } else appendLine("Context window: " + (if (contextLimit > 0) "${UsageStats.contextSize(contextLimit.toLong())} tokens, no reply measured yet" else "size unknown"))
            append("Arcana has been running for ${describeDuration(sessionMillis / 1000)}.")
        }
    }
}
