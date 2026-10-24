package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.MeasureResult
import com.jakewharton.mosaic.layout.MeasureScope
import com.jakewharton.mosaic.layout.Placeable
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.layoutId
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.unit.Constraints

/** Rows available to a screen's content; used to size scrolling windows of long lists. */
val LocalContentHeight = compositionLocalOf { 24 }

/** Columns available to a screen's content (terminal width minus sidebar and padding). */
val LocalContentWidth = compositionLocalOf { 80 }

/**
 * How much room a region has, so a screen swaps to a denser layout only when the roomier one would not fit, instead of
 * having blocks silently hidden.
 */
class Density(val width: Int, val height: Int, private val bias: Int = 0) {
    /** One-row `label  value` fields instead of label-over-value. */
    val inline get() = height < 22 + bias

    /** Drop card padding and optional extras. */
    val compact get() = height < 16 + bias

    /** So short that decoration (card titles, spacers) has to go to leave room for the controls. */
    val tiny get() = height < 13 + bias

    /** Too narrow for two side-by-side columns. */
    val narrow get() = width < 80
}

/** Interface density chosen in Settings: "compact" packs sooner, "spacious" keeps the roomy layouts longer. */
val LocalUiScale = compositionLocalOf { "normal" }

/** Plain words and ASCII instead of decorative glyphs (Settings → Accessibility). */
val LocalPlainGlyphs = compositionLocalOf { false }

fun scaleBias(scale: String) = when (scale) {
    "compact" -> -4
    "spacious" -> 6
    else -> 0
}

@Composable
fun rememberDensity() = Density(LocalContentWidth.current, LocalContentHeight.current, scaleBias(LocalUiScale.current))

@Composable
fun VGap(rows: Int = 1) {
    if (!rememberDensity().tiny) Spacer(Modifier.height(rows))
}

@Composable
fun HGap(cols: Int = 1) = Spacer(Modifier.width(cols))

private const val FLEXIBLE = "flexible"

/** Marks a direct child of [Stack] as able to shrink itself to the height it is offered (it is a Stack too). */
fun Modifier.flexible(): Modifier = layoutId(FLEXIBLE)

/**
 * Vertical flow that never overflows its height. Mosaic can't clip, so a Column taller than the terminal would crash
 * the renderer. Children are placed top to bottom; one that doesn't fit in the remaining rows is hidden (measured to
 * zero size, which Mosaic skips when drawing) instead of being drawn out of bounds.
 */
@Composable
fun Stack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier, debugInfo = { "Stack" }, measurePolicy = StackPolicy)
}

private object StackPolicy : MeasurePolicy {
    private val Hidden = Constraints(maxWidth = 0, maxHeight = 0)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val placed = ArrayList<Pair<Placeable, Int>>()
        var used = 0
        for (m in measurables) {
            val remaining = if (constraints.hasBoundedHeight) maxH - used else Constraints.Infinity
            if (remaining <= 0) {
                m.measure(Hidden)
                continue
            }
            val placeable = if (m.layoutId == FLEXIBLE) {
                m.measure(Constraints(maxWidth = maxW, maxHeight = remaining))
            } else {
                m.measure(Constraints(maxWidth = maxW))
            }
            if (placeable.height > remaining) {
                m.measure(Hidden)
            } else {
                placed += placeable to used
                used += placeable.height
            }
        }
        val width = (placed.maxOfOrNull { it.first.width } ?: 0).coerceIn(constraints.minWidth, maxW)
        val height = used.coerceAtLeast(constraints.minHeight)
        return layout(width, height) {
            placed.forEach { (p, y) -> p.place(0, y) }
        }
    }
}
