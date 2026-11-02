package com.arcana.brain.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.design.Notice
import com.arcana.brain.app.design.Shortcuts
import com.arcana.brain.app.design.PickerState
import com.arcana.brain.app.input.TextFieldState

/** The sidebar section a screen belongs to; the heading is drawn where the section changes. */
enum class ScreenGroup(val heading: String) { Workspace("BRAIN"), Account("SYSTEM") }

/** The views of the Library: every note, or the ones that need attention. */
enum class LibraryFilter(val title: String) {
    All("All"), Recent("Recent"), Reports("Reports"), Inbox("Inbox"), Orphans("No links"), Untagged("No tags"), Broken("Broken links"), Stale("Stale"), Tags("Tags"),
}

enum class Screen(val title: String, val icon: String, val group: ScreenGroup = ScreenGroup.Workspace) {
    Home("Brain", "◉"),
    Chat("Chat", "»"),
    Library("Library", "▤"),
    Inbox("Inbox", "▣"),
    Compose("AI notes", "✦"),
    Session("Session", "◷"),
    Usage("Usage", "▥", ScreenGroup.Account),
    Settings("Settings", "✱", ScreenGroup.Account),
    Guide("Guide", "¶", ScreenGroup.Account),
    Changelog("Changelog", "≡", ScreenGroup.Account),
    ;

    /** The key that opens this screen from anywhere. */
    val shortcut: Char get() = SHORTCUT_KEYS.getOrElse(sidebar.indexOf(this)) { ' ' }

    companion object {
        /** The sidebar in the user's order (see [ScreenOrder]); a screen's position decides its number key. */
        val sidebar: List<Screen> get() = ScreenOrder.order

        /**
         * The sidebar for the screen names saved in the settings: unknown or repeated names are ignored and any screen the
         * list leaves out (one added by an update) is slotted into its own section. Each section is kept together, so tabs
         * are reordered within their section, never across: that would split a section heading in two.
         */
        fun ordered(names: List<String>): List<Screen> {
            val chosen = names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct()
            val full = chosen + entries.filter { it !in chosen }
            val sections = full.map { it.group }.distinct()
            return sections.flatMap { section -> full.filter { it.group == section } }
        }
    }
}

/** The order the sidebar is in right now; observed by everything that draws it or reads a shortcut key. */
object ScreenOrder {
    var order by mutableStateOf(Screen.entries.toList())
        private set

    fun use(names: List<String>) {
        order = Screen.ordered(names)
    }
}

/** Sidebar shortcuts in order: the first entries are 1-9 and 0. */
const val SHORTCUT_KEYS = "1234567890"

/** Which screen is showing, plus what the always-visible shell (sidebar, footer) needs to know about it. */
class Navigator {
    /** Arcana opens on the first screen of the sidebar. */
    var current by mutableStateOf(Screen.sidebar.first())
        private set

    /** Key hints for the footer bar; each screen publishes its own and updates them as its mode changes. */
    var hint by mutableStateOf("")

    /**
     * True while the screen has captured the keyboard for typing (so the screen shortcuts do not work and Esc is the way
     * out). Each such screen publishes it with [FocusMode]; the footer shows an "EDITING" badge.
     */
    var focusMode by mutableStateOf(false)

    var helpOpen by mutableStateOf(false)

    /** The command palette and the quick-capture box, drawn over the current screen. */
    var paletteOpen by mutableStateOf(false)
    var captureOpen by mutableStateOf(false)
    val palette = PickerState()
    val captureField = TextFieldState(multiline = false)
    val overlayOpen: Boolean get() = helpOpen || paletteOpen || captureOpen

    /** Actions the current screen adds to the palette (published with [Commands]). */
    var screenCommands by mutableStateOf<List<Command>>(emptyList())

    /** A short message in the footer that goes away by itself: the outcome of something done from an overlay. */
    var toast by mutableStateOf<Notice?>(null)

    fun showToast(text: String, isError: Boolean = false) {
        toast = Notice(text, isError)
    }

    fun openPalette() {
        helpOpen = false
        captureOpen = false
        palette.reset()
        palette.startFiltering()
        paletteOpen = true
    }

    fun openCapture() {
        helpOpen = false
        paletteOpen = false
        captureOpen = true
    }

    fun closeOverlays() {
        helpOpen = false
        paletteOpen = false
        captureOpen = false
    }

    /** How many action bars are on screen; while there is one, the footer leaves out the keys the bar already shows. */
    var actionBars by mutableStateOf(0)

    /** The screen's key hints as they were when the key map opened (the screen itself is hidden behind it). */
    var helpHint by mutableStateOf("")

    /** True after a first Ctrl+C: the footer then asks for a second one, and only that quits. */
    var quitPending by mutableStateOf(false)

    /**
     * A screen can ask to be told before the user leaves it: return true to stop the navigation (the screen then shows
     * its own question and calls [navigate] with `force = true` when the user confirms).
     */
    var leaveGuard: ((Screen) -> Boolean)? = null

    /** What the Session screen should do as it opens: set by the palette, read once. */
    var sessionIntent by mutableStateOf<String?>(null)

    /** What the Library should show when it opens: set by [openLibrary], read (and cleared) by the Library; null = the view of Settings → Library → Opens on. */
    var libraryFilter by mutableStateOf<LibraryFilter?>(null)
    var libraryNote by mutableStateOf<String?>(null)

    /** For the Tags view: the tag whose notes to show straight away. */
    var libraryTag by mutableStateOf<String?>(null)

    /** A note the AI notes screen should start from (set by the Library's "AI…" action), consumed when it opens. */
    var noteRequest by mutableStateOf<String?>(null)

    fun openLibrary(filter: LibraryFilter, note: String? = null, tag: String? = null) {
        libraryFilter = filter
        libraryNote = note
        libraryTag = tag
        navigate(Screen.Library)
    }

    fun navigate(screen: Screen, force: Boolean = false) {
        if (!force && screen != current && leaveGuard?.invoke(screen) == true) return
        current = screen
    }
}

val LocalNavigator = compositionLocalOf<Navigator> { error("No Navigator provided") }

/** Publishes this screen's key hints to the always-visible footer bar (renders nothing itself). Format: `key  text`. */
@Composable
fun Hints(vararg hints: String) {
    val navigator = LocalNavigator.current
    val text = Shortcuts.label(hints.joinToString("   "))
    SideEffect { navigator.hint = text }
    DisposableEffect(Unit) { onDispose { navigator.hint = "" } }
}

/** Tells the shell this screen has captured the keyboard (typing in a field): screen shortcuts pause, the footer says EDITING. */
@Composable
fun FocusMode(active: Boolean) {
    val navigator = LocalNavigator.current
    SideEffect { navigator.focusMode = active }
    DisposableEffect(Unit) { onDispose { navigator.focusMode = false } }
}

/** One action in the command palette. [keys] is its shortcut (shown for reference), [group] what it belongs to. */
class Command(val label: String, val group: String = "", val keys: String = "", val enabled: Boolean = true, val run: () -> Unit)

/** Publishes this screen's actions to the command palette (renders nothing itself). */
@Composable
fun Commands(commands: List<Command>) {
    val navigator = LocalNavigator.current
    SideEffect { navigator.screenCommands = commands }
    DisposableEffect(Unit) { onDispose { navigator.screenCommands = emptyList() } }
}
