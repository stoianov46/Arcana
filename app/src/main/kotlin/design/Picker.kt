package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/**
 * The state of a [Picker]: the highlighted row and the filter.
 *
 * Keys: ↑/↓ (or k/j) PgUp/PgDn Home/End move. `/` starts filtering; while [filtering] every printable key goes to the
 * filter field (so digits and letters are not shortcuts), ↑/↓ still move, Enter is left to the caller to choose the row,
 * and Esc clears the filter. The filter is a normal text field: word jumps, selection and deleting work as everywhere.
 * Callers should keep the app in focus mode while [filtering] is true (see `FocusMode`).
 */
class PickerState {
    val filterField = TextFieldState()
    val filter: String get() = filterField.text
    var index by mutableIntStateOf(0)
    var filtering by mutableStateOf(false)
        private set

    /** Opens the list with the filter already focused, as a palette does: typing searches at once. */
    fun startFiltering() {
        filtering = true
    }

    fun reset() {
        filterField.clear()
        index = 0
        filtering = false
    }

    /** The items that match the filter: every word typed must appear somewhere in the label, in any order ("theme dar" finds "Theme: Darcula"). */
    fun <T> visible(items: List<T>, label: (T) -> String): List<T> {
        val words = filter.split(' ').filter { it.isNotEmpty() }
        return if (words.isEmpty()) items else items.filter { item -> label(item).let { l -> words.all { l.contains(it, ignoreCase = true) } } }
    }

    fun <T> selected(items: List<T>, label: (T) -> String): T? =
        visible(items, label).let { it.getOrNull(index.coerceIn(0, (it.size - 1).coerceAtLeast(0))) }

    /** Handles navigation and filter typing; returns true when the key was used. Enter is never consumed. */
    fun <T> handle(key: Key, items: List<T>, pageSize: Int = 10, label: (T) -> String): Boolean {
        val count = visible(items, label).size
        val last = (count - 1).coerceAtLeast(0)
        val ch = key.char
        when {
            key == Key.Up || (!filtering && ch == 'k') -> index = (index - 1).coerceAtLeast(0)
            key == Key.Down || (!filtering && ch == 'j') -> index = (index + 1).coerceAtMost(last)
            key == Key.PageUp -> index = (index - pageSize).coerceAtLeast(0)
            key == Key.PageDown -> index = (index + pageSize).coerceAtMost(last)
            key == Key.Home && !filtering -> index = 0
            key == Key.End && !filtering -> index = last
            key == Key.Enter -> return false
            !filtering -> {
                if (ch != '/') return false
                filtering = true
            }
            key == Key.Escape -> reset()
            key == Key.Backspace && filter.isEmpty() -> filtering = false
            else -> {
                if (!filterField.onKey(key)) return false
                index = 0
            }
        }
        return true
    }
}

/**
 * A scrollable, filterable list: a search strip with the match count, then one full-width row per item with a coloured
 * bar on the highlighted one, an optional [icon] and a right-aligned [meta] text, and a scrollbar when not everything fits.
 *
 * @param rows how many item rows fit; the window follows the highlighted row.
 */
@Composable
fun <T> Picker(
    items: List<T>,
    state: PickerState,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    rows: Int = (LocalContentHeight.current - 8).coerceAtLeast(3),
    icon: (T) -> String = { "" },
    iconColor: (T) -> Color = { Palette.Muted },
    meta: (T) -> String = { "" },
    metaColor: (T) -> Color = { Palette.Dim },
    /** How many levels a row sits in (a tree): drawn as two spaces per level before the icon. */
    indent: (T) -> Int = { 0 },
    labelColor: (T) -> Color = { Palette.Text },
    /** The number shown in the search strip when nothing is typed (default: the rows; a tree shows its notes, not its folders). */
    total: Int = items.size,
    placeholder: String = "Press / to filter",
    emptyText: String = "Nothing here yet.",
) {
    val shown = state.visible(items, label)
    val capacity = rows.coerceAtLeast(1)
    val index = state.index.coerceIn(0, (shown.size - 1).coerceAtLeast(0))
    val first = (index - capacity / 2).coerceIn(0, (shown.size - capacity).coerceAtLeast(0))
    val window = shown.drop(first).take(capacity)

    Stack(modifier) {
        Row(Modifier.fillMaxWidth().background(if (state.filtering) Palette.Focus else Palette.SurfaceHigh).padding(horizontal = 1)) {
            Label("⌕ ", color = if (state.filtering) Palette.Cta else Palette.Muted, textStyle = TextStyle.Bold)
            Row(Modifier.weight(1f)) { TextValue(state.filterField, state.filtering, placeholder) }
            Label("  ${if (state.filter.isEmpty()) total else shown.size}${if (state.filter.isEmpty()) "" else "/${items.size}"}", color = Palette.Muted)
        }
        if (shown.isEmpty()) {
            Label(if (items.isEmpty()) emptyText else "No matches for “${state.filter}”.", color = Palette.Muted)
        } else {
            Row(Modifier.fillMaxWidth().height(window.size)) {
                Stack(Modifier.weight(1f)) {
                    window.forEachIndexed { i, item ->
                        val on = first + i == index
                        val bg = if (on) Palette.Selected else if ((first + i) % 2 == 0) Palette.EntryBg else Palette.Surface
                        Row(Modifier.fillMaxWidth().background(bg)) {
                            Label(if (on) "▌" else " ", color = Palette.Accent)
                            val pad = indent(item)
                            if (pad > 0) Label("  ".repeat(pad))
                            val glyph = icon(item)
                            if (glyph.isNotEmpty()) Label(" $glyph", color = iconColor(item))
                            Label(" ${label(item)}", Modifier.weight(1f), color = labelColor(item), textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
                            // the name has priority: the detail takes at most two fifths of the row and ends in "…"
                            val cap = ((LocalContentWidth.current - 10) * 2 / 5).coerceAtLeast(8)
                            val extra = meta(item).let { if (it.length > cap) it.take(cap - 1) + "…" else it }
                            if (extra.isNotEmpty()) Label("  $extra ", color = metaColor(item))
                        }
                    }
                }
                if (shown.size > capacity) {
                    HGap()
                    ScrollBar(first, shown.size, capacity, Modifier.height(window.size))
                }
            }
        }
    }
}
