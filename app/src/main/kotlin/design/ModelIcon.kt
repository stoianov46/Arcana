package com.arcana.brain.app.design

/**
 * The glyph that goes in front of a model's name (`◆ qwen3:30b`): one per model family, so a chat that switches models shows who is
 * speaking at a glance. A family that is not listed gets [DEFAULT]; plain-glyph terminals get ASCII.
 */
object ModelIcon {
    const val DEFAULT = "◆"
    const val USER = "●"

    /** Lower-case name prefix → glyph. The first prefix that matches wins. */
    private val FAMILIES = listOf(
        "gemma" to "◆",
        "qwen" to "◈",
        "llama" to "▲",
        "mistral" to "◐",
        "mixtral" to "◑",
        "phi" to "◇",
        "deepseek" to "◉",
        "gpt" to "✦",
        "granite" to "▣",
        "smollm" to "▪",
        "command" to "◍",
        "claude" to "✺",
    )

    /** The glyph for [model] (an Ollama name such as `qwen3:30b` or `library/gemma4:12b`), or its ASCII stand-in when [plain]. */
    fun of(model: String, plain: Boolean = false): String {
        if (plain) return "*"
        val name = model.substringAfterLast('/').lowercase()
        return FAMILIES.firstOrNull { name.startsWith(it.first) }?.second ?: DEFAULT
    }

    /** The glyph in front of your own messages. */
    fun user(plain: Boolean = false): String = if (plain) ">" else USER

    /** `◈ qwen3:30b`: the glyph and the real model name. */
    fun label(model: String, plain: Boolean = false): String = "${of(model, plain)} $model"
}
