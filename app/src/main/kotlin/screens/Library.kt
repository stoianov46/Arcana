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
import com.arcana.brain.app.input.TextFieldState
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

private enum class LibraryMode { List, Reading, ConfirmDelete, Rename }

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
    val renameField = remember { TextFieldState() }
    // A note the dashboard asked us to show: selected once, as soon as the index is there.
    var startNote by remember { mutableStateOf(navigator.libraryNote.also { navigator.libraryNote = null }) }

    val inbox = app.config.inboxFolder
    val staleDays = app.config.staleDays
    val showingTags = filter == LibraryFilter.Tags && tag == null
    val shown = if (index == null) emptyList() else notesFor(index, filter, tag, inbox, staleDays)
    val tags = index?.tagCounts.orEmpty()
    // The notes as a tree of folders (fold with Enter, h, l) unless the user chose the flat list, is typing a filter (matches are listed with their paths) or is in the tag list.
    val tree = app.config.libraryTree && !showingTags && picker.filter.isEmpty()
    val expanded = app.libraryExpanded
    val rows: List<LibraryRow> = if (tree) libraryRows(shown, expanded) else shown.map { LibraryRow.Note(it, 0) }
    val labelOf: (LibraryRow) -> String = { row ->
        when (row) {
            is LibraryRow.Folder -> row.name + "/"
            is LibraryRow.Note -> if (tree) row.info.title else row.info.path
        }
    }

    // Select the note the dashboard pointed at; if the view does not contain it, show everything.
    val wanted = startNote
    if (wanted != null && index != null) {
        val i = rows.indexOfFirst { it is LibraryRow.Note && it.info.path == wanted }
        if (i >= 0) {
            picker.index = i
            startNote = null
        } else if (tree && shown.any { it.path == wanted } && ancestorsOf(wanted).any { it !in expanded }) {
            app.libraryExpanded = expanded + ancestorsOf(wanted) // unfold the folders above it; it is found on the next pass
        } else if (filter != LibraryFilter.All) {
            filter = LibraryFilter.All
        } else {
            startNote = null
        }
    }

    val currentRow = if (showingTags) null else picker.selected(rows, labelOf)
    val current = (currentRow as? LibraryRow.Note)?.info
    val folder = currentRow as? LibraryRow.Folder
    val wide = LocalContentWidth.current >= 96

    FocusMode(mode == LibraryMode.Rename || (mode == LibraryMode.List && (if (showingTags) tagPicker.filtering else picker.filtering)))
    when (mode) {
        LibraryMode.List -> if (picker.filtering || tagPicker.filtering) Hints("↑↓  move", "Enter  choose", "Esc  clear filter")
        else if (tree) Hints("↑↓  move", "Enter  open / fold", "h l  fold", "+ -  all", "t  flat list", "←→  view", "a  AI", "r  rename", "d  trash", "/  filter")
        else Hints("↑↓  move", "←→  view", "Enter  read", "a  AI", "r  rename", "o  Obsidian", "d  trash", "t  tree", "/  filter")
        LibraryMode.Reading -> Hints("↑↓/Space  scroll", "a  AI", "r  rename", "o  Obsidian", "Esc  back")
        LibraryMode.Rename -> Hints("Enter  rename", "Esc  cancel")
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

    /** Continues with [note] as a chat: a fresh conversation with the note attached, so the model has read it (a saved chat note carries its whole transcript). */
    fun chatAbout(note: NoteInfo) {
        app.chat.startNew()
        app.chat.attachments.notes += note.path
        navigator.navigate(Screen.Chat)
    }

    fun startRename(note: NoteInfo) {
        renameField.set(note.title)
        mode = LibraryMode.Rename
    }

    fun renameNote(note: NoteInfo) {
        val v = vault.getOrNull() ?: return
        val name = renameField.text.trim()
        if (name.isEmpty()) return navigator.showToast("Type the new name.", isError = true)
        if (name == note.title) { mode = LibraryMode.List; return }
        runCatching { v.rename(note.path, name) }
            .onSuccess { result ->
                app.session.log(SessionKind.Renamed, "${note.path} → ${result.path}" + if (result.linksUpdated > 0) " (${result.linksUpdated} links in ${result.notesChanged} notes updated)" else "", result.path)
                app.brain.changed()
                mode = LibraryMode.List
                navigator.libraryNote = result.path
                navigator.showToast("Renamed to ${result.path}" + if (result.linksUpdated > 0) " · ${result.linksUpdated} link${if (result.linksUpdated == 1) "" else "s"} in ${result.notesChanged} note${if (result.notesChanged == 1) "" else "s"} updated" else "")
            }
            .onFailure { navigator.showToast(it.message ?: "Could not rename.", isError = true) }
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

    fun setFolder(path: String, open: Boolean) {
        app.libraryExpanded = if (open) app.libraryExpanded + path else app.libraryExpanded - path
    }

    /** h: fold the folder you are on; on a folded folder or a note, jump to the folder above. */
    fun foldOrParent() {
        if (folder != null && folder.expanded) return setFolder(folder.path, false)
        val parent = if (folder != null) folder.path.substringBeforeLast('/', "") else current?.folder.orEmpty()
        if (parent.isEmpty()) return
        val at = rows.indexOfFirst { it is LibraryRow.Folder && it.path == parent }
        if (at >= 0) picker.index = at
    }

    /** l: unfold the folder you are on; on an unfolded one, step into it. */
    fun unfoldOrEnter() {
        if (folder == null) return
        if (!folder.expanded) setFolder(folder.path, true) else if (picker.index < rows.lastIndex) picker.index += 1
    }

    KeyHandler { key ->
        when (mode) {
            LibraryMode.ConfirmDelete -> {
                if (key.char == 'y' || key.char == 'Y') current?.let { deleteNote(it) }
                mode = LibraryMode.List
                true
            }
            LibraryMode.Rename -> when {
                key == Key.Escape -> { mode = LibraryMode.List; true }
                key == Key.Enter -> { current?.let { renameNote(it) }; true }
                else -> renameField.onKey(key)
            }
            LibraryMode.Reading -> when {
                preview.handle(key) -> true
                key == Key.Escape || key == Key.Enter || key == Key.Left -> { mode = LibraryMode.List; true }
                key.char == 'a' -> { current?.let { askAi(it) }; true }
                key.char == 'c' -> { current?.let { chatAbout(it) }; true }
                key.char == 'r' -> { current?.let { startRename(it) }; true }
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
                picker.handle(key, rows, label = labelOf) -> true
                tree && folder != null && (key == Key.Enter || key.char == ' ') -> { setFolder(folder.path, !folder.expanded); true }
                tree && key.char == 'l' -> { unfoldOrEnter(); true }
                tree && key.char == 'h' -> { foldOrParent(); true }
                tree && key.char == '+' -> { app.libraryExpanded = allFolders(shown); true }
                tree && key.char == '-' -> { app.libraryExpanded = emptySet(); picker.index = 0; true }
                key.char == 't' -> { app.update(app.config.copy(libraryTree = !app.config.libraryTree)); picker.index = 0; true }
                key == Key.Left || key == Key.Right -> {
                    filter = cycle(filter, if (key == Key.Right) 1 else -1)
                    tag = null
                    picker.reset()
                    true
                }
                key == Key.Enter -> { if (current != null) { preview.reset(); mode = LibraryMode.Reading }; true }
                key == Key.Escape -> if (tag != null) { tag = null; picker.reset(); true } else false
                key.char == 'a' -> { current?.let { askAi(it) }; true }
                key.char == 'c' -> { current?.let { chatAbout(it) }; true }
                key.char == 'r' -> { current?.let { startRename(it) }; true }
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
        ScreenHeader("Library", if (tag != null) "Notes tagged #$tag · Esc goes back to the tags" else if (tree) "Your notes in their folders (Enter folds a folder). ←/→ switches the view" else "Every note, and the ones that need attention. ←/→ switches the view")
        VGap()
        TabBar(tabs, LibraryFilter.entries.indexOf(filter))
        VGap()
        if (index == null) {
            Panel("Reading") { Spinner("Reading your vault…") }
            return@Stack
        }
        val actions = when (mode) {
            LibraryMode.List -> listOf(
                ActionItem("Enter", if (folder != null) (if (folder.expanded) "Fold" else "Unfold") else "Read", current != null || folder != null),
                ActionItem("a", "AI on this note", current != null),
                ActionItem("c", "Chat about it", current != null),
                ActionItem("r", "Rename", current != null),
                ActionItem("o", "Open in Obsidian", current != null),
                ActionItem("d", "Trash", current != null, Palette.Red),
                *(if (filter == LibraryFilter.Inbox) arrayOf(ActionItem("p", "Process inbox with AI", accent = Palette.Cta)) else emptyArray()),
                *(if (tree) arrayOf(ActionItem("h l", "Fold"), ActionItem("+ -", "All")) else emptyArray()),
                *(if (!showingTags) arrayOf(ActionItem("t", if (app.config.libraryTree) "Flat list" else "Tree")) else emptyArray()),
                ActionItem("←→", "View"),
                ActionItem("/", "Filter"),
            )
            LibraryMode.Reading -> listOf(ActionItem("↑↓", "Scroll"), ActionItem("a", "AI on this note"), ActionItem("c", "Chat about it"), ActionItem("r", "Rename"), ActionItem("o", "Open in Obsidian"), ActionItem("Esc", "Back", accent = Palette.Cta))
            LibraryMode.ConfirmDelete -> listOf(ActionItem("y", "Move to trash", accent = Palette.Red), ActionItem("any other key", "Keep", accent = Palette.Cta))
            LibraryMode.Rename -> listOf(ActionItem("Enter", "Rename", accent = Palette.Cta), ActionItem("Esc", "Cancel"))
        }
        // header 3 + tabs and gaps 3 + the panel frame 2 + the filter strip 1 over the list
        val listRows = outputRows(fixed = 3 + 3 + 2 + 1, actions = actions)
        val previewRows = outputRows(fixed = 3 + 3 + 2, actions = actions)
        val width = LocalContentWidth.current

        when {
            mode == LibraryMode.Rename && current != null -> Panel("Rename note", accent = Palette.Cta) {
                val folder = current.path.substringBeforeLast('/', "")
                Label("${if (folder.isEmpty()) "Vault root" else "$folder/"}  ·  ${current.path.substringAfterLast('/')}", color = Palette.Muted)
                TextInput("New name", renameField, true, "Type the new name")
                Hint("The note stays in its folder. Links to it in your other notes ([[${current.title}]]) are updated. Rename it back to undo.")
            }
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
                                NoteList(showingTags, tags, tagPicker, shown, rows, labelOf, picker, listRows, filter)
                            }
                        }
                    }
                    HGap()
                    Box(Modifier.weight(0.58f)) {
                        CompositionLocalProvider(LocalContentWidth provides right) {
                            Panel(current?.title ?: "Preview", Modifier.height(listRows + 3), accent = if (mode == LibraryMode.Reading) Palette.Accent else Palette.Dim) {
                                if (folder != null) FolderSummary(folder) else if (current == null) Hint("Choose a note to see its links and content.") else NotePreview(app, vault.getOrNull(), index, current, preview, previewRows)
                            }
                        }
                    }
                }
            }
            else -> Panel(if (showingTags) "Tags" else "Notes · ${shown.size}") { NoteList(showingTags, tags, tagPicker, shown, rows, labelOf, picker, listRows, filter) }
        }
        ActionBar(actions)
    }
}

private fun cycle(f: LibraryFilter, by: Int): LibraryFilter = LibraryFilter.entries[(f.ordinal + by).mod(LibraryFilter.entries.size)]

@Composable
private fun NoteList(
    showingTags: Boolean, tags: List<Pair<String, Int>>, tagPicker: PickerState,
    shown: List<NoteInfo>, rows: List<LibraryRow>, labelOf: (LibraryRow) -> String, picker: PickerState, rowCount: Int, filter: LibraryFilter,
) {
    if (showingTags) {
        Picker(tags, tagPicker, label = { it.first }, rows = rowCount, icon = { "#" }, iconColor = { Palette.Blue }, meta = { "${it.second} note${if (it.second == 1) "" else "s"}" }, emptyText = "No tags yet. Add #tags or a tags: line to your notes.", placeholder = "Press / to filter tags")
    } else {
        Picker(
            rows, picker,
            label = labelOf,
            rows = rowCount,
            // a folder is a cyan ▸ (folded) or ▾ (unfolded) with its name; a note a purple ▤; folders and notes sit two spaces further in per level
            icon = { row -> if (row is LibraryRow.Folder) (if (row.expanded) "▾" else "▸") else "▤" },
            iconColor = { row -> if (row is LibraryRow.Folder) Palette.Cta else Palette.Accent },
            labelColor = { row -> if (row is LibraryRow.Folder) Palette.Cta else Palette.Text },
            indent = { it.depth },
            meta = { row ->
                when (row) {
                    is LibraryRow.Folder -> "${row.notes} note${if (row.notes == 1) "" else "s"}"
                    is LibraryRow.Note -> when (filter) {
                        LibraryFilter.Broken -> "${row.info.unresolved.size} broken"
                        else -> relativeTime(row.info.modified)
                    }
                }
            },
            total = shown.size,
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

/** What the preview pane shows while a folder is highlighted: where it is and what is in it. */
@Composable
private fun FolderSummary(folder: LibraryRow.Folder) {
    Label("▸ ${folder.path}/", color = Palette.Cta, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
    Label("${folder.notes} note${if (folder.notes == 1) "" else "s"}" + if (folder.folders > 0) " in ${folder.folders} subfolder${if (folder.folders == 1) "" else "s"}" else "", color = Palette.Muted)
    VGap()
    Hint(if (folder.expanded) "Enter or h folds it, l steps into it. + unfolds every folder, - folds them all, t switches to a flat list." else "Enter or l unfolds it. + unfolds every folder, - folds them all, t switches to a flat list.")
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
