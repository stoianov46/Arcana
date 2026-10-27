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
        return if (last < 0) messages else messages.subList(last, messages.size)
    }

    /**
     * Where a new summary would go: the index in [messages] of the oldest of the user's last [KEEP_USER_TURNS] turns (which stay as they are),
     * or null when there is too little before it to be worth a summary.
     */
    fun cutPoint(messages: List<ChatMessage>): Int? {
        val from = messages.indexOfLast { isSummary(it) }.coerceAtLeast(0)
        val turns = messages.indices.filter { it >= from && messages[it].role == "user" && !isSummary(messages[it]) }
        if (turns.size < KEEP_USER_TURNS) return null
        val cut = turns[turns.size - KEEP_USER_TURNS]
        return if (cut - from >= MIN_MESSAGES) cut else null
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
