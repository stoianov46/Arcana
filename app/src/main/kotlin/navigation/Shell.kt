package com.arcana.brain.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.CellSize
import com.arcana.brain.app.Connection
import com.arcana.brain.app.ResizeSignalGuard
import com.arcana.brain.app.TerminalSize
import com.arcana.brain.app.design.Dimens
import com.arcana.brain.app.design.HGap
import com.arcana.brain.app.design.Shortcuts
import com.arcana.brain.core.UsageStats
import com.arcana.brain.app.design.Label
import com.arcana.brain.app.design.LocalContentHeight
import com.arcana.brain.app.design.LocalContentWidth
import com.arcana.brain.app.design.LocalPlainGlyphs
import com.arcana.brain.app.design.LocalUiScale
import com.arcana.brain.app.design.Palette
import com.arcana.brain.app.design.Panel
import com.arcana.brain.app.design.StatusPart
import com.arcana.brain.app.design.StatusSegment
import com.arcana.brain.app.design.statusSegment
import com.arcana.brain.app.design.Stack
import com.arcana.brain.app.design.VGap
import com.arcana.brain.app.input.Clipboard
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyDispatcher
import com.arcana.brain.app.input.LocalKeyDispatcher
import com.arcana.brain.app.input.char
import com.arcana.brain.app.input.toKey
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.fillMaxHeight
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.RowScope
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The frame around every screen: a sidebar with the screens and their shortcut keys, the screen itself, and a footer bar
 * with the connection state, the editing marker and the key hints the screen published.
 */
@Composable
fun Shell(app: AppContext, navigator: Navigator, onQuit: () -> Unit, screen: @Composable (Screen) -> Unit) {
    val mosaicSize = LocalTerminalState.current.size
    // Replace Mosaic's crashing native resize handler once it has been installed (see ResizeSignalGuard).
    LaunchedEffect(Unit) {
        delay(300)
        ResizeSignalGuard.install()
    }
    // Trust the OS over the terminal emulator: poll the real size (also catches window resizes).
    val probed by produceState<CellSize?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { TerminalSize.probe() }
            delay(500)
        }
    }
    val size = probed ?: CellSize(mosaicSize.columns, mosaicSize.rows)
    // Mosaic draws inline, and a frame as tall as the terminal makes it scroll by one line: keep the last row free.
    val reserved = remember { (System.getenv("ARCANA_RESERVED_ROWS")?.toIntOrNull() ?: Dimens.ReservedRows).coerceAtLeast(0) }
    val viewRows = size.rows - reserved

    LaunchedEffect(navigator.quitPending) {
        if (navigator.quitPending) {
            delay(2_500)
            navigator.quitPending = false
        }
    }

    // Quitting for real also closes the session file.
    val quit = { app.session.finish(); onQuit() }
    // The overlays (key map, command palette, capture) take every key while one is open.
    val latestCommands by rememberUpdatedState(paletteCommands(app, navigator, quit))
    val dispatcher = remember {
        KeyDispatcher(
            globalHandler = { handleGlobalKey(it, navigator) },
            overlay = { overlayKey(it, app, navigator, latestCommands) },
            clipboard = { Clipboard.readText() },
        )
    }
    LaunchedEffect(navigator.toast) {
        if (navigator.toast != null) {
            delay(3_500)
            navigator.toast = null
        }
    }
    val onKey: (KeyEvent) -> Boolean = { e ->
        when {
            e.ctrl && e.key.equals("c", ignoreCase = true) -> {
                // The first press asks; the second quits (main() then cancels Mosaic and exits).
                if (navigator.quitPending) quit() else navigator.quitPending = true
                true
            }
            e.ctrl && e.key.equals("b", ignoreCase = true) -> {
                navigator.quitPending = false
                if (app.config.navPosition == "top") navigator.showToast("The top bar has nothing to fold. Switch the navigation in Settings → Appearance.") else app.update(app.config.copy(sidebarCollapsed = !app.config.sidebarCollapsed))
                true
            }
            else -> {
                navigator.quitPending = false // any other key takes the question back
                e.toKey()?.let(dispatcher::dispatch) ?: false
            }
        }
    }

    if (size.columns < Dimens.MinColumns || viewRows < Dimens.MinRows) {
        Column(Modifier.onKeyEvent(onKey)) {
            Text("Terminal too small: ${size.columns}x${size.rows}", color = Palette.Orange, textStyle = TextStyle.Bold)
            Text("Need at least ${Dimens.MinColumns}x${Dimens.MinRows + reserved}. Enlarge the window, Ctrl+C to quit.")
        }
        return
    }

    // On a narrow terminal the sidebar folds to icons by itself, so the content keeps its room.
    val collapsed = app.config.sidebarCollapsed || size.columns < 90
    val nav = app.config.navPosition
    val topBar = nav == "top"
    val sidebarWidth = if (topBar) 0 else if (collapsed) 7 else (size.columns / 5).coerceIn(18, Dimens.SidebarWidth)
    val health = remember(app.brain.index, app.config.inboxFolder, app.config.staleDays) { app.brain.index?.health(app.config.inboxFolder, app.config.staleDays) }
    // what the Chat tab says while it waits for you: pulsing, so it is seen from any other screen
    val waitingFor = if (app.chat.approval != null) "!" else if (app.chat.question != null) "?" else null
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(waitingFor) {
        pulse = false
        while (waitingFor != null) { kotlinx.coroutines.delay(500); pulse = !pulse }
    }
    // sounds for what happens in Chat (all off until chosen in Settings → Sounds): a dialog waits for you, a reply finishes or fails
    LaunchedEffect(Unit) {
        var waiting = false
        var last = app.chat.gen.state
        androidx.compose.runtime.snapshotFlow { Triple(app.chat.approval != null || app.chat.question != null, app.chat.gen.state, navigator.current == Screen.Chat) }.collect { (attention, state, onChat) ->
            if (attention && !waiting) com.arcana.brain.core.Sounds.notify(app.config, com.arcana.brain.core.ChatSoundEvent.Attention, onChat)
            waiting = attention
            if (state != last) {
                if (last == com.arcana.brain.app.GenState.Running && state == com.arcana.brain.app.GenState.Done) com.arcana.brain.core.Sounds.notify(app.config, com.arcana.brain.core.ChatSoundEvent.Done, onChat)
                if (last == com.arcana.brain.app.GenState.Running && state == com.arcana.brain.app.GenState.Failed) com.arcana.brain.core.Sounds.notify(app.config, com.arcana.brain.core.ChatSoundEvent.Failed, onChat)
                last = state
            }
        }
    }
    val badge: (Screen) -> Pair<String, Color>? = { screen ->
        when (screen) {
            Screen.Inbox -> health?.inbox?.takeIf { it > 0 }?.let { it.toString() to Palette.Orange }
            Screen.Session -> if (app.session.needsSaving) "${app.session.unsavedCount}" to Palette.Warn else null
            // a dialog the model is waiting on (an approval, a question) must be seen from every screen: the reply cannot go on without an answer
            Screen.Chat -> when {
                waitingFor != null -> waitingFor to (if (pulse) Palette.Orange else Palette.Warn)
                app.chat.gen.state == com.arcana.brain.app.GenState.Running -> "…" to Palette.Accent
                else -> null
            }
            else -> null
        }
    }
    // With an action bar on screen the footer keeps only what the bar does not show: the key map and quitting.
    val hint = when {
        navigator.quitPending -> quitPrompt(size.columns - app.badgeWidth(navigator, size.columns) - 2)
        navigator.overlayOpen -> overlayHint(navigator)
        navigator.actionBars > 0 -> Shortcuts.label("Ctrl+P  commands   Ctrl+N  capture   ?  keys")
        // the two global keys come first, so they are always seen
        else -> Shortcuts.label("Ctrl+P  commands   Ctrl+N  capture   ") + navigator.hint
    }
    val vPad = if (viewRows < 24) 0 else 1

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalKeyDispatcher provides dispatcher,
        LocalUiScale provides app.config.uiScale,
        LocalPlainGlyphs provides app.config.plainGlyphs,
    ) {
        Column(Modifier.size(size.columns, viewRows).background(Palette.Background).onKeyEvent(onKey)) {
            if (topBar) TopBar(navigator, size.columns, busy = app.busy > 0 && app.config.animateLogo, badge = badge)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (nav == "left") Sidebar(navigator, sidebarWidth, collapsed, viewRows, busy = app.busy > 0 && app.config.animateLogo, badge = badge)
                // Breathing room above the content, but none below it: the last row belongs to the content, right above the footer.
                Box(Modifier.weight(1f).fillMaxHeight().padding(left = Dimens.ContentPadding, top = vPad, right = Dimens.ContentPadding, bottom = 0)) {
                    CompositionLocalProvider(
                        LocalContentHeight provides viewRows - footerRows(app, navigator, size.columns).size - vPad - (if (topBar) 1 else 0),
                        LocalContentWidth provides size.columns - sidebarWidth - 2 * Dimens.ContentPadding,
                    ) {
                        // The screen stays on while an overlay is open (so it keeps its state); the overlay hides it.
                        screen(navigator.current)
                        if (navigator.overlayOpen) {
                            val rows = LocalContentHeight.current
                            Box(Modifier.fillMaxWidth().fillMaxHeight().drawBehind { for (row in 0 until rows) drawText(row, 0, " ".repeat(width), Palette.Background, Palette.Background) }) {
                                when {
                                    navigator.helpOpen -> KeyMap(navigator.current.title, navigator.helpHint, if (navigator.current == Screen.Chat) com.arcana.brain.app.screens.chatKeySections() else null)
                                    navigator.paletteOpen -> PaletteOverlay(navigator, latestCommands)
                                    navigator.captureOpen -> CaptureOverlay(app, navigator)
                                }
                            }
                        }
                    }
                }
                if (nav == "right") Sidebar(navigator, sidebarWidth, collapsed, viewRows, busy = app.busy > 0 && app.config.animateLogo, badge = badge)
            }
            Footer(app, navigator, hint, size.columns)
        }
    }
}

/**
 * Shortcuts that work on every screen once the screen itself has declined a key: the key map overlay swallows the next
 * key, `?` opens it, 1-6 switch screens and Esc goes back Home. A text field consumes every printable key first, so
 * typing a digit never switches screens.
 */
private fun handleGlobalKey(key: Key, navigator: Navigator): Boolean {
    val ch = key.char
    if (ch == '?') {
        navigator.helpHint = navigator.hint
        navigator.helpOpen = true
        return true
    }
    if (key == Key.Palette) {
        navigator.openPalette()
        return true
    }
    if (key == Key.Capture) {
        navigator.openCapture()
        return true
    }
    if (key == Key.Escape) return goHome(navigator)
    val index = ch?.let { SHORTCUT_KEYS.indexOf(it) } ?: -1
    return index >= 0 && Screen.sidebar.getOrNull(index)?.also(navigator::navigate) != null
}

private fun goHome(navigator: Navigator): Boolean {
    if (navigator.current == Screen.Home) return false
    navigator.navigate(Screen.Home)
    return true
}

/**
 * Navigation as one row across the top: the logo, then every screen as `number title`, the current one lit, with its badge.
 * When the titles do not fit, the other tabs shrink to `number icon` (the current one keeps its title).
 */
@Composable
private fun TopBar(navigator: Navigator, columns: Int, busy: Boolean, badge: (Screen) -> Pair<String, Color>?) {
    val items = Screen.sidebar
    fun badgeText(s: Screen) = badge(s)?.first?.let { " $it" } ?: ""
    fun full(i: Int, s: Screen) = " ${SHORTCUT_KEYS.getOrElse(i) { ' ' }} ${s.title}${badgeText(s)} "
    val logoWidth = 10
    val fits = logoWidth + items.withIndex().sumOf { (i, s) -> full(i, s).length + 1 } <= columns
    Row(Modifier.fillMaxWidth().background(Palette.Surface)) {
        Label(" ", background = Palette.Surface)
        Logo("⟐ arcana", busy)
        Label("  ", background = Palette.Surface)
        items.forEachIndexed { i, s ->
            val on = s == navigator.current
            val b = badge(s)
            val text = if (fits || on) full(i, s) else " ${SHORTCUT_KEYS.getOrElse(i) { ' ' }} ${s.icon}${if (b != null) "•" else ""} "
            Label(
                text,
                color = if (on) Palette.Background else if (b != null && !fits) b.second else Palette.Muted,
                background = if (on) Palette.Accent else Palette.Surface,
                textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified,
            )
            Label(" ", background = Palette.Surface)
        }
        Label("", Modifier.weight(1f), background = Palette.Surface, fill = true)
    }
}

@Composable
private fun Sidebar(navigator: Navigator, width: Int, collapsed: Boolean, rows: Int, busy: Boolean, badge: (Screen) -> Pair<String, Color>?) {
    val items = Screen.sidebar
    val roomy = rows >= 24
    Column(Modifier.width(width).fillMaxHeight().background(Palette.Surface).padding(horizontal = 1, vertical = if (roomy) 1 else 0)) {
        Stack(Modifier.weight(1f)) {
            Logo(if (collapsed) " ⟐" else " ⟐ arcana", busy)
            if (roomy) VGap()
            items.forEachIndexed { i, s ->
                if (!collapsed && roomy && s.group != items.getOrNull(i - 1)?.group) {
                    if (i > 0) VGap()
                    Label(s.group.heading, color = Palette.Muted, textStyle = TextStyle.Bold)
                }
                val on = s == navigator.current
                val bg = if (on) Palette.Selected else Color.Unspecified
                val b = badge(s)
                // a screen can show what is waiting in it: a count, or a dot (also on the icon-only rail)
                Row(Modifier.fillMaxWidth().background(bg)) {
                    Label(
                        if (collapsed) "${SHORTCUT_KEYS.getOrElse(i) { ' ' }} ${s.icon}" else " ${SHORTCUT_KEYS.getOrElse(i) { ' ' }}  ${s.title}",
                        modifier = Modifier.weight(1f),
                        color = if (on) Palette.Text else Palette.Muted,
                        textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified,
                        fill = true,
                    )
                    if (b != null) {
                        if (collapsed) Label("●", color = b.second) else Label(" ${b.first} ", color = Palette.Background, background = b.second, textStyle = TextStyle.Bold)
                    }
                }
            }
            if (roomy) VGap()
            Row(Modifier.fillMaxWidth()) {
                Label(if (collapsed) "^B" else " ${Shortcuts.label("Ctrl+B")} ", color = Palette.Background, background = Palette.Cta, textStyle = TextStyle.Bold)
                if (!collapsed) Label(" ‹ Fold", Modifier.weight(1f), color = Palette.Text, background = Palette.SurfaceHigh, fill = true)
            }
        }
    }
}

/**
 * The app name in the brand colour. While [busy] (a reply is being generated) a rainbow flows along it: the colour wheel
 * is laid over the letters twice and moves, so every colour appears two times in the word.
 */
@Composable
private fun Logo(text: String, busy: Boolean) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(busy) {
        while (busy) {
            delay(150)
            tick = (tick + 1) % 40
        }
    }
    if (!busy) {
        Label(text, color = Palette.Accent, textStyle = TextStyle.Bold)
        return
    }
    Row {
        text.forEachIndexed { i, ch ->
            val phase = 2 * i.toDouble() / text.length.coerceAtLeast(2) - tick.toDouble() / 40
            Label(ch.toString(), color = Palette.logoShade(phase), textStyle = TextStyle.Bold)
        }
    }
}

/**
 * What the bottom bar says, as plain coloured text (no blocks of colour): the model and its connection, the Oracul, the tokens used since Arcana started, then everything about the
 * machine: whether the model is loaded and what all models take, the processor, a wait or a compaction, and the warnings.
 */
private fun footerSegments(app: AppContext, navigator: Navigator): List<StatusSegment> {
    val plain = app.config.plainGlyphs
    val model = when (val c = app.connection) {
        Connection.Checking -> statusSegment("", "○ checking", Palette.Muted)
        is Connection.Online -> statusSegment("", "● ${app.config.model}", Palette.Green, bold = true)
        is Connection.ModelMissing -> statusSegment("", "● model missing", Palette.Warn, bold = true)
        is Connection.Offline -> statusSegment("", "○ Ollama offline", Palette.Red, bold = true)
    }
    val oracul = oraculBadge(app, navigator.current == Screen.Chat)?.let { statusSegment("", it.first.trim(), it.second, bold = true) }
    val asking = app.chat.consulting != null && oracul != null
    return buildList {
        // the model that works now comes first: the worker, or the Oracul (orange) while it is being asked
        if (asking) { add(oracul!!); add(StatusSegment(listOf(StatusPart(model.parts.joinToString("") { it.text }, Palette.Muted)))) }
        else { add(model); oracul?.let { add(it) } }
        // what the chat model can take in, as Ollama reports it: pictures, sound, and a mixture of experts (only the experts it needs run per token)
        if (app.config.showModelTraits && app.connection is Connection.Online) modelTraits(app, plain)?.let { add(it) }
        // tokens used since Arcana started: grows after every reply; the Usage screen has the full picture
        if (app.sessionTokens > 0) add(statusSegment("", "⚡ ${UsageStats.compact(app.sessionTokens)} tok", Palette.Blue))
        // the machine: where the model sits and what all loaded models take, the processor, the memory file
        com.arcana.brain.app.screens.modelStateSegment(app, plain)?.let { add(it) }
        app.cpuPercent?.let { add(statusSegment("cpu", "$it%", if (it >= 85) Palette.Red else if (it >= 60) Palette.Orange else Palette.Green)) }
        if (app.chat.compacting) add(statusSegment("", if (plain) "compacting" else "◌ compacting…", Palette.Blue))
        com.arcana.brain.app.screens.hardwareWarnings(app, plain).forEach { (text, color) -> add(statusSegment("", text, color, bold = true)) }
        if (navigator.focusMode) add(statusSegment("", "✎ EDITING  Esc", Palette.Cta, bold = true))
    }
}

/** `▣ image  ♪ audio  MoE 128`: what the chat model supports, from `/api/show`; null when it supports none of these or Ollama did not say. */
private fun modelTraits(app: AppContext, plain: Boolean): StatusSegment? {
    val traits = buildList {
        if (app.vision == true) add(if (plain) "image" else "▣ image")
        if (app.audio == true) add(if (plain) "audio" else "♪ audio")
        if (app.experts > 0) add("MoE ${app.experts}")
    }
    return if (traits.isEmpty()) null else statusSegment("", traits.joinToString("  "), Palette.Blue, 4)
}

/** The footer's facts laid out in rows that fit [columns] (it wraps like the status bars, nothing is hidden); the hints share the last row. */
private fun footerRows(app: AppContext, navigator: Navigator, columns: Int): List<List<StatusSegment>> =
    com.arcana.brain.app.design.wrapSegments(footerSegments(app, navigator), columns - 2, 3)

private fun rowWidth(row: List<StatusSegment>): Int = row.sumOf { it.width } + 3 * (row.size - 1).coerceAtLeast(0) + 1

@Composable
private fun Footer(app: AppContext, navigator: Navigator, hint: String, columns: Int) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            app.cpuPercent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.arcana.brain.core.SystemCpu.percent() }
            kotlinx.coroutines.delay(2_000)
        }
    }
    val rows = footerRows(app, navigator, columns)
    val separator = if (app.config.plainGlyphs) " | " else " │ "
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().background(Palette.SurfaceHigh)) {
                Label(" ", background = Palette.SurfaceHigh)
                row.forEachIndexed { i, segment ->
                    if (i > 0) Label(separator, color = Palette.Dim, background = Palette.SurfaceHigh)
                    segment.parts.forEach { part -> Label(part.text, color = part.color, background = Palette.SurfaceHigh, textStyle = if (part.bold) TextStyle.Bold else TextStyle.Unspecified) }
                }
                if (index < rows.lastIndex) {
                    Label("", Modifier.weight(1f), background = Palette.SurfaceHigh, fill = true)
                } else {
                    val room = columns - rowWidth(row) - 1
                    val toast = navigator.toast
                    if (toast != null && !navigator.quitPending) {
                        // a message from an overlay replaces the hints for a few seconds
                        Label("", Modifier.weight(1f), background = Palette.SurfaceHigh, fill = true)
                        Label(" ${if (toast.isError) "✕" else "✓"} ${toast.text} ".take(room.coerceAtLeast(10)), color = if (toast.isError) Palette.Red else Palette.Green, background = Palette.SurfaceHigh, textStyle = TextStyle.Bold)
                        Label(" ", background = Palette.SurfaceHigh)
                    } else {
                        FooterHints(if (navigator.quitPending) hint else fitHints(hint, room), badges = !navigator.quitPending)
                    }
                }
            }
        }
    }
}

/** The second-press question, shortened when the footer is narrow so it never runs off the edge. */
private fun quitPrompt(room: Int): String {
    val full = "Press Ctrl+C again to quit (any other key cancels)"
    val short = "Press Ctrl+C again to quit"
    return if (full.length <= room) full else short.take(room.coerceAtLeast(0))
}

/**
 * The Oracul's badge for the bottom bar: its model, the judge (`⚖`, when it is another model) and when it speaks (`auto` after problems, `always`, or `manual` = only on /oracul), orange while
 * it is being asked; collapsed to the glyphs (`Ψ ⚖`) in Chat. Null when no Oracul model is set, so a user who does not use it sees nothing.
 */
private fun oraculBadge(app: AppContext, collapsed: Boolean = false): Pair<String, Color>? {
    val oracul = com.arcana.brain.core.Oracul
    val model = oracul.model(app.config).ifEmpty { return null }
    // in Chat the status bar under the field already names the models: only the glyphs stay, to show that the Oracul (and a judge) is on
    if (collapsed) {
        val judge = oracul.judge(app.config).takeIf { it != model }
        val asking = app.chat.consulting
        if (asking != null) return " ${if (asking == judge) "⚖" else oracul.GLYPH}… " to Palette.Orange
        return " ${oracul.GLYPH}${if (judge != null) " ⚖" else ""} " to Palette.Accent
    }
    // while the judge is the one working it says so with its own glyph
    app.chat.consulting?.let { return " ${if (it == oracul.judge(app.config) && it != model) "⚖" else oracul.GLYPH} $it… " to Palette.Orange }
    return " ${oracul.statusText(app.config)} " to Palette.Accent
}

private fun AppContext.badgeWidth(navigator: Navigator, columns: Int): Int = rowWidth(footerRows(this, navigator, columns).last())

/** Only the hints that fit, whole; the rest are one "?" away. */
/** What the footer says while an overlay is open. */
private fun overlayHint(navigator: Navigator): String = when {
    navigator.paletteOpen -> "↑↓  choose   Enter  run   Esc  close"
    navigator.captureOpen -> Shortcuts.label("Enter  save the thought   Ctrl+O  attach a file   Esc  cancel")
    else -> "any key  close"
}

private fun fitHints(hint: String, width: Int): String {
    val parts = hint.split("   ").filter { it.isNotBlank() }
    val kept = mutableListOf<String>()
    var used = 0
    for (part in parts) {
        val key = part.substringBefore("  ")
        val w = key.length + 3 + part.substringAfter("  ", "").trim().length + if (kept.isEmpty()) 0 else 2
        if (used + w > width) break
        kept += part
        used += w
    }
    return kept.joinToString("   ")
}

/** One footer line: keys are coloured badges, the explanation is plain text; the hints sit at the right edge. */
@Composable
private fun RowScope.FooterHints(line: String, badges: Boolean) {
    Label("", Modifier.weight(1f), background = Palette.SurfaceHigh, fill = true)
    if (!badges) {
        Label(line, color = Palette.Warn, background = Palette.SurfaceHigh, textStyle = TextStyle.Bold)
        return
    }
    line.split("   ").filter { it.isNotBlank() }.forEachIndexed { i, part ->
        if (i > 0) Label("  ", background = Palette.SurfaceHigh)
        val key = part.substringBefore("  ", "")
        if (key.isEmpty()) {
            Label(part, color = Palette.Muted, background = Palette.SurfaceHigh)
        } else {
            Label(key, color = Palette.Accent, background = Palette.SurfaceHigh, textStyle = TextStyle.Bold)
            Label(" ${part.substringAfter("  ").trim()}", color = Palette.Muted, background = Palette.SurfaceHigh)
        }
    }
    Label(" ", background = Palette.SurfaceHigh)
}

/** A titled group of keys in a screen's key map: each row is the key and what it does. */
class KeySection(val title: String, val rows: List<Pair<String, String>>)

/**
 * The key map of the screen on display plus the keys that work everywhere, laid over the content. Any key closes it. A screen that
 * passes [sections] gets them in two columns (the last ones are left out when the window is too low for them); otherwise the map is
 * the keys the screen shows at the bottom right now.
 */
@Composable
private fun KeyMap(title: String, screenHint: String, sections: List<KeySection>? = null) {
    val rows = LocalContentHeight.current
    val width = LocalContentWidth.current
    val own = screenHint.split("   ").filter { it.isNotBlank() }.map { it.substringBefore("  ", "") to it.substringAfter("  ", it).trim() }
    val everywhere = listOf(
        "1-${Screen.sidebar.size}" to "switch screen",
        Shortcuts.label("Ctrl+P") to "command palette",
        Shortcuts.label("Ctrl+N") to "capture a note",
        Shortcuts.label("Ctrl+B") to "fold the sidebar",
        "Ctrl+C ×2" to "quit (the second press confirms)",
        "?" to "this key map",
        "Esc" to "leave editing, then back to Home",
    )
    Box(Modifier.fillMaxWidth().fillMaxHeight().drawBehind { for (row in 0 until rows) drawText(row, 0, " ".repeat(width), Palette.Background, Palette.Background) }) {
        if (sections != null && width >= 112) {
            val all = sections + KeySection("Everywhere", everywhere)
            // two columns of about the same height; what does not fit the window is dropped from the end
            fun height(group: List<KeySection>) = group.sumOf { it.rows.size + 1 } + (group.size - 1).coerceAtLeast(0)
            val total = height(all)
            val left = ArrayList<KeySection>()
            val right = ArrayList<KeySection>()
            all.forEach { if (height(left) < total / 2 && right.isEmpty()) left += it else right += it }
            val room = rows - 6
            while (height(left) > room && left.size > 1) left.removeLast()
            while (height(right) > room && right.size > 1) right.removeLast()
            Panel("Keys · $title", Modifier.fillMaxWidth(), accent = Palette.Cta, background = Palette.Background) {
                Label("Any key closes this map", color = Palette.Muted)
                VGap()
                Row(Modifier.fillMaxWidth()) {
                    for ((i, group) in listOf(left, right).withIndex()) {
                        Stack(Modifier.weight(1f)) {
                            val keyWidth = group.maxOfOrNull { g -> g.rows.maxOf { it.first.length } } ?: 0
                            group.forEachIndexed { n, g ->
                                if (n > 0) VGap()
                                Label(g.title, color = Palette.Muted, textStyle = TextStyle.Bold)
                                g.rows.forEach { (key, text) -> KeyRow(key, text, keyWidth, if (g.title == "Everywhere") Palette.Cta else Palette.Accent) }
                            }
                        }
                        if (i == 0) HGap()
                    }
                }
            }
            return@Box
        }
        val keyWidth = own.maxOfOrNull { it.first.length } ?: 0
        val globalWidth = everywhere.maxOf { it.first.length }
        Panel("Keys · $title", Modifier.fillMaxWidth(), accent = Palette.Cta, background = Palette.Background) {
            Label("Any key closes this map", color = Palette.Muted)
            VGap()
            own.forEach { (key, text) -> KeyRow(key, text, keyWidth) }
            VGap()
            Label("Everywhere", color = Palette.Muted, textStyle = TextStyle.Bold)
            everywhere.forEach { (key, text) -> KeyRow(key, text, globalWidth, Palette.Cta) }
        }
    }
}

@Composable
private fun KeyRow(key: String, text: String, keyWidth: Int, accent: Color = Palette.Accent) {
    if (key.isEmpty()) {
        Label(text, color = Palette.Muted)
    } else {
        Row {
            Label(" ${key.padEnd(keyWidth)} ", color = Palette.Background, background = accent, textStyle = TextStyle.Bold)
            Label(" $text", color = Palette.Text)
        }
    }
}
