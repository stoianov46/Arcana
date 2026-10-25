package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/** A coloured piece of text inside a [StatusSegment]. */
class StatusPart(val text: String, val color: Color, val bold: Boolean = false, val background: Color = Color.Unspecified)

/**
 * One fact in a [StatusBar]: a muted label and a coloured value (`ctx ▰▰▱▱ 12% 13k/131k`, `⏱ 1h 02m`, `≈ $0.31`).
 * When the bar is too narrow the segments with the lowest [priority] are dropped first, so what matters stays.
 */
class StatusSegment(val parts: List<StatusPart>, val priority: Int = 5) {
    val width: Int get() = parts.sumOf { it.text.length }
}

/** A pill: [text] on a coloured background, for what should catch the eye (the model, a warning). */
fun pillSegment(text: String, background: Color, priority: Int = 5) =
    StatusSegment(listOf(StatusPart(" $text ", Palette.Background, bold = true, background = background)), priority)

/** `label value`: the label muted, the value in [color]. */
fun statusSegment(label: String, value: String, color: Color, priority: Int = 5, bold: Boolean = false) = StatusSegment(
    listOfNotNull(
        if (label.isEmpty()) null else StatusPart("$label ", Palette.Muted),
        StatusPart(value, color, bold),
    ),
    priority,
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

/** The segments that fit in [width] (joined by [separatorWidth] columns each), dropping the lowest priority first and keeping the original order. */
internal fun fitSegments(segments: List<StatusSegment>, width: Int, separatorWidth: Int = 3): List<StatusSegment> {
    fun total(list: List<StatusSegment>) = list.sumOf { it.width } + separatorWidth * (list.size - 1).coerceAtLeast(0)
    val kept = segments.toMutableList()
    while (kept.size > 1 && total(kept) > width) {
        val drop = kept.withIndex().minWithOrNull(compareBy({ it.value.priority }, { -it.index }))!!
        kept.removeAt(drop.index)
    }
    return kept
}

/**
 * A one-line status bar (like the one under a Claude Code prompt): facts separated by `│`, values coloured, gauges for what fills up.
 * The same component serves every screen that wants a summary line; the segments decide what it says.
 */
@Composable
fun StatusBar(segments: List<StatusSegment>, width: Int = LocalContentWidth.current) {
    val plain = LocalPlainGlyphs.current
    val separator = if (plain) " | " else " │ "
    val shown = fitSegments(segments, width, separator.length)
    Row {
        shown.forEachIndexed { i, segment ->
            if (i > 0) Label(separator, color = Palette.Dim)
            segment.parts.forEach { part ->
                Label(part.text, color = part.color, background = part.background, textStyle = if (part.bold) TextStyle.Bold else TextStyle.Unspecified)
            }
        }
    }
}

/** A screen header: the title, and under it a [StatusBar]. */
@Composable
fun StatusHeader(title: String, segments: List<StatusSegment>) {
    Stack {
        Label(title, color = Palette.Text, textStyle = TextStyle.Bold)
        if (!rememberDensity().tiny) StatusBar(segments)
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
