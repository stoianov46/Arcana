package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.Chart
import com.arcana.brain.app.design.Label
import com.arcana.brain.app.design.LocalContentWidth
import com.arcana.brain.app.design.TabLabel
import com.arcana.brain.app.design.Palette
import com.arcana.brain.core.BrainHealth
import com.arcana.brain.core.BrainSnapshot
import com.arcana.brain.core.BrainStats
import com.arcana.brain.core.Bottleneck
import com.arcana.brain.core.HygieneReport
import com.arcana.brain.core.KindPipeline
import com.arcana.brain.core.Reports
import com.arcana.brain.core.Vault
import com.arcana.brain.core.VaultIndex
import com.arcana.brain.core.WorkStore
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/** The pages of the Brain screen: one overview with everything, and a page each for the work, the health of the vault and the activity. */
internal enum class BrainTab(val title: String) { Overview("Overview"), Work("Work"), Health("Health"), Activity("Activity") }

/** The weeks the charts look back over, in the order `w` cycles through them (the first is the default). */
internal val BRAIN_WINDOWS = listOf(12, 8, 4)

/** Reads the vault and the logs and computes what the pages draw. Reads files: call it off the UI thread. */
internal fun computeSnapshot(app: AppContext, vault: Vault, index: VaultIndex, health: BrainHealth, hygiene: HygieneReport?, weeks: Int): BrainSnapshot {
    val reports = Reports.scan(vault)
    val work = WorkStore(vault).items()
    val done = reports.filter { it.status.equals("done", ignoreCase = true) }.map { it.title }.toSet()
    return BrainStats.compute(weeks, index.notes, health, reports, work, done, app.usage.toList(), hygiene, LocalDate.now())
}

@Composable
internal fun BrainTabBar(tab: BrainTab, weeks: Int) {
    Row {
        BrainTab.entries.forEach { t ->
            val on = t == tab
            TabLabel(t.title, on)
            Label(" ")
        }
        Label("│ ", color = Palette.Dim)
        Label("$weeks weeks", color = Palette.Muted)
        Label("  (Tab pages · w window)", color = Palette.Dim)
    }
}

@Composable
private fun inner(): Int = (LocalContentWidth.current - 4).coerceAtLeast(24)

private fun levelColor(percent: Int): Color = when { percent >= 85 -> Palette.CyanSoft; percent >= 60 -> Palette.AccentSoft; else -> Palette.OrangeSoft }

@Composable
private fun Section(title: String) = Label(title, color = Palette.Muted, textStyle = TextStyle.Bold)

@Composable
private fun Metric(label: String, spark: String, value: String, color: Color) {
    Row {
        Label(label.padEnd(15), color = Palette.Muted)
        Label(spark, color = color)
        Label("  $value", color = Palette.Text)
    }
}

@Composable
private fun PercentBar(label: String, percent: Int, detail: String = "") {
    val width = (inner() - 11 - 5 - detail.length - 2).coerceIn(6, 40)
    Row {
        Label(label.padEnd(10), color = Palette.Muted)
        val (filled, empty) = Chart.barParts(percent, 100, width)
        Label(filled, color = levelColor(percent))
        Label(empty, color = Palette.Dim)
        Label(" ${percent.toString().padStart(3)}%", color = Palette.Text)
        if (detail.isNotEmpty()) Label("  $detail", color = Palette.Dim)
    }
}

@Composable
private fun CountBar(label: String, value: Int, max: Int, detail: String = "", color: Color = Palette.CyanSoft, valueText: String = value.toString()) {
    val width = (inner() - 16 - 5 - detail.length - 2).coerceIn(6, 40)
    Row {
        Label(label.take(15).padEnd(16), color = Palette.Muted)
        val (filled, empty) = Chart.barParts(value, max, width)
        Label(filled, color = color)
        Label(empty, color = Palette.Dim)
        Label(" ${valueText.padStart(4)}", color = Palette.Text)
        if (detail.isNotEmpty()) Label("  $detail", color = Palette.Dim)
    }
}

private fun trendText(t: Double?): String = when {
    t == null -> ""
    t >= 1.15 -> "▲ ${(t * 100).toInt()}% of your usual pace"
    t <= 0.5 -> "▼ ${(t * 100).toInt()}% of your usual pace"
    else -> "≈ ${(t * 100).toInt()}% of your usual pace"
}

private fun dayText(d: com.arcana.brain.core.DayPoint): String = "${d.day.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.ENGLISH)} ${d.day.dayOfMonth} ${d.day.month.getDisplayName(DayStyle.SHORT, Locale.ENGLISH)} (${d.changed})"

/** The pulse: how much the vault changes and how much the models are used, week by week. */
@Composable
internal fun PulseBlock(s: BrainSnapshot) {
    Section("PULSE · last ${s.weeks} weeks")
    Metric("notes changed", Chart.spark(s.weekly.map { it.changed.toLong() }), "${s.weekly.lastOrNull()?.changed ?: 0} this week", Palette.AccentSoft)
    Metric("tokens used", Chart.spark(s.tokensByWeek), com.arcana.brain.core.UsageStats.compact(s.tokensByWeek.lastOrNull() ?: 0L) + " this week", Palette.CyanSoft)
    Label("${s.changedInWindow} notes changed" + (s.busiestDay?.let { " · busiest ${dayText(it)}" } ?: "") + (trendText(s.activityTrend).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), color = Palette.Dim)
}

/** Linked, tagged, reports done and work done as bars: green is good, red needs attention. */
@Composable
internal fun HealthBlock(s: BrainSnapshot) {
    Section("HEALTH")
    PercentBar("linked", s.health.connectedPercent, "${s.health.orphans} islands")
    PercentBar("tagged", s.health.taggedPercent, "${s.health.untagged} without")
    PercentBar("reports", s.reports.donePercent, "${s.reports.notDone} not done")
    val all = s.pipeline.sumOf { it.total - it.abandoned }
    PercentBar("work", if (all == 0) 100 else s.pipeline.sumOf { it.done } * 100 / all, "${s.pipeline.sumOf { it.open + it.inProgress }} open")
}

/** Where the work stands, one line per kind: open, in progress and done, a bar of how much is done, and how long the oldest waits. */
@Composable
internal fun PipelineBlock(pipeline: List<KindPipeline>) {
    Section("PIPELINE")
    pipeline.forEach { p ->
        Row {
            Label(p.kind.title.padEnd(10), color = Palette.Muted)
            Label("○${p.open} ", color = Palette.CyanSoft)
            Label("◐${p.inProgress} ", color = Palette.OrangeSoft)
            Label("●${p.done}  ", color = Palette.CyanSoft)
            val (filled, empty) = Chart.barParts(p.donePercent, 100, 10)
            Label(filled, color = levelColor(p.donePercent))
            Label(empty, color = Palette.Dim)
            Label(" ${p.donePercent}%", color = Palette.Text)
            p.oldestOpenDays?.let { Label("  oldest ${it}d", color = if (it >= 7) Palette.Orange else Palette.Dim) }
        }
    }
}

@Composable
internal fun FoldersBlock(s: BrainSnapshot, rows: Int = 5) {
    Section("WHERE YOUR NOTES LIVE")
    val max = s.folders.maxOfOrNull { it.notes } ?: 0
    s.folders.take(rows).forEach { CountBar(it.folder, it.notes, max, "${com.arcana.brain.core.UsageStats.compact(it.words)} words") }
}

@Composable
internal fun HubsBlock(s: BrainSnapshot) {
    if (s.hubs.isEmpty()) return
    Section("HUBS · most linked to")
    val max = s.hubs.maxOf { it.second }
    s.hubs.take(3).forEach { (path, n) -> CountBar(path.substringAfterLast('/').removeSuffix(".md"), n, max, color = Palette.AccentSoft) }
}

/** The days of the window as a calendar: a row per weekday, a column per week, darker is busier. */
@Composable
internal fun HeatBlock(s: BrainSnapshot) {
    Section("ACTIVITY · notes changed per day")
    val grid = Chart.heatLevels(s.days, s.weeks)
    val ramp = listOf(Palette.Dim, Palette.Muted, Palette.CyanSoft, Palette.AccentSoft, Palette.Accent)
    grid.forEachIndexed { row, cells ->
        Row {
            Label(Chart.WEEKDAYS[row] + " ", color = Palette.Dim)
            cells.forEach { level -> Label(if (level == null) "   " else Chart.heatGlyph(level).toString().repeat(2) + " ", color = ramp[(level ?: 0).coerceIn(0, 4)]) }
        }
    }
    Label("    " + s.weekly.joinToString("") { it.weekStart.dayOfMonth.toString().padEnd(3) }, color = Palette.Dim)
    Label("    (the day each week starts)", color = Palette.Dim)
}

@Composable
internal fun WeeklyBlock(s: BrainSnapshot) {
    Section("PER WEEK")
    val max = s.weekly.maxOfOrNull { it.changed } ?: 0
    s.weekly.takeLast(8).forEach { w -> CountBar("week of ${w.weekStart.dayOfMonth} ${w.weekStart.month.getDisplayName(DayStyle.SHORT, Locale.ENGLISH)}", w.changed, max, color = Palette.AccentSoft) }
}

@Composable
internal fun TokensBlock(s: BrainSnapshot) {
    Section("TOKENS PER WEEK")
    val max = s.tokensByWeek.maxOrNull() ?: 0L
    s.tokensByWeek.takeLast(8).forEachIndexed { i, t ->
        val w = s.weekly.getOrNull(s.weekly.size - s.tokensByWeek.takeLast(8).size + i)
        CountBar(w?.let { "week of ${it.weekStart.dayOfMonth} ${it.weekStart.month.getDisplayName(DayStyle.SHORT, Locale.ENGLISH)}" } ?: "week", if (max <= 0) 0 else (t * 1000 / max).toInt(), 1000, color = Palette.CyanSoft, valueText = com.arcana.brain.core.UsageStats.compact(t))
    }
}

/** What a bottleneck looks like as a row of the dashboard: red when it is pressing, orange when it matters, a tick when it is fine. */
internal fun bottleneckRow(b: Bottleneck, open: () -> Unit): BrainRow.Item {
    val (icon, color) = when {
        b.good -> "✓" to Palette.CyanSoft
        b.score >= 70 -> "▲" to Palette.OrangeSoft
        b.score >= 40 -> "▲" to Palette.AccentSoft
        else -> "△" to Palette.Muted
    }
    return BrainRow.Item(icon, color, b.title, b.detail, dim = b.good, open = open)
}
