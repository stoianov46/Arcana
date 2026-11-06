package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/** A row of tabs with the current one lit. With too little room the window follows the current tab, `‹` `›` marking more. */
@Composable
fun TabBar(tabs: List<String>, current: Int) {
    val room = LocalContentWidth.current - 4
    fun width(i: Int) = tabs[i].length + 3
    var from = current
    var to = current
    var used = width(current)
    // grow the window around the current tab, alternating, while it still fits
    while (true) {
        val canLeft = from > 0 && used + width(from - 1) <= room
        val canRight = to < tabs.lastIndex && used + width(to + 1) <= room
        if (!canLeft && !canRight) break
        if (canRight) { to++; used += width(to) }
        if (canLeft) { from--; used += width(from) }
    }
    Row {
        if (from > 0) Label("‹ ", color = Palette.Muted)
        for (i in from..to) {
            val on = i == current
            Label(" ${tabs[i]} ", color = if (on) Palette.Ink else Palette.Muted, background = if (on) Palette.Cta else Color.Unspecified, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(" ")
        }
        if (to < tabs.lastIndex) Label("›", color = Palette.Muted)
    }
}
