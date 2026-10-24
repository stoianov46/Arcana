package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import com.arcana.brain.app.navigation.LocalNavigator
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/** One entry of an [ActionBar]. */
data class ActionItem(val key: String, val label: String, val enabled: Boolean = true, val accent: Color? = null)

/**
 * A row of `[ key ] Label` buttons, each as wide as its own text with a clear gap between neighbours, left-aligned. When
 * they do not fit on one line they continue on the next. A blank row above separates the bar from the panel over it, and
 * while a bar is on screen the footer stops repeating the same keys (they are still listed in the key map, `?`).
 */
@Composable
fun ActionBar(items: List<ActionItem>, width: Int = LocalContentWidth.current) {
    val navigator = LocalNavigator.current
    androidx.compose.runtime.DisposableEffect(navigator) {
        navigator.actionBars++
        onDispose { navigator.actionBars-- }
    }
    if (!rememberDensity().tiny) VGap()
    breakIntoLines(items, width).forEach { ActionLine(it) }
}

private const val ButtonGap = 2

/** Width of one button: ` key ` + ` label `. */
private fun ActionItem.buttonWidth() = key.length + 2 + label.length + 2

/** The lines [ActionBar] breaks [items] into at [width]: a button goes on the current line while it still fits. */
internal fun breakIntoLines(items: List<ActionItem>, width: Int): List<List<ActionItem>> {
    val lines = mutableListOf<MutableList<ActionItem>>()
    var used = 0
    for (item in items) {
        val w = item.buttonWidth()
        if (lines.isEmpty() || used + ButtonGap + w > width) {
            lines += mutableListOf(item)
            used = w
        } else {
            lines.last() += item
            used += ButtonGap + w
        }
    }
    return lines
}

/** How many rows an [ActionBar] of [items] takes (its buttons plus the blank row above), so a screen can give everything else the rest. */
@Composable
fun actionBarRows(items: List<ActionItem>): Int = breakIntoLines(items, LocalContentWidth.current).size + if (rememberDensity().tiny) 0 else 1

@Composable
private fun ActionLine(items: List<ActionItem>) {
    Row {
        items.forEachIndexed { i, item ->
            if (i > 0) HGap(ButtonGap)
            Label(
                " ${item.key} ",
                color = if (item.enabled) Palette.Background else Palette.Dim,
                background = if (item.enabled) item.accent ?: Palette.Accent else Palette.SurfaceHigh,
                textStyle = TextStyle.Bold,
            )
            Label(
                " ${item.label} ",
                color = if (item.enabled) Palette.Text else Palette.Dim,
                background = Palette.SurfaceHigh,
            )
        }
    }
}

/** A small coloured pill, for a status or a tag next to a title. */
@Composable
fun Badge(text: String, color: Color = Palette.Accent, background: Color = Palette.AccentBg) {
    Label(" $text ", color = color, background = background, textStyle = TextStyle.Bold)
}
