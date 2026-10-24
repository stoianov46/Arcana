package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.delay

/** A one-line outcome of an action: shown as ✓ (success) or ✕ (error). */
data class Notice(val text: String, val isError: Boolean = false)

@Composable
fun NoticeLine(notice: Notice?) {
    if (notice != null) {
        val plain = LocalPlainGlyphs.current
        Label(
            (if (notice.isError) (if (plain) "Error: " else "✕ ") else (if (plain) "Done: " else "✓ ")) + notice.text,
            color = if (notice.isError) Palette.Red else Palette.Green,
            wrap = true,
        )
    }
}

/** Call to action: the next thing the user should press to go on. */
@Composable
fun Cta(text: String, modifier: Modifier = Modifier) =
    Label((if (LocalPlainGlyphs.current) "Next: " else "→ ") + text, modifier, color = Palette.Cta, textStyle = TextStyle.Bold, wrap = true)

/** Hint: background information or a tip; nothing to do about it. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Label((if (LocalPlainGlyphs.current) "Note: " else "› ") + text, modifier, color = Palette.Hint, wrap = true)

/** Warning: something is blocked, limited or needs care. */
@Composable
fun Warning(text: String, modifier: Modifier = Modifier) =
    Label((if (LocalPlainGlyphs.current) "Warning: " else "⚠ ") + text, modifier, color = Palette.Warn, wrap = true)

@Composable
fun Loading(what: String) {
    Label("Loading $what…", color = Palette.Muted)
}

@Composable
fun Spinner(text: String) = Spinner { text }

/** A spinner whose [text] is asked for again on every frame, so it can show elapsed time and a growing count. */
@Composable
fun Spinner(text: () -> String) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(90)
            frame++
        }
    }
    val frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    Label("${frames[frame % frames.length]} ${text()}", color = Palette.Warn)
}

/** A row of colour blocks showing what a theme looks like. */
@Composable
fun ThemeSwatches(spec: ThemeSpec) {
    com.jakewharton.mosaic.ui.Row {
        spec.swatchColors().forEach { color ->
            Label("   ", background = color)
            Label(" ")
        }
    }
}
