package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.core.AppConfig
import com.arcana.brain.app.SystemOpen
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LibraryFilter
import com.arcana.brain.core.ReportInfo
import com.arcana.brain.core.Reports
import com.arcana.brain.core.Skills
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

private enum class LibraryMode { List, Reading, ConfirmDelete, Rename, Edit, ConfirmDiscard }

/** Notes in view [filter]; [tag] narrows the Tags view to one tag. */
private fun notesFor(index: VaultIndex, filter: LibraryFilter, tag: String?, inbox: String, staleDays: Int, sort: String = "modified", reports: Map<String, ReportInfo> = emptyMap()): List<NoteInfo> = when (filter) {
    LibraryFilter.All -> byDate(index.notes, sort)
    LibraryFilter.Recent -> index.touchedSince(14)
    // the reports in the order Reports.scan gave them: newest first (the notes are looked up by path, so a report outside the index is not listed)
    LibraryFilter.Reports -> reports.values.mapNotNull { index.note(it.path) }
    LibraryFilter.Inbox -> byDate(index.inFolder(inbox), sort)
    LibraryFilter.Orphans -> index.orphans.sortedBy { it.title.lowercase() }
    LibraryFilter.Untagged -> byDate(index.untagged, sort)
    LibraryFilter.Broken -> index.notes.filter { it.unresolved.isNotEmpty() }.sortedBy { it.title.lowercase() }
    LibraryFilter.Stale -> index.stale(staleDays).sortedBy { it.modified }
    LibraryFilter.Tags -> if (tag == null) emptyList() else byDate(index.notes.filter { tag in it.tags }, sort)
}

/** The lists that are ordered by date: newest first, or by title when Settings → Library → Sort says so. */
private fun byDate(notes: List<NoteInfo>, sort: String): List<NoteInfo> =
    if (sort == "title") notes.sortedBy { it.title.lowercase() } else notes.sortedByDescending { it.modified }

/** The view the Library opens on (Settings → Library → Opens on); All when the setting names none. */
internal fun defaultLibraryFilter(config: AppConfig): LibraryFilter = LibraryFilter.entries.firstOrNull { it.name.equals(config.libraryTab, ignoreCase = true) } ?: LibraryFilter.All

@Composable
fun LibraryScreen(app: AppContext) {
    val navigator = LocalNavigator.current
    val index = app.brain.index
    val vault = remember(app.config.vaultPath) { app.openVault() }
    // What the Library remembers while another tab is open: the view and tag, the selected row and its scroll, the open note and its scroll (the vault path is in the key).
    // The first visit of a run opens the view of Settings → Library → Opens on; a link from the dashboard names its view once; later visits come back to where you left.
    val where = app.config.vaultPath
    val filterState = rememberKept(app, "library.filter@$where") { mutableStateOf(defaultLibraryFilter(app.config)) }
    val tagState = rememberKept(app, "library.tag@$where") { mutableStateOf<String?>(null) }
    val modeState = rememberKept(app, "library.mode@$where") { mutableStateOf(LibraryMode.List) }
    remember {
        val linked = navigator.libraryFilter
        val linkedTag = navigator.libraryTag
        navigator.libraryFilter = null
        navigator.libraryTag = null
        if (linked != null) { filterState.value = linked; tagState.value = linkedTag }
        // a dialog (rename, edit, delete, discard) is never restored: the buffer it held is gone, so the visit goes back to the note being read, or to the list
        if (modeState.value != LibraryMode.List && modeState.value != LibraryMode.Reading) modeState.value = if (modeState.value == LibraryMode.ConfirmDelete) LibraryMode.List else LibraryMode.Reading
    }
    var filter by filterState
    var tag by tagState
    var mode by modeState
    val picker = rememberKept(app, "library.picker@$where") { PickerState() }
    val tagPicker = rememberKept(app, "library.tags@$where") { PickerState() }
    val preview = rememberKept(app, "library.preview@$where") { OutputState() }
    val renameField = remember { TextFieldState() }
    // the note being edited by hand: its whole text (properties included), and what the file held when it was opened
    val editField = remember { TextFieldState(multiline = true, max = 2_000_000) }
    var editOriginal by remember { mutableStateOf("") }
    // A note the dashboard asked us to show: selected once, as soon as the index is there.
    var startNote by remember { mutableStateOf(navigator.libraryNote.also { navigator.libraryNote = null }) }

    val inbox = app.config.inboxFolder
    // the notes with `type: report` (the research skills write them): read once per index, listed in the Reports view and counted on its tab
    val reports = remember(index, vault) { vault.getOrNull()?.let { v -> runCatching { Reports.scan(v) }.getOrDefault(emptyList()) }.orEmpty() }
    val reportByPath = remember(reports) { reports.associateBy { it.path } }
    val staleDays = app.config.staleDays
    val showingTags = filter == LibraryFilter.Tags && tag == null
    // `.` adds the notes of hidden folders (.arcana/…) to the All view; they are not part of the index, so no other view or count includes them
    var showHidden by rememberKept(app, "library.hidden@$where") { mutableStateOf(false) }
    val hidden = remember(showHidden, index, vault) {
        if (!showHidden) emptyList() else vault.getOrNull()?.hiddenNotes().orEmpty().map { n ->
            val words = runCatching { Vault.splitFrontmatter(vault.getOrThrow().read(n.path)).second.split(Regex("\\s+")).count { it.isNotBlank() } }.getOrDefault(0)
            NoteInfo(n.path, n.modified, words, emptySet(), emptySet(), emptySet())
        }
    }
    val shown = if (index == null) emptyList() else notesFor(index, filter, tag, inbox, staleDays, app.config.librarySort, reportByPath).let { if (filter == LibraryFilter.All && hidden.isNotEmpty()) byDate(it + hidden, app.config.librarySort) else it }
    val tags = index?.tagCounts.orEmpty()
    // The notes as a tree of folders (fold with Enter, h, l) unless the user chose the flat list, is typing a filter (matches are listed with their paths) or is in the tag list.
    val tree = app.config.libraryTree && !showingTags && picker.filter.isEmpty() && filter != LibraryFilter.Reports
    val expanded = app.libraryExpanded
    val rows: List<LibraryRow> = if (tree) libraryRows(shown, expanded) else shown.map { LibraryRow.Note(it, 0) }
    val labelOf: (LibraryRow) -> String = { row ->
        when (row) {
            is LibraryRow.Folder -> row.name + "/"
            is LibraryRow.Note -> if (filter == LibraryFilter.Reports) reportLabel(reportByPath[row.info.path], row.info) else if (tree) row.info.title else row.info.path
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
    val wide = LocalContentWidth.current >= 96 && app.config.libraryPreview

    FocusMode(mode == LibraryMode.Rename || mode == LibraryMode.Edit || (mode == LibraryMode.List && (if (showingTags) tagPicker.filtering else picker.filtering)))
    when (mode) {
        LibraryMode.List -> if (picker.filtering || tagPicker.filtering) Hints("↑↓  move", "Enter  choose", "Esc  clear filter")
        else if (tree) Hints("↑↓  move", "Enter  open / fold", "h l  fold", "+ -  all", "t  flat list", "←→  view", "a  AI", "e  edit", "r  rename", "d  trash", ".  hidden", "/  filter")
        else if (filter == LibraryFilter.Reports) Hints("↑↓  move", "←→  view", "Enter  read", "s  run its skill again", "a  AI", "e  edit", "r  rename", "o  Obsidian", "d  trash", "/  filter")
        else Hints("↑↓  move", "←→  view", "Enter  read", "a  AI", "e  edit", "r  rename", "o  Obsidian", "d  trash", "t  tree", ".  hidden", "/  filter")
        LibraryMode.Reading -> Hints("↑↓/Space  scroll", "a  AI", "e  edit", "r  rename", "o  Obsidian", "Esc  back")
        LibraryMode.Rename -> Hints("Enter  rename", "Esc  cancel")
        LibraryMode.ConfirmDelete -> Hints("y  move to trash", "any other key  keep")
        LibraryMode.Edit -> Hints("Ctrl+S  save", "Esc  leave")
        LibraryMode.ConfirmDiscard -> Hints("y  discard the changes", "any other key  keep editing")
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

    /** Runs the skill that made [note] again: a fresh chat with the skill attached and the report's question in the field (the user may change it, then sends). */
    fun runAgain(note: NoteInfo) {
        val report = reportByPath[note.path] ?: return
        val v = vault.getOrNull() ?: return
        if (report.skill.isEmpty()) return navigator.showToast("This report names no skill.", isError = true)
        val skill = Skills.discoverAll(v, app.skillsFolder()).firstOrNull { it.name == report.skill }
            ?: return navigator.showToast("The skill “${report.skill}” is not in ${app.skillsFolder()}/ any more.", isError = true)
        app.chat.startNew()
        app.chat.attachments.notes += skill.path
        app.chat.input.set(report.question)
        navigator.showToast("Skill ${skill.name} attached; the question is in the field. Press Enter to edit it, send it as it is or change it.")
        navigator.navigate(Screen.Chat)
    }

    fun startEdit(note: NoteInfo) {
        val v = vault.getOrNull() ?: return
        runCatching { v.read(note.path) }
            .onSuccess { text -> editField.set(text); editOriginal = text; mode = LibraryMode.Edit }
            .onFailure { navigator.showToast(it.message ?: "Could not read the note.", isError = true) }
    }

    fun saveEdit(note: NoteInfo) {
        val v = vault.getOrNull() ?: return
        if (editField.text == editOriginal) return navigator.showToast("Nothing changed.")
        // the file may have been changed elsewhere (Obsidian) since it was opened: do not overwrite that silently
        if (runCatching { v.read(note.path) }.getOrNull() != editOriginal) return navigator.showToast("The note changed on disk while you edited it. Copy your text, press Esc and open it again.", isError = true)
        runCatching { v.write(note.path, editField.text) }
            .onSuccess {
                editOriginal = editField.text
                app.session.log(SessionKind.Replaced, "${note.title} (edited by hand)", note.path)
                app.brain.changed()
                navigator.libraryNote = note.path
                navigator.showToast("Saved ${note.path}")
            }
            .onFailure { navigator.showToast(it.message ?: "Could not save.", isError = true) }
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
            LibraryMode.ConfirmDiscard -> {
                if (key.char == 'y' || key.char == 'Y') mode = LibraryMode.List else mode = LibraryMode.Edit
                true
            }
            LibraryMode.Edit -> when {
                key == Key.Save -> { current?.let { saveEdit(it) }; true }
                key == Key.Escape -> { if (editField.text == editOriginal) mode = LibraryMode.List else mode = LibraryMode.ConfirmDiscard; true }
                key == Key.Enter -> editField.onKey(Key.PastedNewline)
                else -> editField.onKey(key)
            }
            LibraryMode.Reading -> when {
                preview.handle(key) -> true
                key == Key.Escape || key == Key.Enter || key == Key.Left -> { mode = LibraryMode.List; true }
                key.char == 'a' -> { current?.let { askAi(it) }; true }
                key.char == 'c' -> { current?.let { chatAbout(it) }; true }
                key.char == 'e' -> { current?.let { startEdit(it) }; true }
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
                key.char == '.' -> {
                    showHidden = !showHidden; picker.reset()
                    navigator.showToast(if (showHidden) "Showing hidden folders (the All view)" else "Hidden folders are hidden again", isError = false)
                    true
                }
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
                key.char == 'e' -> { current?.let { startEdit(it) }; true }
                key.char == 'r' -> { current?.let { if (isHiddenPath(it.path)) navigator.showToast("Notes in hidden folders are Arcana's own: rename them in a file manager.", isError = true) else startRename(it) }; true }
                key.char == 'o' -> { current?.let { openInObsidian(it) }; true }
                key.char == 'd' -> { if (current != null) { if (isHiddenPath(current.path)) navigator.showToast("Notes in hidden folders are Arcana's own: they cannot be trashed here.", isError = true) else mode = LibraryMode.ConfirmDelete }; true }
                key.char == 's' && filter == LibraryFilter.Reports -> { current?.let { runAgain(it) }; true }
                key.char == 'p' && filter == LibraryFilter.Inbox -> { navigator.navigate(Screen.Inbox); true }
                else -> false
            }
        }
    }

    val health = remember(index, inbox, staleDays) { index?.health(inbox, staleDays) }
    val listTitle = if (showingTags) "Tags" else if (filter == LibraryFilter.Reports) reportsTitle(reports) else "Notes · ${shown.size}"
    val tabs = LibraryFilter.entries.map { f ->
        val n = when (f) {
            LibraryFilter.All -> health?.notes
            LibraryFilter.Recent -> index?.touchedSince(14)?.size
            LibraryFilter.Reports -> reports.size
            LibraryFilter.Inbox -> health?.inbox
            LibraryFilter.Orphans -> health?.orphans
            LibraryFilter.Untagged -> health?.untagged
            LibraryFilter.Broken -> health?.brokenNotes
            LibraryFilter.Stale -> health?.stale
            LibraryFilter.Tags -> health?.tags
        }
        if (n == null || !app.config.libraryCounts) f.title else "${f.title} $n"
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
                ActionItem("e", "Edit", current != null),
                ActionItem("r", "Rename", current != null),
                ActionItem("o", "Open in Obsidian", current != null),
                ActionItem("d", "Trash", current != null, Palette.Red),
                *(if (filter == LibraryFilter.Inbox) arrayOf(ActionItem("p", "Process inbox with AI", accent = Palette.Cta)) else emptyArray()),
                *(if (tree) arrayOf(ActionItem("h l", "Fold"), ActionItem("+ -", "All")) else emptyArray()),
                *(if (!showingTags) arrayOf(ActionItem("t", if (app.config.libraryTree) "Flat list" else "Tree")) else emptyArray()),
                ActionItem(".", if (showHidden) "Hide hidden" else "Hidden"),
                *(if (filter == LibraryFilter.Reports) arrayOf(ActionItem("s", "Run its skill again", current != null)) else emptyArray()),
                ActionItem("←→", "View"),
                ActionItem("/", "Filter"),
            )
            LibraryMode.Reading -> listOf(ActionItem("↑↓", "Scroll"), ActionItem("a", "AI on this note"), ActionItem("c", "Chat about it"), ActionItem("e", "Edit"), ActionItem("r", "Rename"), ActionItem("o", "Open in Obsidian"), ActionItem("Esc", "Back", accent = Palette.Cta))
            LibraryMode.ConfirmDelete -> listOf(ActionItem("y", "Move to trash", accent = Palette.Red), ActionItem("any other key", "Keep", accent = Palette.Cta))
            LibraryMode.Rename -> listOf(ActionItem("Enter", "Rename", accent = Palette.Cta), ActionItem("Esc", "Cancel"))
            LibraryMode.Edit -> listOf(ActionItem("Ctrl+S", "Save", accent = Palette.Cta), ActionItem("Esc", "Leave"))
            LibraryMode.ConfirmDiscard -> listOf(ActionItem("y", "Discard the changes", accent = Palette.Red), ActionItem("any other key", "Keep editing", accent = Palette.Cta))
        }
        // tabs and gaps 3 + the panel frame 2 + the filter strip 1 over the list
        val listRows = outputRows(fixed = 3 + 2 + 1, actions = actions)
        val previewRows = outputRows(fixed = 3 + 2, actions = actions)
        val width = LocalContentWidth.current

        when {
            mode == LibraryMode.Rename && current != null -> Panel("Rename note", accent = Palette.Cta) {
                val folder = current.path.substringBeforeLast('/', "")
                Label("${if (folder.isEmpty()) "Vault root" else "$folder/"}  ·  ${current.path.substringAfterLast('/')}", color = Palette.Muted)
                TextInput("New name", renameField, true, "Type the new name")
                Hint("The note stays in its folder. Links to it in your other notes ([[${current.title}]]) are updated. Rename it back to undo.")
            }
            (mode == LibraryMode.Edit || mode == LibraryMode.ConfirmDiscard) && current != null -> Panel("Edit · ${current.path}${if (editField.text != editOriginal) "  ·  unsaved" else ""}", accent = if (mode == LibraryMode.ConfirmDiscard) Palette.Red else Palette.Cta) {
                if (mode == LibraryMode.ConfirmDiscard) Warning("Discard what you changed in “${current.title}”? Nothing has been saved.")
                TextValue(editField, mode == LibraryMode.Edit, "", maxRows = (previewRows - 2 - (if (mode == LibraryMode.ConfirmDiscard) 1 else 0)).coerceAtLeast(3))
            }
            mode == LibraryMode.ConfirmDelete -> Panel("Move to trash?", accent = Palette.Red) {
                Warning("“${current?.title}” is moved to the vault's .trash folder. You can restore it from there or from Obsidian.")
            }
            mode == LibraryMode.Reading && current != null && !wide -> Panel(current.title) { NotePreview(app, vault.getOrNull(), index, current, preview, previewRows) }
            wide -> {
                val percent = app.config.libraryListPercent.coerceIn(20, 60)
                val left = ((width - 1) * percent / 100).coerceAtLeast(28)
                val right = (width - 1 - left).coerceAtLeast(30)
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(percent / 100f)) {
                        CompositionLocalProvider(LocalContentWidth provides left) {
                            Panel(listTitle, Modifier.height(listRows + 3), accent = if (mode == LibraryMode.List) Palette.Accent else Palette.Dim) {
                                NoteList(showingTags, tags, tagPicker, shown, rows, labelOf, picker, listRows, filter, reportByPath, app.config.plainGlyphs)
                            }
                        }
                    }
                    HGap()
                    Box(Modifier.weight((100 - percent) / 100f)) {
                        CompositionLocalProvider(LocalContentWidth provides right) {
                            Panel(current?.title ?: "Preview", Modifier.height(listRows + 3), accent = if (mode == LibraryMode.Reading) Palette.Accent else Palette.Dim) {
                                if (folder != null) FolderSummary(folder) else if (current == null) Hint("Choose a note to see its links and content.") else NotePreview(app, vault.getOrNull(), index, current, preview, previewRows)
                            }
                        }
                    }
                }
            }
            else -> Panel(listTitle) { NoteList(showingTags, tags, tagPicker, shown, rows, labelOf, picker, listRows, filter, reportByPath, app.config.plainGlyphs) }
        }
        ActionBar(actions)
    }
}

/** A report's row: date, kind, name without its date prefix, then its summary (the list cuts the end to the width). */
private fun reportLabel(report: ReportInfo?, note: NoteInfo): String {
    if (report == null) return note.title
    val name = note.title.removePrefix(report.date).trim().ifEmpty { note.title }
    return (if (report.date.isEmpty()) "----------" else report.date) + "  " + report.kind.padEnd(9) + "  " + name + (if (report.summary.isEmpty()) "" else "  ·  " + report.summary)
}

/** The status mark of a report row: unfinished first, then aged, then done (a plain-glyph variant for terminals without symbols). */
private fun reportMark(report: ReportInfo?, plain: Boolean): String = when {
    report == null -> "▤"
    Reports.isIncomplete(report) -> if (plain) "!" else "⚠"
    Reports.isStale(report) -> if (plain) "~" else "◷"
    else -> if (plain) "+" else "✓"
}

private fun reportMarkColor(report: ReportInfo?) = when {
    report == null -> Palette.Accent
    Reports.isIncomplete(report) -> Palette.Orange
    Reports.isStale(report) -> Palette.Muted
    else -> Palette.Green
}

/** The right-hand text of a report row: what needs attention, else when the note last changed. */
private fun reportMeta(report: ReportInfo?, note: NoteInfo): String = when {
    report == null -> relativeTime(note.modified)
    Reports.isIncomplete(report) -> "incomplete"
    Reports.isStale(report) -> "stale, ${report.ageDays} d"
    else -> relativeTime(note.modified)
}

/** The list's title in the Reports view: how many, and how many need attention. */
private fun reportsTitle(reports: List<ReportInfo>): String {
    val incomplete = reports.count { Reports.isIncomplete(it) }
    val stale = reports.count { Reports.isStale(it) }
    return "Reports · ${reports.size}" + (if (incomplete > 0) " · $incomplete incomplete" else "") + (if (stale > 0) " · $stale stale" else "")
}

private fun isHiddenPath(path: String): Boolean = path.split('/').any { it.startsWith(".") }

private fun cycle(f: LibraryFilter, by: Int): LibraryFilter = LibraryFilter.entries[(f.ordinal + by).mod(LibraryFilter.entries.size)]

@Composable
private fun NoteList(
    showingTags: Boolean, tags: List<Pair<String, Int>>, tagPicker: PickerState,
    shown: List<NoteInfo>, rows: List<LibraryRow>, labelOf: (LibraryRow) -> String, picker: PickerState, rowCount: Int, filter: LibraryFilter,
    reports: Map<String, ReportInfo>, plain: Boolean,
) {
    if (showingTags) {
        Picker(tags, tagPicker, label = { it.first }, rows = rowCount, icon = { "#" }, iconColor = { Palette.Blue }, meta = { "${it.second} note${if (it.second == 1) "" else "s"}" }, emptyText = "No tags yet. Add #tags or a tags: line to your notes.", placeholder = "Press / to filter tags")
    } else {
        Picker(
            rows, picker,
            label = labelOf,
            rows = rowCount,
            // a folder is a cyan ▸ (folded) or ▾ (unfolded) with its name; a note a purple ▤; folders and notes sit two spaces further in per level
            icon = { row -> if (row is LibraryRow.Folder) (if (row.expanded) "▾" else "▸") else if (filter == LibraryFilter.Reports) reportMark(reports[(row as LibraryRow.Note).info.path], plain) else "▤" },
            iconColor = { row -> if (row is LibraryRow.Folder) Palette.Cta else if (filter == LibraryFilter.Reports) reportMarkColor(reports[(row as LibraryRow.Note).info.path]) else Palette.Accent },
            labelColor = { row -> if (row is LibraryRow.Folder) Palette.Cta else Palette.Text },
            indent = { it.depth },
            meta = { row ->
                when (row) {
                    is LibraryRow.Folder -> "${row.notes} note${if (row.notes == 1) "" else "s"}"
                    is LibraryRow.Note -> when (filter) {
                        LibraryFilter.Broken -> "${row.info.unresolved.size} broken"
                        LibraryFilter.Reports -> reportMeta(reports[row.info.path], row.info)
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
                LibraryFilter.Reports -> "No reports yet. A research skill (for example /create-dev-research-report <question>) writes them to Research/<Domain>/Reports/."
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
        out += Line("─".repeat(width), Palette.Dim)
        val body = vault?.let { v -> runCatching { Vault.splitFrontmatter(v.read(note.path)).second }.getOrNull() }
        out += if (body == null) listOf(Line("The note could not be read.", Palette.Red))
        else renderMarkdown(parseMarkdown(body), width, rich = config.renderMarkdown, plainGlyphs = config.plainGlyphs)
        out
    }
    ScrollView(lines, rows, output)
}
