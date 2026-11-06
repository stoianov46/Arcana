package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import com.jakewharton.mosaic.ui.unit.Constraints

enum class LabelAlign { Start, Center, End }

private class LabelState {
    var lines: List<String> = emptyList()

    /** The first visible row of a [WrappedText], kept between frames so the text only scrolls when the cursor leaves the window. */
    var first = 0

    /** Per drawn row, the columns to show as selected (reverse video), or null. */
    var selected: List<IntRange?> = emptyList()

    /** Per drawn row of a [WrappedText] that scrolls, the scrollbar glyph for its last column ("█" thumb, "│" track); empty when everything fits. */
    var bar: List<String> = emptyList()
}

/** Draws the selected columns of [line] on row [row] in reverse video, over what was already drawn. */
private fun com.jakewharton.mosaic.layout.DrawScope.drawSelected(row: Int, line: String, cols: IntRange?) {
    if (cols == null) return
    val a = cols.first.coerceIn(0, line.length)
    val b = (cols.last + 1).coerceIn(a, line.length)
    if (b > a) drawText(row, a, line.substring(a, b), Palette.Ink, Palette.Text)
}

/**
 * Constraint-aware text. Mosaic's own `Text` ignores the space it is given and draws past the edge of the screen
 * (which crashes the renderer), so every piece of UI text goes through this: it truncates with an ellipsis to the
 * available width and drops lines that don't fit the available height.
 *
 * @param fill take all available width (needed for alignment / full-width backgrounds)
 * @param wrap word-wrap instead of truncating
 */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    textStyle: TextStyle = TextStyle.Unspecified,
    align: LabelAlign = LabelAlign.Start,
    fill: Boolean = false,
    wrap: Boolean = false,
) {
    val state = remember { LabelState() }
    val clean = remember(text) { sanitizeForTerminal(text) }
    // Text with no colour of its own uses the theme's, not the terminal's, so it stays readable on any theme.
    val fg = if (color == Color.Unspecified) Palette.Text else color
    Layout(
        content = {},
        modifier = modifier.drawBehind {
            state.lines.forEachIndexed { row, line ->
                drawText(row, 0, line, fg, background, textStyle)
            }
        },
        debugInfo = { "Label($text)" },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val maxW = constraints.maxWidth
                val maxH = constraints.maxHeight
                var lines = clean.split('\n')
                if (wrap && constraints.hasBoundedWidth) lines = lines.flatMap { wrap(it, maxW) }
                if (constraints.hasBoundedHeight && lines.size > maxH) {
                    // more text than rows: the last row says so instead of the rest vanishing
                    lines = if (maxH <= 0) emptyList() else lines.take(maxH).let { it.dropLast(1) + (it.last().trimEnd() + "…") }
                }
                if (constraints.hasBoundedWidth) {
                    lines = lines.map { if (it.length > maxW) it.take((maxW - 1).coerceAtLeast(0)) + if (maxW > 0) "…" else "" else it }
                }
                var width = lines.maxOfOrNull { it.length } ?: 0
                if (fill && constraints.hasBoundedWidth) width = maxW
                width = width.coerceIn(constraints.minWidth, maxW.coerceAtLeast(0))
                if (width == 0 || lines.isEmpty()) {
                    state.lines = emptyList()
                    return layout(0, 0) {}
                }
                state.lines = lines.map {
                    when (align) {
                        LabelAlign.Start -> it.padEnd(width)
                        LabelAlign.End -> it.padStart(width)
                        LabelAlign.Center -> it.padStart((width + it.length) / 2).padEnd(width)
                    }.let { s -> if (fill || align != LabelAlign.Start) s else it }
                }
                return layout(width, lines.size) {}
            }
        },
    )
}

/** A horizontal bar of block characters, [fraction] filled; fills the available width (capped at [maxCells]). */
@Composable
fun HBar(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    maxCells: Int = 60,
    /** Where to mark a limit on the bar (0..1), drawn as a tick over the bar. */
    marker: Float? = null,
) {
    Layout(
        content = {},
        modifier = modifier.drawBehind {
            val filled = (fraction.coerceIn(0f, 1f) * width).toInt().coerceIn(0, width)
            if (filled > 0) drawText(0, 0, "▬".repeat(filled), color)
            if (width - filled > 0) drawText(0, filled, "▬".repeat(width - filled), track)
            marker?.let { drawText(0, (it * width).toInt().coerceIn(0, (width - 1).coerceAtLeast(0)), "┃", Palette.Text) }
        },
        debugInfo = { "HBar($fraction)" },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val w = (if (constraints.hasBoundedWidth) constraints.maxWidth else 10).coerceAtMost(maxCells)
                return if (w <= 0 || constraints.maxHeight < 1) layout(0, 0) {} else layout(w, 1) {}
            }
        },
    )
}


/**
 * One row of text that follows its cursor. With the cursor at the end (the usual case) it shows the END of the text,
 * cut from the start with "…"; with the cursor moved into the text it shows the stretch around the cursor, with "…" on
 * the cut side(s). Line breaks show as "⏎". Only the visible part is sanitized, so a huge paste costs nothing.
 *
 * @param caretAt the cursor position in [text], or -1 for the end; ignored when [caret] is false.
 */
@Composable
fun TailText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, caret: Boolean = false, caretAt: Int = -1, selection: com.arcana.brain.app.input.Selection? = null) {
    val state = remember { LabelState() }
    Layout(
        content = {},
        modifier = modifier.drawBehind {
            state.lines.firstOrNull()?.let { drawText(0, 0, it, color); drawSelected(0, it, state.selected.firstOrNull()) }
        },
        debugInfo = { "TailText" },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 80
                if (w <= 0 || constraints.maxHeight < 1) {
                    state.lines = emptyList()
                    state.selected = emptyList()
                    return layout(0, 0) {}
                }
                fun flat(part: String) = sanitizeForTerminal(part).replace('\n', '⏎')
                val selected = if (caret) selection?.bounds(text.length) else null
                val pos = if (caret && selected != null) (if (caretAt < 0) text.length else caretAt.coerceAtMost(text.length)) else if (caret && caretAt in 0 until text.length) caretAt else -1
                state.selected = emptyList()
                val line = if (pos < 0) {
                    val cursor = if (caret) "▏" else ""
                    val room = (w - cursor.length).coerceAtLeast(1)
                    // A line break is drawn as one glyph, so the tail we look at can be a little more than the room.
                    val tail = flat(text.takeLast(room * 3))
                    (if (tail.length <= room && text.length <= room * 3) tail else "…" + tail.takeLast(room - 1)) + cursor
                } else {
                    // The cursor is inside the text: show the stretch around it, with "…" where the text goes on.
                    val room = (w - 3).coerceAtLeast(1)
                    val from = (pos - room / 2).coerceIn(0, (text.length - room).coerceAtLeast(0))
                    val to = minOf(text.length, from + room)
                    val lead = if (from > 0) 1 else 0
                    if (selected != null) {
                        // A selection is shown in reverse video instead of a cursor glyph, so columns line up with the text.
                        val a = maxOf(selected.first, from) - from + lead
                        val b = minOf(selected.last + 1, to) - from + lead
                        state.selected = listOf(if (b > a) a until b else null)
                        (if (from > 0) "…" else "") + flat(text.substring(from, to)) + (if (to < text.length) "…" else "")
                    } else {
                        (if (from > 0) "…" else "") + flat(text.substring(from, pos)) + "▏" + flat(text.substring(pos, to)) + (if (to < text.length) "…" else "")
                    }
                }
                state.lines = listOf(line.take(w).padEnd(w))
                return layout(w, 1) {}
            }
        },
    )
}


/**
 * A text field that wraps over up to [maxRows] rows and scrolls, instead of cutting the text: the window follows the
 * cursor, and the last column shows "↑" / "↓" when there are rows above / below it. Without [caret] it shows the start.
 * Rows break by character, so the cursor and the edited text always line up with what is drawn.
 *
 * @param caretAt the cursor position in [text], or -1 for the end; ignored when [caret] is false.
 */
@Composable
fun WrappedText(text: String, maxRows: Int, modifier: Modifier = Modifier, color: Color = Color.Unspecified, caret: Boolean = false, caretAt: Int = -1, selection: com.arcana.brain.app.input.Selection? = null) {
    val state = remember { LabelState() }
    Layout(
        content = {},
        modifier = modifier.drawBehind {
            state.lines.forEachIndexed { row, line -> drawText(row, 0, line, color); drawSelected(row, line, state.selected.getOrNull(row)) }
            state.bar.forEachIndexed { row, glyph -> drawText(row, width - 1, glyph, if (glyph == "█") Palette.Accent else Palette.Dim) }
        },
        debugInfo = { "WrappedText" },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 80
                val rowLimit = minOf(maxRows, if (constraints.hasBoundedHeight) constraints.maxHeight else maxRows)
                if (w < 3 || rowLimit < 1) {
                    state.lines = emptyList()
                    return layout(0, 0) {}
                }
                // One column is kept for the cursor glyph and one for the scroll marks.
                val rows = wrapRows(text, w - 2)
                val shown = minOf(rows.size, rowLimit)
                val pos = if (caret && caretAt in 0..text.length) caretAt else text.length
                val selected = if (caret) selection?.bounds(text.length) else null
                if (caret) {
                    val at = caretRow(rows, pos)
                    if (at < state.first) state.first = at
                    if (at >= state.first + shown) state.first = at - shown + 1
                } else state.first = 0
                state.first = state.first.coerceIn(0, rows.size - shown)
                state.selected = (0 until shown).map { i ->
                    val row = rows[state.first + i]
                    // Columns match the text, because a selection draws no cursor glyph.
                    selected?.let { r -> (maxOf(r.first, row.start) - row.start) until (minOf(r.last + 1, row.end) - row.start) }?.takeIf { !it.isEmpty() }
                }
                state.lines = (0 until shown).map { i ->
                    val index = state.first + i
                    val row = rows[index]
                    val raw = text.substring(row.start, row.end)
                    val body = if (caret && selected == null && caretRow(rows, pos) == index) {
                        val a = (pos - row.start).coerceIn(0, raw.length)
                        sanitizeForTerminal(raw.substring(0, a)) + "▏" + sanitizeForTerminal(raw.substring(a))
                    } else sanitizeForTerminal(raw)
                    body.take(w - 1).padEnd(w - 1) + " "
                }
                // A scrollbar in the last column: the thumb is as tall as the visible share of the text and sits where the window is.
                state.bar = if (rows.size <= shown) emptyList() else {
                    val thumb = (shown * shown / rows.size).coerceIn(1, shown)
                    val top = if (rows.size == shown) 0 else ((state.first.toLong() * (shown - thumb) + (rows.size - shown) / 2) / (rows.size - shown)).toInt()
                    (0 until shown).map { if (it in top until top + thumb) "█" else "│" }
                }
                return layout(w, shown) {}
            }
        },
    )
}

/** Word-wraps [text] to [width] columns; blank lines are kept. */
fun wrap(text: String, width: Int): List<String> {
    val max = width.coerceAtLeast(4)
    val out = mutableListOf<String>()
    for (raw in text.split('\n')) {
        var line = raw
        if (line.isEmpty()) {
            out += ""
            continue
        }
        while (line.length > max) {
            var cut = line.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out += line.substring(0, cut).trimEnd()
            line = line.substring(cut).trimStart()
        }
        out += line
    }
    return out
}

/** One screen row of a wrapped text field: the characters `[start, end)` of the text (a line break is not part of a row). */
data class TextRow(val start: Int, val end: Int)

/**
 * Breaks [text] into rows of at most [width] characters: at every line break, and by character (not by word) where a row
 * is full, so a long branch name or URL is never cut and a cursor position maps to exactly one place. Always at least one row.
 */
fun wrapRows(text: String, width: Int): List<TextRow> {
    val w = width.coerceAtLeast(1)
    val rows = ArrayList<TextRow>()
    var lineStart = 0
    while (true) {
        val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        var start = lineStart
        do {
            var end = minOf(start + w, nl)
            if (end < nl && end > start + 1 && text[end - 1].isHighSurrogate()) end-- // never split a surrogate pair
            rows += TextRow(start, end)
            start = end
        } while (start < nl)
        if (nl >= text.length) break
        lineStart = nl + 1
    }
    return rows
}

/** The row the cursor at [pos] is drawn on: the last row that starts at or before it (a full row hands over to the next). */
fun caretRow(rows: List<TextRow>, pos: Int): Int = rows.indexOfLast { it.start <= pos }.coerceAtLeast(0)
