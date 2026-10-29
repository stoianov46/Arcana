package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.GenState
import com.arcana.brain.app.Generation
import com.arcana.brain.app.design.*
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.Vault

/** Rows a screen has below its header (title, subtitle, gap) and above the footer actions. */
@Composable
fun bodyRows(extra: Int = 0): Int = (LocalContentHeight.current - 3 - extra).coerceAtLeast(3)

/**
 * Rows left for scrolling output once everything else on the screen is counted, so the output fills the whole height.
 *
 * @param fixed rows taken by what is always there (header, panel frame, fields, gaps)
 * @param notice the one-line notice, which takes a row only while there is one
 * @param actions the action bar under the panel, which takes as many rows as its buttons need at this width
 */
@Composable
fun outputRows(fixed: Int, notice: Notice? = null, actions: List<ActionItem>? = null): Int =
    (LocalContentHeight.current - fixed - (if (notice != null) 1 else 0) - (actions?.let { actionBarRows(it) } ?: 0)).coerceAtLeast(3)

/** Inner width of a [Panel]: its frame and padding take two columns on each side. */
@Composable
fun panelWidth(): Int = (LocalContentWidth.current - 4).coerceAtLeast(10)

/** Width for output lines inside a panel: the select-mode gutter (1), a gap and the scrollbar (2) are taken off. */
@Composable
fun outputWidth(): Int = (panelWidth() - 3).coerceAtLeast(10)

@Composable
fun WithVault(app: AppContext, title: String, content: @Composable (Vault) -> Unit) {
    val result = remember(app.config.vaultPath) { app.openVault() }
    val vault = result.getOrNull()
    if (vault != null) {
        content(vault)
        return
    }
    Hints("${Screen.Settings.shortcut}  settings", "?  keys")
    Stack {
        ScreenHeader(title)
        VGap()
        Panel("Vault needed", accent = Palette.Warn) {
            Warning(result.exceptionOrNull()?.message ?: "The vault could not be opened.")
            VGap()
            Cta("Press ${Screen.Settings.shortcut} to open Settings and pick your vault folder.")
        }
    }
}

/** The entries of a reply for select mode: its Markdown blocks, as written. */
@Composable
fun rememberEntries(text: String): List<String> = remember(text) { parseMarkdown(text).map { it.raw } }

/** The streamed reply of a [Generation] as light Markdown, with a spinner while the model works. */
@Composable
fun GenerationView(app: AppContext, gen: Generation, output: OutputState, rows: Int, text: String = gen.text) {
    val width = outputWidth()
    val config = app.config
    val rendered = remember(text, width, config.renderMarkdown, config.plainGlyphs, Palette.themeId) {
        renderMarkdown(parseMarkdown(text), width, rich = config.renderMarkdown, plainGlyphs = config.plainGlyphs)
    }
    // A finished reply ends with its closing line: Done, time, tokens, speed.
    val stats = gen.stats
    val lines = if (gen.state == GenState.Done && stats != null && rendered.isNotEmpty()) rendered + listOf(replyFooter(stats, config.plainGlyphs)) else rendered
    output.copyPlain = config.copyAs == "plain"
    val statusRows = if (gen.state == GenState.Done || gen.state == GenState.Idle) 0 else 1
    if (lines.isNotEmpty()) ScrollView(lines, (rows - statusRows).coerceAtLeast(1), output)
    ReplyStatus(app, gen)
}

/** The line under a reply: a spinner while generating, or why it stopped. */
@Composable
fun ReplyStatus(app: AppContext, gen: Generation, consulting: String? = null, consultingSince: Long = 0L) {
    when (gen.state) {
        GenState.Running -> Spinner {
            // while the Oracul (or the judge) works inside the turn the line is about that model, not the worker that waits for it
            // a later round of a tool loop starts silent: the model reads the whole conversation again before its first piece, so the token count cannot move yet
            val reading = consulting == null && gen.streamed > 0 && gen.roundPieces == 0
            val seconds = (System.currentTimeMillis() - if (consulting != null) consultingSince else if (reading) gen.roundStartedAt else gen.startedAt) / 1000
            val what = when {
                reading -> "${app.config.model} is reading the conversation again…"
                consulting != null -> {
                    val judging = consulting == com.arcana.brain.core.Oracul.judge(app.config) && consulting != com.arcana.brain.core.Oracul.model(app.config)
                    if (judging) "⚖ $consulting is judging the answers…" else "${com.arcana.brain.core.Oracul.GLYPH} $consulting is answering…"
                }
                gen.thinking && app.config.showThinking -> "${app.config.model} is thinking…"
                gen.text.isEmpty() -> "Waiting for the model…"
                else -> "Generating…"
            }
            val tokens = if (gen.streamed > 0 && consulting == null && !reading) " · ${if (app.config.plainGlyphs) "" else "↓ "}${gen.streamed} tokens" else ""
            "$what ${seconds}s$tokens · Esc to cancel"
        }
        GenState.Failed -> Label((if (app.config.plainGlyphs) "Error: " else "✕ ") + gen.error, color = Palette.Red, wrap = true)
        GenState.Cancelled -> Label("Cancelled.", color = Palette.Warn)
        else -> Unit
    }
}

/** "5m ago", "3d ago": when a note was last touched. */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - millis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        seconds < 86_400 * 60 -> "${seconds / 86_400}d ago"
        else -> "${seconds / (86_400 * 30)}mo ago"
    }
}

fun saveFailure(e: Throwable) = Notice(e.message ?: e.toString(), isError = true)
