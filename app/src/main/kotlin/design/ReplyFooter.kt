package com.arcana.brain.app.design

import com.arcana.brain.core.ReplyStats
import com.arcana.brain.core.UsageStats
import com.jakewharton.mosaic.ui.TextStyle

/**
 * The closing line under a finished reply: `✓ Done · 3.2s · ↑ 66  ↓ 235 tokens · 73 tok/s`. [plain] swaps the symbols for
 * words (the screen-reader mode). It belongs to no select-mode entry, so the cursor never rests on it.
 */
/** [step]: a round that ended in a tool call, so the job goes on; it is shown as a quiet "step", not as "Done". */
fun replyFooter(stats: ReplyStats, plain: Boolean, step: Boolean = false): Line {
    val time = if (stats.totalMillis > 0) "%.1fs".format(stats.seconds) else null
    val tokens = when {
        stats.promptTokens <= 0 -> if (plain) "${UsageStats.compact(stats.completionTokens.toLong())} reply tokens" else "↓ ${UsageStats.compact(stats.completionTokens.toLong())} tokens"
        plain -> "${UsageStats.compact(stats.promptTokens.toLong())} prompt + ${UsageStats.compact(stats.completionTokens.toLong())} reply tokens"
        else -> "↑ ${UsageStats.compact(stats.promptTokens.toLong())}  ↓ ${UsageStats.compact(stats.completionTokens.toLong())} tokens"
    }
    val speed = if (stats.tokensPerSecond >= 1) "%.0f tok/s".format(stats.tokensPerSecond) else null
    val head = if (step) (if (plain) "step" else "· step") else if (plain) "Done" else "✓ Done"
    val rest = listOfNotNull(time, tokens, speed).joinToString(" · ", prefix = " · ")
    val runs = listOf(
        if (step) Run(head, Palette.ConsoleDim) else Run(head, Palette.Green, style = TextStyle.Bold),
        Run(rest, Palette.ConsoleDim),
    )
    return Line(head + rest, runs = runs)
}
