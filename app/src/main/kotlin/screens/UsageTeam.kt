package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import com.arcana.brain.app.design.Line
import com.arcana.brain.app.design.OutputState
import com.arcana.brain.app.design.Palette
import com.arcana.brain.app.design.Panel
import com.arcana.brain.app.design.ScrollView
import com.arcana.brain.app.design.priceText
import com.arcana.brain.app.design.wrap
import com.arcana.brain.core.TeamStats
import com.arcana.brain.core.UsageStats
import com.jakewharton.mosaic.ui.Color

/** `████░░░░ 38%`: [share] of [cells] cells (`#` and `-` with plain glyphs). */
internal fun shareBar(share: Double, cells: Int, plain: Boolean): String {
    val filled = Math.round(share.coerceIn(0.0, 1.0) * cells).toInt().coerceIn(0, cells)
    return (if (plain) "#" else "█").repeat(filled) + (if (plain) "-" else "░").repeat(cells - filled)
}

private fun pct(share: Double) = "%.0f%%".format(share * 100)

/**
 * The Team page of the Usage screen as lines of text: what the orchestration costs and what it did, in plain words. It is text in a scrolling view, so nothing is cut off in a
 * short or narrow window: every row is wrapped to [width] and the rest is reached by scrolling.
 */
internal fun teamLines(stats: TeamStats, width: Int, plain: Boolean, priceIn: Int, priceOut: Int): List<Line> {
    val out = ArrayList<Line>()
    fun heading(text: String) { if (out.isNotEmpty()) out += Line(""); out += Line(text, Palette.Accent, bold = true) }
    fun row(text: String, color: Color = Palette.Text) { wrap(text, width.coerceAtLeast(20)).forEach { out += Line(it, color) } }
    fun none(text: String) = row("  $text", Palette.Dim)

    val bar = (width - 42).coerceIn(6, 24)
    heading("WHERE THE TOKENS GO")
    val total = stats.chatTokens + stats.helperTokens
    if (total == 0L) none("No replies in this period.") else {
        row("  Chat model     ${shareBar(1 - stats.helperShare, bar, plain)} ${pct(1 - stats.helperShare).padStart(4)}  ${UsageStats.compact(stats.chatTokens)} tok  ${priceText(stats.chatCost)}")
        row("  Helper models  ${shareBar(stats.helperShare, bar, plain)} ${pct(stats.helperShare).padStart(4)}  ${UsageStats.compact(stats.helperTokens)} tok  ${priceText(stats.helperCost)}", if (stats.helperShare >= 0.5) Palette.Orange else Palette.Text)
        stats.helpers.forEach { (name, t) -> row("    ${name.padEnd(11)} ${t.requests} call${if (t.requests == 1) "" else "s"} · ${UsageStats.compact(t.totalTokens)} tok · avg ${if (t.requests == 0) 0 else t.millis / t.requests / 1000}s", Palette.Muted) }
        row("  Orchestration overhead: helpers took ${pct(stats.helperShare)} of the tokens (${pct(stats.helperCostShare)} of the estimated price at ${priceIn}/${priceOut} per M).", Palette.Muted)
    }

    heading("MODELS")
    if (stats.models.isEmpty()) none("No replies in this period.") else stats.models.forEach { m ->
        val t = m.totals
        row("  ${m.model.take(24).padEnd(24)} ${t.requests.toString().padStart(4)} repl${if (t.requests == 1) "y" else "ies"}  ${UsageStats.compact(t.totalTokens).padStart(6)} tok  ${if (t.tokensPerSecond > 0) "%.0f tok/s".format(t.tokensPerSecond) else "–"}  avg ${if (t.requests == 0) 0 else t.millis / t.requests / 1000}s")
    }

    heading("FLOW")
    if (stats.flowRuns == 0) none("No message went through a flow in this period (/flow switches it on).") else {
        row("  ${stats.flowRuns} message${if (stats.flowRuns == 1) "" else "s"} went through a flow; ${stats.flowFirstTime} (${pct(stats.flowFirstTimeShare ?: 0.0)}) were accepted at the first check, without a redo.")
        row("  Redos: ${stats.redos}" + (if (stats.redoLimits > 0) " · redo limit reached ${stats.redoLimits}×" else ""))
        if (stats.checksPassed + stats.checksFailed > 0) row("  Check command: ${stats.checksPassed} passed, ${stats.checksFailed} failed", if (stats.checksFailed > 0) Palette.Orange else Palette.Green)
    }

    heading("OPINIONS AND VERDICTS")
    val opinions = stats.opinions.values.sum()
    if (opinions == 0 && stats.verdicts.isEmpty()) none("No second opinions in this period.") else {
        if (opinions > 0) row("  Second opinions: $opinions (" + stats.opinions.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" } + ")")
        if (stats.verdicts.isNotEmpty()) {
            row("  Judge verdicts: agree ${stats.verdicts["agree"] ?: 0} · partly ${stats.verdicts["partly"] ?: 0} · disagree ${stats.verdicts["disagree"] ?: 0}" + (stats.verdicts["unclear"]?.let { " · unclear $it" } ?: ""))
            if (stats.autoRevises > 0) row("  Answers revised after the judge agreed (automatically or by your choice): ${stats.autoRevises}×")
            stats.opinionsConfirmed?.let { row("  The judge confirmed ${pct(it)} of the opinions it saw: ${if (it < 0.5) "most opinions were wrong or unneeded, so the Oracul model may be too weak or too eager" else "the second opinions are mostly worth having"}.", Palette.Muted) }
        }
    }

    heading("GOALS")
    if (stats.goalsSet == 0) none("No /goal in this period.") else
        row("  Set ${stats.goalsSet} · met ${stats.goalsMet} · stopped at the turn limit ${stats.goalsStopped} · still open ${(stats.goalsSet - stats.goalsMet - stats.goalsStopped).coerceAtLeast(0)}")

    heading("ROUTER AND SPECIALISTS")
    if (stats.routes.isEmpty() && stats.consults.isEmpty()) none("Nothing was routed or consulted in this period.") else {
        stats.routes.forEach { (what, n) -> row("  routed ${what} ×$n") }
        stats.consults.forEach { (what, n) -> row("  consulted ${what} ×$n") }
    }

    heading("HOUSEKEEPING")
    row("  Cancelled ${stats.cancels}× · compacted ${stats.compacts}×" + (if (stats.todoNudges > 0) " · sent back to open todo steps ${stats.todoNudges}×" else ""))
    if (stats.loops.isNotEmpty()) row("  Loop guard: " + listOf("text" to "rounds stopped for repeating text", "calls" to "warnings for repeated calls", "halt" to "tool loops ended").mapNotNull { (k, label) -> stats.loops[k]?.let { "$it $label" } }.joinToString(" · "))
    row("  Memory: ${stats.memoryDuplicates} duplicate${if (stats.memoryDuplicates == 1) "" else "s"} skipped · ${stats.memoryRefused} dead path${if (stats.memoryRefused == 1) "" else "s"} refused")
    row("  Files and notes: " + (if (stats.artifacts.isEmpty()) "none written" else stats.artifacts.joinToString(" · ") { "${it.first} ${it.second}" }))
    return out
}

/** The Team page: [teamLines] in a scrolling view of [rows] rows. */
@Composable
internal fun TeamPage(stats: TeamStats, output: OutputState, rows: Int, plain: Boolean, priceIn: Int, priceOut: Int) {
    Panel("Team: what the orchestration cost and did") {
        ScrollView(teamLines(stats, panelWidth(), plain, priceIn, priceOut), (rows - 3).coerceAtLeast(3), output)
    }
}
