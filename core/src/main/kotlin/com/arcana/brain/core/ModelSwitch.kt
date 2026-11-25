package com.arcana.brain.core

/**
 * Changing the chat model from the Chat (`/model`), without opening Settings: which installed model a typed name means, what `/model` was given, and which of your
 * messages is "the last task" to send again to the new model. The switch itself is the same setting as Settings → Ollama → Model.
 */
object ModelSwitch {
    /** What `/model` was typed with: a model name (blank = open the list) and whether the last task is sent again to it (`--retry`, or `retry` / `again` after the name). */
    data class Request(val name: String, val retry: Boolean)

    private val RETRY_WORDS = setOf("--retry", "-r", "retry", "again")

    /** `/model qwen3:30b --retry` → name `qwen3:30b`, retry; `/model --retry` alone is a retry with the list. */
    fun parse(args: String): Request {
        val words = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val retry = words.any { it.lowercase() in RETRY_WORDS }
        return Request(words.filterNot { it.lowercase() in RETRY_WORDS }.joinToString(" "), retry)
    }

    /** Which model [typed] means among [installed]: one installed model or a reason it is not one. */
    sealed interface Match {
        data class Found(val model: String) : Match
        data class Missing(val typed: String) : Match
        data class Ambiguous(val typed: String, val candidates: List<String>) : Match
    }

    /**
     * The installed model [typed] names: the exact name, the same name with or without `:latest`, else the only installed model whose name starts with it
     * (`inkling` → `inkling-2-mini:26b`), else the only one that contains it. Case does not matter. Several matches are [Match.Ambiguous], none [Match.Missing].
     */
    fun resolve(typed: String, installed: List<String>): Match {
        val t = typed.trim()
        installed.firstOrNull { it == t || ModelStatus.sameModel(it, t) }?.let { return Match.Found(it) }
        val lower = t.lowercase()
        installed.firstOrNull { it.lowercase() == lower || ModelStatus.sameModel(it.lowercase(), lower) }?.let { return Match.Found(it) }
        for (test in listOf<(String) -> Boolean>({ it.lowercase().startsWith(lower) }, { lower in it.lowercase() })) {
            val hits = installed.filter(test)
            if (hits.size == 1) return Match.Found(hits.single())
            if (hits.size > 1) return Match.Ambiguous(t, hits)
        }
        return Match.Missing(t)
    }

    /**
     * The message to send again after a switch: the last one **you** wrote, with its notes, pictures and files. Compaction summaries and the Oracul's messages
     * (opinions, comparisons, plans, verdicts) are written as the user's turn but are not yours, so they are skipped. Null when you wrote nothing yet.
     */
    fun lastTask(messages: List<ChatMessage>): ChatMessage? =
        messages.lastOrNull { it.role == "user" && !Compaction.isSummary(it) && !Oracul.isOpinion(it) && it.content.isNotBlank() }

    /**
     * The task as a new message, made now: the same text and attachments, without the reasoning level picked for that one message and without what was sent for it
     * the first time (the excerpts and notes are read again for the new model). A picture of a chat reopened from disk has no data any more and goes as its name only.
     */
    fun again(task: ChatMessage, now: Long = System.currentTimeMillis()): ChatMessage = ChatMessage(
        role = "user", content = task.content, images = task.images, imageNames = task.imageNames, noteNames = task.noteNames, files = task.files, at = now,
    )
}
