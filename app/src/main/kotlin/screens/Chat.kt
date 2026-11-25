package com.arcana.brain.app.screens

import com.arcana.brain.app.cancelOracul
import com.arcana.brain.app.clearGoal
import com.arcana.brain.app.compact
import com.arcana.brain.app.compare
import com.arcana.brain.app.deleteCurrent
import com.arcana.brain.app.history
import com.arcana.brain.app.offerMemory
import com.arcana.brain.app.open
import com.arcana.brain.app.revise
import com.arcana.brain.app.secondOpinion
import com.arcana.brain.app.startGoal
import com.arcana.brain.app.startNew
import com.arcana.brain.app.takeOver
import com.arcana.brain.core.effectiveContext
import com.arcana.brain.core.contextTokens
import com.arcana.brain.core.contextUsage
import com.arcana.brain.core.thinkOptions
import com.idea.ink.text.wrapRows

import com.idea.ink.text.sanitizeForTerminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.ChatSession
import com.arcana.brain.app.GenState
import com.arcana.brain.app.Generation
import com.arcana.brain.app.design.*
import com.idea.ink.ui.*
import com.idea.ink.theme.*
import com.idea.ink.input.Key
import com.idea.ink.ui.KeyHandler
import com.idea.ink.ui.TextFieldState
import com.idea.ink.ui.char
import com.arcana.brain.app.navigation.Command
import com.arcana.brain.app.navigation.Commands
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ModelSwitch
import com.arcana.brain.core.Compaction
import com.arcana.brain.core.OraculSteps
import com.arcana.brain.core.RetrievalMode
import com.arcana.brain.app.navigation.LibraryFilter
import com.arcana.brain.core.Artifacts
import com.arcana.brain.core.Events
import com.arcana.brain.core.Flow
import com.arcana.brain.core.MemoryAudit
import com.arcana.brain.core.Oracul
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import com.arcana.brain.core.ImageFiles
import com.arcana.brain.core.TextFiles
import com.arcana.brain.core.StoredChat
import com.arcana.brain.core.ToolCall
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.Skills
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.Vault
import com.arcana.brain.app.SystemOpen
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.ui.RowScope
import com.jakewharton.mosaic.modifier.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import java.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Below this many tokens a long chat, an attached note or a few tool results no longer fit and the oldest text is silently dropped. */
internal const val COMFORTABLE_CONTEXT = 32_768

/** The question and its options as one row each (what the panel shows and what its height is counted from). */
internal fun questionLines(q: ChatSession.Question, width: Int): List<String> {
    val text = wrapText(sanitizeForTerminal(q.text).replace("`", ""), width).take(6)
    val options = q.options.mapIndexed { i, option ->
        val mark = if (q.multiple) (if (i in q.picked) "[x] " else "[ ] ") else ""
        (if (i == q.cursor) "▸ " else "  ") + "$mark${i + 1}  ${sanitizeForTerminal(option)}".take(width - 2)
    }
    return text + options
}

internal fun questionActions(q: ChatSession.Question) =
    if (q.typing) listOfNotNull(ActionItem("Enter", "Send answer", q.field.text.isNotBlank(), Palette.Cta), if (q.options.isNotEmpty()) ActionItem("↑", "Back to options") else null, ActionItem("Esc", "Skip"))
    else listOfNotNull(
        ActionItem("↑↓", "Move"),
        ActionItem("Enter", if (q.multiple) "Confirm" else "Choose", accent = Palette.Cta),
        if (q.multiple) ActionItem("Space", "Tick") else null,
        ActionItem("1-${q.options.size}", "Pick"),
        ActionItem("t", "Type my own"),
        ActionItem("Esc", "Skip"),
    )

internal fun endsWithBackslash(field: com.idea.ink.ui.TextFieldState): Boolean {
    val at = if (field.caretAt in 0..field.text.length) field.caretAt else field.text.length
    return at > 0 && field.text[at - 1] == '\\' && field.selection == null
}

/** How tall the message field may grow before it scrolls: a quarter of the screen, between 3 and 8 rows. */
@Composable
internal fun maxInputRows(): Int = (LocalContentHeight.current / 4).coerceIn(3, 8)
internal const val MaxNotes = 5

/** How many queued messages are listed above the message field; the rest are counted. */
internal const val QUEUE_SHOWN = 3

internal enum class ChatMode { Chat, History, PickNote, PickTarget, ConfirmReplace, Save, Leave, ConfirmClear, ConfirmCancel, ConfirmMemory, Artifacts, Think, Sampling, Status, Context, Model }

/** What happens to the last answer when it is sent to an existing note. */
internal enum class Target(val verb: String) { Append("Append"), Replace("Replace") }

/** One row of the `/think` picker: [value] is what is stored (blank = the Settings value), [label] and [note] are what is shown. */
internal class ThinkChoice(val value: String, val label: String, val note: String)

/** A note offered in a picker: its vault path and when it last changed. */
internal class NoteChoice(val path: String, val modified: Long)

/**
 * The conversation with the model, and the hub of the app: the message field always has the keyboard. `@` attaches a
 * note, `Ctrl+O` a file or picture, `Ctrl+S` saves the chat, `Ctrl+P` lists every action, and `/vault` makes answers come from
 * your notes, with the notes they used listed under them. `Esc` leaves the field to scroll, select and copy.
 */
@Composable
fun ChatScreen(app: AppContext) {
    val chat = app.chat
    val gen = chat.gen
    val navigator = LocalNavigator.current
    val output = remember { OutputState() }
    val historyPicker = remember { PickerState() }
    val thinkPicker = remember { PickerState() }
    val modelPicker = remember { ModelPickerState() }
    val sampling = remember { SamplingDialog() }
    val paneEditor = remember { PaneEditor() }
    // with the status pane on screen, Ctrl+R edits the sampling in the pane itself instead of opening the full dialog
    val statusFits = Docks.sideOf(app.config, PaneId.Status) != null && LocalContentWidth.current >= 80
    var thinkChoices by remember { mutableStateOf(emptyList<ThinkChoice>()) }
    val notePicker = remember { PickerState() }
    val input = chat.input
    var mode by remember { mutableStateOf(ChatMode.Chat) }
    val artifactsDialog = remember { ArtifactsState() }
    // the context inspector: the part chosen, and the scroll of the part opened with Enter
    val inspector = remember { ContextInspectorState() }
    // Chat opens with the field NOT focused (browsing): j/k/g/s and the number keys are shortcuts until Enter starts typing; Esc leaves the field again.
    var browsing by remember { mutableStateOf(true) }
    var past by remember { mutableStateOf<List<StoredChat>>(emptyList()) }
    var leaving by remember { mutableStateOf<Screen?>(null) }
    var saving by remember { mutableStateOf<SaveRequest?>(null) }
    val saveTitle = remember { TextFieldState() }
    var target by remember { mutableStateOf(Target.Append) }
    var replaceTarget by remember { mutableStateOf<String?>(null) }
    // set while the Save dialog is picking an existing note to overwrite with the chat (or its last answer) instead of creating a new one
    var overwriting by remember { mutableStateOf<SaveRequest?>(null) }

    fun toast(text: String, error: Boolean = false) = navigator.showToast(text, error)

    // Chats are kept in the history automatically, so nothing is lost by leaving: ask only when they are not being kept
    val latestUnsaved by rememberUpdatedState(chat.unsaved && !app.config.keepChats)
    DisposableEffect(navigator) {
        navigator.leaveGuard = { to ->
            if (latestUnsaved && to != Screen.Chat) {
                leaving = to
                mode = ChatMode.Leave
                true
            } else {
                false
            }
        }
        onDispose { navigator.leaveGuard = null }
    }

    val lastAnswer = chat.messages.lastOrNull { it.role == "assistant" && it.toolCalls.isEmpty() }?.content

    fun startNew() {
        if (gen.state == GenState.Running) {
            toast("Wait for the reply to finish (or press Esc to cancel it) before starting a new chat.", error = true)
            return
        }
        gen.reset()
        output.reset()
        chat.startNew()
        browsing = false
        toast(if (app.config.keepChats) "New chat. The previous one is in the history." else "New chat.")
    }

    fun openHistory() {
        val chats = chat.history()
        if (chats.isEmpty()) {
            toast(if (app.config.keepChats) "No saved chats yet." else "Chats are not being kept. Turn on Keep chats in Settings → Console.", error = true)
            return
        }
        historyPicker.reset()
        past = chats
        mode = ChatMode.History
    }

    fun noteChoices(): List<NoteChoice> =
        app.brain.index?.notes?.sortedByDescending { it.modified }?.map { NoteChoice(it.path, it.modified) }
            ?: app.openVault().getOrNull()?.notes()?.map { NoteChoice(it.path, it.modified) }.orEmpty()

    fun openNotePick() {
        if (app.openVault().isFailure) {
            toast("Choose your vault folder in Settings first.", error = true)
            return
        }
        if (chat.attachments.notes.size >= MaxNotes) {
            toast("At most $MaxNotes notes per message.", error = true)
            return
        }
        notePicker.reset()
        notePicker.startFiltering()
        mode = ChatMode.PickNote
    }

    fun openTargetPick(action: Target) {
        if (lastAnswer == null) {
            toast("There is no answer to use yet.", error = true)
            return
        }
        target = action
        notePicker.reset()
        notePicker.startFiltering()
        mode = ChatMode.PickTarget
    }

    fun openAttach() {
        mode = ChatMode.Chat
        chat.attachments.open { text, error -> toast(text, error) }
    }

    fun openSave(after: Screen?) {
        if (chat.messages.isEmpty()) {
            toast("Nothing to save yet. Send a message first.", error = true)
            return
        }
        saveTitle.set(Vault.sanitizeTitle("Chat - ${chat.title}"))
        saving = SaveRequest(after)
        mode = ChatMode.Save
    }

    fun doSave(request: SaveRequest) {
        val result = runCatching {
            val vault = app.openVault().getOrThrow()
            val body = if (request.lastAnswerOnly) lastAnswer ?: error("There is no answer to save yet.") else transcript(chat.messages, app.config.model)
            vault.create(app.config.outputFolder, saveTitle.text, body, app.config.model).path
        }
        result.onSuccess { path ->
            chat.savedMessages = chat.messages.size
            app.brain.changed()
            toast("Saved as $path")
            saving = null
            mode = ChatMode.Chat
            request.after?.let { navigator.navigate(it, force = true) }
        }.onFailure { toast(it.message ?: "Could not save.", error = true) }
    }

    fun applyToNote(path: String) {
        val whole = overwriting
        val answer = (if (whole == null) lastAnswer else if (whole.lastAnswerOnly) lastAnswer else transcript(chat.messages, app.config.model)) ?: return
        val result = runCatching {
            val vault = app.openVault().getOrThrow()
            when (target) {
                Target.Append -> { vault.append(path, "From chat", answer); "Added the answer to $path" }
                Target.Replace -> "Replaced $path. The old text is in ${vault.replace(path, answer)}"
            }
        }
        result.onSuccess {
            app.brain.changed()
            toast(it)
            // the chat is in the vault now, so leaving it does not ask again
            if (whole != null) { chat.savedMessages = chat.messages.size; saving = null; whole.after?.let { next -> navigator.navigate(next, force = true) } }
        }.onFailure { toast(it.message ?: "Could not write to the note.", error = true) }
        replaceTarget = null
        overwriting = null
        mode = ChatMode.Chat
    }

    fun toggleVault() {
        val on = !app.config.chatUseVault
        app.update(app.config.copy(chatUseVault = on))
        toast(if (on) "Answering from your vault: each question searches your notes and the answer cites them." else "Answering from the model's own knowledge.")
    }

    fun toggleTools() {
        val on = !app.config.toolsEnabled
        app.update(app.config.copy(toolsEnabled = on))
        when {
            !on -> toast("Tools are off: the model only answers.")
            app.tools == false -> toast("${app.config.model} does not support tools, so it will only answer. Choose another model in Settings → Ollama → Model.", error = true)
            else -> toast("Tools are on: the model may search and read your notes and files, and ask before changing anything. Folders: Settings → Tools.")
        }
    }

    fun openContext() {
        if (chat.lastContext == null) toast("Nothing has been sent in this chat yet: send a message, then /context shows exactly what the model was sent.")
        else { inspector.open(); mode = ChatMode.Context }
    }

    fun toggleThinkingPane() {
        val on = !app.config.thinkingPane
        if (on) Docks.refusal(app.config, PaneId.Thinking)?.let { return toast(it, error = true) }
        app.update(app.config.copy(thinkingPane = on))
        toast(if (on) "Thinking pane on: it follows the model's thoughts under the conversation ([ and ] change its size and hide it; needs a model that can think and a terminal at least 26 rows tall)." else "Thinking pane off.")
    }

    fun toggleStatusPane() {
        val on = !app.config.statusPane
        if (on) Docks.refusal(app.config, PaneId.Status)?.let { return toast(it, error = true) }
        app.update(app.config.copy(statusPane = on))
        val left = app.config.statusPanePlacement == Docks.LEFT
        toast(if (on) "Status pane on: the model, sampling options and context window in a ${app.config.statusPanePlacement}-side column (${if (left) "} wider, { narrower" else "{ wider, } narrower"} and off; Ctrl+R edits the sampling in it)." else "Status pane off.")
    }

    /**
     * `]` / `[` (empty field or browsing): the one pair of keys for the pane. `]` switches it on when it is off, then makes it 5 % larger (up to [THINKING_MAX]);
     * `[` makes it 5 % smaller and, at [THINKING_MIN], switches it off. The same 5 % step and the same 10 to 90 % range as Settings → Console → Thinking pane size
     * and as the Library's list width. `/thinking` and Settings switch the pane too.
     */
    fun stepThinkingPane(larger: Boolean) {
        val now = app.config.thinkingPanePercent.coerceIn(THINKING_MIN, THINKING_MAX)
        if (!app.config.thinkingPane) {
            if (!larger) return
            Docks.refusal(app.config, PaneId.Thinking)?.let { return toast(it, error = true) }
            app.update(app.config.copy(thinkingPane = true, thinkingPanePercent = now))
            return toast("Thinking pane on at $now % ([ smaller, ] larger, step 5 %; [ at $THINKING_MIN % hides it; needs a model that can think and a terminal at least 26 rows tall).")
        }
        if (larger && now >= THINKING_MAX) return toast("The thinking pane is already at its largest: $now %.")
        if (!larger && now <= THINKING_MIN) {
            app.update(app.config.copy(thinkingPane = false, thinkingPanePercent = THINKING_MIN))
            return toast("Thinking pane off (] turns it on again at $THINKING_MIN %).")
        }
        val next = if (larger) (now / 5 + 1) * 5 else ((now + 4) / 5 - 1) * 5
        app.update(app.config.copy(thinkingPanePercent = next.coerceIn(THINKING_MIN, THINKING_MAX)))
        toast("Thinking pane: ${next.coerceIn(THINKING_MIN, THINKING_MAX)} % ([ smaller, ] larger, step 5 %; [ at $THINKING_MIN % hides it).")
    }

    /**
     * `{` / `}` (empty field or browsing): the status pane's keys. The pane is on the right, so the keys point the way its left edge moves: `{` makes it wider (and switches it on when it is
     * off), `}` narrower and, at [THINKING_MIN], off. The 5 % step and the 10 to 90 % range are the thinking pane's. The rules are in [statusPaneStep].
     */
    fun stepStatusPane(wider: Boolean) {
        if (!app.config.statusPane && wider) Docks.refusal(app.config, PaneId.Status)?.let { return toast(it, error = true) }
        val step = statusPaneStep(app.config.statusPane, app.config.statusPanePercent, wider, left = app.config.statusPanePlacement == Docks.LEFT)
        if (step.on != app.config.statusPane || step.percent != app.config.statusPanePercent) app.update(app.config.copy(statusPane = step.on, statusPanePercent = step.percent))
        if (step.message.isNotEmpty()) toast(step.message)
    }

    fun toggleSkills() {
        val on = !app.config.skillsEnabled
        app.update(app.config.copy(skillsEnabled = on))
        if (!on) return toast("Skills are off: the model is not told about them.")
        val vault = app.openVault().getOrNull() ?: return toast("Choose a vault first (Settings → Vault).", error = true)
        val found = Skills.discoverAll(vault, app.skillsFolder())
        toast(when {
            found.isEmpty() -> "Skills are on, but there are none in ${app.skillsFolder()}/ yet (one folder per skill; see the Skills Guide note)."
            !app.config.toolsEnabled -> "${found.size} skill(s): ${found.joinToString { it.name }}. Turn tools on (/tools) so the model can load them."
            else -> "${found.size} skill(s): ${found.joinToString { Skills.summary(it) }}. Type / and pick one."
        }, error = found.any { it.problems.isNotEmpty() })
    }

    fun openMemory() {
        val vault = app.openVault().getOrNull() ?: return toast("Choose a vault first (Settings → Vault).", error = true)
        val file = runCatching { vault.ensureMemory() }.getOrElse { return toast("Could not create ${vault.memoryName()}.", error = true) }
        val opened = SystemOpen.open(file.toString())
        toast(if (opened) "${vault.memoryName()} opened in your editor. Save it; the next message uses it." else "Edit ${file} (it is read in every chat).")
    }

    /** What `/memory check` found, shown in a panel that asks before the repeated lines are removed. */
    var memoryAudit by remember { mutableStateOf<MemoryAudit?>(null) }

    fun openMemoryCheck() {
        val vault = app.openVault().getOrNull() ?: return toast("Choose a vault first (Settings → Vault).", error = true)
        val audit = runCatching { vault.auditMemory() }.getOrElse { return toast("Could not read ${vault.memoryName()}.", error = true) }
        if (audit.clean) return toast("${vault.memoryName()} is clean: no repeated lines and no dead note paths (${vault.memoryEntries().size} entries).")
        memoryAudit = audit
        mode = ChatMode.ConfirmMemory
    }

    // everything the slash menu knows (commands, skills, tools); set once the menu's list is built, read when a typed line is sent
    var slashItemsNow: List<SlashItem> = SlashCommands
    var useSlashNow: (SlashItem) -> Unit = {}

    /** [only]: ask this model instead of the Oracul model, for this one call (`/oracul qwen3:30b`). */
    fun runOracul(compare: Boolean, only: String? = null) {
        if (gen.state == GenState.Running) return toast("Wait for the reply to finish first.", error = true)
        if (chat.consulting != null) return toast("The Oracul is already working.", error = true)
        toast("Asking ${if (compare) "the Oracul and the judge" else (only ?: com.arcana.brain.core.Oracul.model(app.config)).ifEmpty { "the Oracul" }}… (Esc cancels)")
        chat.manualConsult = app.scope.launch {
            runCatching { if (compare) chat.compare(only) else chat.secondOpinion(only) }.onSuccess { toast(it) }.onFailure { if (it is kotlinx.coroutines.CancellationException) toast("Cancelled: nothing was added.") else toast(it.message ?: "The Oracul could not answer.", error = true) }
        }
    }

    fun runTakeover() {
        if (gen.state == GenState.Running) return toast("Wait for the reply to finish first.", error = true)
        if (chat.consulting != null) return toast("The Oracul is already working.", error = true)
        toast("${com.arcana.brain.core.Oracul.model(app.config).ifEmpty { "The Oracul" }} is taking over… (Esc cancels)")
        chat.manualConsult = app.scope.launch {
            runCatching { chat.takeOver() }.onSuccess { gen.reset(); toast(it) }.onFailure { if (it is kotlinx.coroutines.CancellationException) toast("Cancelled.") else toast(it.message ?: "The Oracul could not take over.", error = true) }
        }
    }

    fun runCompact() {
        if (gen.state == GenState.Running) return toast("Wait for the reply to finish before compacting.", error = true)
        if (chat.compacting) return toast("Already compacting.", error = true)
        toast("Compacting: the model is summarizing the older messages…")
        app.scope.launch {
            runCatching { chat.compact() }.onSuccess { toast(it) }.onFailure { toast(it.message ?: "Could not compact.", error = true) }
        }
    }

    /** `/effort xhigh`: the next message's level typed directly; it must be one the model lists (`auto` = the model default, `settings` = the Settings value). */
    fun setEffortFromText(text: String) {
        val options = thinkOptions(app.thinkingValues)
        if (app.thinkingValues.isEmpty()) return toast("${app.config.model} does not offer a thinking switch (Ollama lists none), so nothing is sent.", error = true)
        val word = text.trim().lowercase().let { if (it == "auto" || it == "model default") "default" else it }
        if (word == "settings") { chat.thinkNext = ""; return toast("The next message uses the Settings value (think ${thinkLabel(app.config.thinking).lowercase()}).") }
        if (word !in options) return toast("${app.config.model} offers: ${options.joinToString(", ") { if (it == "default") "auto" else it }}. Type /effort alone to pick from the list.", error = true)
        chat.thinkNext = word
        toast("Next message: think ${if (word == "default") "auto" else word}. After you send it, the Settings value is used again.")
    }

    /**
     * `/effort` (and `/think`, the old name): a list of the reasoning choices **the current model offers** (read from Ollama's `/api/show` → `thinking.values`: `off`/`on` for most, `low · medium · high` for gpt-oss,
     * `low · medium · xhigh` for qwen3.8). Enter picks one for the next message only; `d` makes it the Settings value.
     */
    fun openThinkPicker() {
        val options = thinkOptions(app.thinkingValues)
        if (app.thinkingValues.isEmpty()) return toast("${app.config.model} does not offer a thinking switch (Ollama lists none), so nothing is sent.", error = true)
        val modelDefault = thinkDefaultWord(app.thinkingDefault)
        val settingNow = app.config.thinking.takeIf { it in options } ?: "default"
        fun note(v: String) = when (v) {
            "default" -> "model decides${if (modelDefault.isEmpty()) "" else " ($modelDefault)"}"
            "off" -> "no reasoning: fast"
            "on" -> "reasons first"
            else -> "effort level${if (v == modelDefault) " · default" else ""}"
        }
        thinkChoices = listOf(ThinkChoice("", "Use the Settings value", "now: ${if (settingNow == "default") "model default" + (if (modelDefault.isEmpty()) "" else " ($modelDefault)") else thinkLabel(settingNow).lowercase()}")) + options.map { ThinkChoice(it, thinkLabel(it), note(it)) }
        thinkPicker.reset()
        thinkPicker.index = if (chat.thinkNext.isEmpty()) 0 else thinkChoices.indexOfFirst { it.value == chat.thinkNext }.coerceAtLeast(0)
        mode = ChatMode.Think
    }

    /**
     * Makes [model] the chat model (the same setting as Settings → Ollama → Model: saved, and used from the next message; the conversation stays). Not while a reply or
     * the Oracul is at work. With [retry], once Ollama has said what the new model can do (tools, thinking levels, vision), your last message is sent again to it.
     */
    fun switchModel(model: String, retry: Boolean) {
        if (gen.state == GenState.Running || chat.consulting != null) return toast("Wait for the reply to finish (or Esc to stop it), then switch the model.", error = true)
        val old = app.config.model
        if (old != model) app.update(app.config.copy(model = model))
        app.scope.launch {
            app.refreshConnection()
            val toolsNote = if (app.config.toolsEnabled && app.tools == false) " It cannot use tools, so it answers from the chat alone." else ""
            val same = if (old == model) "$model is already the chat model." else "Chat model: $model (was $old).$toolsNote"
            if (!retry) return@launch toast(same, error = toolsNote.isNotEmpty())
            if (chat.retryLastTask()) toast("$same Running your last message again.") else toast("$same Nothing to run again: you have not written a message yet.", error = true)
        }
    }

    /**
     * `/model`: the installed models in a list ([ModelPickerState]); `/model qwen3:30b` switches at once (a part of a name is enough when only one model has it:
     * `/model inkling`); `--retry` (or `retry`, `again`) after it also sends your last message again to the new model.
     */
    fun modelCommand(args: String) {
        val request = ModelSwitch.parse(args)
        if (request.retry && ModelSwitch.lastTask(chat.messages.toList()) == null) return toast("Nothing to run again yet: you have not written a message in this chat.", error = true)
        app.scope.launch {
            val details = runCatching { app.client.modelDetails() }.getOrElse { return@launch toast("Could not list the models: ${it.message}. Is Ollama running at ${app.config.ollamaUrl}?", error = true) }
            if (details.isEmpty()) return@launch toast("Ollama has no models installed. Install one with ollama pull, for example ollama pull qwen3:8b.", error = true)
            if (request.name.isEmpty()) { modelPicker.open(details, app.config.model, request.retry); mode = ChatMode.Model; return@launch }
            when (val match = ModelSwitch.resolve(request.name, details.map { it.name })) {
                is ModelSwitch.Match.Found -> switchModel(match.model, request.retry)
                is ModelSwitch.Match.Ambiguous -> toast("“${match.typed}” fits ${match.candidates.size} models: ${match.candidates.take(5).joinToString(", ")}. Type more of the name, or /model to pick.", error = true)
                is ModelSwitch.Match.Missing -> toast("${match.typed} is not installed. /model lists what is; ollama pull ${match.typed} installs it.", error = true)
            }
        }
    }

    /** `/sampling` (Ctrl+R): the dialog for temperature, top P, top K, min P and the penalties; `/sampling model` sets the model's own defaults, `/sampling custom` switches to your values. */
    fun openSampling(args: String = "") {
        when (args.trim().lowercase()) {
            "" -> if (statusFits) { paneEditor.open(); mode = ChatMode.Status } else { sampling.open(); mode = ChatMode.Sampling }
            "model" -> { app.update(com.arcana.brain.core.Sampling.toModel(app.config)); toast("Model defaults: nothing is sent, the model\'s own settings apply.") }
            "custom" -> { app.update(com.arcana.brain.core.Sampling.toCustom(app.config, app.modelParameters)); toast("Custom: your values are sent (Ctrl+R changes them).") }
            else -> toast("/sampling takes model or custom, or nothing to open the dialog.", error = true)
        }
    }

    fun toggleAutoRun() {
        if (!app.config.toolShell) return toast("Shell commands are off. Turn them on in Settings → Tools first.", error = true)
        val on = !app.config.toolAutoShell
        app.update(app.config.copy(toolAutoShell = on))
        toast(if (on) "Auto-run: shell commands now run WITHOUT asking (sudo, rm -rf / and the like are still refused)." else "Commands ask before they run again.", error = on)
    }

    /** `/flow` switches the flow on (the first preset) or off; `/flow careful` or `/flow off` sets it by name. */
    fun setFlow(name: String) {
        val wanted = name.trim().lowercase().ifEmpty { if (app.config.flow == Flow.OFF) Flow.CAREFUL else Flow.OFF }
        if (wanted !in Flow.NAMES) return toast("There is no flow called “$wanted”. Flows: ${Flow.NAMES.joinToString(", ")}.", error = true)
        app.update(app.config.copy(flow = wanted))
        toast(if (wanted == Flow.OFF) "Flow off: the chat model answers alone." else "Flow $wanted: every message goes analyst → planner → worker → reviewer → controller (several models run for each message; messages under 60 characters go straight to the worker).")
    }

    /** `/goal text` sets the goal and starts on it, `/goal` shows it, `/goal clear` drops it. */
    fun runGoal(args: String) {
        when {
            args.isEmpty() -> toast(chat.goal?.let { "Goal: $it" } ?: "No goal. Set one with /goal <what must be true>.")
            args.equals("clear", ignoreCase = true) -> { chat.clearGoal(); toast("Goal cleared.") }
            gen.state == GenState.Running -> toast("Wait for the reply to finish before setting a goal.", error = true)
            else -> chat.startGoal(args)
        }
    }

    fun cycleMode() {
        val next = when (app.config.toolMode) { "ask" -> "auto"; "auto" -> "plan"; else -> "ask" }
        app.update(app.config.copy(toolMode = next))
        toast(when (next) {
            "auto" -> "Auto mode: changes run at once; replaced files are backed up."
            "plan" -> "Plan mode: read-only. The model investigates and proposes a plan; nothing is changed."
            else -> "Ask mode: every change waits for your approval."
        })
    }

    fun runCommand(command: String, args: String = "") {
        // text after a command is only meaningful for these; anywhere else say so instead of guessing
        if (args.isNotEmpty() && command !in setOf("/oracul", "/compare", "/revise", "/flow", "/goal", "/memory", "/effort", "/think", "/sampling", "/model")) return toast("$command takes no text after it. Type just $command.", error = true)
        when (command) {
            "/new" -> startNew()
            "/history" -> openHistory()
            "/clear" -> mode = ChatMode.ConfirmClear
            "/save" -> openSave(null)
            "/image" -> openAttach()
            "/note" -> openNotePick()
            "/vault" -> toggleVault()
            "/tools" -> toggleTools()
            "/memory" -> when (args.lowercase()) { "" -> openMemory(); "check" -> openMemoryCheck(); else -> toast("/memory takes only “check” after it.", error = true) }
            "/skills" -> toggleSkills()
            "/reindex" -> chat.semantic.reindex { text, error -> toast(text, error = error) }
            "/thinking" -> toggleThinkingPane()
            "/status" -> toggleStatusPane()
            "/flow" -> setFlow(args)
            "/helpers" -> { val on = !app.config.oraculEnabled; app.update(app.config.copy(oraculEnabled = on)); toast(if (on) "Helper models on." else "Helper models off: only ${app.config.model} works (skill roles are separate: /roles).") }
            "/roles" -> { val on = !app.config.skillRolesEnabled; app.update(app.config.copy(skillRolesEnabled = on)); toast(if (on) "Skill roles on: skills use the worker, planner and reviewer they name." else "Skill roles off: a skill is run by ${app.config.model} alone.") }
            "/context" -> openContext()
            "/artifacts" -> if (chat.artifacts.isEmpty()) toast("Nothing written or changed in this chat yet.") else { artifactsDialog.index = 0; mode = ChatMode.Artifacts }
            "/goal" -> runGoal(args)
            "/mode" -> cycleMode()
            "/autorun" -> toggleAutoRun()
            "/effort", "/think" -> if (args.isBlank()) openThinkPicker() else setEffortFromText(args)
            "/sampling" -> openSampling(args)
            "/model" -> modelCommand(args)
            "/compact" -> runCompact()
            "/oracul" -> runOracul(compare = false, only = args.ifEmpty { null })
            "/compare" -> runOracul(compare = true, only = args.ifEmpty { null })
            "/takeover" -> runTakeover()
            "/revise" -> runCatching { chat.revise(args) }.onFailure { toast(it.message ?: "Nothing to revise.", error = true) }
            "/append" -> openTargetPick(Target.Append)
            "/replace" -> openTargetPick(Target.Replace)
            else -> toast(unknownCommand(command, slashItemsNow), error = true)
        }
    }

    fun send() {
        var text = input.text.trim()
        if (text.isEmpty() && chat.attachments.isEmpty) return
        if (chat.compacting) return toast("Compacting the chat: wait until the summary is ready, then send your message (your text stays in the field).", error = true)
        // while the model works a message is queued: it joins the turn at the next tool round, or follows the reply
        if (gen.state == GenState.Running) {
            if (text.isEmpty() || text.startsWith("/") || !chat.attachments.isEmpty) return toast("Only plain text can be queued while the model works. Wait for the reply, or cancel it with Esc Esc.", error = true)
            input.clear()
            chat.enqueue(text)
            return
        }
        if (text.startsWith("/") && chat.attachments.isEmpty) {
            val (word, args) = splitSlash(text)
            val known = slashItemsNow.firstOrNull { it.name.equals(word, ignoreCase = true) }
            input.clear()
            if (known == null || !(known.skill || known.tool)) return runCommand(word.lowercase(), args)
            // a skill or a tool typed out with its request after it (`/forecast ETH`, `/read_note Plan`) does what the menu would have done, then the request is sent
            if (known.skill) useSlashNow(known)
            if (args.isEmpty()) { if (known.tool) input.set("Use the ${known.name} tool to "); return }
            text = if (known.tool) "Use the ${known.name} tool to $args" else args
        }
        input.clear()
        output.reset()
        if (app.config.toolsEnabled && app.tools == false) toast("${app.config.model} cannot use tools, so it will only answer. Choose another model in Settings → Ollama → Model.", error = true)
        chat.send(text) { input.set(text) }
    }

    // a prompt template run from the Prompts tab: its text is in the field, and it goes out as soon as the chat is shown
    LaunchedEffect(chat.autoSend) { if (chat.autoSend) { chat.autoSend = false; send() } }

    // Select mode walks entries: each of your messages is one, and a reply is split into its Markdown blocks.
    val shownMessages = chat.messages.toList() + if (gen.text.isNotEmpty()) listOf(ChatMessage.assistant(gen.text)) else emptyList()
    val entries = shownMessages.flatMap(::entriesOf)

    Commands(
        listOf(
            Command("New chat", "Chat", "/new") { startNew() },
            Command("Chat history…", "Chat", "/history") { openHistory() },
            Command("Save chat to the vault…", "Chat", "Ctrl+S") { openSave(null) },
            Command("Attach a note…", "Chat", "@") { openNotePick() },
            Command("Attach a file or picture…", "Chat", "Ctrl+O") { openAttach() },
            Command("Memory file (ARCANA.md): open and edit", "Chat", "/memory") { openMemory() },
            Command(if (app.config.skillsEnabled) "Skills: turn off" else "Skills: turn on (list them)", "Chat", "/skills") { toggleSkills() },
            Command("Semantic search: build or refresh the index of the vault", "Chat", "/reindex") { chat.semantic.reindex { text, error -> toast(text, error = error) } },
            Command("Reasoning effort: choose for the next message (what the model offers)", "Chat", "Ctrl+G") { openThinkPicker() },
            Command("Chat model: switch to another installed model", "Chat", "/model") { modelCommand("") },
            Command("Chat model: switch and run my last message again", "Chat", "/model --retry", ModelSwitch.lastTask(chat.messages.toList()) != null) { modelCommand("--retry") },
            Command("Sampling: temperature, top P, top K, min P, penalties", "Chat", "Ctrl+R") { openSampling() },
            Command(if (app.config.thinkingPane) "Thinking pane: turn off" else "Thinking pane: show the model's thoughts live", "Chat", "/thinking") { toggleThinkingPane() },
            Command(if (app.config.statusPane) "Status pane: turn off (model, sampling, context window)" else "Status pane: show model, sampling and context window", "Chat", "/status") { toggleStatusPane() },
            Command("Tools mode: ${app.config.toolMode} → next (ask, auto, plan)", "Chat", "/mode", app.config.toolsEnabled) { cycleMode() },
            Command("Oracul: second opinion on the last answer", "Chat", "/oracul", lastAnswer != null) { runOracul(compare = false) },
            Command("Oracul: compare with another model's answer", "Chat", "/compare", lastAnswer != null) { runOracul(compare = true) },
            Command("Oracul: take over the turn the main model failed", "Chat", "/takeover", gen.state == GenState.Failed) { runTakeover() },
            Command("Oracul: have the main model revise its answer", "Chat", "/revise", Oracul.hasOpenOpinion(chat.messages.toList())) { runCatching { chat.revise() }.onFailure { toast(it.message ?: "Nothing to revise.", error = true) } },
            Command("Compact the chat: summarize the older part", "Chat", "/compact", chat.messages.size >= Compaction.MIN_MESSAGES) { runCompact() },
            Command(if (app.config.toolAutoShell) "Shell commands: ask before running again" else "Shell commands: run without asking", "Chat", "/autorun", app.config.toolsEnabled && app.config.toolShell) { toggleAutoRun() },
            Command(if (app.config.toolsEnabled) "Tools: turn off" else "Tools: turn on (read and write notes and files)", "Chat", "/tools") { toggleTools() },
            Command(if (app.config.chatUseVault) "Answer from my vault: turn off" else "Answer from my vault: turn on", "Chat", "/vault") { toggleVault() },
            Command("Files and notes changed in this chat", "Chat", "Esc then f", chat.artifacts.isNotEmpty()) { artifactsDialog.index = 0; mode = ChatMode.Artifacts },
            Command("What the model was sent (context inspector)", "Chat", "/context", chat.lastContext != null) { openContext() },
            Command("Select & copy", "Chat", "Esc then s", entries.isNotEmpty()) { browsing = true; output.beginSelect { toast(it.text) } },
            Command("Append the last answer to a note…", "Chat", "/append", lastAnswer != null) { openTargetPick(Target.Append) },
            Command("Replace a note with the last answer…", "Chat", "/replace", lastAnswer != null) { openTargetPick(Target.Replace) },
            Command("Erase this chat…", "Chat", "/clear", chat.messages.isNotEmpty()) { mode = ChatMode.ConfirmClear },
        ),
    )

    // typing "/" at the start of a message opens a menu of commands and tools (↑↓ choose, Tab or Enter use, Esc close)
    val slashQuery = input.text.takeIf { mode == ChatMode.Chat && !browsing && chat.question == null && chat.approval == null && it.startsWith("/") && ' ' !in it && '\n' !in it }?.drop(1)
    // the skills are read from the vault folder (never hardcoded): when Chat opens, after each message, and again every time the menu opens, so one added in Obsidian shows up at once
    val skillList = remember(app.config.vaultPath, app.config.skillsEnabled, app.config.skillsFolder, chat.messages.size, slashQuery != null) {
        val vault = app.openVault().getOrNull()
        if (vault == null || !app.config.skillsEnabled) emptyList() else Skills.discoverAll(vault, app.skillsFolder())
    }
    val slashItems = remember(app.config.vaultPath, skillList) {
        SlashCommands + skillList.map { SlashItem("/" + it.name, it.description.ifEmpty { "(no description)" }.substringBefore(". ").take(80).trimEnd('.'), false, skill = true, usage = if (it.usage.isEmpty()) "e.g. /${it.name} <what you want>   (attaches the skill and sends your request)" else "/${it.name} ${it.usage}   (attaches the skill; type the arguments and press Enter)") } + (chat.toolboxFor(app.config.copy(toolsEnabled = true, toolMode = "ask"))?.specs.orEmpty())
            .map { SlashItem(it.name, it.description.substringBefore(". ").substringBefore("\n").take(80).trimEnd('.'), true, kind = SlashKind.forTool(it.name, it.risk), usage = toolUsage(it.name, (it.parameters["required"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { r -> (r as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())) }
    }
    slashItemsNow = slashItems
    val slashMatches = if (slashQuery == null) emptyList() else slashMatches(slashItems, slashQuery).take(8)
    var slashIndex by remember(slashQuery) { androidx.compose.runtime.mutableIntStateOf(0) }
    // an example of how to type the highlighted entry, one row under it
    val slashUsage = slashMatches.getOrNull(slashIndex.coerceIn(0, (slashMatches.size - 1).coerceAtLeast(0)))?.usage.orEmpty()

    fun useSlash(item: SlashItem) {
        if (item.skill) {
            // attaching is explicit: a chip above the field, and Arcana puts the skill's text into the message when it is sent (the model need not call use_skill)
            val skill = skillList.firstOrNull { "/" + it.name == item.name }
            input.clear()
            if (skill == null) return toast("That skill is no longer in ${app.skillsFolder()}/.", error = true)
            if (skill.path !in chat.attachments.notes) chat.attachments.notes += skill.path
            toast(if (app.config.toolsEnabled) "Skill ${skill.name} attached. Type what you want and press Enter (Backspace on an empty field removes it)."
                else "Skill ${skill.name} attached, but tools are off, so it cannot act. Turn them on with /tools.", error = !app.config.toolsEnabled)
        } else if (item.tool) input.set("Use the ${item.name} tool to ")
        else { input.clear(); runCommand(item.name) }
    }

    useSlashNow = ::useSlash
    val attachments = chat.attachments
    val request = saving
    val leave = leaving
    val approval = chat.approval
    val question = chat.question
    FocusMode(
        if (approval != null || question != null) true else when (mode) {
            // while a reply runs and the field is empty, the number keys switch screens (the reply keeps going); the first letter typed brings the field back
            ChatMode.Chat -> if (attachments.browsing) attachments.filtering else !browsing && !(gen.state == GenState.Running && input.text.isEmpty())
            ChatMode.History -> historyPicker.filtering
            ChatMode.Think -> thinkPicker.filtering
            ChatMode.Model -> modelPicker.picker.filtering
            ChatMode.Sampling -> sampling.typing
            ChatMode.Status -> paneEditor.typing
            ChatMode.PickNote, ChatMode.PickTarget -> notePicker.filtering
            else -> true
        },
    )
    if (question != null) Hints(*(if (question.typing) arrayOf("Enter  send the answer", *(if (question.options.isNotEmpty()) arrayOf("↑  back to options") else emptyArray()), "Esc  skip") else arrayOf("↑↓  move", if (question.multiple) "Space  tick" else "Enter  choose", *(if (question.multiple) arrayOf("Enter  confirm") else emptyArray()), "1-${question.options.size}  pick", "t  type my own", "Esc  skip")))
    else if (approval != null) Hints("y  ${approval.yes.lowercase()}", "n  no", *(if (approval.always) arrayOf("a  always this session") else emptyArray())) else if (attachments.browsing) Hints(*attachBrowserHints(attachments).toTypedArray()) else when (mode) {
        ChatMode.Save -> Hints("Enter  save", "Tab  whole chat / last answer", "Ctrl+O  overwrite a note", "Esc  cancel")
        ChatMode.Leave -> Hints("w  save to vault", "Esc  leave chat", "any other key  stay")
        ChatMode.ConfirmClear -> Hints("y  erase this chat", "any other key  keep")
        ChatMode.Artifacts -> Hints("↑↓  choose", "Enter  open the note in the Library", "1-9  open that one", "any other key  back")
        ChatMode.Context -> if (inspector.reading) Hints("↑↓  scroll", "PgUp/PgDn  page", "c  copy it", "Esc  back to the parts") else Hints("↑↓  choose", "Enter  read the part as sent", "c  copy it", "Esc  back")
        ChatMode.ConfirmMemory -> Hints("y  remove the repeated lines", "any other key  leave it as it is")
        ChatMode.ConfirmCancel -> Hints("Enter  cancel it", "y  cancel it", "any other key  keep going")
        ChatMode.ConfirmReplace -> Hints("y  replace", "any other key  keep the note")
        ChatMode.History -> Hints("↑↓  move", "Enter  open", "/  filter", "Esc  back")
        ChatMode.Think -> Hints("↑↓  move", "Enter  use for the next message", "d  make it the Settings value", "Esc  back")
        ChatMode.Model -> if (modelPicker.retry) Hints("↑↓  move", "Enter  switch and run the last message again", "/  filter", "Esc  back") else Hints("↑↓  move", "Enter  switch", "r  switch and run the last message again", "/  filter", "Esc  back")
        ChatMode.Sampling -> if (sampling.typing) Hints("Enter  save", "blank  the model's own", "Ctrl+U  clear line", "Esc  cancel") else Hints("↑↓  move", "←→  change", "Enter  type a value", "m  mode", "r  model defaults", "Esc  back")
        ChatMode.Status -> if (paneEditor.typing) Hints("Enter  save", "Ctrl+U  clear line", "Esc  cancel") else Hints("↑↓  move", "←→  change", "Enter  type or next", "m  sampling mode", "r  model defaults", "Esc  done")
        ChatMode.PickNote -> Hints("type  search", "↑↓  move", "Enter  attach", "Esc  cancel")
        ChatMode.PickTarget -> Hints("type  search", "↑↓  move", "Enter  ${target.verb.lowercase()}", "Esc  cancel")
        ChatMode.Chat -> when {
            output.selecting -> Hints("↑↓  move", "Space  tick", "c  copy", "b  code only", "Esc  leave")
            chat.compacting -> Hints("Wait: summarizing the chat, then you can send", "↑↓  scroll")
            gen.state == GenState.Running -> Hints("Enter  queue a message", *(if (chat.queued.isNotEmpty()) arrayOf("Backspace  remove queued") else emptyArray()), "Esc  cancel reply", "↑↓  scroll", "[ ]  thinking", "{ }  status pane")
            browsing -> Hints("Enter  type a message", "s  select & copy", "↑↓/Space  scroll", "1-9  screens", "[ ]  thinking", "{ }  status pane")
            slashMatches.isNotEmpty() -> Hints("↑↓  choose", "Tab  complete", "Enter  run", "Esc  close")
            else -> Hints("Enter  send", "Alt+Enter  new line", "/  commands", "@  note", "Ctrl+O  attach", "Ctrl+S  save", "↑↓  scroll", "[ ]  thinking", "{ }  status pane")
        }
    }

    // a vault without ARCANA.md gets one, with an offer to fill it in
    androidx.compose.runtime.LaunchedEffect(Unit) { chat.offerMemory() }
    // a skill asked for a model that cannot be used: say so once
    androidx.compose.runtime.LaunchedEffect(chat.routeNote) { chat.routeNote?.let { toast(it); chat.routeNote = null } }
    androidx.compose.runtime.LaunchedEffect(chat.roleNote) { chat.roleNote?.let { toast(it, error = true); chat.roleNote = null } }
    // the worker failed this turn: offer the Oracul (never automatic, it is one keystroke away)
    // a reply that was cancelled or failed does not send what was queued behind it: it goes back to the message field
    androidx.compose.runtime.LaunchedEffect(gen.state) {
        if ((gen.state == GenState.Failed || gen.state == GenState.Cancelled) && chat.queued.isNotEmpty()) {
            val back = chat.takeQueued()
            input.set(if (input.text.isBlank()) back else back + "\n\n" + input.text)
            toast("Your queued message is back in the field.")
        }
    }
    androidx.compose.runtime.LaunchedEffect(gen.state) {
        if (gen.state == GenState.Failed && com.arcana.brain.core.Oracul.model(app.config).isNotEmpty() && app.config.oraculAuto != "off") toast("The model failed this turn. Type /takeover to let ${com.arcana.brain.core.Oracul.model(app.config)} finish it.")
    }

    KeyHandler { key ->
        if (question != null) {
            // a question from the model owns the keys: digits pick, t types an answer, Esc skips; other keys (Ctrl+C, screen numbers) still work
            if (question.typing) {
                // the last row is a text field inside the dialog: typed letters and digits go to it
                return@KeyHandler when {
                    key == Key.Escape -> { chat.answerQuestion(null); true }
                    key == Key.Enter -> { chat.activate(); true }
                    key == Key.Up && question.options.isNotEmpty() -> { chat.move(-1); true }
                    key == Key.Down && question.options.isNotEmpty() -> { chat.move(1); true }
                    else -> question.field.onKey(key)
                }
            }
            return@KeyHandler when {
                key == Key.Up -> { chat.move(-1); true }
                key == Key.Down -> { chat.move(1); true }
                key == Key.Enter -> { chat.activate(); true }
                key.char == ' ' && question.multiple -> { chat.tickCurrent(); true }
                key.char != null && key.char!!.isDigit() && key.char != '0' -> { chat.pick(key.char!! - '0'); true }
                key.char == 't' || key.char == 'T' -> { question.cursor = question.options.size; true }
                key == Key.Escape -> { chat.answerQuestion(null); true }
                else -> false
            }
        }
        if (approval != null) {
            // a pending change owns y / n / a / Esc; other keys (screen numbers, Ctrl+C) still work
            return@KeyHandler when {
                key.char == 'y' || key.char == 'Y' -> { chat.answer(ChatSession.ApprovalChoice.Allow); true }
                key.char == 'a' || key.char == 'A' -> { if (approval.always) chat.answer(ChatSession.ApprovalChoice.Always); true }
                key.char == 'n' || key.char == 'N' || key == Key.Escape -> { chat.answer(ChatSession.ApprovalChoice.Deny); true }
                else -> false
            }
        }
        if (attachments.browsing) return@KeyHandler attachments.handle(key)
        when (mode) {
            ChatMode.Save -> when {
                key == Key.Escape -> { saving = null; mode = ChatMode.Chat; true }
                key == Key.Enter -> { if (saveTitle.text.isNotBlank()) request?.let { doSave(it) }; true }
                key == Key.Tab -> { saving = request?.copy(lastAnswerOnly = !request.lastAnswerOnly); true }
                // Ctrl+O: overwrite a note that is already in the vault instead of creating a new one
                key == Key.Browse -> {
                    if (request?.lastAnswerOnly == true && lastAnswer == null) toast("There is no answer to save yet.", error = true)
                    else { target = Target.Replace; overwriting = request; notePicker.reset(); notePicker.startFiltering(); mode = ChatMode.PickTarget }
                    true
                }
                else -> saveTitle.onKey(key)
            }
            ChatMode.Leave -> {
                when {
                    key == Key.Escape -> { mode = ChatMode.Chat; leave?.let { navigator.navigate(it, force = true) }; leaving = null }
                    key.char == 'w' || key.char == 'W' || key == Key.Save -> { val to = leave; leaving = null; openSave(to) }
                    else -> { leaving = null; mode = ChatMode.Chat }
                }
                true
            }
            ChatMode.ConfirmClear -> {
                if (key.char == 'y' || key.char == 'Y') {
                    gen.reset()
                    output.reset()
                    chat.deleteCurrent()
                    toast("Chat erased")
                }
                mode = ChatMode.Chat
                true
            }
            ChatMode.Context -> {
                if (!inspector.handle(key, chat.lastContext) { part, ok -> toast(if (ok) "Copied “${part.title}” (~${part.tokens} tokens)." else "Could not reach the clipboard.") }) mode = ChatMode.Chat
                true
            }
            ChatMode.Artifacts -> {
                val list = Artifacts.entries(chat.artifacts.toList())
                val root = app.openVault().getOrNull()?.root
                fun open(i: Int) {
                    val picked = list.getOrNull(i)?.artifact ?: return
                    val note = root?.let { Artifacts.libraryNote(picked, it) }
                    if (note != null) { mode = ChatMode.Chat; navigator.openLibrary(LibraryFilter.All, note = note) }
                    else toast(if (picked.action == "deleted") "${picked.path} is deleted." else "${picked.path} is not a note the Library can open (only Markdown notes in the vault).", error = picked.action == "deleted")
                }
                if (!artifactsDialog.handle(key, list.size, ::open)) mode = ChatMode.Chat
                true
            }
            ChatMode.ConfirmMemory -> {
                if (key.char == 'y' || key.char == 'Y') {
                    val removed = app.openVault().getOrNull()?.let { runCatching { it.cleanMemory() }.getOrDefault(0) } ?: 0
                    toast(if (removed == 0) "Nothing to remove." else "Removed $removed repeated line${if (removed == 1) "" else "s"}.")
                }
                memoryAudit = null
                mode = ChatMode.Chat
                true
            }
            ChatMode.ConfirmCancel -> {
                // the reply may have finished while the question was open: then there is nothing left to cancel
                if (key == Key.Enter || key.char == 'y' || key.char == 'Y') { if (gen.state == GenState.Running) gen.cancel() else chat.cancelOracul(); app.recordEvent(Events.CANCEL) }
                mode = ChatMode.Chat
                true
            }
            ChatMode.ConfirmReplace -> {
                if (key.char == 'y' || key.char == 'Y') replaceTarget?.let { applyToNote(it) } else { replaceTarget = null; overwriting = null; saving = null; mode = ChatMode.Chat }
                true
            }
            ChatMode.History -> when {
                historyPicker.handle(key, past) { it.title } -> true
                key == Key.Enter -> {
                    historyPicker.selected(past) { it.title }?.let {
                        gen.reset()
                        output.reset()
                        chat.open(it)
                        toast("Opened “${it.title}”")
                    }
                    mode = ChatMode.Chat
                    true
                }
                key == Key.Escape -> { mode = ChatMode.Chat; true }
                else -> false
            }
            ChatMode.Sampling -> sampling.handle(key, app, { toast(it) }) { mode = ChatMode.Chat }
            ChatMode.Status -> paneEditor.handle(key, app) { mode = ChatMode.Chat }
            ChatMode.Model -> modelPicker.handle(key, ::switchModel) { mode = ChatMode.Chat }
            ChatMode.Think -> when {
                thinkPicker.handle(key, thinkChoices) { it.label } -> true
                key == Key.Enter -> {
                    thinkPicker.selected(thinkChoices) { it.label }?.let { picked ->
                        chat.thinkNext = picked.value
                        toast(if (picked.value.isEmpty()) "The next message uses the Settings value (think ${thinkLabel(app.config.thinking).lowercase()})." else "Next message: think ${picked.value}. After you send it, the Settings value (${thinkLabel(app.config.thinking).lowercase()}) is used again.")
                    }
                    mode = ChatMode.Chat
                    true
                }
                key.char == 'd' && !thinkPicker.filtering -> {
                    thinkPicker.selected(thinkChoices) { it.label }?.let { picked ->
                        app.update(app.config.copy(thinking = picked.value.ifEmpty { app.config.thinking }))
                        chat.thinkNext = ""
                        toast("Settings → Thinking is now ${thinkLabel(app.config.thinking).lowercase()} for every message.")
                    }
                    mode = ChatMode.Chat
                    true
                }
                key == Key.Escape -> { mode = ChatMode.Chat; true }
                else -> false
            }
            ChatMode.PickNote, ChatMode.PickTarget -> {
                val choices = noteChoices()
                when {
                    key == Key.Escape -> { mode = if (overwriting != null) ChatMode.Save else ChatMode.Chat; overwriting = null; true }
                    key == Key.Enter -> {
                        val picked = notePicker.selected(choices) { it.path }
                        if (picked == null) {
                            true
                        } else if (mode == ChatMode.PickNote) {
                            if (picked.path !in chat.attachments.notes) chat.attachments.notes += picked.path
                            mode = ChatMode.Chat
                            true
                        } else if (target == Target.Replace) {
                            replaceTarget = picked.path
                            mode = ChatMode.ConfirmReplace
                            true
                        } else {
                            applyToNote(picked.path)
                            true
                        }
                    }
                    else -> {
                        notePicker.handle(key, choices) { it.path }
                        if (!notePicker.filtering) notePicker.startFiltering()
                        true
                    }
                }
            }
            ChatMode.Chat -> when {
                // `?` on an empty field is the key map, as the footer says ("? keys"), also while the field has the keyboard; with text in the field it is just a question mark
                key.char == '?' && input.text.isEmpty() && !browsing && slashMatches.isEmpty() -> false
                // a digit with an empty field during a reply is a screen number: leave it to the shell
                gen.state == GenState.Running && input.text.isEmpty() && !browsing && key.char?.let { it in '0'..'9' } == true -> false
                // [ and ] are the thinking pane's keys (on, size, off): only with nothing typed yet (or while browsing), and [ only when the pane is on, so a bracket opening a message is still typed
                (key.char == ']' || (key.char == '[' && app.config.thinkingPane)) && (input.text.isEmpty() || browsing) -> { stepThinkingPane(key.char == ']'); true }
                // { and } are the status pane's keys (on, size, off); the pane is on the right, so { (pointing left) makes it wider and } narrower; } is typed as text while the pane is off
                // (on the left the keys swap: } points right, the way its right edge moves)
                (key.char == '{' || key.char == '}') && (key.char == (if (app.config.statusPanePlacement == Docks.LEFT) '}' else '{') || app.config.statusPane) && (input.text.isEmpty() || browsing) ->
                    { stepStatusPane(key.char == (if (app.config.statusPanePlacement == Docks.LEFT) '}' else '{')); true }
                key == Key.Effort -> { openThinkPicker(); true }
                key == Key.Sampling -> { openSampling(); true }
                key == Key.Save -> { openSave(null); true }
                key == Key.Browse -> { openAttach(); true }
                output.selecting -> output.handle(key, entries, onNotice = { toast(it.text) })
                slashMatches.isNotEmpty() && key == Key.Escape -> { input.clear(); true }
                slashMatches.isNotEmpty() && key == Key.Up -> { slashIndex = (slashIndex - 1).mod(slashMatches.size); true }
                slashMatches.isNotEmpty() && key == Key.Down -> { slashIndex = (slashIndex + 1).mod(slashMatches.size); true }
                // Tab completes the highlighted name into the field (`/revise `), ready for the text that follows; Enter runs it
                slashMatches.isNotEmpty() && key == Key.Tab -> { input.set(slashMatches[slashIndex.coerceIn(0, slashMatches.size - 1)].name.let { if (it.startsWith("/")) it else "/$it" } + " "); true }
                slashMatches.isNotEmpty() && key == Key.Enter -> { useSlash(slashMatches[slashIndex.coerceIn(0, slashMatches.size - 1)]); true }
                key == Key.Escape -> when {
                    // a running reply is never cancelled by a stray key: Esc asks first (Settings → Console → Confirm before cancelling)
                    gen.state == GenState.Running -> { if (app.config.confirmCancel) mode = ChatMode.ConfirmCancel else { gen.cancel(); app.recordEvent(Events.CANCEL) }; true }
                    // the Oracul or the judge working on its own (an opinion, a comparison, a takeover): the same question, then nothing is added
                    chat.consulting != null -> { if (app.config.confirmCancel) mode = ChatMode.ConfirmCancel else { chat.cancelOracul(); app.recordEvent(Events.CANCEL) }; true }
                    browsing -> { browsing = false; true }
                    chat.messages.isNotEmpty() -> { browsing = true; true }
                    else -> false
                }
                browsing -> when {
                    key == Key.Enter -> { browsing = false; true }
                    // f: the files and notes the model wrote or changed in this chat (the ✦ pill)
                    key.char == 'f' && chat.artifacts.isNotEmpty() -> { artifactsDialog.index = 0; mode = ChatMode.Artifacts; true }
                    else -> output.handle(key, entries, letters = true, onNotice = { toast(it.text) })
                }
                // in a message of several lines Up/Down move the caret between them; on the first / last line they scroll the conversation
                (key == Key.Up || key == Key.Down) && input.text.contains('\n') && input.onKey(key) -> true
                // Up/Down and PageUp/PageDown scroll while typing; letters and Space always type.
                output.handle(key, entries, letters = false, onNotice = { toast(it.text) }) -> true
                key.char == '@' && (input.text.isEmpty() || input.text.last().isWhitespace()) -> { openNotePick(); true }
                // a backslash right before the caret turns Enter into a line break: works in every terminal (Alt+Enter too, where Option sends Esc)
                key == Key.Enter && endsWithBackslash(input) -> { input.onKey(Key.Backspace); input.onKey(Key.PastedNewline); true }
                key == Key.Enter -> { send(); true }
                // Backspace on an empty message takes back the last attachment.
                key == Key.Backspace && input.text.isEmpty() && chat.attachments.removeLast() -> true
                // ...and then the newest queued message (typed while the model worked, not sent yet): dismissed
                key == Key.Backspace && input.text.isEmpty() && chat.dismissQueued() -> { toast("Queued message removed."); true }
                else -> input.onKey(key)
            }
        }
    }

    if (attachments.browsing) {
        AttachBrowser(attachments)
        return
    }
    Stack {
        when (mode) {
            ChatMode.Save -> SaveDialog(saveTitle, request?.lastAnswerOnly == true, chat.messages.size, app.config.outputFolder)
            ChatMode.Leave -> LeaveDialog(chat.title, chat.messages.size - chat.savedMessages)
            ChatMode.ConfirmClear -> ConfirmClearDialog(chat.title, chat.messages.size)
            ChatMode.Context -> if (inspector.reading) ContextPartView(inspector.part(chat.lastContext), inspector.output) else ContextInspectorView(chat.lastContext, inspector.index, app.config.plainGlyphs)
            ChatMode.Artifacts -> ArtifactsDialog(chat.artifacts.toList(), app.openVault().getOrNull()?.root, artifactsDialog.index, app.config.plainGlyphs)
            ChatMode.ConfirmMemory -> ConfirmMemoryDialog(memoryAudit)
            ChatMode.ConfirmCancel -> ConfirmCancelDialog(chat.title, gen.state == GenState.Running)
            ChatMode.ConfirmReplace -> ConfirmReplaceDialog(replaceTarget, overwriting == null || overwriting?.lastAnswerOnly == true)
            ChatMode.History -> HistoryDialog(past, historyPicker, chat.id)
            ChatMode.Sampling -> SamplingPanel(app, sampling)
            ChatMode.Model -> ModelPickerView(modelPicker, app.config.model, app.config.ollamaUrl, bodyRows(extra = 4))
            ChatMode.Think -> {
                ScreenHeader("Reasoning effort", "${app.config.model}: what Ollama lists · Enter next message only · d Settings value")
                VGap()
                Panel("Thinking & effort") {
                    Picker(
                        thinkChoices, thinkPicker,
                        label = { it.label },
                        rows = bodyRows(extra = 4),
                        icon = { if (it.value == chat.thinkNext) "●" else "»" },
                        // each choice in its level's colour, calm to hot (the first row, the Settings value, in the colour of what that is)
                        iconColor = { thinkColor(it.value.ifEmpty { app.config.thinking }.let { v -> if (v in thinkOptions(app.thinkingValues)) v else "default" }, thinkOptions(app.thinkingValues), thinkDefaultWord(app.thinkingDefault)) },
                        meta = { it.note },
                    )
                }
                // what the choice means, as the other pickers explain themselves under their table: the levels are the model's own, thinking and effort are one setting
                val picked = thinkPicker.selected(thinkChoices) { it.label }
                Hint(
                    "The choices are what ${app.config.model} lists in Ollama (/api/show), so they differ per model. Thinking and effort are one setting: Off = no thinking at all, a level = thinking at that effort, " +
                        "Model default = nothing is sent and the model decides. Enter uses the highlighted choice for your next message only; d makes it the Settings value for every message." +
                        (picked?.takeIf { it.value.isNotEmpty() }?.let { "\n${it.label}: ${thinkEffect(it.value, thinkOptions(app.thinkingValues), thinkDefaultWord(app.thinkingDefault))}" }.orEmpty()) +
                        (picked?.takeIf { it.value.isEmpty() }?.let { "\nUse the Settings value: ${it.note.removePrefix("now: ")}." }.orEmpty()),
                )
            }
            ChatMode.PickNote, ChatMode.PickTarget -> {
                ScreenHeader(
                    if (mode == ChatMode.PickNote) "Attach a note" else if (overwriting != null) "Overwrite a note" else "${target.verb} a note",
                    if (mode == ChatMode.PickNote) "Its text goes to the model with your next message" else if (overwriting != null) "The text of the note you choose is replaced by ${if (overwriting?.lastAnswerOnly == true) "the last answer" else "this whole chat"} (the old text is kept in .trash)" else if (target == Target.Append) "The last answer is added to the end of the note you choose" else "The text of the note you choose is replaced by the last answer (the old text is kept in .trash)",
                )
                VGap()
                Panel("Notes") {
                    Picker(noteChoices(), notePicker, label = { it.path }, rows = bodyRows(extra = 4), icon = { "▤" }, iconColor = { Palette.Accent }, meta = { relativeTime(it.modified) }, placeholder = "Type to search your notes")
                }
            }
            ChatMode.Chat, ChatMode.Status -> {
                // read again after every message, so an edit of the file shows up
                val memoryChars = remember(chat.messages.size, app.config.memoryEnabled, mode) { if (app.config.memoryEnabled) app.openVault().getOrNull()?.memoryLength() ?: 0 else 0 }
                val count = chat.messages.size
                // no header: the tab bar says where you are; the chat's own facts ride on the conversation's frame
                val conversationTitle = "Conversation · ${chat.title}" + (if (count > 0) " · $count messages" else "") + (if (count > 0 && !app.config.keepChats) " · not kept" else "") + (if (chat.unsaved && count > 0) " · not in vault (Ctrl+S)" else "")
                // the status bar sits under the message field (above the buttons): one row more in the count below
                // the status bar wraps instead of dropping facts: reserve as many rows as it needs at this width
                val statusSegments = chatStatus(app, chat, app.config.plainGlyphs, memoryChars, LocalContentWidth.current)
                val statusRows = statusRowCount(statusSegments, LocalContentWidth.current)
                val bottomGap = if (rememberDensity().tiny) 0 else 1
                // Everything under the conversation is counted exactly, so it fills the rest of the height.
                // the reasoning-effort control shares the message field's first row (nothing in a small window, or while a dialog has the screen)
                val effort = if (question == null && approval == null && !rememberDensity().tiny && !rememberDensity().inline) effortView(app, gen.thinking, LocalContentWidth.current - 4) else null
                val inputRows = wrapRows(input.text, (LocalContentWidth.current - 4 - (effort?.width ?: 0)).coerceAtLeast(10)).size.coerceIn(1, maxInputRows())
                val qLines = if (question != null) questionLines(question, panelWidth()) else emptyList()
                // the buttons of a dialog sit inside it, at the bottom; the message field keeps its buttons under the status bar
                val panelActions = if (question != null) questionActions(question) else if (approval != null) approvalActions(approval) else null
                val panelActionRows = panelActions?.let { breakIntoLines(it, panelWidth()).size + (if (rememberDensity().tiny) 0 else 1) } ?: 0
                val actions = if (panelActions != null) null else chatActions(browsing, entries.isNotEmpty(), chat.artifacts.isNotEmpty())
                val pendingRow = chat.attachments.rows
                // the reply line (compacting, running, failed…) and, when /reindex runs, one more line with its progress
                val statusRow = (if (chat.compacting || gen.state == GenState.Running || gen.state == GenState.Failed || gen.state == GenState.Cancelled || chat.consulting != null) 1 else 0) + (if (chat.semantic.indexing != null) 1 else 0)
                // what sits under the conversation: the message field, or the change waiting for an answer
                val typeRows = if (question != null) (if (question.typing) wrapRows(question.field.text, (panelWidth() - 6).coerceAtLeast(10)).size.coerceIn(1, 3) else 1) else 0
                // while a dialog is open the thinking pane stays (a short strip, or its column): the dialog gives up a few lines for it instead of pushing it out
                val dialogOpen = question != null || approval != null
                val paneReserve = if (app.config.thinkingPane && dialogOpen && app.config.thinkingPanePlacement.let { it != "left" && it != "right" }) DIALOG_STRIP_MAX else 0
                val queuedRows = if (question == null && approval == null) chat.queued.size.coerceAtMost(QUEUE_SHOWN + 1) else 0
                val below = queuedRows + (if (question != null) qLines.size + typeRows + 3 else if (approval != null) approvalLines(approval, approvalMax(paneReserve)).size + 3 else 2 + inputRows + pendingRow + slashMatches.size + (if (slashUsage.isEmpty()) 0 else 1)) + statusRows + panelActionRows
                // the thinking pane: a split window around the conversation (a strip above or below it, a column beside it)
                val freeRows = outputRows(fixed = 2 + statusRow + below + bottomGap, actions = actions)
                val pane = if (app.config.thinkingPane) thinkingPaneLayout(app.config.thinkingPanePlacement, app.config.thinkingPanePercent, LocalContentWidth.current, LocalContentHeight.current, freeRows + statusRow + 2, dialogOpen) else null
                val side = pane != null && pane.placement.let { it == "left" || it == "right" }
                val stripRows = if (pane != null && !side) pane.size + 2 else 0
                val scrollRows = (freeRows - stripRows).coerceAtLeast(3)
                val conversation: @Composable () -> Unit = {
                    Panel(conversationTitle) {
                        val width = outputWidth()
                        val lines = buildList {
                            if (chat.messages.isEmpty() && gen.text.isEmpty()) {
                                add(Line(Shortcuts.label("› Press Enter to start typing. Ask your second brain anything. @ attaches a note, Ctrl+O a file or picture, /vault makes answers come from your notes."), Palette.Hint))
                                add(Line(Shortcuts.label("› Ctrl+P lists every action. Esc leaves the field to scroll and copy."), Palette.Hint))
                                if (!app.config.toolsEnabled) add(Line("› Tools are off: type /tools so the model can read and write your notes and files (it asks before changing anything).", Palette.Hint))
                            }
                            var next = 0
                            // the opinion nobody has answered yet says how to use it
                            val openOpinion = if (Oracul.hasOpenOpinion(shownMessages)) shownMessages.last { Oracul.isReview(it) } else null
                            shownMessages.forEach { m ->
                                addAll(messageLines(app, m, next, width, applyHint = m === openOpinion))
                                next += entriesOf(m).size
                            }
                        }
                        output.copyPlain = app.config.copyAs == "plain"
                        ScrollView(lines, scrollRows, output)
                        ReplyStatus(app, gen, compactingSince = chat.compactingSince.takeIf { chat.compacting }, compactingAuto = chat.compactingAuto, compactingPart = chat.compactingPart, consulting = chat.consulting, consultingSince = chat.consultingSince, consultingStage = chat.consultingStage)
                        chat.semantic.indexing?.let { Label((if (app.config.plainGlyphs) "" else "◌ ") + it, color = Palette.Blue) }
                    }
                }
                // the docks: full-height columns beside the conversation, one panel per side (see Docks). A column needs room, so with too little the conversation takes the whole width.
                val total = LocalContentWidth.current
                val paneHeight = scrollRows + statusRow + 2
                val columns = buildList<DockColumn> {
                    if (pane != null && side) add(DockColumn(pane.placement, pane.size) { ThinkingPane(app, gen, chat, paneHeight - 2, app.config.model, Modifier.height(paneHeight)) })
                    val statusDock = Docks.sideOf(app.config, PaneId.Status)
                    if (statusDock != null && total >= 80 && none { it.side == statusDock }) {
                        // the column keeps the conversation at least 40 columns when the other side is taken too
                        val room = total - 40 - 1 - sumOf { it.width + 1 }
                        val want = (total * app.config.statusPanePercent.coerceIn(THINKING_MIN, THINKING_MAX) / 100).coerceIn(24, (total - 60).coerceAtLeast(24))
                        val w = minOf(want, room - 1)
                        if (w >= 24) add(DockColumn(statusDock, w) { StatusPane(app, chat, paneEditor, mode == ChatMode.Status, paneHeight) })
                    }
                }
                when {
                    columns.isNotEmpty() -> {
                        val left = columns.firstOrNull { it.side == Docks.LEFT }
                        val right = columns.firstOrNull { it.side == Docks.RIGHT }
                        val convWidth = total - columns.sumOf { it.width + 1 }
                        val box: @Composable RowScope.(DockColumn) -> Unit = { col ->
                            Box(Modifier.weight(col.width.toFloat() / total)) { CompositionLocalProvider(LocalContentWidth provides col.width) { col.content() } }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            if (left != null) { box(left); HGap() }
                            Box(Modifier.weight(convWidth.toFloat() / total)) { CompositionLocalProvider(LocalContentWidth provides convWidth) { conversation() } }
                            if (right != null) { HGap(); box(right) }
                        }
                    }
                    else -> {
                        if (pane?.placement == "top") ThinkingPane(app, gen, chat, pane.size, app.config.model)
                        conversation()
                        if (pane != null && pane.placement == "bottom") ThinkingPane(app, gen, chat, pane.size, app.config.model)
                    }
                }
                if (question != null) {
                    val qLines = questionLines(question, panelWidth())
                    Panel("Question", accent = Palette.Cta) {
                        Label(if (question.fromArcana) "? Arcana asks" else "? ${app.config.model} asks", color = Palette.Cta, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                        qLines.forEach { l -> Label(l, color = if (l.startsWith("▸")) Palette.Cta else if (l.contains("[x]")) Palette.Green else Palette.Text) }
                        // the last row: "type my own answer", which becomes a text field right here when it is selected
                        Row {
                            Label(if (question.typing) "▸ ✎  " else "  ✎  ", color = if (question.typing) Palette.Cta else Palette.Muted)
                            if (question.typing) com.jakewharton.mosaic.ui.Box(com.jakewharton.mosaic.modifier.Modifier.weight(1f)) { TextValue(question.field, true, "Type your answer", 3) }
                            else Label("Type my own answer", color = Palette.Muted)
                        }
                        ActionBar(questionActions(question), panelWidth(), ActionStyle.Button)
                    }
                    StatusBar(statusSegments)
                } else if (approval != null) {
                    Panel(approval.heading, accent = Palette.Warn) {
                        Label("${approval.icon} ${approval.title}", color = Palette.Warn, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                        approvalLines(approval, approvalMax(paneReserve)).forEach { l ->
                            Label(l, color = when { l.startsWith("+ ") -> Palette.Green; l.startsWith("- ") -> Palette.Red; l.startsWith("@@") -> Palette.Blue; l.startsWith("…") -> Palette.Muted; else -> Palette.Text })
                        }
                        ActionBar(approvalActions(approval), panelWidth(), ActionStyle.Button)
                    }
                    StatusBar(statusSegments)
                } else {
                    VGap()
                    // the menu of commands and tools, just above the message field
                    slashMatches.forEachIndexed { i, item ->
                        val selected = i == slashIndex.coerceIn(0, slashMatches.size - 1)
                        val bg = if (selected) Palette.SurfaceHigh else com.jakewharton.mosaic.ui.Color.Unspecified
                        Row {
                            Label(if (selected) "▸ " else "  ", color = Palette.Cta, background = bg)
                            Label(fit(item.kind.glyph + item.name, 24), color = item.kind.color, background = bg, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                            val state = if (item.tool || item.skill) null else slashState(item.name, app.config, app.tools)
                            Label(" " + fit(item.hint, (LocalContentWidth.current - 30 - 12 - SlashCategory.WIDTH - 3).coerceAtLeast(10)), color = if (selected) Palette.Text else Palette.Muted, background = bg)
                            // what the entry IS, in its own column so the kinds line up: COMMAND, SKILL or TOOL
                            val category = item.kind.category
                            Label(category.badge, color = Palette.Ink, background = category.color, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                            // then, for a command that is a setting, whether it is on right now; for a tool, what it can do (READ, WRITE, SHELL, WEB…)
                            if (state != null) Label(" ${state.first} ", color = Palette.Ink, background = state.second, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                            else if (item.tool) item.kind.badge?.let { badge -> Label(" $badge ", color = Palette.Ink, background = item.kind.color, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold) }
                        }
                        // how to type it, under the highlighted entry and lined up with the descriptions
                        if (selected && slashUsage.isNotEmpty()) Row {
                            Label("  " + " ".repeat(24), color = Palette.Muted)
                            Label(" " + fit(slashUsage, (LocalContentWidth.current - 30).coerceAtLeast(10)), color = Palette.Hint)
                        }
                    }
                    // messages waiting for the model, above the field they were typed in
                    val queueHint = if (app.config.plainGlyphs) "   Backspace on an empty field removes the newest" else "   ⌫ on an empty field removes the newest"
                    chat.queued.take(QUEUE_SHOWN).forEachIndexed { i, q ->
                        val lastShown = i == chat.queued.lastIndex.coerceAtMost(QUEUE_SHOWN - 1) && chat.queued.size <= QUEUE_SHOWN
                        val room = LocalContentWidth.current - (if (lastShown) queueHint.length else 0)
                        Label(fit("  ⏳ queued  " + q.replace('\n', ' '), room.coerceAtLeast(20)) + (if (lastShown) queueHint else ""), color = Palette.Muted)
                    }
                    if (chat.queued.size > QUEUE_SHOWN) Label("  ⏳ … and ${chat.queued.size - QUEUE_SHOWN} more queued", color = Palette.Muted)
                    TextInput("Message", input, !browsing, if (browsing) "Press Enter to type" else "Ask, think, or give an instruction…", maxRows = maxInputRows(), above = attachmentLine(chat.attachments), trailing = effort?.let { v -> { EffortInline(v) } })
                    StatusBar(statusSegments)
                    ActionBar(chatActions(browsing, entries.isNotEmpty(), chat.artifacts.isNotEmpty()))
                }
                // one empty row under the whole screen, so the last row does not touch the footer (counted in the conversation's height above)
                if (bottomGap > 0) Label(" ")
            }
        }
    }
}

/** A request to save the chat: what to save, and where to go afterwards (when it came from the leave question). */
internal data class SaveRequest(val after: Screen?, val lastAnswerOnly: Boolean = false)

/** The buttons under the message field. */
internal fun chatActions(browsing: Boolean, hasEntries: Boolean, hasChanges: Boolean = false): List<ActionItem> =
    if (browsing) {
        listOf(ActionItem("Enter", "Type a message", accent = Palette.Cta), ActionItem("s", "Select & copy", hasEntries), ActionItem("f", "Files changed", hasChanges), ActionItem("↑↓", "Scroll"))
    } else {
        listOf(
            ActionItem("Enter", "Send"),
            ActionItem("@", "Note"),
            ActionItem("Ctrl+O", "Attach"),
            ActionItem("Ctrl+S", "Save to vault"),
            ActionItem("Ctrl+P", "Commands", accent = Palette.Cta),
        )
    }
