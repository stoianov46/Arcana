package com.arcana.brain.core

/**
 * What a captured thought says it is: a prefix names the kind explicitly (`todo: buy a domain`, `question: what is the outbox pattern`,
 * `research: compare KMP and Flutter`, `thought: …`); `? ` still means research, as it always did. No prefix: a plain thought, which stays
 * the note it always was and is not a work item.
 */
object WorkCapture {
    data class Captured(val kind: WorkKind, val text: String)

    private val PREFIXES: List<Pair<Regex, WorkKind>> = listOf(
        Regex("""^(todo|task):\s+""", RegexOption.IGNORE_CASE) to WorkKind.Todo,
        Regex("""^(question|q):\s+""", RegexOption.IGNORE_CASE) to WorkKind.Question,
        Regex("""^(research):\s+""", RegexOption.IGNORE_CASE) to WorkKind.Research,
        Regex("""^\?\s+""") to WorkKind.Research,
        Regex("""^(thought):\s+""", RegexOption.IGNORE_CASE) to WorkKind.Thought,
    )

    /** The kind and the text of [thought] when it starts with a kind prefix and has text after it; null for a plain thought. */
    fun parse(thought: String): Captured? {
        val t = thought.trim()
        for ((prefix, kind) in PREFIXES) {
            val m = prefix.find(t) ?: continue
            val rest = t.substring(m.range.last + 1).trim()
            return if (rest.isEmpty()) null else Captured(kind, rest)
        }
        return null
    }
}
