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

private enum class Period(val title: String) { Session("This session"), Today("Today"), Week("7 days"), All("All time") }

@Composable
fun UsageScreen(app: AppContext) {
    var period by remember { mutableStateOf(Period.Session) }
    var confirmClear by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }

    FocusMode(confirmClear)
    if (confirmClear) Hints("y  clear history", "any other key  keep") else Hints("←→  period", "c  clear history")

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
            key == Key.Left -> { period = Period.entries[(period.ordinal - 1).mod(Period.entries.size)]; true }
            key == Key.Right || key == Key.Tab -> { period = Period.entries[(period.ordinal + 1).mod(Period.entries.size)]; true }
            key == Key.BackTab -> { period = Period.entries[(period.ordinal - 1).mod(Period.entries.size)]; true }
            key.char == 'c' -> { confirmClear = true; true }
            else -> false
        }
    }

    val all = app.usage.toList()
    val records = when (period) {
        Period.Session -> UsageStats.since(all, app.sessionStart)
        Period.Today -> UsageStats.since(all, UsageStats.startOfToday())
        Period.Week -> UsageStats.since(all, UsageStats.startOfToday() - 6 * 86_400_000L)
        Period.All -> all
    }

    Stack {
        if (confirmClear) {
            ScreenHeader("Usage", "Clear the history")
            VGap()
            Panel("Clear all usage history?", accent = Palette.Red) {
                Warning("Deletes usage.jsonl: ${all.size} recorded replies and their token counts. This cannot be undone.")
            }
            ActionBar(listOf(ActionItem("y", "Clear history", accent = Palette.Red), ActionItem("any other key", "Keep it", accent = Palette.Cta)))
        } else {
            ScreenHeader("Usage", "Tokens Ollama reported for every reply, kept in usage.jsonl. Local models cost nothing but time.")
            VGap()
            PeriodBar(period)
            VGap()
            UsageBody(app, records, all)
            NoticeLine(notice)
        }
    }
}

@Composable
private fun PeriodBar(current: Period) {
    Row {
        Period.entries.forEach { p ->
            val on = p == current
            Label(" ${p.title} ", color = if (on) Palette.Background else Palette.Muted, background = if (on) Palette.Cta else Color.Unspecified, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(" ")
        }
    }
}

@Composable
private fun UsageBody(app: AppContext, records: List<UsageRecord>, all: List<UsageRecord>) {
    val totals = UsageStats.totals(records)
    val density = rememberDensity()
    Panel("Totals") {
        if (records.isEmpty()) {
            Hint("Nothing recorded for this period yet. Ask, chat or summarize and the numbers appear here.")
        } else {
            Row(Modifier.fillMaxWidth()) {
                Tile("Replies", totals.requests.toString(), Palette.Text, Modifier.weight(1f))
                Tile("Prompt tokens", UsageStats.compact(totals.promptTokens), Palette.Blue, Modifier.weight(1f))
                Tile("Reply tokens", UsageStats.compact(totals.completionTokens), Palette.Green, Modifier.weight(1f))
                Tile("Total", UsageStats.compact(totals.totalTokens), Palette.Accent, Modifier.weight(1f))
                Tile("Speed", if (totals.tokensPerSecond > 0) "%.0f tok/s".format(totals.tokensPerSecond) else "–", Palette.Orange, Modifier.weight(1f))
            }
        }
    }
    if (records.isEmpty() || density.compact) return
    VGap()
    val wide = LocalContentWidth.current >= 90
    val half = ((LocalContentWidth.current - 1) / 2).coerceAtLeast(20)
    if (wide) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalContentWidth provides half) { BreakdownPanel(records) }
            }
            HGap()
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalContentWidth provides half) { DaysPanel(all) }
            }
        }
    } else {
        // Narrow terminal: stack the panels, and keep only the daily chart when there is no room for both.
        if (LocalContentHeight.current >= 30) {
            BreakdownPanel(records)
            VGap()
        }
        DaysPanel(all)
    }
}

@Composable
private fun Tile(label: String, value: String, color: Color, modifier: Modifier) {
    Stack(modifier.background(Palette.EntryBg)) {
        Label(" $label", color = Palette.Muted)
        Label(" $value", color = color, textStyle = TextStyle.Bold)
    }
}

@Composable
private fun BreakdownPanel(records: List<UsageRecord>) {
    val byFeature = UsageStats.by(records) { it.feature }
    val byModel = UsageStats.by(records) { it.model }
    Panel("By feature") {
        Bars(byFeature.map { Triple(it.first, it.second.totalTokens, it.second) }, Palette.Accent)
        if (byModel.size > 1 || byModel.firstOrNull()?.first != null) {
            VGap()
            Label("MODELS", color = Palette.Muted, textStyle = TextStyle.Bold)
            Bars(byModel.map { Triple(it.first, it.second.totalTokens, it.second) }, Palette.Blue)
        }
    }
}

@Composable
private fun DaysPanel(all: List<UsageRecord>) {
    val days = UsageStats.perDay(all, 7)
    Panel("Last 7 days") {
        Bars(
            days.map { (date, tokens) ->
                Triple("${date.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.ENGLISH)} ${date.dayOfMonth}", tokens, null)
            },
            Palette.Green,
        )
    }
}

/** One row per entry: its name, a bar sized against the largest, and the token count (with the speed when known). */
@Composable
private fun Bars(rows: List<Triple<String, Long, UsageTotals?>>, color: Color) {
    val labelWidth = (rows.maxOfOrNull { it.first.length } ?: 0).coerceIn(6, 14)
    val barWidth = (panelWidth() - labelWidth - 14).coerceIn(4, 40)
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    rows.forEach { (name, value, totals) ->
        val filled = if (value == 0L) 0 else ((value * barWidth) / max).toInt().coerceIn(1, barWidth)
        Row {
            Label(fit(name, labelWidth), color = Palette.FieldLabel)
            Label(" " + "▬".repeat(filled), color = color)
            Label("▬".repeat(barWidth - filled), color = Palette.Dim)
            Label(" ${UsageStats.compact(value)}", color = Palette.Text, textStyle = TextStyle.Bold)
            if (totals != null) Label("  ×${totals.requests}", color = Palette.Muted)
        }
    }
}
