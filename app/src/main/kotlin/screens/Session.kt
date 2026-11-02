package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.Connection
import com.arcana.brain.app.GenState
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.Command
import com.arcana.brain.app.navigation.Commands
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.app.rememberGeneration
import com.arcana.brain.core.Prompts
import com.arcana.brain.core.SessionEvent
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.SessionReport
import com.arcana.brain.core.UsageStats
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle

private enum class SessionMode { Overview, Summary, ConfirmNew }

/**
 * The working session: everything done since Arcana opened (or since "new session"), as a timeline. It can be written
 * into the vault as a note (a summary the model writes from the activity and the chat, or just the plain log), saved at
 * any time, and a new session can start without restarting. It is also what Arcana shows before quitting, so what you
 * did and learned is not lost.
 */
@Composable
fun SessionScreen(app: AppContext) {
    val navigator = LocalNavigator.current
    val session = app.session
    val gen = rememberGeneration(app, "Session")
    val output = remember { OutputState() }
    var mode by remember { mutableStateOf(SessionMode.Overview) }

    fun toast(text: String, error: Boolean = false) = navigator.showToast(text, error)

    fun summaryMessages() = Prompts.sessionSummary(
        app.config,
        SessionReport.activityMarkdown(session.activity),
        if (app.chat.messages.isEmpty()) emptyList() else listOf(app.chat.title to app.chat.messages.joinToString("\n") { "${if (it.role == "user") "You" else "Gemma"}: ${it.content}" }),
    )

    fun startSummary() {
        if (app.connection !is Connection.Online) {
            toast("Ollama is not ready, so a summary cannot be written. Save the plain log instead (l).", error = true)
            return
        }
        mode = SessionMode.Summary
        output.reset()
        val messages = summaryMessages()
        gen.start { messages }
    }

    /** Writes the session to the vault as a note; [summary] may be empty for a plain log. */
    fun save(summary: String) {
        val result = runCatching {
            val vault = app.openVault().getOrThrow()
            // the note records the real length of the session, up to now
            val snapshot = session.snapshot(ended = System.currentTimeMillis())
            val folder = listOf(app.config.outputFolder.trim('/'), "Sessions").filter { it.isNotEmpty() }.joinToString("/")
            vault.create(folder, SessionReport.title(snapshot), SessionReport.note(snapshot, summary), app.config.model).path
        }
        result.onSuccess { path ->
            session.markSaved(path)
            app.brain.changed()
            toast("Session saved as $path")
            mode = SessionMode.Overview
        }.onFailure { toast(it.message ?: "Could not save the session.", error = true) }
    }

    fun startNew() {
        gen.reset()
        app.startNewSession()
        navigator.navigate(Screen.Home, force = true)
        toast("New session started")
    }

    // The palette can open this screen straight into an action.
    LaunchedEffect(Unit) {
        when (navigator.sessionIntent) {
            "summary" -> startSummary()
            "log" -> save("")
            "new" -> if (session.needsSaving) mode = SessionMode.ConfirmNew else startNew()
        }
        navigator.sessionIntent = null
    }

    Commands(
        listOf(
            Command("Save the session as a summary note…", "Session", "s") { startSummary() },
            Command("Save the session log (no AI)", "Session", "l") { save("") },
            Command("Start a new session…", "Session", "n") { if (session.needsSaving) mode = SessionMode.ConfirmNew else startNew() },
        ),
    )

    FocusMode(false)
    when (mode) {
        SessionMode.Overview -> Hints("s  save summary", "l  save log", "n  new session", "↑↓  scroll")
        SessionMode.Summary -> if (gen.state == GenState.Running) Hints("Esc  cancel") else Hints("n  save note", "r  redo", "↑↓/Space  scroll", "Esc  back")
        SessionMode.ConfirmNew -> Hints("y  start anyway", "s  summary first", "l  save log, then start", "Esc  cancel")
    }

    val summaryEntries = rememberEntries(gen.text)

    KeyHandler { key ->
        when (mode) {
            SessionMode.Overview -> when {
                output.handle(key, onNotice = { toast(it.text) }) -> true
                key.char == 's' -> { startSummary(); true }
                key.char == 'l' -> { save(""); true }
                key.char == 'n' -> { if (session.needsSaving) mode = SessionMode.ConfirmNew else startNew(); true }
                else -> false
            }
            SessionMode.Summary -> when {
                output.handle(key, summaryEntries, onNotice = { toast(it.text) }) -> true
                key == Key.Escape -> { gen.reset(); mode = SessionMode.Overview; true }
                key.char == 'n' && gen.state == GenState.Done -> { save(gen.text); true }
                key.char == 'r' && gen.state != GenState.Running -> { startSummary(); true }
                else -> false
            }
            SessionMode.ConfirmNew -> {
                when {
                    key.char == 'y' || key.char == 'Y' -> startNew()
                    key.char == 's' -> startSummary()
                    key.char == 'l' -> { save(""); if (mode == SessionMode.Overview) startNew() }
                    else -> mode = SessionMode.Overview
                }
                true
            }
        }
    }

    Stack {
        val duration = SessionReport.duration(System.currentTimeMillis() - session.started)
        val stats = SessionReport.stats(session.activity)
        when (mode) {
            SessionMode.Overview -> {
                val actions = listOf(
                    ActionItem("s", "Save summary note", app.connection is Connection.Online),
                    ActionItem("l", "Save log only"),
                    ActionItem("n", "New session"),
                    ActionItem("Esc", "Back", accent = Palette.Cta),
                )
                Panel("Activity") {
                    val width = outputWidth()
                    val lines = remember(session.events.size, width, Palette.themeId) { timeline(session.activity, width) }
                    if (lines.isEmpty()) {
                        Hint("Nothing yet. Ask a question, write a note, capture a thought: it all appears here, and can be saved to your vault as a note.")
                    } else {
                        ScrollView(lines, outputRows(fixed = 2 + 2, actions = actions), output)
                    }
                    Label(
                        "${stats.describe()} · ⚡ ${UsageStats.compact(app.sessionTokens)} tokens" + (session.savedAs?.let { "  ·  ✓ saved as $it" } ?: ""),
                        color = Palette.Muted,
                    )
                }
                ActionBar(actions)
            }
            SessionMode.Summary -> {
                val actions = listOf(
                    ActionItem("n", "Save as note", gen.state == GenState.Done),
                    ActionItem("r", "Regenerate", gen.state != GenState.Running),
                    ActionItem("Esc", if (gen.state == GenState.Running) "Cancel" else "Back", accent = Palette.Cta),
                )
                ScreenHeader("Session › Summary", "Written by ${app.config.model} from the activity and your chat. Saved under “${app.config.outputFolder.trim('/').let { if (it.isEmpty()) "" else "$it/" }}Sessions”")
                VGap()
                Panel("Summary") { GenerationView(app, gen, output, outputRows(fixed = 5, actions = actions)) }
                ActionBar(actions)
            }
            SessionMode.ConfirmNew -> {
                ScreenHeader("Session", "Start a new session")
                VGap()
                Panel("Start a new session?", accent = Palette.Warn) {
                    Warning("This session has ${session.unsavedCount} event${if (session.unsavedCount == 1) "" else "s"} that are not saved to your vault. A new session clears the activity log and the open chat (the chat stays in the history).")
                }
                ActionBar(
                    listOf(
                        ActionItem("y", "Start anyway", accent = Palette.Orange),
                        ActionItem("s", "Summary first"),
                        ActionItem("l", "Save log, then start"),
                        ActionItem("Esc", "Cancel", accent = Palette.Cta),
                    ),
                    style = ActionStyle.Button,
                )
            }
        }
    }
}

private fun kindColor(kind: String): Color = when (kind) {
    SessionKind.Asked -> Palette.Purple
    SessionKind.Task -> Palette.Accent
    SessionKind.Created, SessionKind.Saved, SessionKind.Captured, SessionKind.Processed -> Palette.Green
    SessionKind.Appended, SessionKind.Replaced -> Palette.Orange
    SessionKind.Deleted -> Palette.Red
    SessionKind.Attached -> Palette.Blue
    else -> Palette.Muted
}

private fun kindLabel(kind: String) = when (kind) {
    SessionKind.Asked -> "asked   "
    SessionKind.Task -> "AI task "
    SessionKind.Created -> "created "
    SessionKind.Appended -> "added to"
    SessionKind.Replaced -> "replaced"
    SessionKind.Deleted -> "trashed "
    SessionKind.Saved -> "saved   "
    SessionKind.Captured -> "captured"
    SessionKind.Processed -> "filed   "
    SessionKind.Attached -> "attached"
    else -> "session "
}

/** The events as a timeline: time, what kind of thing, and what it was, wrapped under a hanging indent. */
private fun timeline(events: List<SessionEvent>, width: Int): List<Line> = events.flatMap { e ->
    val head = "${SessionReport.clock(e.time)}  ${kindLabel(e.kind)}  "
    val text = wrap(sanitizeForTerminal(e.text), (width - head.length).coerceAtLeast(10))
    text.mapIndexed { i, part ->
        val runs = listOf(
            Run(if (i == 0) SessionReport.clock(e.time) + "  " else " ".repeat(7), Palette.Dim),
            Run(if (i == 0) kindLabel(e.kind) + "  " else " ".repeat(10), kindColor(e.kind), style = TextStyle.Bold),
            Run(part, Palette.Text),
        )
        Line(runs.joinToString("") { it.text }, runs = runs)
    }
}
