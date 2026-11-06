package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.core.TeamStatsBuilder
import com.arcana.brain.core.ToolStats
import com.arcana.brain.core.ToolUseRecord
import com.arcana.brain.core.UsageInsights
import com.arcana.brain.core.UsageRecord
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.UsageTotals
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.time.format.TextStyle as DayStyle
import java.util.Locale

private enum class Period(val title: String) { Session("This session"), Today("Today"), Week("7 days"), Month("30 days"), All("All time") }

private enum class Page(val title: String) { Overview("Overview"), Breakdown("Breakdown"), Team("Orchestration"), Tools("Tools"), Recent("Recent replies") }

private const val DAY = 86_400_000L

@Composable
fun UsageScreen(app: AppContext) {
    var period by remember { mutableStateOf(Period.Session) }
    var page by remember { mutableStateOf(Page.Overview) }
    var confirmClear by remember { mutableStateOf(false) }
    // the Orchestration page is a long text read from the top (the view follows the END of a text by default, like the chat does)
    val teamOutput = remember { OutputState().also { it.showTop() } }
    val scroll = rememberScrollState()
    var notice by remember { mutableStateOf<Notice?>(null) }

    FocusMode(confirmClear)
    if (confirmClear) Hints("y  clear history", "any other key  keep") else Hints("←→  period", "Tab  page", "↑↓  scroll", "c  clear history")

    KeyHandler { key ->
        when {
            confirmClear -> {
                if (key.char == 'y' || key.char == 'Y') {
                    app.clearUsage()
                    notice = Notice("Usage history cleared")
                }
                confirmClear = false
                true
            }
            key == Key.Left -> { period = Period.entries[(period.ordinal - 1).mod(Period.entries.size)]; teamOutput.showTop(); true }
            key == Key.Right -> { period = Period.entries[(period.ordinal + 1).mod(Period.entries.size)]; teamOutput.showTop(); true }
            key == Key.Tab -> { page = Page.entries[(page.ordinal + 1).mod(Page.entries.size)]; scroll.reset(); teamOutput.showTop(); true }
            key == Key.BackTab -> { page = Page.entries[(page.ordinal - 1).mod(Page.entries.size)]; scroll.reset(); teamOutput.showTop(); true }
            page == Page.Team && teamOutput.handle(key, letters = false) -> true
            page != Page.Team && scroll.handle(key) -> true
            key.char == 'c' -> { confirmClear = true; true }
            else -> false
        }
    }

    val all = app.usage.toList()
    val records = when (period) {
        Period.Session -> UsageStats.since(all, app.sessionStart)
        Period.Today -> UsageStats.since(all, UsageStats.startOfToday())
        Period.Week -> UsageStats.since(all, UsageStats.startOfToday() - 6 * DAY)
        Period.Month -> UsageStats.since(all, UsageStats.startOfToday() - 29 * DAY)
        Period.All -> all
    }

    Stack {
        if (confirmClear) {
            ScreenHeader("Usage", "Clear the history")
            VGap()
            Panel("Clear all usage history?", accent = Palette.Red) {
                Warning("Deletes usage.jsonl and tool-usage.jsonl: ${all.size} recorded replies with their token counts, and the tool calls. This cannot be undone.")
            }
            ActionBar(listOf(ActionItem("y", "Clear history", accent = Palette.Red), ActionItem("any other key", "Keep it", accent = Palette.Cta)), style = ActionStyle.Button)
        } else {
            val shown = UsageStats.totals(records)
            val cost = UsageStats.estimatedCost(records, app.config.estPriceIn, app.config.estPriceOut)
            StatusHeader(
                "Usage",
                listOf(
                    pillSegment(page.title, Palette.Accent, 9),
                    statusSegment("", period.title, Palette.Cta, 8, bold = true),
                    statusSegment("", "${shown.requests} replies", Palette.Text, 6),
                    statusSegment("", UsageStats.compact(shown.totalTokens) + " tokens", Palette.Blue, 7),
                    statusSegment("est.", priceText(cost), Palette.Orange, 8, bold = true),
                    statusSegment("", if (shown.tokensPerSecond > 0) "%.0f tok/s".format(shown.tokensPerSecond) else "–", Palette.Green, 4),
                    statusSegment("session", spanText(System.currentTimeMillis() - app.sessionStart), Palette.Accent, 2),
                ),
            )
            VGap()
            TabBar(period, page)
            VGap()
            // rows left for panels: the header (2), the gaps and tab bar (3) are above them
            val room = (LocalContentHeight.current - 5 - (if (notice != null) 1 else 0)).coerceAtLeast(3)
            val toolRecords = ToolStats.since(app.toolUsage.toList(), when (period) {
                Period.Session -> app.sessionStart
                Period.Today -> UsageStats.startOfToday()
                Period.Week -> UsageStats.startOfToday() - 6 * DAY
                Period.Month -> UsageStats.startOfToday() - 29 * DAY
                Period.All -> 0L
            })
            val since = when (period) {
                Period.Session -> app.sessionStart
                Period.Today -> UsageStats.startOfToday()
                Period.Week -> UsageStats.startOfToday() - 6 * DAY
                Period.Month -> UsageStats.startOfToday() - 29 * DAY
                Period.All -> 0L
            }
            when (page) {
                Page.Tools -> ToolsPanel(toolRecords, scroll)
                Page.Team -> TeamPage(TeamStatsBuilder.build(records, TeamStatsBuilder.since(app.events.toList(), since), app.config.estPriceIn, app.config.estPriceOut), teamOutput, room, app.config.plainGlyphs, app.config.estPriceIn, app.config.estPriceOut)
                else -> Dashboard(period, page, records, all, room, scroll)
            }
            NoticeLine(notice)
        }
    }
}

@Composable
private fun TabBar(period: Period, page: Page) {
    Row {
        Period.entries.forEach { p ->
            val on = p == period
            Label(" ${p.title} ", color = if (on) Palette.Ink else Palette.Muted, background = if (on) Palette.Cta else Color.Unspecified, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(" ")
        }
        if (LocalContentWidth.current >= 100) {
            Label("│ ", color = Palette.Dim)
            Page.entries.forEach { p ->
                val on = p == page
                Label(" ${p.title} ", color = if (on) Palette.Ink else Palette.Muted, background = if (on) Palette.Accent else Color.Unspecified, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            }
        }
    }
}

/**
 * The page on display. Its panels sit in a scrolling column: all of them are always there, and what the window cannot show
 * at once is reached with ↑/↓ and PgUp/PgDn (a scrollbar on the right says where you are).
 */
@Composable
private fun Dashboard(period: Period, page: Page, records: List<UsageRecord>, all: List<UsageRecord>, room: Int, scroll: ScrollState) {
    if (records.isEmpty()) {
        Panel("Nothing yet") { Hint("Nothing recorded for this period yet. Ask, chat or summarize and the numbers appear here.") }
        return
    }
    if (page == Page.Recent) { RecentPanel(records, all, UsageStats.insights(records, all), scroll); return }
    val wide = LocalContentWidth.current >= 90
    // the scrollbar takes the last column, so everything inside is one column narrower
    val w = LocalContentWidth.current - 1
    val half = ((w - 1) / 2).coerceAtLeast(20)
    val insights = UsageStats.insights(records, all)
    val totals = UsageStats.totals(records)
    ScrollColumn(scroll, Modifier.flexible()) { CompositionLet(w) {
        when (page) {
            Page.Overview -> {
                Tiles(totals, insights)
                VGap()
                val left = room - TILES_ROWS - 1
                // the chart takes what the other panels leave; when there is no room for them it stays small and they scroll
                val below = if (wide && left >= 24) featureRows(records) + 3 else 0
                val chartRows = (left - below - 3).coerceIn(3, 8)
                if (wide) {
                    val chartWidth = (w * 11) / 20
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(11f)) { CompositionLet(chartWidth) { ActivityPanel(period, records, all, chartRows) } }
                        HGap()
                        Box(Modifier.weight(9f)) { CompositionLet(w - chartWidth - 1) { InsightsPanel(insights, chartRows + 1) } }
                    }
                    VGap()
                    FeaturePair(records, half)
                } else {
                    ActivityPanel(period, records, all, chartRows)
                    VGap()
                    InsightsPanel(insights, 6)
                }
            }
            Page.Breakdown -> {
                if (wide) {
                    FeaturePair(records, half)
                } else {
                    FeaturePanel(records, Palette.Accent, "By feature") { it.feature }
                    VGap()
                    FeaturePanel(records, Palette.Blue, "By model") { it.model }
                }
                VGap()
                AdvisorsPanel(records)
                VGap()
                if (wide) {
                    // the two small panels share a row: a grid fills the window instead of a tall single column
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) { CompositionLet(half) { HoursPanel(records, insights) } }
                        HGap()
                        Box(Modifier.weight(1f)) { CompositionLet(half) { SpeedPanel(all, period) } }
                    }
                } else {
                    HoursPanel(records, insights)
                    VGap()
                    SpeedPanel(all, period)
                }
            }
            else -> Unit // Recent, Team and Tools draw themselves
        }
    } }
}

/** By feature and By model side by side, each sizing its bars to its own half. */
@Composable
private fun FeaturePair(records: List<UsageRecord>, half: Int) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { CompositionLet(half) { FeaturePanel(records, Palette.Accent, "By feature") { it.feature } } }
        HGap()
        Box(Modifier.weight(1f)) { CompositionLet(half) { FeaturePanel(records, Palette.Blue, "By model") { it.model } } }
    }
}

/** Runs [content] as if the screen were [width] columns wide, so panels side by side size their bars to their own half. */
@Composable
private fun CompositionLet(width: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentWidth provides width.coerceAtLeast(20)) { content() }
}

private const val TILES_ROWS = 4
private const val FeatureRows = 6

/** How many rows the By feature / By model panels need: every feature and every model that spent a token is listed (at least [FeatureRows], at most 24 so the rest of the page keeps room). */
private fun featureRows(records: List<UsageRecord>): Int =
    maxOf(FeatureRows, UsageStats.by(records) { it.feature }.size, UsageStats.by(records) { it.model }.size).coerceAtMost(24)
private const val ADVISOR_ROWS = 3

@Composable
private fun Tiles(totals: UsageTotals, insights: UsageInsights) {
    val wide = LocalContentWidth.current >= 90
    Panel("Totals") {
        Row(Modifier.fillMaxWidth()) {
            Tile("Replies", totals.requests.toString(), Palette.Text, Modifier.weight(1f))
            Tile("Prompt", UsageStats.compact(totals.promptTokens), Palette.Blue, Modifier.weight(1f))
            Tile("Reply", UsageStats.compact(totals.completionTokens), Palette.Green, Modifier.weight(1f))
            Tile("Total", UsageStats.compact(totals.totalTokens), Palette.Accent, Modifier.weight(1f))
            Tile("Speed", if (totals.tokensPerSecond > 0) "%.0f tok/s".format(totals.tokensPerSecond) else "–", Palette.Orange, Modifier.weight(1f))
            if (wide) {
                Tile("Avg time", if (insights.averageMillis > 0) seconds(insights.averageMillis) else "–", Palette.Text, Modifier.weight(1f))
                Tile("Avg size", UsageStats.compact(totals.averageTokens), Palette.Text, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, color: Color, modifier: Modifier) {
    Stack(modifier.background(Palette.EntryBg)) {
        Label(" $label", color = Palette.Muted)
        Label(" $value", color = color, textStyle = TextStyle.Bold)
    }
}

private fun seconds(millis: Long) = if (millis < 10_000) "%.1fs".format(millis / 1000.0) else "${millis / 1000}s"

/** Tokens over time as columns: by hour for a session or a day, by day for longer periods. */
@Composable
private fun ActivityPanel(period: Period, records: List<UsageRecord>, all: List<UsageRecord>, chartRows: Int) {
    val byHour = period == Period.Session || period == Period.Today
    val series: List<Pair<String, Long>> = when {
        byHour -> UsageStats.perHour(records).mapIndexed { h, v -> h.toString() to v }
        period == Period.Week -> UsageStats.perDay(all, 7).map { (d, v) -> d.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.ENGLISH) to v }
        else -> UsageStats.perDay(all, 30).map { (d, v) -> d.dayOfMonth.toString() to v }
    }
    val peak = series.maxOfOrNull { it.second } ?: 0
    Panel((if (byHour) "Tokens by hour" else "Tokens per day") + "  ·  peak ${UsageStats.compact(peak)}") {
        ColumnChart(series, chartRows, if (byHour) java.time.LocalTime.now().hour else series.lastIndex)
    }
}

/**
 * Vertical bars, [rows] tall, eight steps per row (▁…█). The column at [highlight] (now) is drawn in the accent colour, empty
 * columns keep a dim baseline so the axis is visible, and a label row below names the columns.
 */
@Composable
private fun ColumnChart(series: List<Pair<String, Long>>, rows: Int, highlight: Int) {
    var data = series
    // not enough width for two columns per entry: merge neighbours (24 hours become 12 buckets)
    while (data.size * 2 > panelWidth() && data.size > 1) {
        data = data.chunked(2).map { it.first().first to it.sumOf { p -> p.second } }
    }
    val merged = series.size / data.size
    val w = (panelWidth() / data.size).coerceIn(2, 4)
    val max = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val levels = data.map { (it.second * rows * 8 / max).toInt() }
    val steps = " ▁▂▃▄▅▆▇█"
    val now = highlight / merged
    for (row in 0 until rows) {
        Row {
            data.forEachIndexed { i, entry ->
                val cell = (levels[i] - (rows - 1 - row) * 8).coerceIn(0, 8)
                val glyph = when {
                    cell > 0 -> steps[cell]
                    row == rows - 1 && entry.second == 0L -> '▁'
                    else -> ' '
                }
                val color = when {
                    entry.second == 0L -> Palette.Dim
                    i == now -> Palette.Accent
                    else -> Palette.Green
                }
                Label(glyph.toString().repeat(w - 1) + " ", color = color)
            }
        }
    }
    Label(axisLabels(data.map { it.first }, w), color = Palette.Muted)
}

/**
 * One line naming the columns: a label every few columns, as long as it needs (a two-digit hour or day is not cut to one
 * character), starting at its own column and never touching the next label.
 */
internal fun axisLabels(labels: List<String>, width: Int): String {
    val longest = labels.maxOfOrNull { it.length } ?: 1
    val every = ((longest + 1) + width - 1) / width
    val line = CharArray(labels.size * width) { ' ' }
    labels.forEachIndexed { i, label ->
        if (i % every == 0) label.forEachIndexed { k, c -> if (i * width + k < line.size) line[i * width + k] = c }
    }
    return String(line)
}

@Composable
private fun InsightsPanel(i: UsageInsights, rows: Int) {
    val lines = buildList {
        i.busiestDay?.let { (d, v) -> add("Busiest day" to "${d.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} · ${UsageStats.compact(v)} tokens") }
        i.peakHour?.let { add("Peak hour" to "%02d:00".format(it)) }
        add("Streak" to "${i.streakDays} day${if (i.streakDays == 1) "" else "s"} · ${i.activeDays} active")
        i.weekChange?.let { add("Week on week" to (if (it >= 0) "▲ " else "▼ ") + "%.0f%%".format(kotlin.math.abs(it) * 100)) }
        if (i.medianMillis > 0) add("Typical reply" to "${seconds(i.medianMillis)} median · ${seconds(i.averageMillis)} avg")
        i.longestReply?.let { add("Longest reply" to "${UsageStats.compact(it.completionTokens.toLong())} tokens (${it.feature})") }
        i.slowestReply?.takeIf { it.millis > 0 }?.let { add("Slowest reply" to "${seconds(it.millis)} (${it.feature})") }
        if (i.contextRatio > 0) add("Context load" to "%.0f : 1".format(i.contextRatio) + if (i.contextRatio > 10) " · long contexts" else " prompt:reply")
    }
    Panel("Insights") {
        val labelWidth = 14
        val valueWidth = (panelWidth() - labelWidth).coerceAtLeast(8)
        lines.take(rows.coerceAtLeast(1)).forEach { (name, value) ->
            Row {
                Label(fit(name, labelWidth), color = Palette.FieldLabel)
                Label(fit(value, valueWidth), color = Palette.Text)
            }
        }
    }
}

@Composable
private fun FeaturePanel(records: List<UsageRecord>, color: Color, title: String, key: (UsageRecord) -> String) {
    val rows = UsageStats.by(records, key).take(featureRows(records))
    val total = records.sumOf { it.totalTokens.toLong() }.coerceAtLeast(1)
    Panel(title) { Bars(rows.map { Triple(it.first, it.second.totalTokens, it.second) }, color, total) }
}

/** The Oracul, the Planner and the Judge side by side: calls, tokens, share of all tokens, time per call and the models that did the work. */
@Composable
private fun AdvisorsPanel(records: List<UsageRecord>) {
    val width = panelWidth()
    Panel("Oracul, Analyst, Planner, Controller, Judge") {
        UsageStats.advisors(records).forEach { a ->
            val t = a.totals
            val line = if (t.requests == 0) fit(a.feature, 8) + " not used in this period"
            else fit(a.feature, 8) + " " + "${t.requests} calls".padEnd(10) + UsageStats.compact(t.totalTokens).padStart(6) + " tok  " + "%.0f%%".format(a.share * 100).padStart(4) +
                "  avg " + seconds(t.millis / t.requests).padStart(5) + "  " + a.models.joinToString(", ")
            Label(line.take(width), color = if (t.requests == 0) Palette.Dim else Palette.Text)
        }
    }
}

/** Which hours of the day are busy: one shaded cell per hour (░▒▓█ by share of the busiest), with the hours named below. */
@Composable
private fun HoursPanel(records: List<UsageRecord>, insights: UsageInsights) {
    val hours = UsageStats.perHour(records)
    val max = hours.max().coerceAtLeast(1)
    val cell = if (panelWidth() >= 54) 2 else 1
    Panel("When you use it" + (insights.peakHour?.let { "  ·  peak %02d:00".format(it) } ?: "")) {
        Row {
            hours.forEach { v ->
                val level = if (v == 0L) 0 else ((v * 4 + max - 1) / max).toInt().coerceIn(1, 4)
                Label(" ░▒▓█"[level].toString().repeat(cell), color = if (level == 0) Palette.Dim else Palette.Accent)
            }
        }
        Row {
            hours.indices.forEach { h ->
                Label((if (h % 6 == 0) h.toString() else "").padEnd(cell), color = Palette.Muted)
            }
            Label("  ░ little  █ most", color = Palette.Dim)
        }
    }
}

/** Generated tokens per second, day by day: is the model getting slower? */
@Composable
private fun SpeedPanel(all: List<UsageRecord>, period: Period) {
    val days = if (period == Period.Week || period == Period.Session || period == Period.Today) 7 else 30
    val speed = UsageStats.speedPerDay(all, days)
    val timed = speed.map { it.second }.filter { it > 0 }
    val glyphs = "▁▂▃▄▅▆▇█"
    val lo = timed.minOrNull() ?: 0.0
    val hi = timed.maxOrNull() ?: 0.0
    Panel("Speed, last $days days" + if (timed.isEmpty()) "" else "  ·  ${"%.0f".format(lo)}–${"%.0f".format(hi)} tok/s") {
        Row {
            // one column per day when two per day would not fit the panel
            val gap = if (panelWidth() >= days * 2) " " else ""
            speed.forEach { (_, v) ->
                val level = if (v <= 0) -1 else if (hi == lo) 3 else (((v - lo) / (hi - lo)) * 7).toInt().coerceIn(0, 7)
                Label(if (level < 0) "·$gap" else glyphs[level].toString() + gap, color = if (level < 0) Palette.Dim else Palette.Orange)
            }
        }
        Label("older ← → today · a dot is a day without replies", color = Palette.Muted)
    }
}

/** The latest replies, newest first, with the longest and the slowest picked out. Up to 200 rows, scrolled inside the panel. */
@Composable
private fun RecentPanel(records: List<UsageRecord>, all: List<UsageRecord>, insights: UsageInsights, scroll: ScrollState) {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now()
    val shown = records.sortedByDescending { it.time }.take(200)
    val width = panelWidth() - 1
    val modelWidth = (width - 9 - 10 - 8 - 8 - 7 - 8).coerceIn(6, 18)
    Panel("Recent replies  ·  ${records.size} in this period", Modifier.flexible()) {
        Label("When     Feature   ${"Model".padEnd(modelWidth)} Prompt   Reply   Time  tok/s".take(width), color = Palette.Muted)
        ScrollColumn(scroll, Modifier.flexible()) {
            shown.forEach { r ->
                val at = java.time.Instant.ofEpochMilli(r.time).atZone(zone)
                val when_ = if (at.toLocalDate() == today) "%02d:%02d".format(at.hour, at.minute) else "${at.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.ENGLISH)} ${at.dayOfMonth}"
                val speed = if (r.millis > 0) "%.0f".format(r.completionTokens * 1000.0 / r.millis) else "–"
                val mark = when {
                    r == insights.longestReply -> Palette.Green
                    r == insights.slowestReply -> Palette.Orange
                    else -> Palette.Text
                }
                Label(
                    (fit(when_, 8) + " " + fit(r.feature, 9) + " " + fit(r.model, modelWidth) + " " + UsageStats.compact(r.promptTokens.toLong()).padStart(6) + "  " +
                        UsageStats.compact(r.completionTokens.toLong()).padStart(6) + "  " + (if (r.millis > 0) seconds(r.millis) else "–").padStart(5) + " " + speed.padStart(6)).take(width),
                    color = mark,
                )
            }
        }
        if (shown.size < records.size) Label("… ${records.size - shown.size} older replies not listed   (green: longest · orange: slowest)", color = Palette.Muted)
        else Label("green: longest reply · orange: slowest reply", color = Palette.Muted)
    }
}

/**
 * What the model's tools cost: per tool, how often it was called, about how many tokens its results put into the context (the part of a
 * prompt that comes from tools), the average time and the calls that failed or were denied. Biggest context cost first.
 */
@Composable
private fun ToolsPanel(records: List<ToolUseRecord>, scroll: ScrollState) {
    if (records.isEmpty()) {
        Panel("Tools") { Hint("No tool calls in this period. Turn tools on in Chat (/tools) and ask the model to read or change something.") }
        return
    }
    val rows = ToolStats.by(records)
    val totalTokens = rows.sumOf { it.approxTokens }.coerceAtLeast(1)
    val labelWidth = (rows.maxOfOrNull { it.tool.length } ?: 0).coerceIn(8, 18)
    val barWidth = (panelWidth() - 1 - labelWidth - 34).coerceIn(0, 30)
    val max = rows.maxOf { it.approxTokens }.coerceAtLeast(1)
    Panel("Tools · ${records.size} calls · ≈${UsageStats.compact(totalTokens)} tokens of results", Modifier.flexible()) {
        ScrollColumn(scroll, Modifier.flexible()) { rows.forEach { t ->
            val filled = if (t.approxTokens == 0L || barWidth == 0) 0 else ((t.approxTokens * barWidth) / max).toInt().coerceIn(1, barWidth)
            Row {
                Label(fit(t.tool, labelWidth), color = Palette.FieldLabel)
                if (barWidth > 0) {
                    Label(" " + "▬".repeat(filled), color = Palette.Blue)
                    Label("▬".repeat(barWidth - filled), color = Palette.Dim)
                }
                Label(" ≈${UsageStats.compact(t.approxTokens)}", color = Palette.Text, textStyle = TextStyle.Bold)
                Label(" %.0f%%".format(t.approxTokens * 100.0 / totalTokens), color = Palette.Muted)
                Label(" ×${t.calls}", color = Palette.Muted)
                Label(" ${seconds(t.averageMillis)}", color = Palette.Muted)
                if (t.errors > 0) Label(" ✕${t.errors}", color = Palette.Red)
                if (t.denied > 0) Label(" ⊘${t.denied}", color = Palette.Warn)
            }
        } }
        Hint("≈ tokens: what the results added to the conversation (4 characters ≈ 1 token). ×calls · average time · ✕ failed · ⊘ denied.")
    }
}

/** One row per entry: its name, a bar sized against the largest, the tokens, their share of [total] and the number of replies. */
@Composable
private fun Bars(rows: List<Triple<String, Long, UsageTotals?>>, color: Color, total: Long) {
    val labelWidth = (rows.maxOfOrNull { it.first.length } ?: 0).coerceIn(6, 22)
    val barWidth = (panelWidth() - labelWidth - 22).coerceIn(4, 40)
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    rows.forEach { (name, value, totals) ->
        val filled = if (value == 0L) 0 else ((value * barWidth) / max).toInt().coerceIn(1, barWidth)
        Row {
            Label(fit(name, labelWidth), color = Palette.FieldLabel)
            Label(" " + "▬".repeat(filled), color = color)
            Label("▬".repeat(barWidth - filled), color = Palette.Dim)
            Label(" ${UsageStats.compact(value)}", color = Palette.Text, textStyle = TextStyle.Bold)
            Label(" %.0f%%".format(value * 100.0 / total), color = Palette.Muted)
            if (totals != null) Label(" ×${totals.requests}", color = Palette.Muted)
        }
    }
}
