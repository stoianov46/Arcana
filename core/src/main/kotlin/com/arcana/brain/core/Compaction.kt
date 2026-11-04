package com.arcana.brain.core

/**
 * Compacting a long chat: the older part is summarized into one message, and from then on the model sees that summary plus what
 * came after it instead of everything. Nothing is deleted: the summary is inserted into the chat where the cut is, so the messages
 * above it stay on screen and in saved notes; only [forModel] hides them from the model.
 */
object Compaction {
    /** The first line of a summary message; it is how a summary is told from a message of the user. */
    const val MARK = "[Summary of the earlier conversation]"

    /** How many of the user's latest turns stay word for word after a compaction. */
    const val KEEP_USER_TURNS = 2

    /** At least this many messages must be before the cut, or a summary would save nothing. */
    const val MIN_MESSAGES = 4

    /** The most transcript text given to the model to summarize (a longer one keeps its start and its end). */
    private const val MAX_TRANSCRIPT = 30_000

    private const val TOOL_RESULT_CHARS = 400

    fun isSummary(message: ChatMessage): Boolean = message.role == "user" && message.content.startsWith(MARK)

    /** The text of a summary message without its mark. */
    fun summaryText(message: ChatMessage): String = message.content.removePrefix(MARK).trim()

    fun summaryMessage(summary: String): ChatMessage = ChatMessage.user("$MARK\n\n${summary.trim()}")

    /** What the model is sent: everything from the latest summary on (the summary included), or the whole chat when there is none. */
    fun forModel(messages: List<ChatMessage>): List<ChatMessage> {
        val last = messages.indexOfLast { isSummary(it) }
        return (if (last < 0) messages else messages.subList(last, messages.size)).filter { !it.displayOnly }
    }

    /**
     * Where a new summary would go: the index in [messages] of the oldest of the user's last [KEEP_USER_TURNS] turns (which stay as they are; with [keepTokens] the latest turns that fit in that many tokens stay instead),
     * or null when there is too little before it to be worth a summary.
     */
    fun cutPoint(messages: List<ChatMessage>, keepTokens: Int = 0): Int? {
        val from = messages.indexOfLast { isSummary(it) }.coerceAtLeast(0)
        val turns = messages.indices.filter { it >= from && messages[it].role == "user" && !isSummary(messages[it]) && !Oracul.isOpinion(messages[it]) }
        val keep = if (keepTokens > 0) keptTurns(messages, turns, keepTokens) else KEEP_USER_TURNS
        if (keep >= 1 && turns.size >= keep) {
            val cut = turns[turns.size - keep]
            if (cut - from >= MIN_MESSAGES) return cut
        }
        return insideTurn(messages, from, keepTokens)
    }

    /**
     * A chat that is one long turn (a request and a long run of tool rounds) has no earlier turn to summarize: cut between two rounds instead, at an assistant message,
     * so the latest rounds that fit in [keepTokens] (a quarter of nothing is not kept: at least the last round) stay and the rounds before them become the summary.
     */
    private fun insideTurn(messages: List<ChatMessage>, from: Int, keepTokens: Int): Int? {
        var tokens = 0
        var cut = -1
        for (i in messages.indices.reversed()) {
            if (i <= from) break
            val m = messages[i]
            tokens += (m.content.length + m.toolCalls.sumOf { c -> c.function.arguments.toString().length }) / 4 + 1
            if (m.role == "assistant" && !isSummary(m) && tokens >= keepTokens.coerceAtLeast(1)) { cut = i; break }
        }
        // the latest round always stays, so the cut is never the last message's round start when it is the only one
        return if (cut > 0 && cut - from >= MIN_MESSAGES) cut else null
    }

    /**
     * How many of the latest turns fit in [keepTokens] (a turn runs from a message of the user to the next one; text counts about 4 characters per token): as many as fit, and
     * always at least the latest turn, which is the one being worked on.
     */
    private fun keptTurns(messages: List<ChatMessage>, turns: List<Int>, keepTokens: Int): Int {
        var tokens = 0
        var kept = 0
        for (i in turns.indices.reversed()) {
            val end = if (i == turns.lastIndex) messages.size else turns[i + 1]
            val size = messages.subList(turns[i], end).sumOf { (it.content.length + it.toolCalls.sumOf { c -> c.function.arguments.toString().length }) / 4 + 1 }
            if (kept >= 1 && tokens + size > keepTokens) break
            tokens += size
            kept++
        }
        return kept
    }

    /** The part of [messages] a summary made at [cut] would replace for the model: from the latest summary (kept, so summaries chain) up to [cut]. */
    fun toSummarize(messages: List<ChatMessage>, cut: Int): List<ChatMessage> = forModel(messages.subList(0, cut))

    /** [part] as plain text for the summarizer; tool results are shortened, and a very long text keeps its start and its end. */
    fun transcript(part: List<ChatMessage>): String {
        val text = part.joinToString("\n\n") { m ->
            when {
                isSummary(m) -> "EARLIER SUMMARY:\n${summaryText(m)}"
                m.role == "user" -> "USER:\n${m.content}" + m.noteNames.joinToString("") { "\n(attached note: $it)" } + m.files.joinToString("") { "\n(attached file: ${it.name})" }
                m.role == "tool" -> "TOOL RESULT (${m.toolName ?: "tool"}):\n" + m.content.take(TOOL_RESULT_CHARS) + if (m.content.length > TOOL_RESULT_CHARS) "\n… (${m.content.length} characters in all)" else ""
                else -> "ASSISTANT:\n${m.content}" + m.toolCalls.joinToString("") { "\n(called ${it.function.name})" }
            }
        }
        return if (text.length <= MAX_TRANSCRIPT) text else text.take(MAX_TRANSCRIPT / 2) + "\n\n… (the middle is left out) …\n\n" + text.takeLast(MAX_TRANSCRIPT / 2)
    }

    const val INSTRUCTIONS = "You compress a conversation between a user and an assistant so the work can go on from a short text. " +
        "Write a concise but complete summary: what the user wants, what was decided, the facts, numbers, names, file and note paths that matter (copy them exactly), " +
        "what was done and with what result, and what is still open. Keep the language of the conversation. Write only the summary, no introduction."
}
