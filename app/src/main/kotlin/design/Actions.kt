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

/** How an [ActionBar] looks: [Quiet] (coloured key, muted label, no background) for the controls inside a screen, [Button] (filled chips) for dialogs and calls to act. */
enum class ActionStyle { Quiet, Button }

/**
 * A row of `[ key ] Label` buttons (style [ActionStyle.Button]), or of quiet `key Label` hints (the default, [ActionStyle.Quiet]: nothing filled, no padding row above), each as wide as its own text with a clear gap between neighbours, left-aligned. When
 * they do not fit on one line they continue on the next. A blank row above separates the bar from the panel over it, and
 * while a bar is on screen the footer stops repeating the same keys (they are still listed in the key map, `?`).
 */
@Composable
fun ActionBar(items: List<ActionItem>, width: Int = LocalContentWidth.current, style: ActionStyle = ActionStyle.Quiet) {
    val navigator = LocalNavigator.current
    androidx.compose.runtime.DisposableEffect(navigator) {
        navigator.actionBars++
        onDispose { navigator.actionBars-- }
    }
    if (style == ActionStyle.Button && !rememberDensity().tiny) VGap()
    breakIntoLines(items.map { it.copy(key = Shortcuts.label(it.key)) }, width, style).forEach { if (style == ActionStyle.Button) ActionLine(it) else QuietLine(it) }
}

private const val ButtonGap = 2
private const val QuietGap = 3 // " · "

/** Width of one button: ` key ` + ` label `; of a quiet hint: `key label`. */
private fun ActionItem.buttonWidth(style: ActionStyle) = if (style == ActionStyle.Button) key.length + 2 + label.length + 2 else key.length + 1 + label.length

/** The lines [ActionBar] breaks [items] into at [width]: a button goes on the current line while it still fits. */
internal fun breakIntoLines(items: List<ActionItem>, width: Int, style: ActionStyle = ActionStyle.Button): List<List<ActionItem>> {
    val gap = if (style == ActionStyle.Button) ButtonGap else QuietGap
    val lines = mutableListOf<MutableList<ActionItem>>()
    var used = 0
    for (item in items) {
        val w = item.buttonWidth(style)
        if (lines.isEmpty() || used + gap + w > width) {
            lines += mutableListOf(item)
            used = w
        } else {
            lines.last() += item
            used += gap + w
        }
    }
    return lines
}

/** How many rows an [ActionBar] of [items] takes (its buttons plus the blank row above), so a screen can give everything else the rest. */
@Composable
fun actionBarRows(items: List<ActionItem>, style: ActionStyle = ActionStyle.Quiet): Int =
    breakIntoLines(items.map { it.copy(key = Shortcuts.label(it.key)) }, LocalContentWidth.current, style).size + if (style == ActionStyle.Button && !rememberDensity().tiny) 1 else 0

@Composable
private fun ActionLine(items: List<ActionItem>) {
    Row {
        items.forEachIndexed { i, item ->
            if (i > 0) HGap(ButtonGap)
            Label(
                " ${item.key} ",
                color = if (item.enabled) Palette.Ink else Palette.Dim,
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

/** The quiet look: the key in its colour (bold), the label muted, a dim dot between; a disabled action is dim all over. */
@Composable
private fun QuietLine(items: List<ActionItem>) {
    Row {
        items.forEachIndexed { i, item ->
            if (i > 0) Label(" · ", color = Palette.Dim)
            Label(item.key, color = if (item.enabled) item.accent ?: Palette.Accent else Palette.Dim, textStyle = TextStyle.Bold)
            Label(" ${item.label}", color = if (item.enabled) Palette.Muted else Palette.Dim)
        }
    }
}

/** A small coloured pill, for a status or a tag next to a title. */
@Composable
fun Badge(text: String, color: Color = Palette.Accent, background: Color = Palette.AccentBg) {
    Label(" $text ", color = color, background = background, textStyle = TextStyle.Bold)
}
