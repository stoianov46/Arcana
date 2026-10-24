package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.AppConfig
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.launch

private enum class Group(val title: String) {
    Ollama("Ollama"), Vault("Vault"), Appearance("Appearance"), Console("Console"), Prompts("Prompts"), Accessibility("Accessibility"), Tabs("Tabs"),
}

/** How Enter edits a setting that is not simply stepped with ←/→. */
private sealed interface Editor {
    /** Types a value; [apply] throws IllegalArgumentException with a user-facing message on invalid input. */
    class Text(val apply: (AppConfig, String) -> AppConfig) : Editor
    data object Model : Editor
    data object Folder : Editor
    data object Theme : Editor
}

private class Setting(
    val group: Group,
    val label: String,
    val help: String,
    val value: (AppConfig) -> String,
    /** ←/→ (and Enter, when there is no [editor]) step the value; null = not steppable. */
    val step: ((AppConfig, Int) -> AppConfig)? = null,
    val editor: Editor? = null,
)

private fun choice(group: Group, label: String, help: String, options: List<Pair<String, String>>, get: (AppConfig) -> String, set: (AppConfig, String) -> AppConfig) =
    Setting(
        group, label, help,
        value = { c -> options.firstOrNull { it.first == get(c) }?.second ?: get(c) },
        step = { c, dir ->
            val i = options.indexOfFirst { it.first == get(c) }.coerceAtLeast(0)
            set(c, options[(i + dir).mod(options.size)].first)
        },
    )

private fun toggle(group: Group, label: String, help: String, get: (AppConfig) -> Boolean, set: (AppConfig, Boolean) -> AppConfig) =
    choice(group, label, help, listOf("on" to "On", "off" to "Off"), { if (get(it)) "on" else "off" }, { c, v -> set(c, v == "on") })

private fun number(text: String, name: String): Int =
    text.trim().toIntOrNull() ?: throw IllegalArgumentException("$name must be a whole number")

private fun stepper(group: Group, label: String, help: String, range: IntRange, step: Int, get: (AppConfig) -> Int, set: (AppConfig, Int) -> AppConfig, unit: String = "") =
    Setting(
        group, label, help,
        value = { get(it).toString() + unit },
        step = { c, dir -> set(c, (get(c) + dir * step).coerceIn(range)) },
        editor = Editor.Text { c, v ->
            val n = number(v, label)
            require(n in range) { "$label must be ${range.first}–${range.last}" }
            set(c, n)
        },
    )

private fun text(group: Group, label: String, help: String, get: (AppConfig) -> String, set: (AppConfig, String) -> AppConfig, allowEmpty: Boolean = false) =
    Setting(
        group, label, help,
        value = { get(it).ifEmpty { "(none)" } },
        editor = Editor.Text { c, v ->
            require(allowEmpty || v.isNotBlank()) { "$label cannot be empty" }
            set(c, v.trim())
        },
    )

private val settings: List<Setting> = listOf(
    text(Group.Ollama, "Ollama URL", "Where the Ollama server listens.", { it.ollamaUrl }, { c, v ->
        require(v.startsWith("http://") || v.startsWith("https://")) { "URL must start with http:// or https://" }
        c.copy(ollamaUrl = v.trimEnd('/'))
    }),
    Setting(Group.Ollama, "Model", "The model that answers. Enter lists what is installed.", { it.model }, editor = Editor.Model),
    Setting(
        Group.Ollama, "Temperature", "0 is focused and repeatable, 2 is adventurous. ←/→ step by 0.1, Enter types a value.",
        value = { it.temperature.toString() },
        step = { c, dir -> c.copy(temperature = ((c.temperature * 10).toInt() + dir).coerceIn(0, 20) / 10.0) },
        editor = Editor.Text { c, v ->
            val t = v.trim().toDoubleOrNull()
            require(t != null && t in 0.0..2.0) { "Temperature must be between 0 and 2" }
            c.copy(temperature = t)
        },
    ),
    stepper(Group.Ollama, "Timeout", "How long to wait for a reply before giving up. ←/→ step by 15 s.", 5..3600, 15, { it.timeoutSeconds }, { c, n -> c.copy(timeoutSeconds = n) }, " s"),

    Setting(
        Group.Vault, "Vault path", "Your Obsidian vault folder. Enter opens a folder browser.",
        value = { it.vaultPath.ifEmpty { "(not set)" } }, editor = Editor.Folder,
    ),
    text(Group.Vault, "Output folder", "Folder inside the vault where new notes go. Empty = the vault root.", { it.outputFolder }, { c, v -> c.copy(outputFolder = v.trim('/')) }, allowEmpty = true),
    stepper(Group.Vault, "Context notes", "How many notes Ask your vault sends to the model.", 1..20, 1, { it.contextNotes }, { c, n -> c.copy(contextNotes = n) }),
    text(Group.Vault, "Inbox folder", "Where new, unprocessed notes land. The Brain screen counts what is waiting there.", { it.inboxFolder }, { c, v -> c.copy(inboxFolder = v.trim('/')) }, allowEmpty = true),
    text(Group.Vault, "Daily notes folder", "Where capture (Ctrl+N) writes today's note. Used only when Obsidian's Daily notes plugin has no setting of its own for the vault. Empty = the vault root.", { it.dailyFolder }, { c, v -> c.copy(dailyFolder = v.trim('/')) }, allowEmpty = true),
    text(Group.Vault, "Daily note format", "The file name of a daily note, like Obsidian's: YYYY-MM-DD, DD.MM.YYYY… Used only without an Obsidian setting.", { it.dailyFormat }, { c, v -> c.copy(dailyFormat = v.trim()) }),
    stepper(Group.Vault, "Stale after", "A note counts as stale after this many days without a change. ←/→ step by 30.", 7..1000, 30, { it.staleDays }, { c, n -> c.copy(staleDays = n) }, " days"),
    stepper(Group.Vault, "Chars per note", "How much of each note Ask sends. ←/→ step by 500.", 200..20_000, 500, { it.contextCharsPerNote }, { c, n -> c.copy(contextCharsPerNote = n) }),

    Setting(
        Group.Appearance, "Colour theme", "Applies at once. ←/→ step through the themes, Enter opens the list with a preview.",
        value = { Themes.byId(it.theme).name },
        step = { c, dir ->
            val i = Themes.all.indexOfFirst { it.id == c.theme }.coerceAtLeast(0)
            c.copy(theme = Themes.all[(i + dir).mod(Themes.all.size)].id)
        },
        editor = Editor.Theme,
    ),
    choice(Group.Appearance, "Navigation", "Where the screens are listed: a sidebar on the left or the right, or a bar across the top. Switches at once, so you can compare.", listOf("left" to "Left sidebar", "right" to "Right sidebar", "top" to "Top bar"), { it.navPosition }, { c, v -> c.copy(navPosition = v) }),
    choice(Group.Appearance, "Sidebar", "Full: screen names. Icons: only the number key and an icon, which leaves the content more width. Ctrl+B switches it from any screen.", listOf("full" to "Full", "icons" to "Icons"), { if (it.sidebarCollapsed) "icons" else "full" }, { c, v -> c.copy(sidebarCollapsed = v == "icons") }),
    toggle(Group.Appearance, "Logo animation", "While a reply is being generated, the colours of the sidebar logo sweep along it. Off keeps the logo still.", { it.animateLogo }, { c, v -> c.copy(animateLogo = v) }),

    toggle(Group.Console, "Answer from vault", "Chat searches your notes for each question and answers from what it finds, citing the notes. You can also switch this per chat with /vault.", { it.chatUseVault }, { c, v -> c.copy(chatUseVault = v) }),
    toggle(Group.Console, "Render Markdown", "On: headings, bullets and code are drawn as such. Off: the model's text exactly as written.", { it.renderMarkdown }, { c, v -> c.copy(renderMarkdown = v) }),
    toggle(Group.Console, "Show thinking", "Show \"Gemma is thinking…\" while a reasoning model works before its first words.", { it.showThinking }, { c, v -> c.copy(showThinking = v) }),
    stepper(Group.Console, "Chat history", "How many of the latest messages Chat sends to the model as context. ←/→ step by 2.", 2..100, 2, { it.chatHistory }, { c, n -> c.copy(chatHistory = n) }),
    toggle(Group.Console, "Keep chats", "Save every chat to disk and reopen the last one when Arcana starts. Off: the chat lives only until you quit.", { it.keepChats }, { c, v -> c.copy(keepChats = v) }),
    toggle(Group.Console, "Ask before leaving chat", "When a chat has not been saved to your vault, show a question first: save it, or press Esc again to leave.", { it.confirmLeaveChat }, { c, v -> c.copy(confirmLeaveChat = v) }),
    stepper(Group.Console, "Chats kept", "How many saved chats to keep; the least recently used are deleted. ←/→ step by 5.", 5..200, 5, { it.chatsKept }, { c, n -> c.copy(chatsKept = n) }),
    choice(Group.Console, "Copy as", "What select mode (s) puts on the clipboard. Markdown: as written. Plain text: markers removed.", listOf("markdown" to "Markdown", "plain" to "Plain text"), { it.copyAs }, { c, v -> c.copy(copyAs = v) }),

    text(Group.Prompts, "System prompt", "Tells the model who it is. Sent with every request.", { it.systemPrompt }, { c, v -> c.copy(systemPrompt = v) }),
    text(Group.Prompts, "Summary prompt", "The instruction used by Summarize.", { it.summaryPrompt }, { c, v -> c.copy(summaryPrompt = v) }),

    choice(Group.Accessibility, "Interface scale", "A terminal sets its own font size, so this is the layout density: Spacious keeps the roomy screens for longer, Compact packs more in. Change your terminal's font size for bigger text.", listOf("compact" to "Compact", "normal" to "Normal", "spacious" to "Spacious"), { it.uiScale }, { c, v -> c.copy(uiScale = v) }),
    toggle(Group.Accessibility, "Screen-reader output", "Drops decorative symbols (▌ ✓ ✕ → • and the rounded frames) in favour of plain words and ASCII that read cleanly in a screen reader.", { it.plainGlyphs }, { c, v -> c.copy(plainGlyphs = v) }),
)

private const val CustomModel = "✎ Type a custom name…"

/** What the screen is doing with the highlighted setting. */
private sealed interface Editing {
    data class Typing(val setting: Setting, val editor: Editor.Text) : Editing
    data class PickModel(val models: List<String>) : Editing
    data object PickTheme : Editing
    data class PickFolder(val browser: DirectoryPickerState) : Editing
    data object ConfirmReset : Editing
}

@Composable
fun SettingsScreen(app: AppContext) {
    val scope = rememberCoroutineScope()
    val picker = remember { PickerState() }
    val field = remember { TextFieldState() }
    var group by remember { mutableStateOf(Group.Ollama) }
    var index by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }

    val inGroup = settings.filter { it.group == group }
    val selected = inGroup.getOrNull(index.coerceIn(0, (inGroup.size - 1).coerceAtLeast(0)))

    fun changed(newConfig: AppConfig, what: String) {
        app.update(newConfig)
        if (what == "Ollama URL" || what == "Model") scope.launch { app.refreshConnection() }
        notice = Notice("$what saved")
    }

    fun commitText(setting: Setting, editor: Editor.Text) {
        try {
            changed(editor.apply(app.config, field.text), setting.label)
            editing = null
        } catch (e: IllegalArgumentException) {
            notice = Notice(e.message ?: "Invalid value", isError = true)
        } catch (e: Exception) {
            notice = Notice("Could not save: ${e.message}", isError = true)
        }
    }

    fun commitFolder(path: Path) {
        if (!Files.isDirectory(path)) {
            notice = Notice("Not a directory: $path", isError = true)
            return
        }
        changed(app.config.copy(vaultPath = path.toString()), "Vault path")
        editing = null
    }

    fun startEdit(setting: Setting) {
        notice = null
        when (val editor = setting.editor) {
            is Editor.Text -> {
                field.set(setting.value(app.config).takeUnless { it.startsWith("(") } ?: "")
                editing = Editing.Typing(setting, editor)
            }
            Editor.Folder -> {
                val start = app.config.vaultPath.takeIf { it.isNotBlank() }?.let { AppContext.expandHome(it) }?.takeIf { Files.isDirectory(it) }
                    ?: Path.of(System.getProperty("user.home"))
                editing = Editing.PickFolder(DirectoryPickerState(start, onChoose = ::commitFolder, onCancel = { editing = null }))
            }
            Editor.Theme -> {
                picker.reset()
                picker.index = Themes.all.indexOfFirst { it.id == app.config.theme }.coerceAtLeast(0)
                editing = Editing.PickTheme
            }
            Editor.Model -> scope.launch {
                try {
                    val models = app.client.listModels() + CustomModel
                    picker.reset()
                    picker.index = models.indexOf(app.config.model).coerceAtLeast(0)
                    editing = Editing.PickModel(models)
                } catch (e: Exception) {
                    notice = Notice("${e.message} Type the model name instead.", isError = true)
                    field.set(app.config.model)
                    editing = Editing.Typing(setting, Editor.Text { c, v -> require(v.isNotBlank()) { "Model name cannot be empty" }; c.copy(model = v.trim()) })
                }
            }
            null -> setting.step?.let { changed(it(app.config, 1), setting.label) }
        }
    }

    fun testConnection() {
        notice = Notice("Testing…")
        scope.launch {
            app.refreshConnection()
            notice = try {
                val installed = app.client.listModels()
                if (app.config.model in installed) {
                    Notice("Connected · ${installed.size} models installed · ${app.config.model} is available")
                } else {
                    Notice("Connected, but ${app.config.model} is not installed. Installed: ${installed.joinToString()}", isError = true)
                }
            } catch (e: Exception) {
                Notice(e.message ?: "Cannot reach Ollama", isError = true)
            }
        }
    }

    /** Moves the chosen tab one place earlier (-1) or later (+1), within its own sidebar section. */
    fun moveTab(delta: Int) {
        val tabs = Screen.sidebar
        val from = index.coerceIn(0, tabs.lastIndex)
        val to = from + delta
        if (to !in tabs.indices) return
        if (tabs[from].group != tabs[to].group) {
            notice = Notice("Tabs stay inside their section (${tabs[from].group.heading.lowercase()}).", isError = true)
            return
        }
        val order = tabs.toMutableList()
        order[from] = tabs[to]
        order[to] = tabs[from]
        index = to
        changed(app.config.copy(tabOrder = order.map { it.name }), "Tab order")
    }

    fun switchGroup(delta: Int) {
        group = Group.entries[(group.ordinal + delta).mod(Group.entries.size)]
        index = 0
        notice = null
    }

    val mode = editing
    FocusMode(
        when (mode) {
            is Editing.Typing -> true
            is Editing.PickModel -> picker.filtering
            Editing.PickTheme -> picker.filtering
            is Editing.PickFolder -> mode.browser.filtering
            else -> false
        },
    )
    when (mode) {
        null -> if (group == Group.Tabs) {
            Hints("↑↓  choose a tab", "←→  move it earlier / later", "Tab  next group", "R  reset")
        } else {
            Hints("↑↓  choose", "←→  change", "Enter  edit", "Tab  next group", "R  reset", "t  test")
        }
        is Editing.Typing -> Hints("Enter  save", "Ctrl+U  clear line", "Esc  cancel")
        is Editing.PickModel -> Hints("↑↓  move", "Enter  choose", "/  filter", "Esc  cancel")
        Editing.PickTheme -> Hints("↑↓  preview", "Enter  choose", "/  filter", "Esc  cancel")
        is Editing.PickFolder ->
            if (mode.browser.filtering) Hints("↑↓  move", "Enter  open", "Esc  clear filter") else Hints("Space  use folder", "Enter  open", "←  up", "/  filter", "Esc  cancel")
        Editing.ConfirmReset -> Hints("y  reset all", "any other key  keep")
    }

    KeyHandler { key ->
        when (mode) {
            is Editing.Typing -> when {
                key == Key.Escape -> { editing = null; true }
                key == Key.Enter -> { commitText(mode.setting, mode.editor); true }
                else -> field.onKey(key)
            }
            is Editing.PickModel -> when {
                picker.handle(key, mode.models) { it } -> true
                key == Key.Enter -> {
                    when (val choice = picker.selected(mode.models) { it }) {
                        null -> Unit
                        CustomModel -> {
                            field.set(app.config.model)
                            editing = Editing.Typing(settings.first { it.label == "Model" }, Editor.Text { c, v -> require(v.isNotBlank()) { "Model name cannot be empty" }; c.copy(model = v.trim()) })
                        }
                        else -> { changed(app.config.copy(model = choice), "Model"); editing = null }
                    }
                    true
                }
                key == Key.Escape -> { editing = null; true }
                else -> false
            }
            Editing.PickTheme -> {
                val names = Themes.all.map { it.name }
                when {
                    picker.handle(key, names) { it } -> true
                    key == Key.Enter -> {
                        picker.selected(names) { it }?.let { name -> changed(app.config.copy(theme = Themes.all.first { it.name == name }.id), "Colour theme") }
                        editing = null
                        true
                    }
                    key == Key.Escape -> { editing = null; true }
                    else -> false
                }
            }
            is Editing.PickFolder -> mode.browser.handle(key)
            Editing.ConfirmReset -> {
                if (key.char == 'y' || key.char == 'Y') {
                    // Everything goes back to the built-in defaults except where the vault is.
                    app.update(AppConfig(vaultPath = app.config.vaultPath))
                    notice = Notice("All options reset to the built-in defaults")
                }
                editing = null
                true
            }
            null -> if (group == Group.Tabs) when {
                key == Key.Up || key.char == 'k' -> { index = (index - 1).coerceAtLeast(0); true }
                key == Key.Down || key.char == 'j' -> { index = (index + 1).coerceAtMost(Screen.sidebar.lastIndex); true }
                key == Key.Left || key.char == 'K' -> { moveTab(-1); true }
                key == Key.Right || key.char == 'J' -> { moveTab(1); true }
                key == Key.Tab -> { switchGroup(1); true }
                key == Key.BackTab -> { switchGroup(-1); true }
                key.char == 'R' -> { editing = Editing.ConfirmReset; true }
                else -> false
            } else when {
                key == Key.Up || key.char == 'k' -> { index = (index - 1).coerceAtLeast(0); true }
                key == Key.Down || key.char == 'j' -> { index = (index + 1).coerceAtMost(inGroup.lastIndex); true }
                key == Key.Tab -> { switchGroup(1); true }
                key == Key.BackTab -> { switchGroup(-1); true }
                key == Key.Left -> { selected?.step?.let { changed(it(app.config, -1), selected.label) }; true }
                key == Key.Right -> { selected?.step?.let { changed(it(app.config, 1), selected.label) }; true }
                key == Key.Enter -> { selected?.let(::startEdit); true }
                key.char == 't' -> { testConnection(); true }
                key.char == 'R' -> { editing = Editing.ConfirmReset; true }
                else -> false
            }
        }
    }

    Stack {
        when (mode) {
            null -> {
                ScreenHeader("Settings", "Changes are saved at once to ${app.configPath}")
                VGap()
                GroupBar(group)
                VGap()
                Panel(group.title) {
                    if (group == Group.Tabs) {
                        TabList(index.coerceIn(0, Screen.sidebar.lastIndex))
                    } else {
                        OptionList(app.config, inGroup, index.coerceIn(0, inGroup.lastIndex))
                    }
                    if (group == Group.Appearance) {
                        VGap()
                        ThemeSwatches(Themes.byId(app.config.theme))
                    }
                    NoticeLine(notice)
                }
                if (group == Group.Tabs) {
                    if (!rememberDensity().compact) Hint("The first tab is where Arcana opens, and a tab's position decides its number key. Tabs move within their own section.")
                } else {
                    selected?.let { if (!rememberDensity().compact) Hint("${it.label}: ${it.help}") }
                }
            }
            is Editing.Typing -> {
                ScreenHeader("Settings › ${mode.setting.label}", "Current: ${mode.setting.value(app.config)}")
                VGap()
                Panel(mode.setting.label) {
                    TextInput(mode.setting.label, field, true, maxRows = 4)
                    VGap()
                    Hint(mode.setting.help)
                    NoticeLine(notice)
                }
            }
            is Editing.PickModel -> {
                ScreenHeader("Settings › Model", "Installed on ${app.config.ollamaUrl}")
                VGap()
                Panel("Models") {
                    Picker(mode.models, picker, label = { it }, rows = bodyRows(extra = 5), icon = { if (it == CustomModel) "" else "◆" }, iconColor = { Palette.Accent }, meta = { if (it == app.config.model) "current" else "" }, metaColor = { Palette.Green })
                }
            }
            Editing.PickTheme -> {
                val names = Themes.all.map { it.name }
                val highlighted = picker.selected(names) { it }?.let { n -> Themes.all.first { it.name == n } } ?: Themes.byId(app.config.theme)
                ScreenHeader("Settings › Colour theme", "Chosen: ${Themes.byId(app.config.theme).name}")
                VGap()
                Panel("Themes") {
                    Picker(names, picker, label = { it }, rows = bodyRows(extra = 8), icon = { "◐" }, iconColor = { Palette.Accent }, meta = { if (it == Themes.byId(app.config.theme).name) "current" else "" }, metaColor = { Palette.Green })
                    VGap()
                    Label("PREVIEW  ${highlighted.name}", color = Palette.Muted, textStyle = TextStyle.Bold)
                    ThemeSwatches(highlighted)
                }
            }
            is Editing.PickFolder -> {
                ScreenHeader("Settings › Vault path", "Your Obsidian vault folder")
                VGap()
                Panel("Choose a folder") {
                    DirectoryPicker(mode.browser, rows = bodyRows(extra = 8))
                    NoticeLine(notice)
                }
                ActionBar(DirectoryPickerState.actions(mode.browser))
            }
            Editing.ConfirmReset -> {
                ScreenHeader("Settings", "Reset every option")
                VGap()
                Panel("Reset all options?", accent = Palette.Red) {
                    Warning("Every option goes back to its built-in default, including prompts, model and Ollama URL. The vault path is kept.")
                }
                ActionBar(listOf(ActionItem("y", "Reset all", accent = Palette.Red), ActionItem("any other key", "Keep my settings", accent = Palette.Cta)))
            }
        }
    }
}

/** The groups as a row of tabs; the one on show is lit. */
@Composable
private fun GroupBar(current: Group) {
    Row {
        Group.entries.forEach { g ->
            val on = g == current
            Label(" ${g.title} ", color = if (on) Palette.Background else Palette.Muted, background = if (on) Palette.Cta else Color.Unspecified, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(" ")
        }
    }
}

@Composable
private fun OptionList(config: AppConfig, items: List<Setting>, selected: Int) {
    val labelWidth = items.maxOf { it.label.length } + 2
    val valueWidth = (panelWidth() - labelWidth - 8).coerceAtLeast(10)
    items.forEachIndexed { i, s ->
        val on = i == selected
        val bg = if (on) Palette.Selected else if (i % 2 == 0) Palette.EntryBg else Palette.Surface
        val value = s.value(config)
        Row(Modifier.fillMaxWidth().background(bg)) {
            Label(if (on) "▌" else " ", color = Palette.Accent)
            Label(" " + fit(s.label, labelWidth), color = if (on) Palette.Cta else Palette.FieldLabel, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(if (on && s.step != null) "‹ " else "  ", color = Palette.Muted)
            Label(if (value.length > valueWidth) value.take(valueWidth - 1) + "…" else value, color = Palette.Text, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(if (on && s.step != null) " ›" else "", color = Palette.Muted)
        }
    }
}

/** The sidebar tabs in their current order, with the number key each one has; the chosen one can be moved. */
@Composable
private fun TabList(selected: Int) {
    val tabs = Screen.sidebar
    tabs.forEachIndexed { i, screen ->
        val on = i == selected
        val bg = if (on) Palette.Selected else if (i % 2 == 0) Palette.EntryBg else Palette.Surface
        Row(Modifier.fillMaxWidth().background(bg)) {
            Label(if (on) "▌" else " ", color = Palette.Accent)
            Label(" ${screen.shortcut}  ", color = Palette.Cta, textStyle = TextStyle.Bold)
            Label("${screen.icon}  ${fit(screen.title, 16)}", color = if (on) Palette.Text else Palette.Muted, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label("  ${screen.group.heading.lowercase()}", Modifier.weight(1f), color = Palette.Dim)
            if (on) Label(" ‹ move ›", color = Palette.Muted)
            if (i == 0) Label("  opens first", color = Palette.Green)
        }
    }
}
