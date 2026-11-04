package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.delay

/**
 * [text] in [color], or, while [animate], with the logo's rainbow flowing along it: the colour wheel is laid over the letters [waves] times and moves, so every colour appears
 * [waves] times in the word. The sidebar logo and the reasoning-effort badge under the message field both use it, so they move the same way.
 */
@Composable
fun RainbowText(text: String, animate: Boolean, color: Color, waves: Int = 2, bold: Boolean = true) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(animate) {
        while (animate) {
            delay(150)
            tick = (tick + 1) % 40
        }
    }
    if (!animate) {
        Label(text, color = color, textStyle = if (bold) TextStyle.Bold else TextStyle.Unspecified)
        return
    }
    Row {
        text.forEachIndexed { i, ch ->
            val phase = waves * i.toDouble() / text.length.coerceAtLeast(2) - tick.toDouble() / 40
            Label(ch.toString(), color = Palette.logoShade(phase), textStyle = if (bold) TextStyle.Bold else TextStyle.Unspecified)
        }
    }
}
