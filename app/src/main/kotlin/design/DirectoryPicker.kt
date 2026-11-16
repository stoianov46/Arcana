package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.char
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** One row of the folder browser: the way up, or a sub-folder. */
data class DirEntry(val path: Path, val name: String, val isParent: Boolean = false, val isVault: Boolean = false)

/**
 * A folder browser built on [Picker]: Enter/→ opens a folder, ←/Backspace goes up, Space picks the folder being shown,
 * `.` toggles hidden folders, `~` jumps home, `/` filters, Esc cancels. Folders that hold an `.obsidian` directory are
 * marked as vaults.
 *
 * `f` searches ALL the folders below the one being shown (down to [SEARCH_DEPTH] levels, found in the background), listed by their path
 * from here; typing narrows them (`~` then `f` searches the whole home folder). Enter opens a found folder, Space picks the highlighted one, Esc goes back.
 * [onClear] (key `x`, labelled [clearLabel]) and [onType] (key `e`) are offered only when given: a setting that may be empty, and one that also accepts a pasted path.
 */
class DirectoryPickerState(
    start: Path,
    private val onChoose: (Path) -> Unit,
    private val onCancel: () -> Unit,
    private val onClear: (() -> Unit)? = null,
    private val clearLabel: String = "Clear",
    private val onType: (() -> Unit)? = null,
) {
    val picker = PickerState()
    var dir: Path by mutableStateOf(start.toAbsolutePath().normalize())
        private set
    var showHidden by mutableStateOf(false)
        private set
    var entries by mutableStateOf<List<DirEntry>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** True while the filter has the keyboard, so single-key shortcuts must stay off. */
    val filtering: Boolean get() = picker.filtering

    val isVault: Boolean get() = Files.isDirectory(dir.resolve(".obsidian"))
    val hasClear: Boolean get() = onClear != null
    val clearText: String get() = clearLabel
    val hasType: Boolean get() = onType != null

    /** True while the list is the result of a search below [dir] (key `f`) instead of the folder's own sub-folders. */
    var searching by mutableStateOf(false)
        private set

    /** True while the background search is still walking folders: more may appear. */
    var scanning by mutableStateOf(false)
        private set
    private var scanId = 0

    init {
        open(dir)
    }

    fun open(target: Path) {
        cancelSearch()
        val path = target.toAbsolutePath().normalize()
        val listed = runCatching {
            Files.list(path).use { stream ->
                stream.filter { Files.isDirectory(it) && (showHidden || !it.name.startsWith(".")) }
                    .map { DirEntry(it, it.name, isVault = Files.isDirectory(it.resolve(".obsidian"))) }
                    .toList()
            }.sortedBy { it.name.lowercase() }
        }
        listed.onFailure {
            error = "Cannot open $path: ${it.message}"
            return
        }
        error = null
        dir = path
        val parent = path.parent
        entries = (if (parent != null) listOf(DirEntry(parent, "..", isParent = true)) else emptyList()) + listed.getOrThrow()
        picker.reset()
    }

    private fun cancelSearch() {
        scanId++
        searching = false
        scanning = false
    }

    /** Lists every folder below [dir] (breadth first, so the nearest come first) from a background thread, publishing the list as it grows. */
    private fun startSearch() {
        val root = dir
        val hidden = showHidden
        val id = ++scanId
        searching = true
        scanning = true
        entries = emptyList()
        picker.reset()
        picker.startFiltering()
        Thread {
            val found = ArrayList<DirEntry>()
            var level: List<Path> = listOf(root)
            var published = 0
            var depth = 0
            while (level.isNotEmpty() && depth < SEARCH_DEPTH && found.size < SEARCH_LIMIT && id == scanId) {
                val next = ArrayList<Path>()
                for (parent in level) {
                    if (id != scanId || found.size >= SEARCH_LIMIT) break
                    val children = runCatching {
                        Files.list(parent).use { stream -> stream.filter { Files.isDirectory(it) && (hidden || !it.name.startsWith(".")) && it.name !in SKIPPED }.toList() }
                    }.getOrNull() ?: continue
                    for (child in children.sortedBy { it.name.lowercase() }) {
                        found.add(DirEntry(child, root.relativize(child).toString(), isVault = Files.isDirectory(child.resolve(".obsidian"))))
                        next.add(child)
                    }
                    if (found.size - published >= 200 && id == scanId) { entries = found.toList(); published = found.size }
                }
                level = next
                depth++
            }
            if (id == scanId) { entries = found.toList(); scanning = false }
        }.apply { isDaemon = true }.start()
    }

    private fun up() {
        dir.parent?.let { open(it) }
    }

    fun handle(key: Key): Boolean {
        if (picker.handle(key, entries) { it.name }) return true
        when {
            key == Key.Enter || key == Key.Right -> picker.selected(entries) { it.name }?.let { open(it.path) }
            key == Key.Left || key == Key.Backspace -> if (searching) open(dir) else up()
            key.char == ' ' -> if (searching) picker.selected(entries) { it.name }?.let { onChoose(it.path) } else onChoose(dir)
            key.char == 'f' -> if (!searching) startSearch()
            key.char == '.' -> {
                showHidden = !showHidden
                if (searching) startSearch() else open(dir)
            }
            key.char == '~' -> open(Path.of(System.getProperty("user.home")))
            key.char == 'x' && onClear != null -> onClear.invoke()
            key.char == 'e' && onType != null -> onType.invoke()
            key == Key.Escape -> if (searching) open(dir) else onCancel()
            else -> return false
        }
        return true
    }

    /** The key hints for the screen's hint line, matching what the browser is doing now. */
    fun hints(): List<String> = when {
        filtering -> listOf("↑↓  move", "Enter  open", "Esc  clear filter")
        searching -> listOf("/  type to narrow", "↑↓  move", "Space  use highlighted", "Enter  open", "Esc  back")
        else -> buildList {
            add("Space  use folder"); add("Enter  open"); add("←  up"); add("f  search subfolders"); add("/  filter")
            if (onClear != null) add("x  $clearLabel")
            if (onType != null) add("e  type a path")
        }
    }

    companion object {
        /** The folders left out of a search: they are huge, generated, or never somewhere to work in. */
        private val SKIPPED = setOf("node_modules", "build", "target", "dist", "out", "venv", "__pycache__", "Library", "Pods", ".git", ".gradle", ".Trash")

        /** How many levels below the starting folder a search goes, and how many folders it keeps. */
        const val SEARCH_DEPTH = 5
        const val SEARCH_LIMIT = 20_000

        /** The actions the browser offers, for an [ActionBar]. */
        fun actions(state: DirectoryPickerState) =
            if (state.searching) listOf(
                ActionItem("/", "Type to narrow"),
                ActionItem("Space", "Use highlighted"),
                ActionItem("Enter", "Open"),
                ActionItem("Esc", "Back"),
            ) else buildList {
                add(ActionItem("Space", "Use this folder"))
                add(ActionItem("Enter", "Open"))
                add(ActionItem("←", "Up"))
                add(ActionItem("f", "Search subfolders"))
                add(ActionItem("~", "Home"))
                add(ActionItem(".", if (state.showHidden) "Hide hidden" else "Show hidden"))
                if (state.hasClear) add(ActionItem("x", state.clearText))
                if (state.hasType) add(ActionItem("e", "Type a path"))
            }
    }
}

@Composable
fun DirectoryPicker(state: DirectoryPickerState, rows: Int, modifier: Modifier = Modifier) {
    Stack(modifier) {
        Row {
            Label(if (state.searching) "⌕ " else "▸ ", color = Palette.Cta, textStyle = TextStyle.Bold)
            Label(if (state.searching) "Searching below ${state.dir}" else state.dir.toString(), color = Palette.Cta, textStyle = TextStyle.Bold)
            if (state.searching && state.scanning) {
                HGap()
                Label("scanning…", color = Palette.Muted)
            }
            if (state.isVault && !state.searching) {
                HGap()
                Badge("Obsidian vault", Palette.Green, Palette.GreenBg)
            }
        }
        state.error?.let { Warning(it) }
        Picker(
            items = state.entries,
            state = state.picker,
            label = { it.name },
            rows = rows,
            icon = { if (it.isParent) "↩" else "▣" },
            iconColor = { if (it.isVault) Palette.Green else Palette.Blue },
            meta = { if (it.isVault) "vault" else "" },
            metaColor = { Palette.Green },
            placeholder = if (state.searching) "Type part of a folder name" else "Press / to filter folders, f to search all subfolders",
            emptyText = if (state.searching) (if (state.scanning) "Scanning folders…" else "No sub-folders below here.") else "No sub-folders here. Press Space to use this folder.",
        )
    }
}
