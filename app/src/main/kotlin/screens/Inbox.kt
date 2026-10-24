package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.GenState
import com.arcana.brain.app.Generation
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.Command
import com.arcana.brain.app.navigation.Commands
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.core.InboxPlan
import com.arcana.brain.core.InboxProcessing
import com.arcana.brain.core.NoteInfo
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.Vault
import com.arcana.brain.core.processInboxNote

private enum class InboxMode { List, Review }

/** What the model made of one inbox note: a checked plan, or why there is none. */
private sealed interface Analysis {
    data class Ready(val plan: InboxPlan) : Analysis
    data class Failed(val message: String) : Analysis
}

private const val FieldCount = 5

/**
 * Processing the inbox, one note at a time. For each note in the inbox folder Gemma proposes a clearer title, a folder,
 * tags (from the ones already in use), links to related notes and a one-line summary. You review the proposal in a small
 * form, change anything, and apply it or skip it. While you review one note the next is already being read. Applying
 * rewrites the note and moves it out of the inbox; the version before is always kept in the vault's `.trash`.
 */
@Composable
fun InboxScreen(app: AppContext) = WithVault(app, "Inbox") { vault -> InboxContent(app, vault) }

@Composable
private fun InboxContent(app: AppContext, vault: Vault) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val index = app.brain.index
    val inbox = app.config.inboxFolder
    val results = remember { mutableStateMapOf<String, Analysis>() }
    // What happened to each note in this visit: "filed → path" or "skipped".
    val outcome = remember { mutableStateMapOf<String, String>() }
    val picker = remember { PickerState() }
    var mode by remember { mutableStateOf(InboxMode.List) }
    var reviewing by remember { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableIntStateOf(0) }
    val fields = remember { List(FieldCount) { TextFieldState() } }
    var loadedFor by remember { mutableStateOf<String?>(null) }

    val queue: List<NoteInfo> = index?.inFolder(inbox)?.sortedBy { it.modified }.orEmpty()
    val pending = queue.filter { it.path !in outcome }
    val current = queue.firstOrNull { it.path == reviewing }

    fun context(note: NoteInfo): Triple<List<String>, List<String>, List<String>>? {
        val idx = index ?: return null
        val tags = idx.tagCounts.map { it.first }
        val titles = idx.notes.filter { it.path != note.path && !it.path.startsWith(inbox.trim('/') + "/") }.sortedByDescending { it.modified }.map { it.title }
        val folders = idx.notes.map { it.folder }.filter { it.isNotEmpty() && !it.startsWith(".") && !it.equals(inbox.trim('/'), ignoreCase = true) }.distinct().sorted()
        return Triple(tags, titles, folders)
    }

    // One analysis at a time, in the order the notes will be reviewed; the reply is checked against the vault.
    val gen = remember {
        Generation(
            scope, app.client,
            onBusy = { busy -> app.busy = (app.busy + if (busy) 1 else -1).coerceAtLeast(0) },
            onUsage = { chunk -> app.recordUsage("Inbox", chunk) },
            onComplete = { text, _ ->
                val path = analyzing
                if (path != null) {
                    val idx = app.brain.index
                    val title = path.substringAfterLast('/').removeSuffix(".md")
                    val plan = idx?.let {
                        InboxProcessing.parse(text, title, it.notes.map { n -> n.title }, it.notes.map { n -> n.folder }.filter { f -> f.isNotEmpty() }.distinct())
                    }
                    results[path] = if (plan != null) Analysis.Ready(plan) else Analysis.Failed("The model's reply could not be read. Press Enter to try again.")
                    analyzing = null
                }
            },
        )
    }
    DisposableEffect(gen) { onDispose { gen.cancel() } }

    fun analyzeNext() {
        if (gen.state == GenState.Running || analyzing != null) return
        // the note being reviewed first, then the one after it, so the next proposal is ready when needed
        val order = listOfNotNull(current).filter { it.path !in outcome } + pending
        val next = order.take(2).firstOrNull { it.path !in results } ?: return
        val (tags, titles, folders) = context(next) ?: return
        analyzing = next.path
        val raw = runCatching { vault.read(next.path) }.getOrDefault("")
        gen.start(json = true) { InboxProcessing.prompt(app.config, next.title, Vault.splitFrontmatter(raw).second, tags, titles, folders) }
    }

    LaunchedEffect(gen.state) {
        if (gen.state == GenState.Failed) {
            analyzing?.let { results[it] = Analysis.Failed(gen.error ?: "The model could not be reached.") }
            analyzing = null
        }
    }
    // Start (or continue) reading notes whenever something relevant changes.
    LaunchedEffect(reviewing, results.size, outcome.size, analyzing, index) {
        if (mode == InboxMode.Review) analyzeNext()
    }

    // Fill the form when a proposal arrives for the note on screen.
    val analysis = current?.let { results[it.path] }
    LaunchedEffect(current?.path, analysis) {
        val a = analysis
        if (current != null && a is Analysis.Ready && loadedFor != current.path) {
            val p = a.plan
            fields[0].set(p.title)
            fields[1].set(p.folder)
            fields[2].set(p.tags.joinToString(" "))
            fields[3].set(p.links.joinToString(" ") { "[[$it]]" })
            fields[4].set(p.summary)
            focus = 0
            loadedFor = current.path
        }
    }

    fun review(path: String) {
        reviewing = path
        loadedFor = null
        mode = InboxMode.Review
    }

    fun nextPending(after: String?): NoteInfo? = pending.firstOrNull { it.path != after }

    fun skip(note: NoteInfo) {
        outcome[note.path] = "skipped"
        val next = nextPending(note.path)
        if (next != null) review(next.path) else mode = InboxMode.List
    }

    fun parseLinks(text: String, known: Set<String>): List<String> {
        val bracketed = Regex("""\[\[([^\]]+)]]""").findAll(text).map { it.groupValues[1].substringBefore('|').trim() }.toList()
        val raw = bracketed.ifEmpty { text.split(',', ';').map { it.trim() } }
        return raw.filter { it.isNotEmpty() }.mapNotNull { name -> known.firstOrNull { it.equals(name, ignoreCase = true) } }.distinct()
    }

    fun apply(note: NoteInfo) {
        val idx = index ?: return
        val known = idx.notes.map { it.title }.toSet()
        val links = parseLinks(fields[3].text, known)
        val plan = InboxPlan(
            title = fields[0].text.trim().ifEmpty { note.title },
            folder = fields[1].text.trim().trim('/'),
            tags = fields[2].text.split(' ', ',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct(),
            links = links,
            summary = fields[4].text.trim(),
        )
        // other notes link to this one by name: renaming it would break those links
        val keepName = idx.backlinksOf(note.path).isNotEmpty()
        runCatching { vault.processInboxNote(note.path, plan, keepName) }
            .onSuccess { done ->
                outcome[note.path] = "filed → ${done.path}"
                app.session.log(SessionKind.Processed, "${note.title} → ${done.path.removeSuffix(".md")}", done.path)
                app.brain.changed()
                navigator.showToast("Filed as ${done.path}. The original is in ${done.backup}")
                val next = nextPending(note.path)
                if (next != null) review(next.path) else mode = InboxMode.List
            }
            .onFailure { navigator.showToast(it.message ?: "Could not file the note.", isError = true) }
    }

    Commands(listOf(Command("Process all inbox notes", "Inbox") { nextPending(null)?.let { review(it.path) } }))
    FocusMode(mode == InboxMode.Review && analysis is Analysis.Ready)
    when (mode) {
        InboxMode.List -> Hints("↑↓  move", "Enter  review", "p  process all", "/  filter")
        InboxMode.Review -> when (analysis) {
            is Analysis.Ready -> Hints("Enter  apply", "Ctrl+X  skip", "Tab  next field", "Esc  back")
            is Analysis.Failed -> Hints("Enter  try again", "Ctrl+X  skip", "Esc  back")
            null -> Hints("Ctrl+X  skip", "Esc  back")
        }
    }

    KeyHandler { key ->
        when (mode) {
            InboxMode.List -> when {
                picker.handle(key, queue) { it.path } -> true
                key == Key.Enter -> { picker.selected(queue) { it.path }?.let { review(it.path) }; true }
                key.char == 'p' -> { nextPending(null)?.let { review(it.path) }; true }
                else -> false
            }
            InboxMode.Review -> when {
                key == Key.Escape -> { mode = InboxMode.List; true }
                key == Key.Skip -> { current?.let { skip(it) }; true }
                analysis is Analysis.Failed && key == Key.Enter -> { current?.let { results.remove(it.path) }; analyzeNext(); true }
                analysis !is Analysis.Ready -> false
                key == Key.Tab || key == Key.Down -> { focus = (focus + 1) % FieldCount; true }
                key == Key.BackTab || key == Key.Up -> { focus = (focus + FieldCount - 1) % FieldCount; true }
                key == Key.Enter -> { current?.let { apply(it) }; true }
                else -> {
                    // the title stays as it is when other notes link to it by name
                    val locked = focus == 0 && current != null && index?.backlinksOf(current.path)?.isNotEmpty() == true
                    if (locked) false else fields[focus].onKey(key)
                }
            }
        }
    }

    Stack {
        if (index == null) {
            ScreenHeader("Inbox")
            VGap()
            Panel("Reading") { Spinner("Reading your vault…") }
            return@Stack
        }
        when (mode) {
            InboxMode.List -> {
                val actions = listOf(
                    ActionItem("Enter", "Review", queue.isNotEmpty()),
                    ActionItem("p", "Process all", pending.isNotEmpty(), Palette.Accent),
                    ActionItem("/", "Filter"),
                )
                ScreenHeader("Inbox", "Notes waiting in “$inbox”: AI proposes a title, folder, tags and links; you decide")
                VGap()
                Panel("Inbox · ${queue.size} note${if (queue.size == 1) "" else "s"}") {
                    if (queue.isEmpty()) {
                        Label("✓ Inbox zero.", color = Palette.Green, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                        Hint("Capture a thought with Ctrl+N, or put new notes in the “$inbox” folder, and they appear here.")
                    } else {
                        Picker(
                            queue, picker,
                            label = { it.path },
                            rows = outputRows(fixed = 3 + 2 + 1, actions = actions),
                            icon = { if (outcome[it.path]?.startsWith("filed") == true) "✓" else if (outcome[it.path] == "skipped") "⊘" else if (results[it.path] is Analysis.Ready) "●" else "▣" },
                            iconColor = { if (outcome[it.path]?.startsWith("filed") == true) Palette.Green else if (outcome[it.path] == "skipped") Palette.Dim else Palette.Orange },
                            meta = { outcome[it.path] ?: if (results[it.path] is Analysis.Ready) "proposal ready" else relativeTime(it.modified) },
                            metaColor = { Palette.Muted },
                        )
                    }
                }
                ActionBar(actions)
            }
            InboxMode.Review -> {
                val note = current
                if (note == null) {
                    ScreenHeader("Inbox", "Nothing to review")
                    return@Stack
                }
                val position = outcome.size + 1
                val total = outcome.size + pending.size
                val backlinks = index.backlinksOf(note.path)
                val actions = listOf(
                    ActionItem("Enter", if (analysis is Analysis.Failed) "Try again" else "Apply", analysis != null),
                    ActionItem("Ctrl+X", "Skip"),
                    ActionItem("Tab", "Next field", analysis is Analysis.Ready),
                    ActionItem("Esc", "Back", accent = Palette.Cta),
                )
                ScreenHeader("Inbox › ${note.title}", "${note.path} · note $position of $total")
                VGap()
                val excerptRows = (LocalContentHeight.current - 3 - 1 - 2 - (FieldCount + 5) - actionBarRows(actions)).coerceIn(2, 8)
                Panel("Original") {
                    val body = remember(note.path) { runCatching { Vault.splitFrontmatter(vault.read(note.path)).second }.getOrDefault("").take(1500) }
                    val width = outputWidth()
                    val lines = remember(body, width) { renderMarkdown(parseMarkdown(body), width, rich = app.config.renderMarkdown, plainGlyphs = app.config.plainGlyphs) }
                    // only as many rows as the note has text, up to what fits
                    ScrollView(lines, lines.size.coerceIn(1, excerptRows), remember { OutputState() })
                }
                VGap()
                Panel("Proposal", accent = Palette.Cta) {
                    when (analysis) {
                        null -> Spinner(if (analyzing == note.path) "Gemma is reading the note…" else "Waiting for the model…")
                        is Analysis.Failed -> Warning(analysis.message)
                        is Analysis.Ready -> {
                            val labels = listOf("Title", "Folder", "Tags", "Links", "Summary")
                            val hints = listOf("", "empty = stay here", "separate with spaces", "[[Note]] names", "")
                            labels.forEachIndexed { i, label ->
                                val lockedTitle = i == 0 && backlinks.isNotEmpty()
                                InlineField(label, focus == i, labelWidth = 9) {
                                    TextValue(fields[i], focus == i && !lockedTitle, hints[i])
                                }
                            }
                            if (backlinks.isNotEmpty()) Hint("${backlinks.size} note${if (backlinks.size == 1) "" else "s"} link here by name, so the title stays. It can still be moved.")
                            else index.notes.map { it.folder }.filter { it.isNotEmpty() && !it.startsWith(".") && !it.equals(inbox.trim('/'), ignoreCase = true) }.distinct().sorted().take(8).let { f ->
                                if (f.isNotEmpty()) Label("Folders: " + f.joinToString(" · "), color = Palette.Dim)
                            }
                        }
                    }
                }
                ActionBar(actions)
            }
        }
    }
}
