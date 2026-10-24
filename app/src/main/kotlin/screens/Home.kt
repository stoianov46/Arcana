package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.Connection
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.Command
import com.arcana.brain.app.navigation.Commands
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LibraryFilter
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.app.navigation.Navigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.BrainHealth
import com.arcana.brain.core.SessionReport
import com.arcana.brain.core.UsageStats
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.nio.file.Files
import java.time.LocalDate

/** One row of the dashboard: a section heading, or something you can open. */
private sealed interface BrainRow {
    data class Heading(val text: String) : BrainRow
    class Item(val icon: String, val color: Color, val title: String, val detail: String, val dim: Boolean = false, val open: () -> Unit) : BrainRow
}

/** The dashboard in two parts: what to do now (capture, what needs attention) and where to jump back in. */
private class BrainRows(val now: List<BrainRow>, val back: List<BrainRow>) {
    val nowItems get() = now.filterIsInstance<BrainRow.Item>()
    val backItems get() = back.filterIsInstance<BrainRow.Item>()
}

@Composable
fun HomeScreen(app: AppContext) {
    val navigator = LocalNavigator.current
    var selected by remember { mutableIntStateOf(0) }
    val index = app.brain.index
    val vault = remember(app.config.vaultPath) { app.openVault() }
    val health = remember(index, app.config.inboxFolder, app.config.staleDays) { index?.health(app.config.inboxFolder, app.config.staleDays) }
    val wide = LocalContentWidth.current >= 100

    val rows = buildRows(app, navigator, health, vault.isSuccess, recents = if (wide) 12 else 5, topTags = if (wide) 8 else 0)
    val items = rows.nowItems + rows.backItems
    val current = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    Hints("↑↓  choose", "←→  column", "Enter  open", "r  re-read the vault")
    Commands(listOf(Command("Re-read the vault", "Brain", "r") { app.brain.changed() }))

    KeyHandler { key ->
        when {
            key == Key.Up || key.char == 'k' -> { selected = (current - 1).coerceAtLeast(0); true }
            key == Key.Down || key.char == 'j' -> { selected = (current + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)); true }
            // the two columns are one list: ← goes to the first item of the left, → to the first of the right
            key == Key.Left && wide -> { selected = 0; true }
            key == Key.Right && wide && rows.backItems.isNotEmpty() -> { selected = rows.nowItems.size; true }
            key == Key.Enter -> { items.getOrNull(current)?.open?.invoke(); true }
            key.char == 'r' -> { app.brain.changed(); navigator.showToast("Reading the vault again…"); true }
            else -> false
        }
    }

    Stack {
        ScreenHeader("Brain", vault.getOrNull()?.let { "${it.root.fileName} · control panel of your second brain" } ?: "Control panel of your second brain")
        VGap()
        Vitals(health, reading = app.brain.reading)
        val offline = app.connection is Connection.Offline
        if (offline) Warning("Ollama is not running, so AI features are off. Start it, or check the URL in Settings.")
        VGap()
        // header 3 + vitals 1 + gap 1 (+ the warning): the panels get every other row
        val height = outputRows(fixed = 3 + 1 + 1 + (if (offline) 1 else 0))
        when {
            vault.isFailure -> Panel("Today") {
                Warning(vault.exceptionOrNull()?.message ?: "No vault.")
                Cta("Press ${Screen.Settings.shortcut} to choose your vault folder.")
            }
            index == null -> Panel("Today") { Spinner("Reading your vault…") }
            wide -> {
                val half = ((LocalContentWidth.current - 1) / 2).coerceAtLeast(30)
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(0.5f)) {
                        CompositionLocalProvider(LocalContentWidth provides half) {
                            Panel("Now", Modifier.height(height)) { RowList(rows.now, current, height - 2) }
                        }
                    }
                    HGap()
                    Box(Modifier.weight(0.5f)) {
                        CompositionLocalProvider(LocalContentWidth provides half) {
                            Panel("Jump back in", Modifier.height(height)) { RowList(rows.back, current - rows.nowItems.size, height - 2) }
                        }
                    }
                }
            }
            else -> Panel("Today") { RowList(rows.now + rows.back, current, height - 2) }
        }
    }
}

/** The vault's vital signs on one line. */
@Composable
private fun Vitals(h: BrainHealth?, reading: Boolean) {
    if (h == null) {
        Label(if (reading) "Reading the vault…" else " ", color = Palette.Muted)
        return
    }
    val line = "▤ ${h.notes} notes  ✎ ${UsageStats.compact(h.words)} words  ⇄ ${h.links} links  # ${h.tags} tags  " +
        "◐ ${h.connectedPercent}% linked  ◐ ${h.taggedPercent}% tagged  ↻ ${h.changedThisWeek} this week"
    Label(line, color = Palette.Text)
}

private fun buildRows(app: AppContext, navigator: Navigator, h: BrainHealth?, hasVault: Boolean, recents: Int, topTags: Int): BrainRows {
    if (!hasVault) return BrainRows(emptyList(), emptyList())
    val now = buildList {
        val daily = app.openVault().getOrNull()?.let { v -> app.dailyNotes(v).pathFor(LocalDate.now()).let { it to Files.exists(v.root.resolve(it)) } }
        add(BrainRow.Heading("CAPTURE"))
        add(
            BrainRow.Item("✎", Palette.Accent, "Capture a thought", "Ctrl+N from anywhere · " + (daily?.let { if (it.second) "today's note: ${it.first}" else "no note for today yet" } ?: ""), open = { navigator.openCapture() }),
        )
        if (h != null) {
            add(BrainRow.Heading("NEEDS ATTENTION"))
            fun attention(icon: String, count: Int, what: String, filter: LibraryFilter, color: Color) {
                if (count > 0) add(BrainRow.Item(icon, color, "$count $what", "", open = { navigator.openLibrary(filter) }))
            }
            if (h.inbox > 0) {
                add(BrainRow.Item("▣", Palette.Orange, "${h.inbox} ${if (h.inbox == 1) "note" else "notes"} waiting in the inbox", "process with AI", open = { navigator.navigate(Screen.Inbox) }))
            }
            attention("⊘", h.orphans, "notes are not linked to anything", LibraryFilter.Orphans, Palette.Orange)
            attention("#", h.untagged, "notes have no tags", LibraryFilter.Untagged, Palette.Blue)
            attention("✕", h.brokenNotes, "notes contain broken links (${h.brokenLinks} in all)", LibraryFilter.Broken, Palette.Red)
            attention("◷", h.stale, "notes untouched for ${app.config.staleDays}+ days", LibraryFilter.Stale, Palette.Muted)
            if (h.inbox + h.orphans + h.untagged + h.brokenNotes + h.stale == 0) {
                add(BrainRow.Item("✓", Palette.Green, "Everything is in order", "no inbox, no islands, no broken links", dim = true, open = { navigator.openLibrary(LibraryFilter.All) }))
            }
        }
        // the topics the vault is about: each opens the Library on that tag
        val tags = app.brain.index?.tagCounts?.take(topTags).orEmpty()
        if (tags.isNotEmpty()) {
            add(BrainRow.Heading("TOP TAGS"))
            tags.forEach { (name, count) -> add(BrainRow.Item("#", Palette.Blue, name, "$count note${if (count == 1) "" else "s"}", open = { navigator.openLibrary(LibraryFilter.Tags, tag = name) })) }
        }
    }
    val back = buildList {
        add(BrainRow.Heading("WORK"))
        val chat = app.chat
        if (chat.messages.isNotEmpty()) {
            add(BrainRow.Item("»", Palette.Accent, "Continue chat: ${chat.title}", "${chat.messages.size} messages", open = { navigator.navigate(Screen.Chat) }))
        } else {
            add(BrainRow.Item("»", Palette.Accent, "Ask your brain", "chat with Gemma about your notes", open = { navigator.navigate(Screen.Chat) }))
        }
        add(BrainRow.Item("✦", Palette.Accent, "Write or improve a note with AI", "AI notes", open = { navigator.navigate(Screen.Compose) }))
        val session = app.session
        add(
            BrainRow.Item(
                "◷", Palette.Muted, "This session",
                "${SessionReport.duration(System.currentTimeMillis() - session.started)} · ${SessionReport.stats(session.activity).describe()}" + if (session.needsSaving) " · not saved" else "",
                open = { navigator.navigate(Screen.Session) },
            ),
        )
        session.previous?.takeIf { it.savedAs != null }?.let { last ->
            add(BrainRow.Item("◷", Palette.Dim, "Last session", "${relativeTime(last.started)} · ${SessionReport.stats(last.events).describe()}", dim = true, open = { navigator.openLibrary(LibraryFilter.All, last.savedAs) }))
        }
        val recent = app.brain.index?.touchedSince(30)?.take(recents).orEmpty()
        if (recent.isNotEmpty()) add(BrainRow.Heading("RECENT NOTES"))
        recent.forEach { n -> add(BrainRow.Item("▤", Palette.Muted, n.title, relativeTime(n.modified), open = { navigator.openLibrary(LibraryFilter.All, n.path) })) }
    }
    return BrainRows(now, back)
}

/** The rows in a window that follows the chosen one; headings are shown but cannot be chosen. [selected] is -1 when the chosen row is elsewhere. */
@Composable
private fun RowList(rows: List<BrainRow>, selected: Int, capacity: Int) {
    var itemIndex = -1
    val numbered = rows.map { r -> if (r is BrainRow.Item) { itemIndex++; r to itemIndex } else r to -1 }
    val flat = numbered.indexOfFirst { it.second == selected }.coerceAtLeast(0)
    val first = (flat - capacity / 2).coerceIn(0, (rows.size - capacity).coerceAtLeast(0))
    numbered.drop(first).take(capacity.coerceAtLeast(1)).forEach { (row, number) ->
        when (row) {
            is BrainRow.Heading -> Label(row.text, color = Palette.Muted, textStyle = TextStyle.Bold)
            is BrainRow.Item -> {
                val on = number == selected
                Row(Modifier.fillMaxWidth().background(if (on) Palette.Selected else Color.Unspecified)) {
                    Label(if (on) "▌" else " ", color = Palette.Accent)
                    Label(" ${row.icon} ", color = if (row.dim) Palette.Dim else row.color)
                    Label(row.title, color = if (row.dim) Palette.Muted else Palette.Text, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
                    if (row.detail.isNotEmpty()) Label("   ${row.detail}", color = Palette.Dim)
                }
            }
        }
    }
}
