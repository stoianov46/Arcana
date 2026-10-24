package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.SystemOpen
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LibraryFilter
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.NoteInfo
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.Vault
import com.arcana.brain.core.VaultIndex
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Row

private enum class LibraryMode { List, Reading, ConfirmDelete }

/** Notes in view [filter]; [tag] narrows the Tags view to one tag. */
private fun notesFor(index: VaultIndex, filter: LibraryFilter, tag: String?, inbox: String, staleDays: Int): List<NoteInfo> = when (filter) {
    LibraryFilter.All -> index.notes.sortedByDescending { it.modified }
    LibraryFilter.Recent -> index.touchedSince(14)
    LibraryFilter.Inbox -> index.inFolder(inbox).sortedByDescending { it.modified }
    LibraryFilter.Orphans -> index.orphans.sortedBy { it.title.lowercase() }
    LibraryFilter.Untagged -> index.untagged.sortedByDescending { it.modified }
    LibraryFilter.Broken -> index.notes.filter { it.unresolved.isNotEmpty() }.sortedBy { it.title.lowercase() }
    LibraryFilter.Stale -> index.stale(staleDays).sortedBy { it.modified }
    LibraryFilter.Tags -> if (tag == null) emptyList() else index.notes.filter { tag in it.tags }.sortedByDescending { it.modified }
}

@Composable
fun LibraryScreen(app: AppContext) {
    val navigator = LocalNavigator.current
    val index = app.brain.index
    val vault = remember(app.config.vaultPath) { app.openVault() }
    var filter by remember { mutableStateOf(navigator.libraryFilter) }
    var tag by remember { mutableStateOf(navigator.libraryTag.also { navigator.libraryTag = null }) }
    var mode by remember { mutableStateOf(LibraryMode.List) }
    val picker = remember { PickerState() }
    val tagPicker = remember { PickerState() }
    val preview = remember { OutputState() }
    // A note the dashboard asked us to show: selected once, as soon as the index is there.
    var startNote by remember { mutableStateOf(navigator.libraryNote.also { navigator.libraryNote = null }) }

    val inbox = app.config.inboxFolder
    val staleDays = app.config.staleDays
    val showingTags = filter == LibraryFilter.Tags && tag == null
    val shown = if (index == null) emptyList() else notesFor(index, filter, tag, inbox, staleDays)
    val tags = index?.tagCounts.orEmpty()

    // Select the note the dashboard pointed at; if the view does not contain it, show everything.
    val wanted = startNote
    if (wanted != null && index != null) {
        val i = shown.indexOfFirst { it.path == wanted }
        if (i >= 0) {
            picker.index = i
            startNote = null
        } else if (filter != LibraryFilter.All) {
            filter = LibraryFilter.All
        } else {
            startNote = null
        }
    }

    val current = if (showingTags) null else picker.selected(shown) { it.path }
    val wide = LocalContentWidth.current >= 96

    FocusMode((mode == LibraryMode.List && (if (showingTags) tagPicker.filtering else picker.filtering)))
    when (mode) {
        LibraryMode.List -> if (picker.filtering || tagPicker.filtering) Hints("↑↓  move", "Enter  choose", "Esc  clear filter")
        else Hints("↑↓  move", "←→  view", "Enter  read", "a  AI", "o  Obsidian", "d  trash", "/  filter")
        LibraryMode.Reading -> Hints("↑↓/Space  scroll", "a  AI", "o  Obsidian", "Esc  back")
        LibraryMode.ConfirmDelete -> Hints("y  move to trash", "any other key  keep")
    }

    fun openInObsidian(note: NoteInfo) {
        val root = vault.getOrNull()?.root?.fileName?.toString() ?: return
        navigator.showToast(if (SystemOpen.open(SystemOpen.obsidianLink(root, note.path))) "Opening ${note.title} in Obsidian…" else "Could not start Obsidian.", isError = false)
    }

    fun askAi(note: NoteInfo) {
        navigator.noteRequest = note.path
        navigator.navigate(Screen.Compose)
    }

    fun deleteNote(note: NoteInfo) {
        val v = vault.getOrNull() ?: return
        runCatching { v.trash(note.path) }
            .onSuccess { moved ->
                app.session.log(SessionKind.Deleted, "${note.title} (moved to $moved)", note.path)
                app.brain.changed()
                navigator.showToast("Moved ${note.path} to the trash")
            }
            .onFailure { navigator.showToast(it.message ?: "Could not delete.", isError = true) }
    }

    KeyHandler { key ->
        when (mode) {
            LibraryMode.ConfirmDelete -> {
                if (key.char == 'y' || key.char == 'Y') current?.let { deleteNote(it) }
                mode = LibraryMode.List
                true
            }
            LibraryMode.Reading -> when {
                preview.handle(key) -> true
                key == Key.Escape || key == Key.Enter || key == Key.Left -> { mode = LibraryMode.List; true }
                key.char == 'a' -> { current?.let { askAi(it) }; true }
                key.char == 'o' -> { current?.let { openInObsidian(it) }; true }
                else -> false
            }
            LibraryMode.List -> when {
                showingTags -> when {
                    tagPicker.handle(key, tags) { it.first } -> true
                    key == Key.Enter -> { tagPicker.selected(tags) { it.first }?.let { tag = it.first; picker.reset() }; true }
                    key == Key.Left || key == Key.Right -> { filter = cycle(filter, if (key == Key.Right) 1 else -1); true }
                    else -> false
                }
                picker.handle(key, shown) { it.path } -> true
                key == Key.Left || key == Key.Right -> {
                    filter = cycle(filter, if (key == Key.Right) 1 else -1)
                    tag = null
                    picker.reset()
                    true
                }
                key == Key.Enter -> { if (current != null) { preview.reset(); mode = LibraryMode.Reading }; true }
                key == Key.Escape -> if (tag != null) { tag = null; picker.reset(); true } else false
                key.char == 'a' -> { current?.let { askAi(it) }; true }
                key.char == 'o' -> { current?.let { openInObsidian(it) }; true }
                key.char == 'd' -> { if (current != null) mode = LibraryMode.ConfirmDelete; true }
                key.char == 'p' && filter == LibraryFilter.Inbox -> { navigator.navigate(Screen.Inbox); true }
                else -> false
            }
        }
    }

    val health = remember(index, inbox, staleDays) { index?.health(inbox, staleDays) }
    val tabs = LibraryFilter.entries.map { f ->
        val n = when (f) {
            LibraryFilter.All -> health?.notes
            LibraryFilter.Recent -> index?.touchedSince(14)?.size
            LibraryFilter.Inbox -> health?.inbox
            LibraryFilter.Orphans -> health?.orphans
            LibraryFilter.Untagged -> health?.untagged
            LibraryFilter.Broken -> health?.brokenNotes
            LibraryFilter.Stale -> health?.stale
            LibraryFilter.Tags -> health?.tags
        }
        if (n == null) f.title else "${f.title} $n"
    }

    Stack {
        if (vault.isFailure) {
            ScreenHeader("Library")
            VGap()
            Panel("Vault needed", accent = Palette.Warn) {
                Warning(vault.exceptionOrNull()?.message ?: "No vault.")
                Cta("Press ${Screen.Settings.shortcut} to choose your vault folder.")
            }
            return@Stack
        }
        ScreenHeader("Library", if (tag != null) "Notes tagged #$tag · Esc goes back to the tags" else "Every note, and the ones that need attention. ←/→ switches the view")
        VGap()
        TabBar(tabs, LibraryFilter.entries.indexOf(filter))
        VGap()
        if (index == null) {
            Panel("Reading") { Spinner("Reading your vault…") }
            return@Stack
        }
        val actions = when (mode) {
            LibraryMode.List -> listOf(
                ActionItem("Enter", "Read", current != null),
                ActionItem("a", "AI on this note", current != null),
                ActionItem("o", "Open in Obsidian", current != null),
                ActionItem("d", "Trash", current != null, Palette.Red),
                *(if (filter == LibraryFilter.Inbox) arrayOf(ActionItem("p", "Process inbox with AI", accent = Palette.Cta)) else emptyArray()),
                ActionItem("←→", "View"),
                ActionItem("/", "Filter"),
            )
            LibraryMode.Reading -> listOf(ActionItem("↑↓", "Scroll"), ActionItem("a", "AI on this note"), ActionItem("o", "Open in Obsidian"), ActionItem("Esc", "Back", accent = Palette.Cta))
            LibraryMode.ConfirmDelete -> listOf(ActionItem("y", "Move to trash", accent = Palette.Red), ActionItem("any other key", "Keep", accent = Palette.Cta))
        }
        // header 3 + tabs and gaps 3 + the panel frame 2 + the filter strip 1 over the list
        val listRows = outputRows(fixed = 3 + 3 + 2 + 1, actions = actions)
        val previewRows = outputRows(fixed = 3 + 3 + 2, actions = actions)
        val width = LocalContentWidth.current

        when {
            mode == LibraryMode.ConfirmDelete -> Panel("Move to trash?", accent = Palette.Red) {
                Warning("“${current?.title}” is moved to the vault's .trash folder. You can restore it from there or from Obsidian.")
            }
            mode == LibraryMode.Reading && current != null && !wide -> Panel(current.title) { NotePreview(app, vault.getOrNull(), index, current, preview, previewRows) }
            wide -> {
                val left = ((width - 1) * 42 / 100).coerceAtLeast(30)
                val right = (width - 1 - left).coerceAtLeast(30)
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(0.42f)) {
                        CompositionLocalProvider(LocalContentWidth provides left) {
                            Panel(if (showingTags) "Tags" else "Notes · ${shown.size}", Modifier.height(listRows + 3), accent = if (mode == LibraryMode.List) Palette.Accent else Palette.Dim) {
                                NoteList(showingTags, tags, tagPicker, shown, picker, listRows, filter)
                            }
                        }
                    }
                    HGap()
                    Box(Modifier.weight(0.58f)) {
                        CompositionLocalProvider(LocalContentWidth provides right) {
                            Panel(current?.title ?: "Preview", Modifier.height(listRows + 3), accent = if (mode == LibraryMode.Reading) Palette.Accent else Palette.Dim) {
                                if (current == null) Hint("Choose a note to see its links and content.") else NotePreview(app, vault.getOrNull(), index, current, preview, previewRows)
                            }
                        }
                    }
                }
            }
            else -> Panel(if (showingTags) "Tags" else "Notes · ${shown.size}") { NoteList(showingTags, tags, tagPicker, shown, picker, listRows, filter) }
        }
        ActionBar(actions)
    }
}

private fun cycle(f: LibraryFilter, by: Int): LibraryFilter = LibraryFilter.entries[(f.ordinal + by).mod(LibraryFilter.entries.size)]

@Composable
private fun NoteList(showingTags: Boolean, tags: List<Pair<String, Int>>, tagPicker: PickerState, shown: List<NoteInfo>, picker: PickerState, rows: Int, filter: LibraryFilter) {
    if (showingTags) {
        Picker(tags, tagPicker, label = { it.first }, rows = rows, icon = { "#" }, iconColor = { Palette.Blue }, meta = { "${it.second} note${if (it.second == 1) "" else "s"}" }, emptyText = "No tags yet. Add #tags or a tags: line to your notes.", placeholder = "Press / to filter tags")
    } else {
        Picker(
            shown, picker,
            label = { it.path },
            rows = rows,
            icon = { "▤" },
            iconColor = { Palette.Accent },
            meta = {
                when (filter) {
                    LibraryFilter.Stale -> relativeTime(it.modified)
                    LibraryFilter.Broken -> "${it.unresolved.size} broken"
                    else -> relativeTime(it.modified)
                }
            },
            emptyText = when (filter) {
                LibraryFilter.Inbox -> "The inbox is empty."
                LibraryFilter.Orphans -> "Every note is connected."
                LibraryFilter.Untagged -> "Every note has a tag."
                LibraryFilter.Broken -> "No broken links."
                LibraryFilter.Stale -> "Nothing has been forgotten."
                else -> "No notes here yet."
            },
        )
    }
}

/** A note laid out for reading: where it sits in the web of notes, then its text. */
@Composable
private fun NotePreview(app: AppContext, vault: Vault?, index: VaultIndex, note: NoteInfo, output: OutputState, rows: Int) {
    val width = outputWidth()
    val config = app.config
    val lines = remember(note.path, note.modified, width, config.renderMarkdown, config.plainGlyphs, Palette.themeId, index) {
        val out = ArrayList<Line>()
        out += Line(note.path, Palette.Muted)
        out += Line("${note.words} words · changed ${relativeTime(note.modified)}", Palette.Muted)
        if (note.tags.isNotEmpty()) wrap(note.tags.sorted().joinToString("  ") { "#$it" }, width).forEach { out += Line(it, Palette.Blue) }
        fun names(label: String, notes: List<NoteInfo>, color: com.jakewharton.mosaic.ui.Color) {
            if (notes.isNotEmpty()) wrap("$label (${notes.size}): " + notes.take(12).joinToString(", ") { it.title } + if (notes.size > 12) ", …" else "", width).forEach { out += Line(it, color) }
        }
        names("→ links to", index.linksOf(note.path), Palette.Text)
        names("← linked from", index.backlinksOf(note.path), Palette.Text)
        names("≈ related", index.related(note.path, 6), Palette.Muted)
        if (note.unresolved.isNotEmpty()) wrap("✕ broken: " + note.unresolved.joinToString(", "), width).forEach { out += Line(it, Palette.Red) }
        out += Line("─".repeat(width.coerceAtMost(60)), Palette.Dim)
        val body = vault?.let { v -> runCatching { Vault.splitFrontmatter(v.read(note.path)).second }.getOrNull() }
        out += if (body == null) listOf(Line("The note could not be read.", Palette.Red))
        else renderMarkdown(parseMarkdown(body), width, rich = config.renderMarkdown, plainGlyphs = config.plainGlyphs)
        out
    }
    ScrollView(lines, rows, output)
}
