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
import com.arcana.brain.core.Skills
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.BottleneckAction
import com.arcana.brain.core.BrainSnapshot
import com.arcana.brain.core.ThoughtLine
import com.arcana.brain.core.Thoughts
import com.arcana.brain.core.Skill
import com.arcana.brain.core.VaultHygiene
import com.arcana.brain.core.WorkSkills
import com.arcana.brain.core.WorkItem
import com.arcana.brain.core.WorkKind
import com.arcana.brain.core.WorkNotes
import com.arcana.brain.core.WorkService
import com.arcana.brain.core.WorkState
import com.arcana.brain.core.WorkStore
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

/** What is about to be started and the skill choices around it: the item (an id the log knows, or blank), its pin, and for a research line the backlog line. */
private data class PickTarget(val kind: WorkKind, val text: String, val itemId: String, val pinned: String, val backlog: BacklogItem? = null)

/** One row of the dashboard: a section heading, or something you can open. */
internal sealed interface BrainRow {
    data class Heading(val text: String) : BrainRow
    class Item(val icon: String, val color: Color, val title: String, val detail: String, val dim: Boolean = false, val open: () -> Unit, val actions: Map<Char, () -> Unit> = emptyMap()) : BrainRow
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
    val health = remember(index, app.config.staleDays) { index?.health(app.config.staleDays) }
    val wide = LocalContentWidth.current >= 100

    // the hygiene check reads every note once: it runs off the UI thread whenever the vault was read again, and the last result stays on screen meanwhile
    var hygiene by rememberKept(app, "brain.hygiene@$where") { mutableStateOf<HygieneReport?>(null) }
    LaunchedEffect(index) {
        val v = vault.getOrNull()
        if (index != null && v != null) hygiene = withContext(Dispatchers.IO) { runCatching { VaultHygiene.check(v, index = index) }.getOrNull() }
    }
    // the page on show and the weeks the charts look back over; the numbers behind the pages are computed off the UI thread when the vault, the hygiene check, the work or the usage changed
    var tab by rememberKept(app, "brain.tab@$where") { mutableStateOf(BrainTab.Overview) }
    var weeks by rememberKept(app, "brain.weeks@$where") { mutableIntStateOf(BRAIN_WINDOWS.first()) }
    var snapshot by rememberKept(app, "brain.snapshot@$where") { mutableStateOf<BrainSnapshot?>(null) }
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
    // the work list: questions, research (the backlog note) and todos, one kind at a time (proposals/0002)
    var workKind by rememberKept(app, "brain.workKind@$where") { mutableStateOf(WorkKind.Research) }
    var workItems by rememberKept(app, "brain.workItems@$where") { mutableStateOf<List<WorkItem>>(emptyList()) }
    fun reloadWork() { workItems = vault.getOrNull()?.let { v -> runCatching { WorkService(WorkStore(v)).items() }.getOrDefault(emptyList()) } ?: emptyList() }
    var allBacklog by rememberKept(app, "brain.allBacklog@$where") { mutableStateOf<List<BacklogItem>>(emptyList()) }
    var thoughts by rememberKept(app, "brain.thoughts@$where") { mutableStateOf<List<ThoughtLine>>(emptyList()) }
    // starting an item: the skills it may run with, shown in a picker (not kept when you leave the screen)
    var pickFor by remember { mutableStateOf<PickTarget?>(null) }
    var pickSkills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var pickSel by remember { mutableIntStateOf(0) }
    var backlog by rememberKept(app, "brain.backlog@$where") { mutableStateOf<List<BacklogItem>>(emptyList()) }
    LaunchedEffect(index) {
        val v = vault.getOrNull()
        if (index != null && v != null) {
            // the generated work notes are written here; when that created or changed a note the vault is read again, so the index (links, orphans, broken links) includes it
            var wrote = false
            backlog = withContext(Dispatchers.IO) { runCatching { ResearchBacklog.reconcile(v); runCatching { val store = com.arcana.brain.core.WorkStore(v); com.arcana.brain.core.WorkMigration.sync(v, store); if (WorkNotes.sync(v, store).isNotEmpty()) wrote = true }; ResearchBacklog.open(v) }.getOrDefault(emptyList()) }
            if (wrote) app.brain.changed()
            reloadWork()
            withContext(Dispatchers.IO) {
                allBacklog = runCatching { ResearchBacklog.items(v) }.getOrDefault(emptyList())
                thoughts = runCatching { Thoughts.recent(v, app.dailyNotes(v)) }.getOrDefault(emptyList())
            }
        }
    }
    var backlogOpen by rememberKept(app, "brain.backlogOpen@$where") { mutableStateOf(false) }
    var backlogSel by rememberKept(app, "brain.backlogSel@$where") { mutableIntStateOf(0) }
    fun openBacklog(kind: WorkKind? = null) { kind?.let { workKind = it }; backlogSel = 0; reloadWork(); backlogOpen = true }
    val workKinds = listOf(WorkKind.Question, WorkKind.Research, WorkKind.Todo, WorkKind.Thought)
    // open first, then in progress, done and given up, each by the day it was captured; the research lines are the backlog note's own, ticked ones last
    val kindItems = workItems.filter { it.kind == workKind }.sortedWith(compareBy({ it.state().ordinal }, { it.capturedAt }))
    val researchAll = allBacklog.sortedWith(compareBy({ it.done }, { it.line }))
    fun countOf(kind: WorkKind) = if (kind == WorkKind.Research) backlog.size else if (kind == WorkKind.Thought) thoughts.size else workItems.count { it.kind == kind && it.state() != WorkState.Answered && it.state() != WorkState.Abandoned }
    val listSize = when (workKind) { WorkKind.Research -> researchAll.size; WorkKind.Thought -> thoughts.size; else -> kindItems.size }
    fun switchKind(by: Int) { workKind = workKinds[(workKinds.indexOf(workKind) + by).mod(workKinds.size)]; backlogSel = 0 }
    val chosenQuestion = researchAll.getOrNull(backlogSel.coerceIn(0, (researchAll.size - 1).coerceAtLeast(0)))
    val chosenThought = thoughts.getOrNull(backlogSel.coerceIn(0, (thoughts.size - 1).coerceAtLeast(0)))
    // t, q and r on a thought: it becomes a todo, a question or a research item (the thought itself stays where it was written)
    fun promote(kind: WorkKind) {
        val thought = chosenThought ?: return
        val v = vault.getOrNull() ?: return
        val made = runCatching { Thoughts.promote(v, WorkStore(v), thought, kind) }.getOrNull()
        if (made == null) navigator.showToast("That thought is already ${Thoughts.promotedAs(thought, workItems)?.id ?: "an item"}.", true)
        else {
            reloadWork()
            allBacklog = runCatching { ResearchBacklog.items(v) }.getOrDefault(allBacklog)
            backlog = runCatching { ResearchBacklog.open(v) }.getOrDefault(backlog)
            app.brain.changed()
            navigator.showToast("Added to ${kind.title.lowercase()}: ${made.text.take(40)}")
        }
    }
    val chosenWork = kindItems.getOrNull(backlogSel.coerceIn(0, (kindItems.size - 1).coerceAtLeast(0)))
    // a command on the chosen item of the log: the event is written, the note of the kind is written again, the list is read again
    fun actOn(item: WorkItem, act: (WorkService, WorkItem) -> Unit) {
        val v = vault.getOrNull() ?: return
        runCatching { val store = WorkStore(v); act(WorkService(store), item); WorkNotes.sync(v, store) }
        reloadWork()
    }
    fun toggle(item: WorkItem) = actOn(item) { svc, i -> if (i.closing == null) svc.close(i.id) else svc.reopen(i.id) }
    fun retype(item: WorkItem) = actOn(item) { svc, i -> svc.retype(i.id, if (i.kind == WorkKind.Question) WorkKind.Todo else WorkKind.Question) }
    fun toggleDone() { chosenWork?.let(::toggle) }
    fun retypeNext() { chosenWork?.let(::retype) }
    // a fresh chat for the item with the chosen skill attached; its text waits in the field (Enter starts typing, Enter again sends), and the chat is linked to the item:
    // its runs and the notes they write belong to it
    fun beginChat(target: PickTarget, skill: Skill?) {
        app.chat.startNew()
        target.backlog?.let { b ->
            app.chat.backlogQuestion = b.question
            app.chat.backlogId = vault.getOrNull()?.let { v -> runCatching { ResearchBacklog.ensureId(v, b) }.getOrNull() }.orEmpty()
        }
        app.chat.workItemId = app.chat.backlogId.ifBlank { target.itemId }
        skill?.let { app.chat.attachments.notes += it.path }
        app.chat.input.set(if (target.kind == WorkKind.Todo) "Do this task: ${target.text}" else target.text)
        pickFor = null
        backlogOpen = false
        navigator.navigate(Screen.Chat)
    }
    // the skills the picker offers: those that opt in for the kind, and the one that would run anyway (an automatic or an old default is shown even when it did not opt in)
    fun pickOptions(target: PickTarget, skills: List<Skill>): List<Skill?> {
        val offered = WorkSkills.offered(skills, target.kind)
        val chosen = WorkSkills.resolve(target.kind, target.pinned, skills, app.config).skill
        return (listOfNotNull(chosen).filter { it !in offered } + offered + listOf<Skill?>(null))
    }
    // Enter on an item: the picker when a skill could run it, else straight into the chat
    fun requestRun(target: PickTarget) {
        val skills = if (app.config.skillsEnabled) vault.getOrNull()?.let { v -> runCatching { Skills.discoverAll(v, app.skillsFolder()) }.getOrDefault(emptyList()) }.orEmpty() else emptyList()
        val chosen = WorkSkills.resolve(target.kind, target.pinned, skills, app.config).skill
        if (WorkSkills.offered(skills, target.kind).isEmpty()) { beginChat(target, chosen); return }
        pickSkills = skills
        pickSel = pickOptions(target, skills).indexOf(chosen).coerceAtLeast(0)
        pickFor = target
    }
    fun askQuestion(item: WorkItem) = requestRun(PickTarget(item.kind, item.text, item.id, item.skill))
    fun startResearch(item: BacklogItem) {
        val marker = item.id.orEmpty()
        val logged = workItems.firstOrNull { w -> marker.isNotEmpty() && w.isKnownAs(marker) }
        requestRun(PickTarget(WorkKind.Research, item.question, logged?.id ?: marker, logged?.skill.orEmpty(), item))
    }
    // p pins the highlighted choice to this item, u takes the pin off, d makes it the default of the kind
    fun pinPicked(skill: Skill?, clear: Boolean) {
        val target = pickFor ?: return
        val v = vault.getOrNull() ?: return
        if (target.itemId.isBlank()) { navigator.showToast("This research line is not in the work log yet. Open the Brain screen again in a moment.", true); return }
        val value = if (clear) "" else skill?.name ?: WorkSkills.NONE
        runCatching { val store = WorkStore(v); WorkService(store).pinSkill(target.itemId, value); WorkNotes.sync(v, store) }
        reloadWork()
        pickFor = target.copy(pinned = value)
        navigator.showToast(if (clear) "Pin removed" else "Pinned: ${skill?.name ?: "a plain chat"}")
    }
    fun defaultPicked(skill: Skill?) {
        val target = pickFor ?: return
        app.update(WorkSkills.withDefault(app.config, target.kind, skill?.name ?: WorkSkills.NONE))
        navigator.showToast("Default for ${target.kind.id}: ${skill?.name ?: "a plain chat"}")
    }
    fun markDone(item: BacklogItem) {
        val v = vault.getOrNull() ?: return
        runCatching { ResearchBacklog.tick(v, item); backlog = ResearchBacklog.open(v); allBacklog = ResearchBacklog.items(v) }
        backlogSel = backlogSel.coerceIn(0, (backlog.size - 1).coerceAtLeast(0))
    }
    // a ticked research line opens its report; an open one starts the research
    fun openResearchLine(item: BacklogItem) {
        if (!item.done) { startResearch(item); return }
        val title = item.link?.removePrefix("[[")?.removeSuffix("]]")?.substringBefore('|')?.trim().orEmpty()
        val path = app.brain.index?.notes?.firstOrNull { it.title.equals(title, ignoreCase = true) }?.path
        if (path != null) { backlogOpen = false; navigator.openLibrary(LibraryFilter.All, path) }
    }
    val backlogRows = backlog.map { BrainRow.Item("⌕", Palette.Orange, it.question, it.date, open = {}) }
    // the same items as rows of the dashboard itself: Enter and x work on them where they stand
    val questionRows = workItems.filter { it.kind == WorkKind.Question && it.state() in listOf(WorkState.Open, WorkState.InProgress) }
        .map { workRow(it, open = { askQuestion(it) }, actions = mapOf('x' to { toggle(it) }, 't' to { retype(it) })) }
    val todoRows = workItems.filter { it.kind == WorkKind.Todo && it.state() in listOf(WorkState.Open, WorkState.InProgress) }
        .map { workRow(it, open = { askQuestion(it) }, actions = mapOf('x' to { toggle(it) }, 't' to { retype(it) })) }
    val researchRows = backlog.map { q -> BrainRow.Item("⌕", Palette.Orange, q.question, q.date, open = { startResearch(q) }, actions = mapOf('x' to { markDone(q) })) }

    LaunchedEffect(index, weeks, hygiene, workItems, app.usage.size) {
        val v = vault.getOrNull()
        if (index != null && v != null && health != null) snapshot = withContext(Dispatchers.IO) { runCatching { computeSnapshot(app, v, index, health, hygiene, weeks) }.getOrNull() }
    }
    fun runBottleneck(a: BottleneckAction) = when (a) {
        is BottleneckAction.Hygiene -> openHygiene(a.kind)
        is BottleneckAction.Library -> navigator.openLibrary(when (a.filter) { "broken" -> LibraryFilter.Broken; "orphans" -> LibraryFilter.Orphans; "untagged" -> LibraryFilter.Untagged; "stale" -> LibraryFilter.Stale; else -> LibraryFilter.All })
        BottleneckAction.Work -> openBacklog()
        BottleneckAction.None -> Unit
    }
    // the overview's list: what holds the brain back (each row goes to the fix), what to do next from the work, and where to go
    val nextUp = workItems.filter { it.state() == WorkState.Open && it.kind != WorkKind.Research && it.kind != WorkKind.Thought }.sortedBy { it.capturedAt }.take(3)
        .map { workRow(it, open = { askQuestion(it) }, actions = mapOf('x' to { toggle(it) }, 't' to { retype(it) })) } +
        backlog.take(2).map { q -> BrainRow.Item("⌕", Palette.Orange, q.question, q.date, open = { startResearch(q) }, actions = mapOf('x' to { markDone(q) })) }
    val overviewRows: List<BrainRow> = buildList {
        add(BrainRow.Heading("BOTTLENECKS · where to improve"))
        snapshot?.bottlenecks?.take(6)?.forEach { b -> add(bottleneckRow(b) { runBottleneck(b.action) }) } ?: add(BrainRow.Item("·", Palette.Dim, "Reading the vault…", "", dim = true, open = {}))
        if (nextUp.isNotEmpty()) { add(BrainRow.Heading("NEXT UP")); addAll(nextUp) }
        add(BrainRow.Heading("LISTS · open them, tick and read"))
        val itemsOf = { k: WorkKind -> workItems.filter { it.kind == k } }
        fun listRow(icon: String, color: Color, kind: WorkKind, open: Int, total: Int) = BrainRow.Item(icon, color, kind.title, "$open open · ${total - open} done", open = { openBacklog(kind) })
        add(listRow("○", Palette.Cta, WorkKind.Todo, itemsOf(WorkKind.Todo).count { it.state() == WorkState.Open || it.state() == WorkState.InProgress }, itemsOf(WorkKind.Todo).size))
        add(listRow("?", Palette.Cta, WorkKind.Question, itemsOf(WorkKind.Question).count { it.state() == WorkState.Open || it.state() == WorkState.InProgress }, itemsOf(WorkKind.Question).size))
        add(listRow("⌕", Palette.Orange, WorkKind.Research, backlog.size, allBacklog.size))
        add(BrainRow.Item("✎", Palette.Accent, "Thoughts", "${thoughts.size} captured recently", open = { openBacklog(WorkKind.Thought) }))
        add(BrainRow.Item("☑", Palette.CyanSoft, "Research Backlog note", "${allBacklog.count { it.done }}/${allBacklog.size} ticked · open the file", open = { navigator.openLibrary(LibraryFilter.All, ResearchBacklog.PATH) }))
        add(BrainRow.Heading("GO TO"))
        add(BrainRow.Item("✎", Palette.Accent, "Capture a thought", "Ctrl+N from anywhere", open = { navigator.openCapture() }))
        add(BrainRow.Item("»", Palette.Accent, if (app.chat.messages.isNotEmpty()) "Continue chat: ${app.chat.title}" else "Ask your brain", "Chat", open = { navigator.navigate(Screen.Chat) }))
        add(BrainRow.Item("▤", Palette.Muted, "Library", "all your notes", open = { navigator.openLibrary(LibraryFilter.All) }))
        add(BrainRow.Item("✦", Palette.Muted, "Write or improve a note with AI", "AI notes", open = { navigator.navigate(Screen.Compose) }))
    }
    val rows = if (tab == BrainTab.Overview) BrainRows(overviewRows, emptyList()) else if (tab == BrainTab.Activity) BrainRows(emptyList(), emptyList()) else buildRows(app, navigator, health, vault.isSuccess, tab = tab, recents = if (wide) 12 else 5, topTags = if (wide) 8 else 0, hygiene = hygiene, openHygiene = ::openHygiene, questions = questionRows, research = researchRows, todos = todoRows, openWork = { openBacklog() }, openCapture = { navigator.openCapture() })
    val items = rows.nowItems + rows.backItems
    val current = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    if (pickFor != null) Hints("↑↓  choose", "Enter  run it", "p  pin to this item", "u  unpin", "d  default for ${pickFor?.kind?.id}", "Esc  back")
    else if (backlogOpen) Hints("↑↓  choose", "Tab  next kind", "Enter  " + when (workKind) { WorkKind.Research -> "start it / open its report"; WorkKind.Thought -> "open the note"; else -> "start it in a chat" }, *(if (workKind == WorkKind.Thought) emptyArray() else arrayOf("x  " + (if (workKind == WorkKind.Research) "mark done" else "done / not done"))), *(if (workKind == WorkKind.Question || workKind == WorkKind.Todo) arrayOf("t  question ⇄ todo") else if (workKind == WorkKind.Thought) arrayOf("t  → todo", "q  → question", "r  → research") else emptyArray()), "Esc  back")
    else if (hygieneOpen) Hints("↑↓  choose", "Enter  open the note", "c  chat to fix it", "Esc  back")
    else Hints("↑↓  choose", "Tab  next page", "w  weeks", "Enter  open", "x  done", "t  question ⇄ todo", "h  vault hygiene", "b  all work", "r  re-read the vault")
    Commands(listOf(Command("Re-read the vault", "Brain", "r") { app.brain.changed() }, Command("Vault hygiene: names, properties, reports, islands", "Brain", "h") { openHygiene(null) }, Command("Research backlog: questions captured with “? ”", "Brain", "b") { openBacklog() }))

    // x and t act on the work row that is selected on the dashboard
    fun rowAction(c: Char?): Boolean {
        val action = c?.let { items.getOrNull(current)?.actions?.get(it) } ?: return false
        action()
        return true
    }

    KeyHandler { key ->
        // the skill picker has the keys while it is open
        pickFor?.let { target ->
            val options = pickOptions(target, pickSkills)
            val picked = options.getOrNull(pickSel.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
            return@KeyHandler when {
                key == Key.Escape -> { pickFor = null; true }
                key == Key.Up || key.char == 'k' -> { pickSel = (pickSel - 1).coerceAtLeast(0); true }
                key == Key.Down || key.char == 'j' -> { pickSel = (pickSel + 1).coerceAtMost((options.size - 1).coerceAtLeast(0)); true }
                key == Key.Enter -> { beginChat(target, picked); true }
                key.char == 'p' -> { pinPicked(picked, clear = false); true }
                key.char == 'u' -> { pinPicked(picked, clear = true); true }
                key.char == 'd' -> { defaultPicked(picked); true }
                else -> true
            }
        }
        // the research backlog list has the keys while it is open; `b` opens it from the dashboard (not while the hygiene list is open)
        if (backlogOpen) return@KeyHandler when {
            key == Key.Escape -> { backlogOpen = false; true }
            key == Key.Up || key.char == 'k' -> { backlogSel = (backlogSel - 1).coerceAtLeast(0); true }
            key == Key.Down || key.char == 'j' -> { backlogSel = (backlogSel + 1).coerceAtMost((listSize - 1).coerceAtLeast(0)); true }
            key == Key.Tab || key == Key.Right -> { switchKind(1); true }
            key == Key.Left || key == Key.BackTab -> { switchKind(-1); true }
            key == Key.Enter -> { when (workKind) { WorkKind.Research -> chosenQuestion?.let(::openResearchLine); WorkKind.Thought -> chosenThought?.let { backlogOpen = false; navigator.openLibrary(LibraryFilter.All, it.path) }; else -> chosenWork?.let(::askQuestion) }; true }
            key.char == 'x' || key.char == ' ' -> { if (workKind == WorkKind.Research) chosenQuestion?.takeIf { !it.done }?.let(::markDone) else if (workKind != WorkKind.Thought) toggleDone(); true }
            key.char == 't' -> { if (workKind == WorkKind.Thought) promote(WorkKind.Todo) else if (workKind == WorkKind.Question || workKind == WorkKind.Todo) retypeNext(); true }
            key.char == 'q' && workKind == WorkKind.Thought -> { promote(WorkKind.Question); true }
            key.char == 'r' && workKind == WorkKind.Thought -> { promote(WorkKind.Research); true }
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
            rowAction(key.char) -> true
            key == Key.Tab -> { tab = BrainTab.entries[(tab.ordinal + 1).mod(BrainTab.entries.size)]; selected = 0; true }
            key == Key.BackTab -> { tab = BrainTab.entries[(tab.ordinal - 1).mod(BrainTab.entries.size)]; selected = 0; true }
            key.char == 'w' -> { weeks = BRAIN_WINDOWS[(BRAIN_WINDOWS.indexOf(weeks) + 1).mod(BRAIN_WINDOWS.size)]; true }
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
        BrainTabBar(tab, weeks)
        val offline = app.connection is Connection.Offline
        if (offline) Warning("Ollama is not running, so AI features are off. Start it, or check the URL in Settings.")
        VGap()
        // header 3 + vitals 1 + gap 1 (+ the warning): the panels get every other row
        val height = outputRows(fixed = 3 + 1 + 1 + 1 + (if (offline) 1 else 0))
        when {
            vault.isFailure -> Panel("Today") {
                Warning(vault.exceptionOrNull()?.message ?: "No vault.")
                Cta("Press ${Screen.Settings.shortcut} to choose your vault folder.")
            }
            index == null -> Panel("Today") { Spinner("Reading your vault…") }
            pickFor != null -> pickFor?.let { t ->
                val options = pickOptions(t, pickSkills)
                val will = WorkSkills.resolve(t.kind, t.pinned, pickSkills, app.config)
                val default = WorkSkills.defaultFor(app.config, t.kind)
                Panel("Run “${t.text.take(48)}” with…", Modifier.height(height)) {
                    Label("Runs with: ${will.skill?.name ?: "a plain chat"} (${will.source})", color = Palette.Muted)
                    VGap()
                    RowList(options.map { s ->
                        val name = s?.name ?: WorkSkills.NONE
                        val marks = listOfNotNull(if (t.pinned == name) "pinned" else null, if (default == name) "default for ${t.kind.id}" else null).joinToString(" · ")
                        if (s == null) BrainRow.Item("○", Palette.Muted, "Plain chat", listOf(marks, "no skill: the model answers with its tools").filter { it.isNotEmpty() }.joinToString(" · "), open = {})
                        else BrainRow.Item("◆", Palette.AccentSoft, s.name, listOf(marks, s.description.take(70)).filter { it.isNotEmpty() }.joinToString(" · "), open = {})
                    }, pickSel.coerceIn(0, (options.size - 1).coerceAtLeast(0)), height - 5)
                }
            }
            backlogOpen -> Panel("Lists · " + workKinds.joinToString("   ") { k -> (if (k == workKind) "‹ ${k.title} ${countOf(k)} ›" else "${k.title} ${countOf(k)}") }, Modifier.height(height)) {
                val prefix = when (workKind) { WorkKind.Question -> "question: "; WorkKind.Todo -> "todo: "; WorkKind.Thought -> ""; else -> "? " }
                when {
                    workKind == WorkKind.Research && researchAll.isEmpty() -> Label("✓ No research yet. Capture one with Ctrl+N and start it with “? ” or “research: ”.", color = Palette.CyanSoft)
                    workKind == WorkKind.Research -> RowList(researchAll.map { q -> BrainRow.Item(if (q.done) "☑" else "☐", if (q.done) Palette.CyanSoft else Palette.Cta, q.question, q.date + (q.link?.let { " · $it" } ?: ""), dim = q.done, open = {}) }, backlogSel.coerceIn(0, (researchAll.size - 1).coerceAtLeast(0)), height - 2)
                    workKind == WorkKind.Thought && thoughts.isEmpty() -> Label("No thoughts yet. Capture one with Ctrl+N.", color = Palette.Dim)
                    workKind == WorkKind.Thought -> RowList(thoughts.map { t -> Thoughts.promotedAs(t, workItems).let { k -> BrainRow.Item(if (k == null) "✎" else "✓", if (k == null) Palette.Accent else Palette.CyanSoft, t.text, "${t.date} ${t.time}" + (k?.let { " · → ${it.id}" } ?: ""), dim = k != null, open = {}) } }, backlogSel.coerceIn(0, (thoughts.size - 1).coerceAtLeast(0)), height - 2)
                    kindItems.isEmpty() -> Label("Nothing here. Capture one with Ctrl+N and start it with “$prefix”.", color = Palette.Dim)
                    else -> RowList(kindItems.map { i -> workRow(i, open = { askQuestion(i) }) }, backlogSel.coerceIn(0, (kindItems.size - 1).coerceAtLeast(0)), height - 2)
                }
            }
            hygieneOpen -> Panel("Vault hygiene · ${hygiene?.total ?: 0} issues" + (hygieneKind?.let { " · ${it.label}" } ?: ""), Modifier.height(height)) {
                when {
                    hygiene == null -> Spinner("Checking the vault…")
                    listRows.isEmpty() -> Label("✓ Nothing to fix here.", color = Palette.CyanSoft)
                    else -> RowList(listRows, hygieneSel.coerceIn(0, (listed.size - 1).coerceAtLeast(0)), height - 2)
                }
            }
            wide -> {
                val half = ((LocalContentWidth.current - 1) / 2).coerceAtLeast(30)
                val snap = snapshot
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(0.5f)) {
                        CompositionLocalProvider(LocalContentWidth provides half) {
                            when (tab) {
                                BrainTab.Overview -> Panel("Do and start", Modifier.height(height)) { RowList(rows.now, current, height - 2) }
                                BrainTab.Work -> Panel("Work", Modifier.height(height)) { RowList(rows.now, current, height - 2) }
                                BrainTab.Health -> Panel("Needs attention", Modifier.height(height)) { RowList(rows.now, current, height - 2) }
                                BrainTab.Activity -> Panel("Activity", Modifier.height(height)) { if (snap == null) Spinner("Reading the vault…") else { HeatBlock(snap); VGap(); PulseBlock(snap) } }
                            }
                        }
                    }
                    HGap()
                    Box(Modifier.weight(0.5f)) {
                        CompositionLocalProvider(LocalContentWidth provides half) {
                            when (tab) {
                                BrainTab.Overview -> Panel("Pulse", Modifier.height(height)) {
                                    if (snap == null) Spinner("Reading the vault…") else { PulseBlock(snap); VGap(); HealthBlock(snap); VGap(); PipelineBlock(snap.pipeline); VGap(); FoldersBlock(snap); VGap(); HubsBlock(snap) }
                                }
                                BrainTab.Work -> Panel("Pipeline", Modifier.height(height)) { if (snap == null) Spinner("Reading the vault…") else { PipelineBlock(snap.pipeline); VGap(); HealthBlock(snap) } }
                                BrainTab.Health -> Panel("Health", Modifier.height(height)) { if (snap == null) Spinner("Reading the vault…") else { HealthBlock(snap); VGap(); FoldersBlock(snap, 8); VGap(); HubsBlock(snap) } }
                                BrainTab.Activity -> Panel("Per week", Modifier.height(height)) { if (snap == null) Spinner("Reading the vault…") else { WeeklyBlock(snap); VGap(); TokensBlock(snap) } }
                            }
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

private fun buildRows(app: AppContext, navigator: Navigator, h: BrainHealth?, hasVault: Boolean, tab: BrainTab, recents: Int, topTags: Int, hygiene: HygieneReport? = null, openHygiene: (HygieneKind?) -> Unit = {}, questions: List<BrainRow.Item> = emptyList(), research: List<BrainRow.Item> = emptyList(), todos: List<BrainRow.Item> = emptyList(), openWork: () -> Unit = {}, openCapture: () -> Unit = {}): BrainRows {
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
            attention("⊘", h.orphans, "notes are not linked to anything", LibraryFilter.Orphans, Palette.Orange)
            attention("#", h.untagged, "notes have no tags", LibraryFilter.Untagged, Palette.Blue)
            attention("✕", h.brokenNotes, "notes contain broken links (${h.brokenLinks} in all)", LibraryFilter.Broken, Palette.OrangeSoft)
            attention("◷", h.stale, "notes untouched for ${app.config.staleDays}+ days", LibraryFilter.Stale, Palette.Muted)
            if (h.orphans + h.untagged + h.brokenNotes + h.stale == 0) {
                add(BrainRow.Item("✓", Palette.CyanSoft, "Everything is in order", "no islands, no broken links", dim = true, open = { navigator.openLibrary(LibraryFilter.All) }))
            }
        }
        // the work: questions, research and todos, each its own section with what is still to do (Enter acts on a row, x marks it done, t turns a question into a todo and back; b opens them all)
        fun section(heading: String, rows: List<BrainRow.Item>, prefix: String) {
            add(BrainRow.Heading("$heading · ${rows.size} open"))
            if (rows.isEmpty()) add(BrainRow.Item("·", Palette.Dim, "Nothing open", "Ctrl+N, then “$prefix…”", dim = true, open = openCapture))
            else rows.take(if (tab == BrainTab.Work) 100 else 5).forEach { add(it) }
            if (tab != BrainTab.Work && rows.size > 5) add(BrainRow.Item("…", Palette.Dim, "${rows.size - 5} more", "Enter or b: see all", dim = true, open = openWork))
        }
        section("QUESTIONS", questions, "question: ")
        section("RESEARCH", research, "research: ")
        section("TODOS", todos, "todo: ")
        // the habits that keep the vault navigable (names, properties, reports, islands): one line per kind, examples for the two worst
        if (hygiene != null) {
            add(BrainRow.Heading("VAULT HYGIENE"))
            if (hygiene.total == 0) add(BrainRow.Item("✓", Palette.CyanSoft, "Vault hygiene is clean", "names, properties, reports and links all follow the rules", dim = true, open = {}))
            else {
                HygieneKind.entries.forEach { k ->
                    val n = hygiene.count(k)
                    if (n == 0) add(BrainRow.Item("✓", Palette.CyanSoft, k.counted(0), "", dim = true, open = {}))
                    else add(BrainRow.Item(k.icon, if (k == HygieneKind.ReportNotDone) Palette.OrangeSoft else Palette.Orange, k.counted(n), "Enter or h: see them", open = { openHygiene(k) }))
                }
                hygiene.worst.take(2).forEach { k ->
                    hygiene.byKind.getValue(k).take(3).forEach { i -> add(BrainRow.Item("▤", Palette.Muted, i.title, i.message, open = { navigator.openLibrary(LibraryFilter.All, i.path) })) }
                }
            }
        }
        // the topics the vault is about: each opens the Library on that tag
        val tags = app.brain.index?.tagCounts?.take(topTags).orEmpty()
        if (tags.isNotEmpty()) {
            add(BrainRow.Heading("TOP TAGS"))
            tags.forEach { (name, count) -> add(BrainRow.Item("#", Palette.Blue, name, "$count note${if (count == 1) "" else "s"}", open = { navigator.openLibrary(LibraryFilter.Tags, tag = name) })) }
        }
    }
    // each page keeps its own sections: the work page the capture row and the work, the health page the attention list, the hygiene and the tags
    val keep = when (tab) {
        BrainTab.Work -> listOf("CAPTURE", "QUESTIONS", "RESEARCH", "TODOS")
        BrainTab.Health -> listOf("NEEDS ATTENTION", "VAULT HYGIENE", "TOP TAGS")
        else -> emptyList()
    }
    var inKept = false
    val shown = now.filter { r -> if (r is BrainRow.Heading) { inKept = keep.any { r.text.startsWith(it) }; inKept } else inKept }
    return BrainRows(shown, emptyList())
}

/** The rows in a window that follows the chosen one; headings are shown but cannot be chosen. [selected] is -1 when the chosen row is elsewhere. */
@Composable
internal fun RowList(rows: List<BrainRow>, selected: Int, capacity: Int) {
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

/** One item of the log as a row of the work list: its state as the icon, its text, and how far it has got. */
private fun workRow(item: WorkItem, open: () -> Unit = {}, actions: Map<Char, () -> Unit> = emptyMap()): BrainRow.Item {
    val state = item.state()
    val (icon, color) = when (state) {
        WorkState.Open -> "○" to Palette.Cta
        WorkState.InProgress -> "◐" to Palette.Orange
        WorkState.Answered -> "●" to Palette.CyanSoft
        WorkState.Abandoned -> "✕" to Palette.Dim
    }
    val label = when (state) { WorkState.Open -> "open"; WorkState.InProgress -> "in progress"; WorkState.Answered -> "done"; WorkState.Abandoned -> "given up" }
    val notes = item.artifacts.size.takeIf { it > 0 }?.let { " · $it note${if (it == 1) "" else "s"}" }.orEmpty()
    return BrainRow.Item(icon, color, item.text, "$label · ${item.date}$notes" + if (item.skill.isBlank()) "" else " · ${item.skill}", dim = state == WorkState.Abandoned, open = open, actions = actions)
}
