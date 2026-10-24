package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.input.Clipboard
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.char
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/** One drawn row of output. [entry] is the block it belongs to (-1 = none), so select mode can walk and copy whole blocks. */
data class Line(
    val text: String,
    val color: Color = Color.Unspecified,
    val bold: Boolean = false,
    val background: Color = Color.Unspecified,
    val entry: Int = -1,
    /** When set, the line is drawn run by run (Markdown); [text] is then the plain text of the runs. */
    val runs: List<Run>? = null,
    /** Background painted across the whole row before the runs (code blocks). */
    val fill: Color = Color.Unspecified,
)

/** The wrapped lines each entry covers, indexed by entry (an entry with no lines gets an empty range). */
internal fun entryRanges(lines: List<Line>): List<IntRange> {
    val first = HashMap<Int, Int>()
    val last = HashMap<Int, Int>()
    lines.forEachIndexed { i, l ->
        if (l.entry >= 0) {
            first.putIfAbsent(l.entry, i)
            last[l.entry] = i
        }
    }
    val count = (last.keys.maxOrNull() ?: -1) + 1
    return (0 until count).map { e -> first[e]?.let { it..last.getValue(e) } ?: IntRange.EMPTY }
}

/**
 * Scrolling and select mode of an output view.
 *
 * Scrolling: ↑/↓ (or k/j) one line, PgUp/PgDn a page, Space a page down, Home/g the top, End/G the latest text.
 *
 * Select mode (`s`): a cursor walks the entries (blocks) of the output; ↑/↓ move it, Space ticks the entry and moves on,
 * `a` ticks all or none, `c` or Enter copies the ticked entries (or the one under the cursor) to the clipboard, `b` copies
 * only code blocks and tables, Esc or `s` leaves.
 */
class OutputState {
    /** Lines scrolled up from the bottom. */
    var offset by mutableIntStateOf(0)
    var selecting by mutableStateOf(false)
        private set
    var cursor by mutableIntStateOf(0)
        private set
    var marked by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** Select mode copies plain text (Markdown markers removed) instead of the text as written. */
    var copyPlain = false

    // Set by [ScrollView] every frame.
    internal var maxOffset = 0
    internal var page = 10
    internal var total = 0
    internal var visible = 1
    internal var ranges: List<IntRange> = emptyList()

    fun reset() {
        offset = 0
        leaveSelect()
    }

    private fun leaveSelect() {
        selecting = false
        marked = emptySet()
    }

    private fun scrollUp(lines: Int) {
        offset = (offset + lines).coerceAtMost(maxOffset)
    }

    private fun scrollDown(lines: Int) {
        offset = (offset - lines).coerceAtLeast(0)
    }

    /**
     * @param entries the source text of each entry, for select mode (empty = no select mode)
     * @param letters letter keys (k j g G s …) work; false while a text field has the keyboard
     * @param space Space scrolls a page down; false while a text field has the keyboard
     */
    fun handle(key: Key, entries: List<String> = emptyList(), letters: Boolean = true, space: Boolean = letters, onNotice: (Notice) -> Unit = {}): Boolean {
        if (selecting) return selectKey(key, entries, onNotice)
        val ch = key.char
        when {
            key == Key.Up || (letters && ch == 'k') -> scrollUp(1)
            key == Key.Down || (letters && ch == 'j') -> scrollDown(1)
            key == Key.PageUp -> scrollUp(page)
            key == Key.PageDown || (space && ch == ' ') -> scrollDown(page)
            key == Key.Home && letters || (letters && ch == 'g') -> offset = maxOffset
            key == Key.End || (letters && ch == 'G') -> offset = 0
            letters && ch == 's' && entries.isNotEmpty() -> beginSelect(onNotice)
            else -> return false
        }
        return true
    }

    /** Starts select mode on the entry nearest the top of the window (also used by the command palette). */
    fun beginSelect(onNotice: (Notice) -> Unit = {}) {
        selecting = true
        marked = emptySet()
        val start = total - offset - visible
        cursor = ranges.indexOfFirst { !it.isEmpty() && it.last >= start }.coerceAtLeast(0)
        onNotice(Notice("Select mode: ↑↓ move, Space ticks, c copies, b copies code only, Esc leaves."))
    }

    private fun selectKey(key: Key, entries: List<String>, onNotice: (Notice) -> Unit): Boolean {
        val ch = key.char
        when {
            key == Key.Escape || ch == 's' -> leaveSelect()
            // An entry taller than the window is scrolled through line by line; the cursor moves on once its edge is in view.
            key == Key.Up || ch == 'k' -> if (!stepWithinEntry(-1)) moveCursor(cursor - 1, entries.size)
            key == Key.Down || ch == 'j' -> if (!stepWithinEntry(+1)) moveCursor(cursor + 1, entries.size)
            key == Key.PageUp -> { scrollUp(page); followWindow(up = true) }
            key == Key.PageDown -> { scrollDown(page); followWindow(up = false) }
            key == Key.Home || ch == 'g' -> moveCursor(0, entries.size, jump = true)
            key == Key.End || ch == 'G' -> moveCursor(Int.MAX_VALUE, entries.size, jump = true, showEnd = true)
            ch == ' ' -> {
                marked = if (cursor in marked) marked - cursor else marked + cursor
                moveCursor(cursor + 1, entries.size)
            }
            ch == 'a' -> marked = if (marked.size == entries.size) emptySet() else entries.indices.toSet()
            ch == 'c' || key == Key.Enter -> copy(entries, codeOnly = false, onNotice)
            ch == 'b' -> copy(entries, codeOnly = true, onNotice)
            else -> return false
        }
        return true
    }

    /** First line of the window and the line after its last. */
    private fun window(): IntRange = (total - offset - visible).let { it until (it + visible) }

    /**
     * When the cursor's entry runs past the edge of the window in direction [dir], scrolls one line that way and returns
     * true (the cursor stays); false when the whole edge is already visible, so the cursor may move on.
     */
    private fun stepWithinEntry(dir: Int): Boolean {
        val range = ranges.getOrNull(cursor)?.takeUnless { it.isEmpty() } ?: return false
        val w = window()
        return when {
            dir > 0 && range.last > w.last -> { scrollDown(1); true }
            dir < 0 && range.first < w.first -> { scrollUp(1); true }
            else -> false
        }
    }

    /** After the window was scrolled by a page, the cursor goes to the entry now in view if its own entry left the window. */
    private fun followWindow(up: Boolean) {
        val w = window()
        val inView = ranges.withIndex().filter { (_, r) -> !r.isEmpty() && r.first <= w.last && r.last >= w.first }.map { it.index }
        if (inView.isEmpty() || cursor in inView) return
        cursor = if (up) inView.last() else inView.first()
    }

    /**
     * Moves the cursor to entry [to] and brings it into view. A move that changes nothing (the cursor is already on the
     * last entry) leaves the view alone, unless [jump] is set (Home / End). An entry taller than the window is shown from
     * its top when the cursor arrives from above, and from its end when it arrives from below (or with [showEnd]).
     */
    private fun moveCursor(to: Int, count: Int, jump: Boolean = false, showEnd: Boolean = false) {
        val target = to.coerceIn(0, (count - 1).coerceAtLeast(0))
        if (target == cursor && !jump) return
        val movingUp = target < cursor || showEnd
        cursor = target
        val range = ranges.getOrNull(cursor)?.takeUnless { it.isEmpty() } ?: return
        val start = total - offset - visible
        val end = start + visible
        if (range.first >= start && range.last < end) return
        val fits = range.last - range.first + 1 <= visible
        val newStart = when {
            fits -> if (range.first < start) range.first else range.last - visible + 1
            movingUp -> range.last - visible + 1
            else -> range.first
        }.coerceAtLeast(0)
        offset = (total - visible - newStart).coerceIn(0, maxOffset)
    }

    private fun copy(entries: List<String>, codeOnly: Boolean, onNotice: (Notice) -> Unit) {
        val picked = marked.ifEmpty { setOf(cursor) }.sorted().mapNotNull { entries.getOrNull(it) }
        val joined = picked.mapNotNull { if (codeOnly) codeAndTables(it) else it.trim('\n') }.filter { it.isNotBlank() }.joinToString("\n\n")
        val text = if (copyPlain && !codeOnly) plainText(joined) else joined
        onNotice(
            when {
                text.isBlank() -> Notice(if (codeOnly) "No code block or table in what you picked. c copies the whole text." else "Nothing to copy here.", true)
                Clipboard.writeText(text) -> Notice("Copied ${picked.size} ${if (picked.size == 1) "entry" else "entries"}${if (codeOnly) " (code and tables only)" else ""}: ${text.length} characters.")
                else -> Notice("Clipboard is unavailable.", true)
            },
        )
    }
}

/** A one-column vertical scroll indicator: a faint track with a brighter thumb. */
@Composable
fun ScrollBar(scroll: Int, total: Int, visible: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.width(1).drawBehind {
            if (height <= 0) return@drawBehind
            val thumb = scrollThumb(height, scroll, total, visible)
            for (row in 0 until height) {
                val onThumb = thumb != null && row in thumb
                drawText(row, 0, if (onThumb) "█" else "│", if (onThumb) Palette.Muted else Palette.Dim)
            }
        },
    ) {}
}

/** The rows the thumb covers in a track [height] rows tall, or null when everything fits. */
fun scrollThumb(height: Int, scroll: Int, total: Int, visible: Int): IntRange? {
    if (height <= 0 || total <= visible || total <= 0) return null
    val thumb = (height.toLong() * visible / total).toInt().coerceIn(1, height)
    val track = height - thumb
    val maxScroll = (total - visible).coerceAtLeast(0)
    val pos = scroll.coerceIn(0, maxScroll)
    val top = if (maxScroll == 0) 0 else (track.toLong() * pos / maxScroll).toInt().coerceIn(0, track)
    return top until (top + thumb)
}

/**
 * [lines] in a window of [rows] rows that follows the end of the text (a stream), scrolled up by [state]; a scrollbar
 * sits in the last column. A one-column gutter on the left carries the select-mode bars (cyan = cursor, green = ticked),
 * so lines should be wrapped to the width minus three.
 */
@Composable
fun ScrollView(lines: List<Line>, rows: Int, state: OutputState, modifier: Modifier = Modifier) {
    val visible = rows.coerceAtLeast(1)
    state.maxOffset = (lines.size - visible).coerceAtLeast(0)
    state.page = (visible - 1).coerceAtLeast(1)
    state.total = lines.size
    state.visible = visible
    state.ranges = entryRanges(lines)
    val offset = state.offset.coerceIn(0, state.maxOffset)
    val end = lines.size - offset
    val start = (end - visible).coerceAtLeast(0)
    Row(modifier.fillMaxWidth().height(visible)) {
        Column(Modifier.weight(1f)) {
            for (line in lines.subList(start, end)) {
                val onCursor = state.selecting && line.entry >= 0 && line.entry == state.cursor
                val ticked = state.selecting && line.entry in state.marked
                val tint = if (onCursor) Palette.Selected else if (ticked) Palette.Focus else line.background
                Row(Modifier.fillMaxWidth().background(tint)) {
                    Label(if (onCursor || ticked) "▌" else " ", color = if (onCursor) Palette.Cta else Palette.Green)
                    val runs = line.runs
                    if (runs != null) {
                        RunsRow(runs, line.fill, Modifier.weight(1f))
                    } else {
                        Label(
                            line.text.ifEmpty { " " },
                            Modifier.weight(1f),
                            color = line.color,
                            textStyle = if (line.bold) TextStyle.Bold else TextStyle.Unspecified,
                        )
                    }
                }
            }
        }
        HGap()
        ScrollBar(start, lines.size, visible, Modifier.height(visible))
    }
}

private class RunsState {
    var runs: List<Run> = emptyList()
}

/** One row of differently styled runs, clipped to the width it is given, on an optional full-width [fill] background. */
@Composable
internal fun RunsRow(runs: List<Run>, fill: Color, modifier: Modifier = Modifier) {
    val state = androidx.compose.runtime.remember { RunsState() }
    state.runs = runs
    com.jakewharton.mosaic.ui.Layout(
        content = {},
        modifier = modifier.drawBehind {
            if (fill != Color.Unspecified) drawText(0, 0, " ".repeat(width), background = fill)
            var col = 0
            for (run in state.runs) {
                if (col >= width) break
                val shown = run.text.take(width - col)
                drawText(0, col, shown, if (run.color == Color.Unspecified) Palette.Text else run.color, run.background, run.style)
                col += shown.length
            }
        },
        debugInfo = { "RunsRow" },
        measurePolicy = object : com.jakewharton.mosaic.layout.MeasurePolicy {
            override fun com.jakewharton.mosaic.layout.MeasureScope.measure(
                measurables: List<com.jakewharton.mosaic.layout.Measurable>,
                constraints: com.jakewharton.mosaic.ui.unit.Constraints,
            ): com.jakewharton.mosaic.layout.MeasureResult {
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else 80
                return if (w <= 0 || constraints.maxHeight < 1) layout(0, 0) {} else layout(w, 1) {}
            }
        },
    )
}
