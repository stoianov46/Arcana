package com.arcana.brain.core

/**
 * Keeps a chat's system prompt the same from message to message, so Ollama can reuse what it already read: a changed first message makes the model read the whole chat again
 * (minutes on a long local chat). It is built once ([take] the first time, or after [reset]: a new or opened chat, a compaction) and kept while the chat's [key] stays the same
 * (the base prompt, the model, tools on or off, vault answers: what kind of chat it is). What may change on the way ([Prompts.SystemParts]: the date, the memory file, the tool
 * notes, the goal) does not rebuild it: the change is returned as a note ([Prompts.changesNote]) for the next message of the user, which is then frozen with it.
 */
class SystemFreeze {
    private var key: String? = null
    private var parts: Prompts.SystemParts? = null
    private var text = ""

    /** Forgets the frozen prompt: the next [take] builds it anew. */
    fun reset() { key = null; parts = null; text = "" }

    /**
     * The system prompt to send, and the note about what changed since it was frozen (blank when nothing did). [build] makes the prompt from [now] when it has to be built.
     * [commit] is true when a new message of the user is being frozen: only then is the note handed out and [now] becomes the state the next note compares with; between the
     * rounds of one turn (false) nothing is handed out, so a change during a turn is told with the next message instead of being lost.
     */
    fun take(key: String, now: Prompts.SystemParts, commit: Boolean, build: (Prompts.SystemParts) -> String): Pair<String, String> {
        val was = parts
        if (this.key != key || was == null) {
            this.key = key
            parts = now
            text = build(now)
            return text to ""
        }
        if (!commit) return text to ""
        parts = now
        return text to Prompts.changesNote(was, now)
    }
}
