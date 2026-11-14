package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.PromptTemplate
import com.arcana.brain.core.PromptTemplates
import com.arcana.brain.core.Vault
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Row

/** What the Prompts screen is doing. */
internal enum class PromptMode { List, Fill, NewName, Edit, ConfirmDiscard, ConfirmDelete }

/**
 * The Templates tab (`Screen.Prompts`): the templates in the vault's Prompts folder, picked and run. Enter starts a NEW chat with the prompt and sends it (the blanks, `{{like this}}`,
 * are asked first, one by one); `u` puts it in the message field of the current chat instead, unsent. `n` writes a new template, `e` edits one, `d` trashes it.
 */
@Composable
fun PromptsScreen(app: AppContext) {
    val navigator = LocalNavigator.current
    val vault = remember(app.config.vaultPath) { app.openVault() }
    var version by remember { mutableIntStateOf(0) }
    val templates = remember(vault, version, app.brain.revision) { vault.getOrNull()?.let { runCatching { PromptTemplates.list(it) }.getOrDefault(emptyList()) }.orEmpty() }
    val picker = remember { PickerState() }
    var mode by remember { mutableStateOf(PromptMode.List) }

    val nameField = remember { TextFieldState() }
    val editField = remember { TextFieldState(multiline = true, max = 200_000) }
    var editOriginal by remember { mutableStateOf("") }
    /** The note being edited; null while a new template is being written (the note is made on the first save). */
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }
    var newName by remember { mutableStateOf("") }

    val fillField = remember { TextFieldState() }
    var filling by remember { mutableStateOf<PromptTemplate?>(null) }
    var fillIndex by remember { mutableIntStateOf(0) }
    val fillValues = remember { mutableMapOf<String, String>() }

    val current = picker.selected(templates) { it.name }
    val wide = LocalContentWidth.current >= 90

    FocusMode(mode == PromptMode.Fill || mode == PromptMode.NewName || mode == PromptMode.Edit || (mode == PromptMode.List && picker.filtering))
    when (mode) {
        PromptMode.List -> if (picker.filtering) Hints("↑↓  move", "Enter  run", "Esc  clear filter") else Hints("↑↓  move", "Enter  run in a new chat", "u  use in this chat", "n  new", "e  edit", "d  trash", "[ ]  list width", "/  filter")
        PromptMode.Fill -> Hints("Enter  next", "Esc  cancel")
        PromptMode.NewName -> Hints("Enter  write the prompt", "Esc  cancel")
        PromptMode.Edit -> Hints("Ctrl+S  save", "Esc  leave")
        PromptMode.ConfirmDiscard -> Hints("y  discard", "any other key  keep editing")
        PromptMode.ConfirmDelete -> Hints("y  move to trash", "any other key  keep")
    }

    /** A new chat with [text] in the message field, sent at once. */
    fun runNow(text: String) {
        app.chat.startNew()
        app.chat.input.set(text)
        app.chat.autoSend = true
        navigator.navigate(Screen.Chat)
    }

    fun run(t: PromptTemplate) {
        if (t.body.isBlank()) return navigator.showToast("“${t.name}” is empty: press e to write it.", true)
        if (t.placeholders.isEmpty()) return runNow(t.body)
        filling = t; fillIndex = 0; fillValues.clear(); fillField.clear(); mode = PromptMode.Fill
    }

    fun useHere(t: PromptTemplate) {
        if (t.body.isBlank()) return navigator.showToast("“${t.name}” is empty: press e to write it.", true)
        val blanks = t.placeholders
        val text = t.body
        app.chat.input.set(if (app.chat.input.text.isBlank()) text else app.chat.input.text + "\n\n" + text)
        navigator.navigate(Screen.Chat)
        if (blanks.isNotEmpty()) navigator.showToast("Fill in " + blanks.joinToString(", ") { "{{$it}}" } + " before you send it.")
    }

    /** `[` / `]`: the list takes 5 % less / more of a wide Templates tab (`promptsListPercent`, 20 to 60, like the Library's list). */
    fun resizeList(delta: Int) {
        if (!wide) return navigator.showToast("The list and the preview share the window only when it is at least 90 columns wide.")
        val now = app.config.promptsListPercent.coerceIn(LIBRARY_LIST_MIN, LIBRARY_LIST_MAX)
        val next = (now + delta).coerceIn(LIBRARY_LIST_MIN, LIBRARY_LIST_MAX)
        if (next == now) return navigator.showToast("The list is already at its ${if (delta < 0) "narrowest" else "widest"}: $now %.")
        app.update(app.config.copy(promptsListPercent = next))
        navigator.showToast("List $next %, preview ${100 - next} % ([ narrower, ] wider).")
    }

    fun answerBlank() {
        val t = filling ?: return
        val name = t.placeholders.getOrNull(fillIndex) ?: return
        val value = fillField.text.trim()
        if (value.isEmpty()) return navigator.showToast("Type a value for $name (Esc cancels).", true)
        fillValues[name] = value
        fillField.clear()
        if (fillIndex + 1 < t.placeholders.size) fillIndex++ else { mode = PromptMode.List; filling = null; runNow(PromptTemplates.fill(t.body, fillValues.toMap())) }
    }

    fun startEdit(t: PromptTemplate) {
        val v = vault.getOrNull() ?: return
        runCatching { v.read(t.path) }
            .onSuccess { text -> editing = t; newName = ""; editField.set(text); editOriginal = text; mode = PromptMode.Edit }
            .onFailure { navigator.showToast(it.message ?: "Could not read the template.", true) }
    }

    fun startNew() {
        val name = nameField.text.trim()
        if (name.isEmpty()) return navigator.showToast("Give the template a name.", true)
        editing = null; newName = name
        val starter = PromptTemplates.noteText("", "") + "\n"
        editField.set(starter); editOriginal = starter; mode = PromptMode.Edit
    }

    fun save() {
        val v = vault.getOrNull() ?: return
        if (editField.text == editOriginal) return navigator.showToast("Nothing changed.")
        val target = editing
        runCatching {
            if (target == null) {
                val path = PromptTemplates.create(v, newName)
                v.write(path, editField.text)
                path
            } else {
                if (v.read(target.path) != editOriginal) error("The template changed on disk while you edited it. Copy your text, press Esc and open it again.")
                v.write(target.path, editField.text)
                target.path
            }
        }.onSuccess { path ->
            app.brain.changed(); version++
            editOriginal = editField.text
            editing = PromptTemplates.parse(path, editField.text); newName = ""
            navigator.showToast("Saved $path")
        }.onFailure { navigator.showToast(it.message ?: "Could not save.", true) }
    }

    fun trash(t: PromptTemplate) {
        val v = vault.getOrNull() ?: return
        runCatching { v.trash(t.path) }
            .onSuccess { app.brain.changed(); version++; navigator.showToast("Moved ${t.path} to the trash") }
            .onFailure { navigator.showToast(it.message ?: "Could not delete.", true) }
    }

    KeyHandler { key ->
        when (mode) {
            PromptMode.Fill -> when {
                key == Key.Escape -> { mode = PromptMode.List; filling = null; true }
                key == Key.Enter -> { answerBlank(); true }
                else -> fillField.onKey(key)
            }
            PromptMode.NewName -> when {
                key == Key.Escape -> { mode = PromptMode.List; true }
                key == Key.Enter -> { startNew(); true }
                else -> nameField.onKey(key)
            }
            PromptMode.Edit -> when {
                key == Key.Save -> { save(); true }
                key == Key.Escape -> { mode = if (editField.text == editOriginal) PromptMode.List else PromptMode.ConfirmDiscard; true }
                key == Key.Enter -> editField.onKey(Key.PastedNewline)
                else -> editField.onKey(key)
            }
            PromptMode.ConfirmDiscard -> { mode = if (key.char == 'y' || key.char == 'Y') PromptMode.List else PromptMode.Edit; true }
            PromptMode.ConfirmDelete -> { if (key.char == 'y' || key.char == 'Y') current?.let { trash(it) }; mode = PromptMode.List; true }
            PromptMode.List -> when {
                picker.handle(key, templates, label = { it.name }) -> true
                !picker.filtering && (key.char == '[' || key.char == ']') -> { resizeList(if (key.char == ']') 5 else -5); true }
                key == Key.Enter -> { current?.let { run(it) }; true }
                key.char == 'u' -> { current?.let { useHere(it) }; true }
                key.char == 'n' -> { nameField.clear(); mode = PromptMode.NewName; true }
                key.char == 'e' -> { current?.let { startEdit(it) }; true }
                key.char == 'd' || key == Key.Delete -> { if (current != null) mode = PromptMode.ConfirmDelete; true }
                else -> false
            }
        }
    }

    val actions = when (mode) {
        PromptMode.List -> listOf(
            ActionItem("Enter", "Run in a new chat", current != null, Palette.Cta),
            ActionItem("u", "Use in this chat", current != null),
            ActionItem("n", "New"),
            ActionItem("e", "Edit", current != null),
            ActionItem("d", "Trash", current != null, Palette.Red),
            *(if (wide) arrayOf(ActionItem("[ ]", "List width")) else emptyArray()),
            ActionItem("/", "Filter"),
        )
        PromptMode.Fill -> listOf(ActionItem("Enter", "Next", accent = Palette.Cta), ActionItem("Esc", "Cancel"))
        PromptMode.NewName -> listOf(ActionItem("Enter", "Write the prompt", accent = Palette.Cta), ActionItem("Esc", "Cancel"))
        PromptMode.Edit -> listOf(ActionItem("Ctrl+S", "Save", accent = Palette.Cta), ActionItem("Esc", "Leave"))
        PromptMode.ConfirmDiscard -> listOf(ActionItem("y", "Discard the changes", accent = Palette.Red), ActionItem("any other key", "Keep editing", accent = Palette.Cta))
        PromptMode.ConfirmDelete -> listOf(ActionItem("y", "Move to trash", accent = Palette.Red), ActionItem("any other key", "Keep", accent = Palette.Cta))
    }
    val listRows = outputRows(fixed = 3 + 2 + 1, actions = actions)
    val width = LocalContentWidth.current

    Stack {
        when {
            vault.isFailure -> Panel("Prompt templates") { Warning("No vault is open. Choose one in Settings → Vault; the templates are notes in its ${PromptTemplates.FOLDER} folder.") }
            mode == PromptMode.Fill && filling != null -> {
                val t = filling!!
                val name = t.placeholders.getOrElse(fillIndex) { "" }
                Panel("Fill in · ${t.name}", accent = Palette.Cta) {
                    Label("${fillIndex + 1} of ${t.placeholders.size}", color = Palette.Muted)
                    TextInput(name, fillField, true, "Type the value for {{$name}}")
                    Hint("When the last blank is answered, a new chat starts and sends the prompt.")
                }
            }
            mode == PromptMode.NewName -> Panel("New prompt template", accent = Palette.Cta) {
                TextInput("Name", nameField, true, "For example: Weekly review")
                Hint("It becomes a note in the ${PromptTemplates.FOLDER} folder of your vault. Next you write the prompt; put {{name}} where a value is asked each time you run it.")
            }
            (mode == PromptMode.Edit || mode == PromptMode.ConfirmDiscard) -> Panel("Edit · ${editing?.path ?: "${PromptTemplates.FOLDER}/$newName.md"}${if (editField.text != editOriginal) "  ·  unsaved" else ""}", accent = if (mode == PromptMode.ConfirmDiscard) Palette.Red else Palette.Cta) {
                if (mode == PromptMode.ConfirmDiscard) Warning("Discard what you changed? Nothing has been saved.")
                TextValue(editField, mode == PromptMode.Edit, "", maxRows = (listRows - 2 - (if (mode == PromptMode.ConfirmDiscard) 1 else 0)).coerceAtLeast(3))
            }
            mode == PromptMode.ConfirmDelete -> Panel("Move to trash?", accent = Palette.Red) {
                Warning("“${current?.name}” is moved to the vault's .trash folder. You can restore it from there or from Obsidian.")
            }
            wide -> {
                val percent = app.config.promptsListPercent.coerceIn(LIBRARY_LIST_MIN, LIBRARY_LIST_MAX)
                val left = ((width - 1) * percent / 100).coerceAtLeast(28)
                val right = (width - 1 - left).coerceAtLeast(30)
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(percent / 100f)) {
                        CompositionLocalProvider(LocalContentWidth provides left) {
                            Panel("Prompt templates · ${templates.size}", Modifier.height(listRows + 3)) { TemplateList(templates, picker, listRows, showMeta = false) }
                        }
                    }
                    HGap()
                    Box(Modifier.weight((100 - percent) / 100f)) {
                        CompositionLocalProvider(LocalContentWidth provides right) {
                            Panel(current?.name ?: "Preview", Modifier.height(listRows + 3), accent = Palette.Dim) { TemplatePreview(current, listRows) }
                        }
                    }
                }
            }
            else -> Panel("Prompt templates · ${templates.size}") { TemplateList(templates, picker, listRows, showMeta = true) }
        }
        ActionBar(actions)
    }
}

@Composable
private fun TemplateList(templates: List<PromptTemplate>, picker: PickerState, rows: Int, showMeta: Boolean) {
    Picker(
        items = templates, state = picker, label = { t -> t.name },
        rows = rows, icon = { "❯" }, iconColor = { Palette.Accent },
        meta = { t -> if (showMeta) t.description else t.placeholders.size.let { if (it == 0) "" else "$it blank${if (it == 1) "" else "s"}" } },
        placeholder = "Press / to filter prompts",
        emptyText = "No prompt templates yet. Press n to write one: it becomes a note in the ${PromptTemplates.FOLDER} folder of your vault.",
    )
}

@Composable
private fun TemplatePreview(t: PromptTemplate?, rows: Int) {
    if (t == null) return Hint("Choose a prompt to see what it says.")
    if (t.description.isNotBlank() && !t.body.startsWith(t.description)) Label(t.description, color = Palette.Muted)
    if (t.placeholders.isNotEmpty()) Label("Asks for: " + t.placeholders.joinToString(", "), color = Palette.Orange)
    val lines = wrapWords(sanitizeForTerminal(t.body), LocalContentWidth.current - 4)
    val room = (rows - 1 - (if (t.placeholders.isEmpty()) 0 else 1) - (if (t.description.isNotBlank() && !t.body.startsWith(t.description)) 1 else 0)).coerceAtLeast(1)
    lines.take(room).forEach { Label(it) }
    if (lines.size > room) Label("… ${lines.size - room} more lines (e edits it)", color = Palette.Muted)
}
