package com.arcana.brain.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One part of a request as the inspector lists it: a [title], about how many [tokens] it takes, the exact [text] the model is sent for it (opened with Enter), an optional
 * [detail] (what it is made of), and [depth] 1 for a piece of the part above it (the memory file inside the system prompt). [reused] is true when the same text was sent at
 * the same place in the request before, so Ollama can take it from its cache instead of reading it again.
 */
data class ContextPart(val title: String, val tokens: Int, val text: String, val detail: String = "", val depth: Int = 0, val reused: Boolean = false)

/**
 * What the model was sent with the latest request of a chat, part by part ([parts]), with the sum ([total]), the context [window] (0 = unknown), and [cachedTokens]:
 * the start of the request that matches the request before it word for word, which Ollama can reuse (it re-reads from the first difference on).
 */
data class ContextSnapshot(val model: String, val at: Long, val parts: List<ContextPart>, val total: Int, val window: Int, val cachedTokens: Int, val toolCount: Int) {
    /** The share of the window this request fills, 0 when the window is not known. */
    val share: Double get() = if (window > 0) (total.toDouble() / window).coerceIn(0.0, 1.0) else 0.0
}

/**
 * The context inspector: takes the request Arcana just built for the chat model (the messages and the tool definitions) and says what it is made of. It does not build
 * anything itself, so what it shows is what was sent: the system prompt with its pieces (memory file, what the tools are, the goal), the tool definitions, the summary of
 * a compaction, the earlier conversation, the message being answered with what Arcana added to it (vault excerpts, attached notes, what changed), and the tool rounds of
 * this turn. Token counts are estimates ([Compaction.tokens]); Ollama's own count is under each reply.
 */
object ContextInspector {
    /**
     * [request] is the request as sent (the system message first), [tools] the tool definitions offered with it (null = none); [memory], [toolsNote] and [goal] are the
     * pieces of the system prompt as Arcana has them now (a piece that is not in the frozen prompt any more is not listed), [typed] what the user typed for the message
     * being answered; [previous] the request before this one, to tell what Ollama can reuse.
     */
    fun inspect(
        request: List<ChatMessage>, tools: JsonArray?, model: String, window: Int,
        memory: String = "", toolsNote: String = "", goal: String = "", typed: String? = null,
        previous: List<ChatMessage>? = null, at: Long = System.currentTimeMillis(),
    ): ContextSnapshot {
        val parts = ArrayList<ContextPart>()
        // how far this request is the same as the one before: Ollama keeps what it read and starts again at the first message that differs
        val same = sharedPrefix(request, previous)
        val system = request.firstOrNull()?.takeIf { it.role == "system" }
        if (system != null) {
            val text = system.content
            parts += ContextPart("System prompt", Compaction.tokens(text), text, "Arcana's instructions, the date, what the tools are, the memory file, the goal", reused = same > 0)
            val pieces = listOf(
                Triple("Memory file (ARCANA.md)", memory.trim(), "your standing instructions, sent with every message"),
                Triple("Tools, folders and skills", toolsNote.trim(), "where things are, what is switched off, the skills catalog"),
                Triple("Goal (/goal)", goal.trim(), "the goal of this chat"),
            ).filter { it.second.isNotEmpty() && it.second in text }
            var rest = Compaction.tokens(text)
            for ((title, piece, detail) in pieces) {
                val tokens = Compaction.tokens(piece)
                rest -= tokens
                parts += ContextPart(title, tokens, piece, detail, depth = 1, reused = same > 0)
            }
            if (pieces.isNotEmpty()) parts += ContextPart("Instructions and the date", rest.coerceAtLeast(0), pieces.fold(text) { t, p -> t.replace(p.second, "[${p.first} above]") }, "the base prompt (Settings → Console → System prompt) and Arcana's rules", depth = 1, reused = same > 0)
        }
        val toolList = tools?.mapNotNull { (it as? JsonObject)?.let(::toolName)?.let { name -> name to Compaction.tokens(it.toString()) } }.orEmpty()
        if (tools != null && tools.isNotEmpty()) {
            val text = toolList.sortedByDescending { it.second }.joinToString("\n") { (name, t) -> "${name.padEnd(22)} ~$t tokens" } + "\n\n" + tools.joinToString("\n\n") { it.toString() }
            parts += ContextPart("Tool definitions", Compaction.tokens(tools.toString()), text, "${toolList.size} tools: their names, what they do and their arguments", reused = same > 0)
        }
        val body = if (system != null) request.drop(1) else request
        val offset = if (system != null) 1 else 0
        val lastUser = body.indexOfLast { it.role == "user" && !Compaction.isSummary(it) }
        val summaryAt = body.indexOfFirst { Compaction.isSummary(it) }
        if (summaryAt >= 0) {
            val m = body[summaryAt]
            parts += ContextPart("Summary of the earlier chat", Compaction.messageTokens(m), m.content, "made by a compaction; the model is not sent what is above it", reused = same > summaryAt + offset)
        }
        val earlierFrom = if (summaryAt >= 0) summaryAt + 1 else 0
        val earlier = if (lastUser > earlierFrom) body.subList(earlierFrom, lastUser) else emptyList()
        if (earlier.isNotEmpty()) {
            val yours = earlier.count { it.role == "user" }
            val replies = earlier.count { it.role == "assistant" }
            val results = earlier.count { it.role == "tool" }
            parts += ContextPart(
                "Earlier conversation", earlier.sumOf { Compaction.messageTokens(it) }, transcript(earlier),
                "${earlier.size} messages: $yours yours, $replies replies, $results tool results",
                reused = same >= earlierFrom + earlier.size + offset,
            )
        }
        if (lastUser >= 0) {
            val m = body[lastUser]
            val tokens = Compaction.messageTokens(m)
            val reused = same > lastUser + offset
            parts += ContextPart("The message being answered", tokens, m.content, "what you wrote and what Arcana added to it", reused = reused)
            val own = typed?.trim().orEmpty()
            if (own.isNotEmpty() && m.content.length > own.length && own in m.content) {
                val typedTokens = Compaction.tokens(own)
                parts += ContextPart("What you typed", typedTokens, own, depth = 1, reused = reused)
                parts += ContextPart("Added by Arcana", (tokens - typedTokens).coerceAtLeast(0), m.content.replace(own, "[what you typed]"), addedDetail(m.content), depth = 1, reused = reused)
            }
            val turn = body.drop(lastUser + 1)
            if (turn.isNotEmpty()) {
                val calls = turn.flatMap { it.toolCalls }.size
                parts += ContextPart("This turn so far", turn.sumOf { Compaction.messageTokens(it) }, transcript(turn), "$calls tool call${if (calls == 1) "" else "s"} and ${turn.count { it.role == "tool" }} results since your message", reused = same >= request.size)
            }
        }
        val total = request.sumOf { Compaction.messageTokens(it) } + if (tools != null) Compaction.tokens(tools.toString()) else 0
        val cached = if (same <= 0) 0 else request.take(same).sumOf { Compaction.messageTokens(it) } + if (tools != null) Compaction.tokens(tools.toString()) else 0
        return ContextSnapshot(model, at, parts, total, window, cached.coerceAtMost(total), toolList.size)
    }

    /** How many messages at the start of [now] are the same as in [before] (role, text and tool calls): what Ollama can reuse. */
    fun sharedPrefix(now: List<ChatMessage>, before: List<ChatMessage>?): Int {
        if (before == null) return 0
        var i = 0
        while (i < now.size && i < before.size && same(now[i], before[i])) i++
        return i
    }

    private fun same(a: ChatMessage, b: ChatMessage) = a.role == b.role && a.content == b.content && a.toolCalls == b.toolCalls && a.toolName == b.toolName

    private fun toolName(definition: JsonObject): String? =
        (definition["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

    /** What Arcana put around a message, in words: `vault excerpts (3 notes) · 1 attached note · what changed since the chat began`. */
    internal fun addedDetail(content: String): String = listOfNotNull(
        Regex("<note path=").findAll(content).count().takeIf { it > 0 }?.let { "vault excerpts ($it note${if (it == 1) "" else "s"})" }
            ?: "vault excerpts (none found)".takeIf { content.startsWith("Vault excerpts:") },
        Regex("\n---\nAttached note ").findAll(content).count().takeIf { it > 0 }?.let { "$it attached note${if (it == 1) "" else "s"}" },
        "a skill".takeIf { "\n---\nSKILL “" in content },
        "what changed since the chat began".takeIf { "changed; follow the new version" in content || "What you were told about your tools" in content || "The goal of this chat" in content },
    ).joinToString(" · ").ifEmpty { "notes, files or excerpts sent with it" }

    /** Messages as the inspector shows them when a part is opened: who, then exactly what was sent (tool calls with their arguments). */
    fun transcript(messages: List<ChatMessage>): String = messages.joinToString("\n\n") { m ->
        val who = when (m.role) {
            "user" -> if (Compaction.isSummary(m)) "SUMMARY" else "YOU"
            "assistant" -> "MODEL"
            "tool" -> "TOOL RESULT (${m.toolName ?: "tool"})"
            else -> m.role.uppercase()
        }
        val calls = m.toolCalls.joinToString("") { "\n→ ${it.name} ${it.arguments}" }
        "── $who · ~${Compaction.messageTokens(m)} tokens\n${m.content}$calls"
    }

    /** `▰▰▰▱▱▱▱▱`: [tokens] as a share of [of], in [cells] cells (at least one filled cell for anything above zero). */
    fun bar(tokens: Int, of: Int, cells: Int, plain: Boolean = false): String {
        if (of <= 0 || cells <= 0) return ""
        val filled = (tokens.toDouble() / of * cells).toInt().coerceIn(0, cells).let { if (it == 0 && tokens > 0) 1 else it }
        return if (plain) "#".repeat(filled) + ".".repeat(cells - filled) else "▰".repeat(filled) + "▱".repeat(cells - filled)
    }
}
