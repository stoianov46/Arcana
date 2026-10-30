package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/** A coloured piece of text inside a [StatusSegment]. */
class StatusPart(val text: String, val color: Color, val bold: Boolean = false, val background: Color = Color.Unspecified)

/**
 * One fact in a [StatusBar]: a muted label and a coloured value (`ctx ▰▰▱▱ 12% 13k/131k`, `⏱ 1h 02m`, `≈ $0.31`).
 * [priority] no longer drops anything (the bar wraps, see [wrapSegments]); it is kept so a segment says how much it matters.
 */
class StatusSegment(val parts: List<StatusPart>, val priority: Int = 5, val second: Boolean = false) {
    val width: Int get() = parts.sumOf { it.text.length }

    /** A pill: it has a coloured background, so it asks for attention. */
    val filled: Boolean get() = parts.any { it.background != Color.Unspecified }

    /** Goes after the quiet facts, on the second row: the pills, and what asked for it with `second` (a passing state, like the wait reason). */
    val late: Boolean get() = filled || second
}

/** A pill: [text] on a coloured background, for what should catch the eye (the model, a warning). */
fun pillSegment(text: String, background: Color, priority: Int = 5) =
    StatusSegment(listOf(StatusPart(" $text ", Palette.Background, bold = true, background = background)), priority)

/** `label value`: the label muted, the value in [color]. */
fun statusSegment(label: String, value: String, color: Color, priority: Int = 5, bold: Boolean = false, second: Boolean = false) = StatusSegment(
    listOfNotNull(
        if (label.isEmpty()) null else StatusPart("$label ", Palette.Muted),
        StatusPart(value, color, bold),
    ),
    priority,
    second,
)

/** A gauge: `label ▰▰▱▱▱▱▱▱ 12% detail`, green while there is room, orange from 60% and red from 85% (more is worse). */
fun gaugeSegment(label: String, fraction: Double, detail: String, priority: Int = 8, plain: Boolean = false, cells: Int = 10): StatusSegment {
    val share = fraction.coerceIn(0.0, 1.0)
    val filled = (share * cells).toInt().coerceIn(0, cells).let { if (it == 0 && share > 0.0) 1 else it }
    val color = when {
        share >= 0.85 -> Palette.Red
        share >= 0.60 -> Palette.Orange
        else -> Palette.Green
    }
    val on = if (plain) "#" else "█"
    val off = if (plain) "-" else "░"
    return StatusSegment(
        listOf(
            StatusPart("$label ", Palette.Muted),
            StatusPart(on.repeat(filled), color),
            StatusPart(off.repeat(cells - filled), Palette.Dim),
            StatusPart(" ${"%.0f".format(share * 100)}%", color, bold = true),
            *(if (detail.isEmpty()) emptyArray() else arrayOf(StatusPart(" $detail", Palette.Muted))),
        ),
        priority,
    )
}

/**
 * The segments laid out in rows of at most [width] columns (joined by [separatorWidth] columns), in their original order: **nothing is dropped**, a segment that does not fit
 * on the row goes to the next one, and one that is wider than a whole row is cut with `…`. So every indicator stays visible at any width; a narrow window just needs more rows.
 * The filled ones (pills, which ask for attention) come after the quiet facts and start a row of their own; the first segment (the lead: the model, the page) stays where it is.
 */
internal fun wrapSegments(segments: List<StatusSegment>, width: Int, separatorWidth: Int = 3): List<List<StatusSegment>> {
    val limit = width.coerceAtLeast(8)
    val rows = mutableListOf(mutableListOf<StatusSegment>())
    var used = 0
    val cut = segments.map { it.cut(limit) }
    // the first row is for the quiet facts (the lead first); the filled ones, which ask for attention, start the next row
    val ordered = cut.take(1) + cut.drop(1).filter { !it.late } + cut.drop(1).filter { it.late }
    val firstRest = ordered.drop(1).indexOfFirst { it.late }.let { if (it < 0 || ordered.drop(1).none { s -> !s.late }) -1 else it + 1 }
    for ((index, segment) in ordered.withIndex()) {
        val row = rows.last()
        if (row.isNotEmpty() && (index == firstRest || used + separatorWidth + segment.width > limit)) {
            rows += mutableListOf(segment)
            used = segment.width
        } else {
            used += if (row.isEmpty()) segment.width else separatorWidth + segment.width
            row += segment
        }
    }
    return rows
}

/** How many rows [wrapSegments] needs: what a screen must reserve for its status bar. */
internal fun statusRowCount(segments: List<StatusSegment>, width: Int, separatorWidth: Int = 3): Int = wrapSegments(segments, width, separatorWidth).size

/** This segment cut to at most [limit] columns: the last parts are shortened or left out and the text ends in `…`. */
private fun StatusSegment.cut(limit: Int): StatusSegment {
    if (width <= limit) return this
    val parts = ArrayList<StatusPart>()
    var left = limit - 1
    for (part in this.parts) {
        if (left <= 0) break
        if (part.text.length <= left) { parts += part; left -= part.text.length }
        else { parts += StatusPart(part.text.take(left), part.color, part.bold, part.background); left = 0 }
    }
    parts += StatusPart("…", Palette.Muted)
    return StatusSegment(parts, priority, second)
}

/**
 * A status bar (like the one under a Claude Code prompt): facts separated by `│`, values coloured, gauges for what fills up. It wraps onto as many rows as it needs, so no fact is ever
 * hidden because the window is narrow ([statusRowCount] says how many rows to reserve). The segments decide what it says.
 */
@Composable
fun StatusBar(segments: List<StatusSegment>, width: Int = LocalContentWidth.current) {
    val plain = LocalPlainGlyphs.current
    val separator = if (plain) " | " else " │ "
    Column {
        wrapSegments(segments, width, separator.length).forEach { row ->
            Row {
                row.forEachIndexed { i, segment ->
                    if (i > 0) Label(separator, color = Palette.Dim)
                    segment.parts.forEach { part ->
                        Label(part.text, color = part.color, background = part.background, textStyle = if (part.bold) TextStyle.Bold else TextStyle.Unspecified)
                    }
                }
            }
        }
    }
}

/** A screen header: the title, and under it a [StatusBar]. */
@Composable
fun StatusHeader(title: String, segments: List<StatusSegment>) {
    Stack {
        Label(title, color = Palette.Text, textStyle = TextStyle.Bold)
        StatusBar(segments)
    }
}

/** `1h 02m`, `14m`, `45s`: a time span in the shortest readable form. */
fun spanText(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}h ${"%02d".format(seconds % 3600 / 60)}m"
    }
}

/** `$0.31`, `$12.40`, or `<$0.01`: an estimated price. */
fun priceText(dollars: Double): String = when {
    dollars <= 0.0 -> "$0.00"
    dollars < 0.01 -> "<$0.01"
    dollars < 100 -> "$" + "%.2f".format(dollars)
    else -> "$" + "%.0f".format(dollars)
}
