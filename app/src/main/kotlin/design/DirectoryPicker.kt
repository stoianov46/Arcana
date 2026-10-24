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
 */
class DirectoryPickerState(
    start: Path,
    private val onChoose: (Path) -> Unit,
    private val onCancel: () -> Unit,
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

    init {
        open(dir)
    }

    fun open(target: Path) {
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

    private fun up() {
        dir.parent?.let { open(it) }
    }

    fun handle(key: Key): Boolean {
        if (picker.handle(key, entries) { it.name }) return true
        when {
            key == Key.Enter || key == Key.Right -> picker.selected(entries) { it.name }?.let { open(it.path) }
            key == Key.Left || key == Key.Backspace -> up()
            key.char == ' ' -> onChoose(dir)
            key.char == '.' -> {
                showHidden = !showHidden
                open(dir)
            }
            key.char == '~' -> open(Path.of(System.getProperty("user.home")))
            key == Key.Escape -> onCancel()
            else -> return false
        }
        return true
    }

    companion object {
        /** The actions the browser offers, for an [ActionBar]. */
        fun actions(state: DirectoryPickerState) = listOf(
            ActionItem("Space", "Use this folder"),
            ActionItem("Enter", "Open"),
            ActionItem("←", "Up"),
            ActionItem("~", "Home"),
            ActionItem(".", if (state.showHidden) "Hide hidden" else "Show hidden"),
        )
    }
}

@Composable
fun DirectoryPicker(state: DirectoryPickerState, rows: Int, modifier: Modifier = Modifier) {
    Stack(modifier) {
        Row {
            Label("▸ ", color = Palette.Cta, textStyle = TextStyle.Bold)
            Label(state.dir.toString(), color = Palette.Cta, textStyle = TextStyle.Bold)
            if (state.isVault) {
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
            placeholder = "Press / to filter folders",
            emptyText = "No sub-folders here. Press Space to use this folder.",
        )
    }
}
