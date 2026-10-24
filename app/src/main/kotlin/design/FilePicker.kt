package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.char
import com.arcana.brain.core.ImageFiles
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** One row of the file browser: ".." (the parent), a folder, or a file the browser accepts. */
data class FileEntry(val name: String, val path: Path, val isDir: Boolean, val isParent: Boolean = false, val size: Long = 0)

/**
 * A folder browser that returns files, built on [Picker]. Folders are always shown so you can walk to your files; files
 * only when [accepts] says so (for images: PNG, JPEG, WebP).
 *
 * Keys: ↑/↓ move, `/` filters. Enter or → opens a folder; Enter on a file chooses it together with the ticked ones.
 * Space ticks a file (up to [limit]), Tab chooses the ticked files, ←/Backspace go up, `~` home, `.` hidden files, Esc cancels.
 */
class FilePickerState(
    start: Path?,
    private val accepts: (Path) -> Boolean,
    val limit: Int,
    private val onDone: (List<Path>) -> Unit,
    private val onCancel: () -> Unit,
    /** Told which folder the browser was left in, to start there next time. */
    private val onFolder: (Path) -> Unit = {},
) {
    val picker = PickerState()
    var dir: Path by mutableStateOf(Path.of("/"))
        private set
    var showHidden by mutableStateOf(false)
        private set
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var checked by mutableStateOf<List<Path>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** True while the filter has the keyboard, so single-key shortcuts must stay off. */
    val filtering: Boolean get() = picker.filtering

    init {
        val first = listOfNotNull(start, System.getProperty("user.home")?.let { Path.of(it) }, Path.of("/")).first { Files.isDirectory(it) }
        open(first)
    }

    /** ".." first, then folders, then the accepted files; each group by name, ignoring case. Hidden ones only on request. */
    private fun list(target: Path): List<FileEntry> {
        val parent = target.parent?.let { listOf(FileEntry("..", it, isDir = true, isParent = true)) }.orEmpty()
        val children = runCatching { Files.list(target).use { it.toList() } }.getOrDefault(emptyList())
            .filter { showHidden || !it.name.startsWith(".") }
            .mapNotNull { p ->
                when {
                    Files.isDirectory(p) -> FileEntry(p.name, p, isDir = true)
                    Files.isRegularFile(p) && accepts(p) -> FileEntry(p.name, p, isDir = false, size = runCatching { Files.size(p) }.getOrDefault(0))
                    else -> null
                }
            }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return parent + children
    }

    fun open(target: Path, select: Path? = null) {
        val path = target.toAbsolutePath().normalize()
        dir = path
        entries = list(path)
        picker.reset()
        notice = null
        select?.let { s -> picker.index = entries.indexOfFirst { it.path == s }.coerceAtLeast(0) }
    }

    private fun finish(paths: List<Path>) {
        val chosen = paths.distinct()
        if (chosen.size > limit) {
            notice = "You can choose at most $limit file${if (limit == 1) "" else "s"}."
            return
        }
        onFolder(dir)
        onDone(chosen)
    }

    private fun toggle(path: Path) {
        notice = null
        when {
            path in checked -> checked = checked.filter { it != path }
            checked.size >= limit -> notice = "You can choose at most $limit file${if (limit == 1) "" else "s"}."
            else -> checked = checked + listOf(path)
        }
    }

    fun handle(key: Key): Boolean {
        if (picker.handle(key, entries) { it.name }) {
            notice = null
            return true
        }
        val entry = picker.selected(entries) { it.name }
        when {
            key == Key.Escape -> {
                onFolder(dir)
                onCancel()
            }
            key == Key.Left || key == Key.Backspace -> dir.parent?.let { open(it, select = dir) }
            key == Key.Enter || key == Key.Right ->
                when {
                    entry == null -> Unit
                    entry.isDir -> if (entry.isParent) open(entry.path, select = dir) else open(entry.path)
                    key == Key.Enter -> finish(checked + listOf(entry.path))
                }
            key == Key.Tab -> if (checked.isEmpty()) notice = "Tick files with Space first, then press Tab." else finish(checked)
            key.char == ' ' -> if (entry != null && !entry.isDir) toggle(entry.path)
            key.char == '~' -> System.getProperty("user.home")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }?.let { open(it) }
            key.char == '.' -> {
                showHidden = !showHidden
                open(dir, select = entry?.path)
            }
            else -> return false
        }
        return true
    }

    /** The actions the browser offers, for an [ActionBar]. */
    fun actions(chooseLabel: String = "Attach") = listOf(
        ActionItem("Space", "Tick"),
        ActionItem("Enter", "Open / $chooseLabel"),
        ActionItem("Tab", "$chooseLabel ticked${if (checked.isEmpty()) "" else " (${checked.size})"}", checked.isNotEmpty()),
        ActionItem("←", "Up"),
        ActionItem("~", "Home"),
        ActionItem(".", if (showHidden) "Hide hidden" else "Show hidden"),
        ActionItem("Esc", "Cancel", accent = Palette.Cta),
    )
}

@Composable
fun FilePicker(state: FilePickerState, rows: Int, modifier: Modifier = Modifier) {
    Stack(modifier) {
        Row {
            Label("▸ ", color = Palette.Cta, textStyle = TextStyle.Bold)
            Label(state.dir.toString(), color = Palette.Cta, textStyle = TextStyle.Bold)
        }
        Picker(
            items = state.entries,
            state = state.picker,
            label = { it.name },
            rows = rows,
            icon = { if (it.isParent) "↩" else if (it.isDir) "▣" else if (it.path in state.checked) "☑" else "☐" },
            iconColor = { if (it.path in state.checked) Palette.Green else if (it.isDir) Palette.Blue else Palette.Muted },
            meta = { if (it.isDir) "" else ImageFiles.size(it.size) },
            placeholder = "Press / to filter",
            emptyText = "No folders or images here.",
        )
        state.notice?.let { Warning(it) }
        if (state.checked.isNotEmpty()) Label("${state.checked.size} of ${state.limit} ticked: ${state.checked.joinToString(", ") { it.name }}", color = Palette.Green)
    }
}
