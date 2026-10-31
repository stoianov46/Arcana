package com.arcana.brain.core

/**
 * Two cheap checks against a model that goes round in circles without any error: text that keeps repeating while it is being written (a
 * reasoning model re-deriving the same paragraph, an answer that chants one sentence), and tool calls that keep asking for the same thing.
 * Neither needs another model. See docs/functionality.md ("Loop guard").
 */
object LoopGuard {
    /** The length of the stretch of text that is looked for again earlier in the text. */
    const val PROBE = 80

    /** How far back the text is searched; a loop of whole paragraphs still shows up, and the search stays cheap. */
    const val WINDOW = 12_000

    /** The text is checked after about this many new characters, not after every piece of the stream. */
    const val CHECK_EVERY = 160

    /**
     * True when the last [PROBE] characters of [text] already stand [repeats] times (not overlapping) in its last [WINDOW] characters: the model is
     * writing the same thing again and again. [repeats] below 2 switches the check off. A probe with hardly any letters (a table rule, a line of
     * dashes) or only a few different characters never counts, and text inside an open code fence is not checked.
     */
    fun repeating(text: String, repeats: Int): Boolean {
        if (repeats < 2 || text.length < PROBE * 2) return false
        if (fenceOpen(text)) return false
        val probe = text.takeLast(PROBE)
        if (probe.count { it.isLetterOrDigit() } < PROBE / 4 || probe.toSet().size < 12) return false
        val tail = text.takeLast(WINDOW)
        var count = 0
        var from = tail.length
        while (count < repeats) {
            val at = tail.lastIndexOf(probe, from - 1)
            if (at < 0) return false
            count++
            from = at - PROBE + 1 // the next hit must not overlap this one
        }
        return true
    }

    private fun fenceOpen(text: String): Boolean = Regex("```").findAll(text).count() % 2 == 1

    /** The text up to where the loop starts: [text] cut after the first occurrence of its repeated probe, so the first round of it is kept and the rest dropped. */
    fun withoutLoop(text: String): String {
        val probe = text.takeLast(PROBE)
        val first = text.indexOf(probe)
        return if (first < 0 || first + PROBE >= text.length) text else text.substring(0, first + PROBE).trimEnd()
    }

    /** What the model is told, out of sight, when its text went round in circles and the round was stopped. */
    const val TEXT_NUDGE = "You were repeating yourself: the same text came again and again, so it was stopped. Do not think it through again from the start. " +
        "Take the decision you were circling around, or the best answer you have so far, and give it now in a few sentences. Say what is still unclear."

    /** The first warning to a model that repeats a call, and the last one before tools are taken away. */
    const val CALLS_NUDGE = "You keep making calls you already made, with the same arguments, and nothing has changed since. The result will be the same. " +
        "Step back: say in one sentence what you are trying to find out, then use a different tool or different arguments, or answer with what you already have."
    const val HALT_NUDGE = "You are still repeating the same calls. Tools are switched off for the rest of this question. Answer now with what you have found so far, " +
        "and say plainly what you could not find or do."

    /** What a turn's repeated calls lead to. */
    enum class Step { None, Warn, Halt }

    /**
     * Counts the calls of one turn that repeat an earlier call (same tool, same arguments, nothing changed in between, a failed one too). The
     * [warnAt]-th repeat is a [Step.Warn] and the one at twice that is a [Step.Halt]; `0` = off. A call that changes something, or a pause,
     * makes the earlier ones history: [forget].
     */
    class Calls(private val warnAt: () -> Int) {
        private val seen = HashSet<String>()
        private var repeats = 0

        /** Notes one call and says what the model should be told now. */
        fun note(key: String): Step {
            val limit = warnAt()
            if (limit <= 0) return Step.None
            if (seen.add(key)) return Step.None
            repeats++
            return when {
                repeats >= limit * 2 -> Step.Halt
                repeats == limit -> Step.Warn
                else -> Step.None
            }
        }

        /** Something was changed or run: an older call, asked again, is no longer the same question. */
        fun forget() = seen.clear()

        /** A new question starts. */
        fun reset() { seen.clear(); repeats = 0 }

        /** True once the repeats have gone as far as [Step.Halt]. */
        val halted: Boolean get() = warnAt().let { it > 0 && repeats >= it * 2 }
    }
}
