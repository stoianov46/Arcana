package com.arcana.brain.core

/**
 * Finds the line in which a tool says it left something out ("… (cut after 20000 characters …)", "… and 40 more", "… 12 more lines …", "N characters left out"),
 * so the chat can show the user what the model was not given instead of hiding it behind a character count.
 */
object ToolCuts {
    private val marker = Regex("""^…\s.*|.*\((cut|shortened)\b.*|.*\d+ characters left out.*""", RegexOption.IGNORE_CASE)

    /** The first line of [result] that reports an omission, trimmed, or null when nothing was left out. */
    fun marker(result: String): String? =
        result.lineSequence().map { it.trim() }.firstOrNull { it.length in 3..400 && marker.matches(it) && ("cut" in it || "shortened" in it || "more" in it || "left out" in it || "continue" in it) }
}
