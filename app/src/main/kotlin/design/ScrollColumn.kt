package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.char
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.MeasureResult
import com.jakewharton.mosaic.layout.MeasureScope
import com.jakewharton.mosaic.layout.Placeable
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.unit.Constraints

/**
 * Where a [ScrollColumn] is. [first] is the index of the first child on show; the column fills in the rest while it is
 * measured, so a key press can be clamped to what is really there.
 */
class ScrollState {
    var first = 0
    internal var count = 0
    internal var shown = 0
    internal var maxFirst = 0
    internal var heights = IntArray(0)

    /** True when some children are out of view (the column drew a scrollbar). */
    val scrollable get() = count > shown

    fun reset() { first = 0 }

    /** Children move whole, so a step skips children until at least [STEP] rows have gone by: a spacer row never makes a press look dead. */
    private fun stepDown(): Int {
        var to = first
        var moved = 0
        while (to < maxFirst && moved < STEP) { moved += heights.getOrElse(to) { 1 }; to++ }
        return to
    }

    private fun stepUp(): Int {
        var to = first
        var moved = 0
        while (to > 0 && moved < STEP) { to--; moved += heights.getOrElse(to) { 1 } }
        return to
    }

    /**
     * ↑/↓ (and k/j when [letters]) move one child, PgUp/PgDn a page, Home/End the ends. Returns whether the key was used,
     * so a screen can offer these keys only while there is something to scroll.
     */
    fun handle(key: Key, letters: Boolean = false): Boolean {
        if (!scrollable) return false
        val page = (shown - 1).coerceAtLeast(1)
        val ch = if (letters) key.char else null
        val to = when {
            key == Key.Up || ch == 'k' -> stepUp()
            key == Key.Down || ch == 'j' -> stepDown()
            key == Key.PageUp -> first - page
            key == Key.PageDown -> first + page
            key == Key.Home -> 0
            key == Key.End -> maxFirst
            else -> return false
        }
        first = to.coerceIn(0, maxFirst)
        return true
    }
}

private const val STEP = 3
private const val MIN_PARTIAL = 5

@Composable
fun rememberScrollState() = remember { ScrollState() }

/**
 * A vertical list of children taller than the rows it is given: it shows a window of them and a scrollbar in its last
 * column (the column is always reserved, so the width does not jump when something becomes scrollable). Children move whole (Mosaic can't
 * clip), so each one should be a row, a panel or another block that fits on its own. With [follow] set to a child's index
 * the window moves just far enough to keep that child on screen (a cursor); otherwise [state] decides what is on top.
 *
 * Give it [flexible] in a [Stack] so it is offered the rows left instead of the whole content height.
 */
@Composable
fun ScrollColumn(state: ScrollState, modifier: Modifier = Modifier, follow: Int = -1, content: @Composable () -> Unit) {
    val policy = remember(state) { ScrollPolicy(state) }
    policy.follow = follow
    Layout(
        content = content,
        modifier = modifier.drawBehind {
            if (width < 1 || height < 1 || !state.scrollable) return@drawBehind
            val thumb = scrollThumb(height, state.first, state.count, state.shown)
            for (row in 0 until height) {
                val onThumb = thumb != null && row in thumb
                drawText(row, width - 1, if (onThumb) "█" else "│", if (onThumb) Palette.Muted else Palette.Dim)
            }
        },
        debugInfo = { "ScrollColumn" },
        measurePolicy = policy,
    )
}

private class ScrollPolicy(val state: ScrollState) : MeasurePolicy {
    var follow = -1
    private val Hidden = Constraints(maxWidth = 0, maxHeight = 0)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val maxW = constraints.maxWidth
        val inner = (maxW - 1).coerceAtLeast(0)
        val bounded = constraints.hasBoundedHeight
        val room = if (bounded) constraints.maxHeight else Int.MAX_VALUE
        val n = measurables.size
        val placeables = measurables.map { it.measure(Constraints(maxWidth = inner)) }
        val heights = IntArray(n) { placeables[it].height }

        // the furthest the top can go while the last child is still fully in view
        var maxFirst = n
        var tail = 0
        while (maxFirst > 0 && tail + heights[maxFirst - 1] <= room) { maxFirst--; tail += heights[maxFirst] }
        var first = state.first.coerceIn(0, maxFirst.coerceAtMost((n - 1).coerceAtLeast(0)))
        if (follow in 0 until n) {
            if (follow < first) first = follow
            var span = (first..follow).sumOf { heights[it] }
            while (span > room && first < follow) { span -= heights[first]; first++ }
        }
        state.first = first
        state.maxFirst = maxFirst.coerceAtMost((n - 1).coerceAtLeast(0))

        var used = 0
        var last = first - 1
        while (last + 1 < n && used + heights[last + 1] <= room) { last++; used += heights[last] }
        state.count = n
        state.heights = heights
        state.shown = last - first + 1

        // the next child does not fit whole: when there is room worth filling, show it shortened (a panel keeps its frame and
        // the rows that fit) instead of leaving a gap; scrolling brings the rest
        var partial: Placeable? = null
        var partialAt = -1
        val spare = room - used
        if (bounded && last + 1 < n && spare >= MIN_PARTIAL) {
            val p = measurables[last + 1].measure(Constraints(maxWidth = inner, maxHeight = spare))
            if (p.height in 1..spare) { partial = p; partialAt = used; used += p.height }
        }
        for (i in 0 until n) if (i < first || i > last + (if (partial != null) 1 else 0)) measurables[i].measure(Hidden)
        val scrolling = state.shown < n
        val height = if (scrolling && bounded) room else used
        val width = if (maxW == Constraints.Infinity) (placeables.maxOfOrNull { it.width } ?: 0) + 1 else maxW
        return layout(width.coerceAtLeast(constraints.minWidth), height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            for (i in first..last) { placeables[i].place(0, y); y += heights[i] }
            partial?.place(0, partialAt)
        }
    }
}
