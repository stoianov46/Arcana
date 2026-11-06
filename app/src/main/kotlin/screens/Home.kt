package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.arcana.brain.core.BacklogItem
import com.arcana.brain.core.BrainHealth
import com.arcana.brain.core.HygieneKind
import com.arcana.brain.core.HygieneReport
import com.arcana.brain.core.ResearchBacklog
import com.arcana.brain.core.SessionReport
import com.arcana.brain.core.Skills
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.VaultHygiene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // what the Brain remembers while another tab is open: the selected line and the hygiene list (see ScreenMemory)
    val where = app.config.vaultPath
    var selected by rememberKept(app, "brain.selected@$where") { mutableIntStateOf(0) }
    val index = app.brain.index
    val vault = remember(app.config.vaultPath) { app.openVault() }
    val health = remember(index, app.config.inboxFolder, app.config.staleDays) { index?.health(app.config.inboxFolder, app.config.staleDays) }
    val wide = LocalContentWidth.current >= 100

    // the hygiene check reads every note once: it runs off the UI thread whenever the vault was read again, and the last result stays on screen meanwhile
    var hygiene by rememberKept(app, "brain.hygiene@$where") { mutableStateOf<HygieneReport?>(null) }
    LaunchedEffect(index) {
        val v = vault.getOrNull()
        if (index != null && v != null) hygiene = withContext(Dispatchers.IO) { runCatching { VaultHygiene.check(v, index = index) }.getOrNull() }
    }
    var hygieneOpen by rememberKept(app, "brain.hygieneOpen@$where") { mutableStateOf(false) }
    var hygieneKind by rememberKept(app, "brain.hygieneKind@$where") { mutableStateOf<HygieneKind?>(null) }
    var hygieneSel by rememberKept(app, "brain.hygieneSel@$where") { mutableIntStateOf(0) }
    fun openHygiene(kind: HygieneKind?) { hygieneKind = kind; hygieneSel = 0; hygieneOpen = true }
    // the issues the list shows, in the order of its rows (a kind after a kind)
    val listed = hygiene?.let { r -> (hygieneKind?.let { listOf(it) } ?: HygieneKind.entries).flatMap { r.byKind.getValue(it) } }.orEmpty()
    val listRows = hygiene?.let { r ->
        buildList {
            (hygieneKind?.let { listOf(it) } ?: HygieneKind.entries).filter { r.count(it) > 0 }.forEach { k ->
                add(BrainRow.Heading("${k.icon} ${k.label.uppercase()} (${r.count(k)})"))
                r.byKind.getValue(k).forEach { i -> add(BrainRow.Item("▤", Palette.Muted, i.path.removeSuffix(".md"), i.message, open = { navigator.openLibrary(LibraryFilter.All, i.path) })) }
            }
        }
    }.orEmpty()
    val chosenIssue = listed.getOrNull(hygieneSel.coerceIn(0, (listed.size - 1).coerceAtLeast(0)))

    // the research backlog (Research/Research Backlog.md): read, and ticked by the reports that answer its questions, off the UI thread whenever the vault was read again
    var backlog by rememberKept(app, "brain.backlog@$where") { mutableStateOf<List<BacklogItem>>(emptyList()) }
    LaunchedEffect(index) {
        val v = vault.getOrNull()
        if (index != null && v != null) backlog = withContext(Dispatchers.IO) { runCatching { ResearchBacklog.reconcile(v); runCatching { com.arcana.brain.core.WorkMigration.sync(v, com.arcana.brain.core.WorkStore(v)) }; ResearchBacklog.open(v) }.getOrDefault(emptyList()) }
    }
    var backlogOpen by rememberKept(app, "brain.backlogOpen@$where") { mutableStateOf(false) }
    var backlogSel by rememberKept(app, "brain.backlogSel@$where") { mutableIntStateOf(0) }
    fun openBacklog() { backlogSel = 0; backlogOpen = true }
    val chosenQuestion = backlog.getOrNull(backlogSel.coerceIn(0, (backlog.size - 1).coerceAtLeast(0)))
    // a fresh chat with the research skill attached (the first one named ...research-report) and the question waiting in the field: Enter starts typing, Enter again sends
    fun startResearch(item: BacklogItem) {
        val skill = if (app.config.skillsEnabled) vault.getOrNull()?.let { v -> Skills.discoverAll(v, app.skillsFolder()).firstOrNull { it.name.endsWith("research-report") } } else null
        app.chat.startNew()
        app.chat.backlogQuestion = item.question
        app.chat.backlogId = vault.getOrNull()?.let { v -> runCatching { ResearchBacklog.ensureId(v, item) }.getOrNull() }.orEmpty()
        skill?.let { app.chat.attachments.notes += it.path }
        app.chat.input.set(item.question)
        backlogOpen = false
        navigator.navigate(Screen.Chat)
    }
    fun markDone(item: BacklogItem) {
        val v = vault.getOrNull() ?: return
        runCatching { ResearchBacklog.tick(v, item); backlog = ResearchBacklog.open(v) }
        backlogSel = backlogSel.coerceIn(0, (backlog.size - 1).coerceAtLeast(0))
    }
    val backlogRows = backlog.map { BrainRow.Item("?", Palette.Cta, it.question, it.date, open = {}) }

    val rows = buildRows(app, navigator, health, vault.isSuccess, recents = if (wide) 12 else 5, topTags = if (wide) 8 else 0, hygiene = hygiene, openHygiene = ::openHygiene, backlog = backlog, openBacklog = ::openBacklog)
    val items = rows.nowItems + rows.backItems
    val current = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    if (backlogOpen) Hints("↑↓  choose", "Enter  start the research", "x  mark done", "Esc  back")
    else if (hygieneOpen) Hints("↑↓  choose", "Enter  open the note", "c  chat to fix it", "Esc  back")
    else Hints("↑↓  choose", "←→  column", "Enter  open", "h  vault hygiene", "b  research backlog", "r  re-read the vault")
    Commands(listOf(Command("Re-read the vault", "Brain", "r") { app.brain.changed() }, Command("Vault hygiene: names, properties, reports, islands", "Brain", "h") { openHygiene(null) }, Command("Research backlog: questions captured with “? ”", "Brain", "b") { openBacklog() }))

    KeyHandler { key ->
        // the research backlog list has the keys while it is open; `b` opens it from the dashboard (not while the hygiene list is open)
        if (backlogOpen) return@KeyHandler when {
            key == Key.Escape -> { backlogOpen = false; true }
            key == Key.Up || key.char == 'k' -> { backlogSel = (backlogSel - 1).coerceAtLeast(0); true }
            key == Key.Down || key.char == 'j' -> { backlogSel = (backlogSel + 1).coerceAtMost((backlog.size - 1).coerceAtLeast(0)); true }
            key == Key.Enter -> { chosenQuestion?.let(::startResearch); true }
            key.char == 'x' -> { chosenQuestion?.let(::markDone); true }
            else -> true
        }
        if (!hygieneOpen && key.char == 'b') { openBacklog(); return@KeyHandler true }
        if (hygieneOpen) when {
            key == Key.Escape -> { hygieneOpen = false; true }
            key == Key.Up || key.char == 'k' -> { hygieneSel = (hygieneSel - 1).coerceAtLeast(0); true }
            key == Key.Down || key.char == 'j' -> { hygieneSel = (hygieneSel + 1).coerceAtMost((listed.size - 1).coerceAtLeast(0)); true }
            key == Key.Enter -> { chosenIssue?.let { navigator.openLibrary(LibraryFilter.All, it.path) }; true }
            key.char == 'c' -> {
                chosenIssue?.let { i ->
                    // a fresh chat with the note attached, and the instruction waiting in the field (Enter starts typing, Enter again sends)
                    app.chat.startNew()
                    app.chat.attachments.notes += i.path
                    app.chat.input.set("Fix this vault issue: ${i.message}. Suggested fix: ${i.fix}")
                    navigator.navigate(Screen.Chat)
                }
                true
            }
            else -> true
        } else when {
            key.char == 'h' -> { openHygiene(null); true }
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
        ScreenHeader("Brain", vault.getOrNull()?.let { "${it.root.fileName} · research control panel of your second brain" } ?: "Research control panel of your second brain")
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
            backlogOpen -> Panel("Research backlog · ${backlog.size} open", Modifier.height(height)) {
                if (backlog.isEmpty()) Label("✓ No open questions. Capture one with Ctrl+N and start it with “? ”.", color = Palette.Green)
                else RowList(backlogRows, backlogSel.coerceIn(0, (backlog.size - 1).coerceAtLeast(0)), height - 2)
            }
            hygieneOpen -> Panel("Vault hygiene · ${hygiene?.total ?: 0} issues" + (hygieneKind?.let { " · ${it.label}" } ?: ""), Modifier.height(height)) {
                when {
                    hygiene == null -> Spinner("Checking the vault…")
                    listRows.isEmpty() -> Label("✓ Nothing to fix here.", color = Palette.Green)
                    else -> RowList(listRows, hygieneSel.coerceIn(0, (listed.size - 1).coerceAtLeast(0)), height - 2)
                }
            }
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

private fun buildRows(app: AppContext, navigator: Navigator, h: BrainHealth?, hasVault: Boolean, recents: Int, topTags: Int, hygiene: HygieneReport? = null, openHygiene: (HygieneKind?) -> Unit = {}, backlog: List<BacklogItem> = emptyList(), openBacklog: () -> Unit = {}): BrainRows {
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
        // the habits that keep the vault navigable (names, properties, reports, islands): one line per kind, examples for the two worst
        if (hygiene != null) {
            add(BrainRow.Heading("VAULT HYGIENE"))
            if (hygiene.total == 0) add(BrainRow.Item("✓", Palette.Green, "Vault hygiene is clean", "names, properties, reports and links all follow the rules", dim = true, open = {}))
            else {
                HygieneKind.entries.forEach { k ->
                    val n = hygiene.count(k)
                    if (n == 0) add(BrainRow.Item("✓", Palette.Green, k.counted(0), "", dim = true, open = {}))
                    else add(BrainRow.Item(k.icon, if (k == HygieneKind.ReportNotDone) Palette.Red else Palette.Orange, k.counted(n), "Enter or h: see them", open = { openHygiene(k) }))
                }
                hygiene.worst.take(2).forEach { k ->
                    hygiene.byKind.getValue(k).take(3).forEach { i -> add(BrainRow.Item("▤", Palette.Muted, i.title, i.message, open = { navigator.openLibrary(LibraryFilter.All, i.path) })) }
                }
            }
        }
        // the questions waiting to be researched (captured with `? `): how many, the first three; Enter or b opens the list that starts the research
        add(BrainRow.Heading("RESEARCH BACKLOG"))
        if (backlog.isEmpty()) add(BrainRow.Item("?", Palette.Dim, "No open research questions", "Ctrl+N, then “? your question”", dim = true, open = openBacklog))
        else {
            add(BrainRow.Item("?", Palette.Cta, "${backlog.size} open ${if (backlog.size == 1) "question" else "questions"}", "Enter or b: see them, start one", open = openBacklog))
            backlog.take(3).forEach { q -> add(BrainRow.Item("·", Palette.Muted, q.question, q.date, open = openBacklog)) }
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
