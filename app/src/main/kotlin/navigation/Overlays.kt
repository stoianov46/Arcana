package com.arcana.brain.app.navigation

import androidx.compose.runtime.Composable
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.screens.AttachBrowser
import com.jakewharton.mosaic.modifier.Modifier
import java.time.LocalDate

/** The commands the palette offers: those of the current screen first, then the ones that work everywhere. */
fun paletteCommands(app: AppContext, navigator: Navigator, onQuit: () -> Unit): List<Command> = buildList {
    addAll(navigator.screenCommands)
    add(Command("Capture a thought", "Brain", "Ctrl+N") { navigator.openCapture() })
    add(Command("Process the inbox with AI", "Brain") { navigator.navigate(Screen.Inbox) })
    add(Command("Refresh the vault index", "Brain") { app.brain.changed(); navigator.showToast("Reading the vault again…") })
    LibraryFilter.entries.filter { it != LibraryFilter.Tags }.forEach { f ->
        add(Command("Library: ${f.title}", "Brain") { navigator.openLibrary(f) })
    }
    Screen.sidebar.forEach { s -> add(Command("Go to ${s.title}", "Go to", s.shortcut.toString()) { navigator.navigate(s) }) }
    Themes.all.forEach { t ->
        add(Command("Theme: ${t.name}", "Appearance", if (t.id == app.config.theme) "current" else "") { app.update(app.config.copy(theme = t.id)) })
    }
    listOf("left" to "left sidebar", "right" to "right sidebar", "top" to "top bar").forEach { (id, name) ->
        add(Command("Navigation: $name", "Appearance", if (app.config.navPosition == id) "current" else "") { app.update(app.config.copy(navPosition = id)) })
    }
    add(Command(if (app.config.sidebarCollapsed) "Unfold the sidebar" else "Fold the sidebar", "Appearance", "Ctrl+B") {
        app.update(app.config.copy(sidebarCollapsed = !app.config.sidebarCollapsed))
    })
    add(Command("Keys of this screen", "Help", "?") { navigator.helpHint = navigator.hint; navigator.helpOpen = true })
    add(Command("Quit Arcana", "Arcana", "Ctrl+C ×2") {
        // with unsaved activity, the Session screen asks first
        if (app.session.needsSaving) {
            navigator.exitFlow = true
            navigator.navigate(Screen.Session, force = true)
            navigator.quitPending = true
        } else {
            onQuit()
        }
    })
    add(Command("Session: save a summary note…", "Session") { navigator.sessionIntent = "summary"; navigator.navigate(Screen.Session) })
    add(Command("Session: save the log", "Session") { navigator.sessionIntent = "log"; navigator.navigate(Screen.Session) })
    add(Command("Session: start a new one…", "Session") { navigator.sessionIntent = "new"; navigator.navigate(Screen.Session) })
}

/**
 * Keys while an overlay is open: it takes every one of them, so nothing reaches the screen underneath.
 * Returns true when an overlay was open (the key is used up).
 */
fun overlayKey(key: Key, app: AppContext, navigator: Navigator, commands: List<Command>): Boolean {
    when {
        navigator.helpOpen -> navigator.helpOpen = false
        navigator.paletteOpen -> {
            val shown = navigator.palette.visible(commands) { it.label }
            when {
                key == Key.Escape -> navigator.paletteOpen = false
                key == Key.Enter -> {
                    val chosen = navigator.palette.selected(commands) { it.label }
                    navigator.paletteOpen = false
                    if (chosen != null) {
                        if (chosen.enabled) chosen.run() else navigator.showToast("“${chosen.label}” is not available right now.", isError = true)
                    }
                }
                else -> {
                    navigator.palette.handle(key, commands) { it.label }
                    // typing always searches: Backspace on an empty search must not leave the field
                    if (!navigator.palette.filtering) navigator.palette.startFiltering()
                    if (shown.isEmpty() && key == Key.Enter) navigator.paletteOpen = false
                }
            }
        }
        navigator.captureOpen -> {
            val files = app.captureAttachments
            when {
                files.browsing -> files.handle(key)
                key == Key.Escape -> { navigator.captureOpen = false; files.clear() }
                handleAttachmentKey(files, navigator.captureField, key) { text, error -> navigator.showToast(text, error) } -> Unit
                key == Key.Enter -> {
                    val text = navigator.captureField.text.trim()
                    if (text.isNotEmpty() || !files.isEmpty) {
                        app.capture(text, files.plain.toList())
                            .onSuccess { path ->
                                navigator.captureField.clear()
                                navigator.captureOpen = false
                                files.clear()
                                navigator.showToast("Captured to $path")
                            }
                            .onFailure { navigator.showToast(it.message ?: "Could not capture.", isError = true) }
                    }
                }
                else -> navigator.captureField.onKey(key)
            }
        }
        else -> return false
    }
    return true
}

/** The command palette: a search box over every action, with its shortcut. */
@Composable
fun PaletteOverlay(navigator: Navigator, commands: List<Command>) {
    Stack {
        ScreenHeader("Commands", "Type to search · ↑↓ choose · Enter runs it · Esc closes")
        VGap()
        Panel("Commands", accent = Palette.Cta) {
            Picker(
                items = commands,
                state = navigator.palette,
                label = { it.label },
                rows = (LocalContentHeight.current - 7).coerceAtLeast(3),
                icon = { if (it.enabled) "›" else "×" },
                iconColor = { Palette.Cta },
                meta = { if (it.keys.isNotEmpty()) "${it.group}  ·  ${Shortcuts.label(it.keys)}" else it.group },
                metaColor = { Palette.Muted },
                placeholder = "Type to search commands",
                emptyText = "No commands.",
            )
        }
    }
}

/** Quick capture: one line that becomes a timestamped bullet in today's daily note. */
@Composable
fun CaptureOverlay(app: AppContext, navigator: Navigator) {
    val target = app.openVault().getOrNull()?.let { app.dailyNotes(it).pathFor(LocalDate.now()) }
    if (app.captureAttachments.browsing) {
        AttachBrowser(app.captureAttachments, "(embedded in the thought)")
        return
    }
    Stack {
        ScreenHeader("Capture", "Get it out of your head now, sort it later")
        VGap()
        Panel("Capture a thought", accent = Palette.Cta) {
            TextInput("Thought", navigator.captureField, true, "e.g. Ask Anna about the Lisbon flights", maxRows = 3, attachments = app.captureAttachments)
            VGap()
            if (target == null) {
                Warning("No vault is set. Choose your vault folder in Settings first.")
            } else {
                Hint("Enter adds it to today's daily note, “$target”, with the time. Ctrl+O attaches a file: it is copied into the vault and embedded. Esc cancels.")
            }
        }
    }
}
