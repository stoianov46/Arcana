package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.ChatSession
import com.arcana.brain.app.GenState
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.Command
import com.arcana.brain.app.navigation.Commands
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ImageFiles
import com.arcana.brain.core.TextFiles
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.StoredChat
import com.arcana.brain.core.ToolCall
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.Skills
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.Vault
import com.arcana.brain.app.SystemOpen
import com.jakewharton.mosaic.ui.Row
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The window the gauge counts against: what was asked for, but never more than the model supports (when that is known). */
internal fun effectiveContext(asked: Int, modelMax: Int, loaded: Int = 0): Int = when {
    asked > 0 && modelMax > 0 -> minOf(asked, modelMax)
    asked > 0 -> asked
    // "Model default": Ollama runs with its own window, usually much smaller than what the model could do; once it has loaded the model it says which
    loaded > 0 -> loaded
    else -> modelMax
}

/** Below this many tokens a long chat, an attached note or a few tool results no longer fit and the oldest text is silently dropped. */
internal const val COMFORTABLE_CONTEXT = 32_768

/** One entry of the menu that opens when a message starts with "/": a command, or a tool (which writes the start of a request for it). */
internal class SlashItem(
    val name: String, val hint: String, val tool: Boolean, val skill: Boolean = false,
    val kind: SlashKind = if (skill) SlashKind.Skill else if (tool) SlashKind.Read else SlashKind.Command,
)

internal val SlashCommands = listOf(
    SlashItem("/new", "Start a new chat", false),
    SlashItem("/history", "Reopen an earlier chat", false),
    SlashItem("/save", "Save this chat to the vault as a note", false),
    SlashItem("/note", "Attach a note to your next message", false),
    SlashItem("/image", "Attach a file or picture", false),
    SlashItem("/vault", "Answer from your vault notes (toggle)", false),
    SlashItem("/tools", "Let the model use tools (toggle)", false),
    SlashItem("/mode", "Tools mode: ask, auto, plan (cycles)", false),
    SlashItem("/autorun", "Run shell commands without asking (toggle)", false),
    SlashItem("/memory", "Open ARCANA.md, your standing instructions", false),
    SlashItem("/skills", "Skills from your vault: list them, on/off (toggle)", false),
    SlashItem("/append", "Add the last answer to a note", false),
    SlashItem("/replace", "Replace a note with the last answer", false),
    SlashItem("/clear", "Erase this chat", false),
)

/** The current state of a setting-like command, as the badge shown next to it: text and colour; null for a command that is not a setting. */
internal fun slashState(name: String, config: AppConfig, toolsSupported: Boolean?): Pair<String, com.jakewharton.mosaic.ui.Color>? = when (name) {
    "/vault" -> if (config.chatUseVault) "ON" to Palette.Green else "OFF" to Palette.Dim
    "/tools" -> when {
        !config.toolsEnabled -> "OFF" to Palette.Dim
        toolsSupported == false -> "NO SUPPORT" to Palette.Red
        else -> "ON" to Palette.Green
    }
    "/mode" -> if (!config.toolsEnabled) "TOOLS OFF" to Palette.Dim else config.toolMode.uppercase() to when (config.toolMode) { "auto" -> Palette.Orange; "plan" -> Palette.Blue; else -> Palette.Green }
    "/autorun" -> if (!config.toolShell) "SHELL OFF" to Palette.Dim else if (config.toolAutoShell) "ON" to Palette.Red else "OFF" to Palette.Dim
    "/memory" -> if (config.memoryEnabled) "ON" to Palette.Green else "OFF" to Palette.Dim
    "/skills" -> if (config.skillsEnabled) "ON" to Palette.Green else "OFF" to Palette.Dim
    else -> null
}

/** The entries that fit what is typed after the slash, grouped commands, skills, tools; in each group names that start with it first, then names that contain it. */
internal fun slashMatches(items: List<SlashItem>, query: String): List<SlashItem> {
    val q = query.trim().lowercase().removePrefix("/")
    val names = { item: SlashItem -> item.name.lowercase().removePrefix("/") }
    // commands, then skills, then tools (so each kind sits together and is easy to tell apart); inside a kind the names that start with the text come first
    return (items.filter { names(it).startsWith(q) } + items.filter { !names(it).startsWith(q) && q in names(it) }).sortedBy { it.kind.category.ordinal }
}

/** How full the model's context was after the last reply (its prompt plus its answer) as a share and a `13.6k/131k` text; null before the first reply or when the size is unknown. */
internal fun contextUsage(messages: List<ChatMessage>, limit: Int): Pair<Double, String>? {
    val stats = messages.mapNotNull { it.stats }.lastOrNull { it.promptTokens > 0 } ?: return null
    if (limit <= 0) return null
    val used = stats.promptTokens + stats.completionTokens
    return (used.toDouble() / limit).coerceIn(0.0, 1.0) to "${UsageStats.compact(used.toLong())}/${UsageStats.contextSize(limit.toLong())}"
}

/** `ctx ▰▰▱▱▱▱▱▱ 12% 13k/131k`: how full the model's context was after the last reply (its prompt plus its answer); empty before the first reply or when the size is unknown. */
internal fun contextMeter(messages: List<ChatMessage>, limit: Int): String {
    val stats = messages.mapNotNull { it.stats }.lastOrNull { it.promptTokens > 0 } ?: return ""
    if (limit <= 0) return ""
    val used = stats.promptTokens + stats.completionTokens
    val share = (used * 100.0 / limit).coerceIn(0.0, 100.0)
    val filled = (share / 12.5).toInt().coerceIn(0, 8).let { if (it == 0 && used > 0) 1 else it }
    val full = com.arcana.brain.core.UsageStats::compact
    return " · ctx " + "▰".repeat(filled) + "▱".repeat(8 - filled) + " ${"%.0f".format(share)}% ${full(used.toLong())}/${com.arcana.brain.core.UsageStats.contextSize(limit.toLong())}" +
        if (share >= 80) " ⚠ nearly full: /new starts fresh" else ""
}

/** What the Chat status bar says: model, chat, context gauge, tokens, estimated price, session time and the tool state. */
private fun chatStatus(app: AppContext, chat: ChatSession, plain: Boolean, memoryChars: Int): List<StatusSegment> {
    val config = app.config
    val stats = chat.messages.mapNotNull { it.stats }
    val sessionRecords = UsageStats.since(app.usage.toList(), app.sessionStart)
    val cost = UsageStats.estimatedCost(sessionRecords, config.estPriceIn, config.estPriceOut) + app.liveTokens * config.estPriceOut / 1_000_000.0
    val compact = UsageStats::compact
    return buildList {
        add(pillSegment(ModelIcon.label(config.model, plain), Palette.Accent, 9))
        add(statusSegment("temperature", "°${config.temperature}", Palette.Accent, 6))
        if (config.chatUseVault) add(pillSegment("◉ vault", Palette.Cta, 6))
        // the memory file the assistant reads in every chat: shown, so you can see that it is in use (and how much room it takes)
        if (config.memoryEnabled) {
            if (memoryChars > 0) add(statusSegment("", "✎ memory ${UsageStats.compact(memoryChars.toLong())}", Palette.Green, 6))
            else add(statusSegment("", "✎ no ARCANA.md", Palette.Muted, 3))
        }
        val window = effectiveContext(config.contextTokens, app.modelContext, app.loadedContext)
        contextUsage(chat.messages, window)?.let { (share, detail) -> add(gaugeSegment("ctx", share, detail, 10, plain)) }
        // a small window drops the oldest text without a word: say so before it matters (Settings → Ollama → Context window)
        if (window in 1 until COMFORTABLE_CONTEXT) add(pillSegment(if (plain) "! small ctx ${UsageStats.contextSize(window.toLong())}" else "⚠ ctx ${UsageStats.contextSize(window.toLong())}", Palette.Orange, 9))
        // ↑ (prompt) is exact and moves when a request ends; ↓ (reply) also counts the pieces streaming right now, until the exact figure replaces them
        if (stats.isNotEmpty() || app.liveTokens > 0) add(StatusSegment(listOf(StatusPart("↑${compact(stats.sumOf { it.promptTokens.toLong() })}", Palette.Blue), StatusPart(" ↓${compact(stats.sumOf { it.completionTokens.toLong() } + app.liveTokens)}", Palette.Green)), 3))
        add(statusSegment("est.", priceText(cost), Palette.Orange, 7, bold = true))
        add(statusSegment("", "◷ ${spanText(System.currentTimeMillis() - app.sessionStart)}", Palette.Accent, 2))
        // the skill being run: how many of its required steps are done (the gate sends the model back if it tries to finish early)
        chat.skillTick
        chat.skillRun?.let { run ->
            val state = when {
                run.finished && run.skippedCount > 0 -> Palette.Red
                run.done == run.total -> Palette.Green
                else -> Palette.Purple
            }
            val text = "◆ ${run.name} ${run.done}/${run.total}" + (if (run.skippedCount > 0) " ✕${run.skippedCount} skipped" else if (run.done == run.total) " ✓" else "")
            add(pillSegment(text, state, 7))
        }
        if (config.toolsEnabled) {
            if (app.tools == false) add(pillSegment("⚠ no tools", Palette.Red, 8))
            else add(pillSegment("⚙ ${config.toolMode}", when (config.toolMode) { "auto" -> Palette.Orange; "plan" -> Palette.Blue; else -> Palette.Green }, 8))
            // commands run without asking: said loudly, in the colour of high risk
            if (config.toolShell && config.toolAutoShell && config.toolMode != "plan") add(pillSegment(if (plain) "! auto-run" else "▶ auto-run", Palette.Red, 9))
        }
    }
}

/** The question and its options as one row each (what the panel shows and what its height is counted from). */
private fun questionLines(q: ChatSession.Question, width: Int): List<String> {
    val text = wrap(sanitizeForTerminal(q.text).replace("`", ""), width).take(6)
    val options = q.options.mapIndexed { i, option ->
        val mark = if (q.multiple) (if (i in q.picked) "[x] " else "[ ] ") else ""
        (if (i == q.cursor) "▸ " else "  ") + "$mark${i + 1}  ${sanitizeForTerminal(option)}".take(width - 2)
    }
    return text + options
}

private fun questionActions(q: ChatSession.Question) =
    if (q.typing) listOfNotNull(ActionItem("Enter", "Send answer", q.field.text.isNotBlank(), Palette.Cta), if (q.options.isNotEmpty()) ActionItem("↑", "Back to options") else null, ActionItem("Esc", "Skip"))
    else listOfNotNull(
        ActionItem("↑↓", "Move"),
        ActionItem("Enter", if (q.multiple) "Confirm" else "Choose", accent = Palette.Cta),
        if (q.multiple) ActionItem("Space", "Tick") else null,
        ActionItem("1-${q.options.size}", "Pick"),
        ActionItem("t", "Type my own"),
        ActionItem("Esc", "Skip"),
    )

private fun endsWithBackslash(field: com.arcana.brain.app.input.TextFieldState): Boolean {
    val at = if (field.caretAt in 0..field.text.length) field.caretAt else field.text.length
    return at > 0 && field.text[at - 1] == '\\' && field.selection == null
}

/** How tall the message field may grow before it scrolls: a quarter of the screen, between 3 and 8 rows. */
@Composable
private fun maxInputRows(): Int = (LocalContentHeight.current / 4).coerceIn(3, 8)
private const val MaxNotes = 5

private enum class ChatMode { Chat, History, PickNote, PickTarget, ConfirmReplace, Save, Leave, ConfirmClear }

/** What happens to the last answer when it is sent to an existing note. */
private enum class Target(val verb: String) { Append("Append"), Replace("Replace") }

/** A note offered in a picker: its vault path and when it last changed. */
private class NoteChoice(val path: String, val modified: Long)

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
    val notePicker = remember { PickerState() }
    val input = chat.input
    var mode by remember { mutableStateOf(ChatMode.Chat) }
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
            toast("Wait for the reply to finish (or press Esc twice to cancel it) before starting a new chat.", error = true)
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
            app.session.log(SessionKind.Saved, "Chat saved as a note", path)
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
            app.session.log(if (target == Target.Append) SessionKind.Appended else SessionKind.Replaced, "Chat answer → ${path.removeSuffix(".md")}", path)
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

    fun toggleSkills() {
        val on = !app.config.skillsEnabled
        app.update(app.config.copy(skillsEnabled = on))
        if (!on) return toast("Skills are off: the model is not told about them.")
        val vault = app.openVault().getOrNull() ?: return toast("Choose a vault first (Settings → Vault).", error = true)
        val found = Skills.discover(vault, app.config.skillsFolder)
        toast(when {
            found.isEmpty() -> "Skills are on, but there are none in ${app.config.skillsFolder}/ yet (one folder per skill; see the Skills Guide note)."
            !app.config.toolsEnabled -> "${found.size} skill(s): ${found.joinToString { it.name }}. Turn tools on (/tools) so the model can load them."
            else -> "${found.size} skill(s): ${found.joinToString { Skills.summary(it) }}. Type / and pick one."
        }, error = found.any { it.problems.isNotEmpty() })
    }

    fun openMemory() {
        val vault = app.openVault().getOrNull() ?: return toast("Choose a vault first (Settings → Vault).", error = true)
        val file = runCatching { vault.ensureMemory() }.getOrElse { return toast("Could not create ${Vault.MEMORY_FILE}.", error = true) }
        val opened = SystemOpen.open(file.toString())
        toast(if (opened) "${Vault.MEMORY_FILE} opened in your editor. Save it; the next message uses it." else "Edit ${file} (it is read in every chat).")
    }

    fun toggleAutoRun() {
        if (!app.config.toolShell) return toast("Shell commands are off. Turn them on in Settings → Tools first.", error = true)
        val on = !app.config.toolAutoShell
        app.update(app.config.copy(toolAutoShell = on))
        toast(if (on) "Auto-run: shell commands now run WITHOUT asking (sudo, rm -rf / and the like are still refused)." else "Commands ask before they run again.", error = on)
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

    fun runCommand(command: String) {
        when (command) {
            "/new" -> startNew()
            "/history" -> openHistory()
            "/clear" -> mode = ChatMode.ConfirmClear
            "/save" -> openSave(null)
            "/image" -> openAttach()
            "/note" -> openNotePick()
            "/vault" -> toggleVault()
            "/tools" -> toggleTools()
            "/memory" -> openMemory()
            "/skills" -> toggleSkills()
            "/mode" -> cycleMode()
            "/autorun" -> toggleAutoRun()
            "/append" -> openTargetPick(Target.Append)
            "/replace" -> openTargetPick(Target.Replace)
            else -> toast("Unknown command. Type / to see the commands and tools", error = true)
        }
    }

    fun send() {
        val text = input.text.trim()
        if ((text.isEmpty() && chat.attachments.isEmpty) || gen.state == GenState.Running) return
        if (text.startsWith("/") && chat.attachments.isEmpty) {
            input.clear()
            return runCommand(text)
        }
        input.clear()
        output.reset()
        if (app.config.toolsEnabled && app.tools == false) toast("${app.config.model} cannot use tools, so it will only answer. Choose another model in Settings → Ollama → Model.", error = true)
        chat.send(text)
    }

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
            Command("Tools mode: ${app.config.toolMode} → next (ask, auto, plan)", "Chat", "/mode", app.config.toolsEnabled) { cycleMode() },
            Command(if (app.config.toolAutoShell) "Shell commands: ask before running again" else "Shell commands: run without asking", "Chat", "/autorun", app.config.toolsEnabled && app.config.toolShell) { toggleAutoRun() },
            Command(if (app.config.toolsEnabled) "Tools: turn off" else "Tools: turn on (read and write notes and files)", "Chat", "/tools") { toggleTools() },
            Command(if (app.config.chatUseVault) "Answer from my vault: turn off" else "Answer from my vault: turn on", "Chat", "/vault") { toggleVault() },
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
        if (vault == null || !app.config.skillsEnabled) emptyList() else Skills.discover(vault, app.config.skillsFolder)
    }
    val slashItems = remember(app.config.vaultPath, skillList) {
        SlashCommands + skillList.map { SlashItem("/" + it.name, it.description.ifEmpty { "(no description)" }.substringBefore(". ").take(80).trimEnd('.'), false, skill = true) } + (chat.toolboxFor(app.config.copy(toolsEnabled = true, toolMode = "ask"))?.specs.orEmpty())
            .map { SlashItem(it.name, it.description.substringBefore(". ").substringBefore("\n").take(80).trimEnd('.'), true, kind = SlashKind.forTool(it.name, it.risk)) }
    }
    val slashMatches = if (slashQuery == null) emptyList() else slashMatches(slashItems, slashQuery).take(8)
    var slashIndex by remember(slashQuery) { androidx.compose.runtime.mutableIntStateOf(0) }

    fun useSlash(item: SlashItem) {
        if (item.skill) {
            // attaching is explicit: a chip above the field, and Arcana puts the skill's text into the message when it is sent (the model need not call use_skill)
            val skill = skillList.firstOrNull { "/" + it.name == item.name }
            input.clear()
            if (skill == null) return toast("That skill is no longer in ${app.config.skillsFolder}/.", error = true)
            if (skill.path !in chat.attachments.notes) chat.attachments.notes += skill.path
            app.session.log(SessionKind.Attached, "Skill ${skill.name}", skill.path)
            toast(if (app.config.toolsEnabled) "Skill ${skill.name} attached. Type what you want and press Enter (Backspace on an empty field removes it)."
                else "Skill ${skill.name} attached, but tools are off, so it cannot act. Turn them on with /tools.", error = !app.config.toolsEnabled)
        } else if (item.tool) input.set("Use the ${item.name} tool to ")
        else { input.clear(); runCommand(item.name) }
    }

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
            ChatMode.PickNote, ChatMode.PickTarget -> notePicker.filtering
            else -> true
        },
    )
    if (question != null) Hints(*(if (question.typing) arrayOf("Enter  send the answer", *(if (question.options.isNotEmpty()) arrayOf("↑  back to options") else emptyArray()), "Esc  skip") else arrayOf("↑↓  move", if (question.multiple) "Space  tick" else "Enter  choose", *(if (question.multiple) arrayOf("Enter  confirm") else emptyArray()), "1-${question.options.size}  pick", "t  type my own", "Esc  skip")))
    else if (approval != null) Hints("y  ${approval.yes.lowercase()}", "n  no", *(if (approval.always) arrayOf("a  always this session") else emptyArray())) else if (attachments.browsing) Hints(*attachBrowserHints(attachments).toTypedArray()) else when (mode) {
        ChatMode.Save -> Hints("Enter  save", "Tab  whole chat / last answer", "Ctrl+O  overwrite a note", "Esc  cancel")
        ChatMode.Leave -> Hints("w  save to vault", "Esc  leave chat", "any other key  stay")
        ChatMode.ConfirmClear -> Hints("y  erase this chat", "any other key  keep")
        ChatMode.ConfirmReplace -> Hints("y  replace", "any other key  keep the note")
        ChatMode.History -> Hints("↑↓  move", "Enter  open", "/  filter", "Esc  back")
        ChatMode.PickNote -> Hints("type  search", "↑↓  move", "Enter  attach", "Esc  cancel")
        ChatMode.PickTarget -> Hints("type  search", "↑↓  move", "Enter  ${target.verb.lowercase()}", "Esc  cancel")
        ChatMode.Chat -> when {
            output.selecting -> Hints("↑↓  move", "Space  tick", "c  copy", "b  code only", "Esc  leave")
            gen.state == GenState.Running -> Hints("Esc Esc  cancel reply", "↑↓  scroll")
            browsing -> Hints("Enter  type a message", "s  select & copy", "↑↓/Space  scroll", "1-9  screens")
            slashMatches.isNotEmpty() -> Hints("↑↓  choose", "Tab/Enter  use", "Esc  close")
            else -> Hints("Enter  send", "Alt+Enter  new line", "/  commands", "@  note", "Ctrl+O  attach", "Ctrl+S  save", "↑↓  scroll", "Esc  browse")
        }
    }

    // a vault without ARCANA.md gets one, with an offer to fill it in
    androidx.compose.runtime.LaunchedEffect(Unit) { chat.offerMemory() }

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
                            app.session.log(SessionKind.Attached, "Note ${picked.path.removeSuffix(".md")}", picked.path)
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
                // a digit with an empty field during a reply is a screen number: leave it to the shell
                gen.state == GenState.Running && input.text.isEmpty() && !browsing && key.char?.let { it in '0'..'9' } == true -> false
                key == Key.Save -> { openSave(null); true }
                key == Key.Browse -> { openAttach(); true }
                output.selecting -> output.handle(key, entries, onNotice = { toast(it.text) })
                slashMatches.isNotEmpty() && key == Key.Escape -> { input.clear(); true }
                slashMatches.isNotEmpty() && key == Key.Up -> { slashIndex = (slashIndex - 1).mod(slashMatches.size); true }
                slashMatches.isNotEmpty() && key == Key.Down -> { slashIndex = (slashIndex + 1).mod(slashMatches.size); true }
                slashMatches.isNotEmpty() && (key == Key.Tab || key == Key.Enter) -> { useSlash(slashMatches[slashIndex.coerceIn(0, slashMatches.size - 1)]); true }
                key == Key.Escape -> when {
                    gen.state == GenState.Running -> { gen.requestCancel(); true }
                    browsing -> { browsing = false; true }
                    chat.messages.isNotEmpty() -> { browsing = true; true }
                    else -> false
                }
                browsing -> when {
                    key == Key.Enter -> { browsing = false; true }
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
            ChatMode.Save -> {
                ScreenHeader("Save chat", "Write this conversation to your vault as a Markdown note")
                VGap()
                Panel("Save to vault") {
                    TextInput("Note title", saveTitle, true)
                    VGap()
                    Row {
                        Label("Save  ", color = Palette.Muted)
                        Badge(if (request?.lastAnswerOnly == true) "Last answer only" else "Whole chat · ${chat.messages.size} messages")
                        Label("   in  ${app.config.outputFolder.ifEmpty { "the vault root" }}", color = Palette.Muted)
                    }
                }
                ActionBar(
                    listOf(
                        ActionItem("Enter", "Save note", saveTitle.text.isNotBlank()),
                        ActionItem("Tab", if (request?.lastAnswerOnly == true) "Save the whole chat" else "Save last answer only"),
                        ActionItem("Ctrl+O", "Overwrite a note…"),
                        ActionItem("Esc", "Cancel", accent = Palette.Cta),
                    ),
                )
            }
            ChatMode.Leave -> {
                ScreenHeader("Chat", chat.title)
                VGap()
                Panel("Leave this chat?", accent = Palette.Warn) {
                    Warning("This conversation (${chat.messages.size - chat.savedMessages} new messages) is not saved to your vault as a note.")
                    Hint("Chats are not being kept (Settings → Console → Keep chats), so it is gone once you leave.")
                }
                ActionBar(listOf(ActionItem("w", "Save to vault first"), ActionItem("Esc", "Leave chat", accent = Palette.Red), ActionItem("any other key", "Stay", accent = Palette.Cta)))
            }
            ChatMode.ConfirmClear -> {
                ScreenHeader("Chat", chat.title)
                VGap()
                Panel("Erase this chat?", accent = Palette.Red) {
                    Warning("“${chat.title}” (${chat.messages.size} messages) is deleted from the history. /new keeps it and starts a fresh chat instead.")
                }
                ActionBar(listOf(ActionItem("y", "Erase chat", accent = Palette.Red), ActionItem("any other key", "Keep it", accent = Palette.Cta)))
            }
            ChatMode.ConfirmReplace -> {
                ScreenHeader("Chat", "Replace “${replaceTarget}”")
                VGap()
                Panel("Replace this note with ${if (overwriting == null || overwriting?.lastAnswerOnly == true) "the last answer" else "this chat"}?", accent = Palette.Orange) {
                    Warning("The text of the note is replaced by ${if (overwriting == null || overwriting?.lastAnswerOnly == true) "the answer" else "the chat"}. Its frontmatter is kept, and the old text is saved first in the vault's .trash folder.")
                }
                ActionBar(listOf(ActionItem("y", "Replace note", accent = Palette.Orange), ActionItem("any other key", "Keep the note", accent = Palette.Cta)))
            }
            ChatMode.History -> {
                ScreenHeader("Chat history", "Pick a conversation to continue. The current one is saved as it is")
                VGap()
                Panel("Saved chats") {
                    Picker(
                        past, historyPicker,
                        label = { it.title },
                        rows = bodyRows(extra = 4),
                        icon = { if (it.id == chat.id) "●" else "»" },
                        iconColor = { if (it.id == chat.id) Palette.Green else Palette.Accent },
                        meta = { "${it.messages.size} msgs · ${relativeTime(it.updated)}" },
                    )
                }
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
            ChatMode.Chat -> {
                // read again after every message, so an edit of the file shows up
                val memoryChars = remember(chat.messages.size, app.config.memoryEnabled, mode) { if (app.config.memoryEnabled) app.openVault().getOrNull()?.memory()?.length ?: 0 else 0 }
                val count = chat.messages.size
                ScreenHeader(
                    "Chat",
                    "${chat.title} · ${if (count == 0) "empty" else "$count messages"}" +
                        (if (count > 0 && app.config.keepChats) " · kept in history" else if (count > 0) " · not kept" else "") +
                        (if (chat.unsaved && count > 0) " · not in vault (Ctrl+S)" else ""),
                )
                // the status bar sits under the message field (above the buttons): one row more in the count below
                val statusRows = if (rememberDensity().tiny) 0 else 1
                VGap()
                Panel("Conversation") {
                    val width = outputWidth()
                    val lines = buildList {
                        if (chat.messages.isEmpty() && gen.text.isEmpty()) {
                            add(Line(Shortcuts.label("› Press Enter to start typing. Ask your second brain anything. @ attaches a note, Ctrl+O a file or picture, /vault makes answers come from your notes."), Palette.Hint))
                            add(Line(Shortcuts.label("› Ctrl+P lists every action. Esc leaves the field to scroll and copy."), Palette.Hint))
                            if (!app.config.toolsEnabled) add(Line("› Tools are off: type /tools so the model can read and write your notes and files (it asks before changing anything).", Palette.Hint))
                        }
                        var next = 0
                        shownMessages.forEach { m ->
                            addAll(messageLines(app, m, next, width))
                            next += entriesOf(m).size
                        }
                    }
                    output.copyPlain = app.config.copyAs == "plain"
                    // Everything under the panel is counted exactly, so the conversation fills the rest of the height.
                    val inputRows = wrapRows(input.text, (LocalContentWidth.current - 4).coerceAtLeast(10)).size.coerceIn(1, maxInputRows())
                    val qLines = if (question != null) questionLines(question, panelWidth()) else emptyList()
                    // the buttons of a dialog sit inside it, at the bottom; the message field keeps its buttons under the status bar
                    val panelActions = if (question != null) questionActions(question) else if (approval != null) approvalActions(approval) else null
                    val panelActionRows = panelActions?.let { breakIntoLines(it, panelWidth()).size + (if (rememberDensity().tiny) 0 else 1) } ?: 0
                    val actions = if (panelActions != null) null else chatActions(browsing, entries.isNotEmpty())
                    val pendingRow = chat.attachments.rows
                    val statusRow = if (gen.state == GenState.Running || gen.state == GenState.Failed || gen.state == GenState.Cancelled) 1 else 0
                    // what sits under the conversation: the message field, or the change waiting for an answer
                    val typeRows = if (question != null) (if (question.typing) wrapRows(question.field.text, (panelWidth() - 6).coerceAtLeast(10)).size.coerceIn(1, 3) else 1) else 0
                    val below = (if (question != null) qLines.size + typeRows + 3 else if (approval != null) approvalLines(approval, approvalMax()).size + 3 else 2 + inputRows + pendingRow + slashMatches.size) + statusRows + panelActionRows
                    ScrollView(lines, outputRows(fixed = 3 + 2 + statusRow + below, actions = actions), output)
                    ReplyStatus(app, gen, doubleEsc = true)
                }
                if (question != null) {
                    val qLines = questionLines(question, panelWidth())
                    Panel("Question", accent = Palette.Cta) {
                        Label("? ${app.config.model} asks", color = Palette.Cta, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                        qLines.forEach { l -> Label(l, color = if (l.startsWith("▸")) Palette.Cta else if (l.contains("[x]")) Palette.Green else Palette.Text) }
                        // the last row: "type my own answer", which becomes a text field right here when it is selected
                        Row {
                            Label(if (question.typing) "▸ ✎  " else "  ✎  ", color = if (question.typing) Palette.Cta else Palette.Muted)
                            if (question.typing) com.jakewharton.mosaic.ui.Box(com.jakewharton.mosaic.modifier.Modifier.weight(1f)) { TextValue(question.field, true, "Type your answer", 3) }
                            else Label("Type my own answer", color = Palette.Muted)
                        }
                        ActionBar(questionActions(question), panelWidth())
                    }
                    if (statusRows > 0) StatusBar(chatStatus(app, chat, app.config.plainGlyphs, memoryChars))
                } else if (approval != null) {
                    Panel(approval.heading, accent = Palette.Warn) {
                        Label("${approval.icon} ${approval.title}", color = Palette.Warn, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                        approvalLines(approval, approvalMax()).forEach { l ->
                            Label(l, color = when { l.startsWith("+ ") -> Palette.Green; l.startsWith("- ") -> Palette.Red; l.startsWith("@@") -> Palette.Blue; l.startsWith("…") -> Palette.Muted; else -> Palette.Text })
                        }
                        ActionBar(approvalActions(approval), panelWidth())
                    }
                    if (statusRows > 0) StatusBar(chatStatus(app, chat, app.config.plainGlyphs, memoryChars))
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
                            Label(category.badge, color = Palette.Background, background = category.color, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                            // then, for a command that is a setting, whether it is on right now; for a tool, what it can do (READ, WRITE, SHELL, WEB…)
                            if (state != null) Label(" ${state.first} ", color = Palette.Background, background = state.second, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                            else if (item.tool) item.kind.badge?.let { badge -> Label(" $badge ", color = Palette.Background, background = item.kind.color, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold) }
                        }
                    }
                    TextInput("Message", input, !browsing, if (browsing) "Press Enter to type" else "Ask, think, or give an instruction…", maxRows = maxInputRows(), attachments = chat.attachments)
                    if (statusRows > 0) StatusBar(chatStatus(app, chat, app.config.plainGlyphs, memoryChars))
                    ActionBar(chatActions(browsing, entries.isNotEmpty()))
                }
            }
        }
    }
}

/** The lines of one message; [entry] is the number of its first select-mode entry (a reply uses one per Markdown block). */
private fun messageLines(app: AppContext, message: ChatMessage, entry: Int, width: Int): List<Line> {
    val user = message.role == "user"
    val config = app.config
    if (message.role == "tool") return toolResultLines(message, config.plainGlyphs, width)
    return buildList {
        if (user) {
            // Your messages sit on an amber band, so they stand apart from the model's answers.
            val band = Palette.UserBg
            add(Line("${ModelIcon.user(config.plainGlyphs)} You", Palette.UserName, bold = true, background = band, entry = entry))
            wrap(sanitizeForTerminal(message.content), width).forEach { add(Line(it, Palette.UserText, background = band, entry = entry)) }
            message.noteNames.forEach { add(Line(Skills.chipLabel(it)?.let { chip -> sanitizeForTerminal(chip) } ?: "▤ ${sanitizeForTerminal(it.removeSuffix(".md"))}", Palette.UserName, background = band, entry = entry)) }
            message.files.forEach { add(Line("❐ ${sanitizeForTerminal(it.name)}", Palette.UserName, background = band, entry = entry)) }
            message.imageNames.forEach { name ->
                val kept = if (message.images.isEmpty()) " (picture not kept)" else ""
                add(Line("▣ ${sanitizeForTerminal(name)}$kept", Palette.UserName, background = band, entry = entry))
            }
            add(Line("", background = band, entry = entry))
        } else {
            add(Line(ModelIcon.label(config.model, config.plainGlyphs), Palette.Accent, bold = true, entry = entry))
            addAll(thinkingLines(message.thinking, config.thinkingView, config.plainGlyphs, width))
            addAll(renderMarkdown(parseMarkdown(message.content), width, entry = { entry + it }, rich = config.renderMarkdown, plainGlyphs = config.plainGlyphs))
            // the tools it asked for, one line each
            message.toolCalls.forEach { call -> add(Line("${if (config.plainGlyphs) "[tool]" else "⚙"} ${call.name}  ${sanitizeForTerminal(callSummary(call))}".take(width), Palette.ConsoleTool, bold = true)) }
            // the notes the answer was built from, when the vault was searched
            if (message.sources.isNotEmpty()) {
                wrap("Sources  " + message.sources.joinToString("  ") { "▤ ${sanitizeForTerminal(it.removeSuffix(".md"))}" }, width).forEach { add(Line(it, Palette.Accent)) }
            }
            // the closing line of a finished reply: Done, time, tokens, speed
            message.stats?.let { add(replyFooter(it, config.plainGlyphs, step = message.toolCalls.isNotEmpty())) }
        }
        // the gap after a message belongs to no entry, so the cursor never rests on it
        add(Line(""))
    }
}

/**
 * What a reasoning model thought, kept apart from its answer: nothing ([view] "hidden"), one dim line with the length ("summary"), or all of it,
 * dimmed, under a header ("full"). These lines belong to no select entry, so copying takes only the answer.
 */
internal fun thinkingLines(thinking: String, view: String, plain: Boolean, width: Int): List<Line> {
    val text = thinking.trim()
    if (text.isEmpty() || view == "hidden") return emptyList()
    val words = text.split(Regex("\\s+")).size
    val mark = if (plain) "..." else "◌"
    val header = "$mark thinking · $words ${if (words == 1) "word" else "words"}"
    if (view != "full") return listOf(Line(header, Palette.ConsoleDim))
    return listOf(Line(header, Palette.ConsoleDim, bold = true)) + wrap(sanitizeForTerminal(text), (width - 2).coerceAtLeast(10)).map { Line("  $it", Palette.ConsoleDim) } + Line("")
}

/** What select mode can pick in [m]: your message as one entry, a reply as its Markdown blocks, tool results as none. */
private fun entriesOf(m: ChatMessage): List<String> = when (m.role) {
    "user" -> listOf(m.content)
    "tool" -> emptyList()
    else -> parseMarkdown(m.content).map { it.raw }
}

/** The one thing a tool call is about, for its line in the chat: its path, query or title. */
private fun callSummary(call: ToolCall): String {
    val args = call.arguments
    val key = listOf("command", "url", "question", "from", "path", "query", "pattern", "name", "glob", "title", "folder", "reason", "seconds").filter { args[it] != null }
    if (key.isEmpty()) return ""
    // a search names what it looks for and where; the others have one subject
    return key.take(2).joinToString("  ") { (args[it] as? JsonPrimitive)?.contentOrNull.orEmpty().replace('\n', ' ') }.trim()
}

/** The result of a tool, as a quiet line (or two) under the call: ✓ with what happened, ✕ with why not. */
private fun toolResultLines(message: ChatMessage, plain: Boolean, width: Int): List<Line> {
    val text = message.content.trim()
    val failed = text.startsWith("Error:") || text.startsWith("The user denied") || text.startsWith("The user did not allow") ||
        text.startsWith("Timed out") || (text.startsWith("exit code ") && !text.startsWith("exit code 0"))
    val first = sanitizeForTerminal(text.lineSequence().firstOrNull().orEmpty())
    val size = if (text.length > 80 || '\n' in text) "  (${text.length} characters)" else ""
    val mark = if (plain) (if (failed) "x" else "ok") else if (failed) "✕" else "✓"
    val body = "  ${if (plain) "->" else "↳"} $mark ${first.take((width - 8 - size.length).coerceAtLeast(10))}$size"
    return listOf(Line(body.take(width), if (failed) Palette.Red else Palette.Muted), Line(""))
}

/** The lines of a change waiting for approval, as many as fit the panel. */
/** The lines of a change waiting for an answer, cut to [max] (with a note of how many are left out); a long diff is cut short on a small screen. */
private fun approvalLines(a: ChatSession.Approval, max: Int): List<String> =
    if (a.lines.size <= max) a.lines else a.lines.take(max - 1) + "… ${a.lines.size - max + 1} more lines"

/** How many lines of a change fit above the buttons: about a third of the screen, at least 8. */
@Composable
private fun approvalMax(): Int = (LocalContentHeight.current / 3).coerceIn(8, 24)

private fun approvalActions(a: ChatSession.Approval) = listOfNotNull(
    ActionItem("y", a.yes, accent = Palette.Cta),
    ActionItem("n", if (a.tool == "access" || a.tool == "steps" || a.tool == "memory" || a.tool == "mode") "Not now" else "Deny"),
    if (a.always) ActionItem("a", "Always this session") else null,
)

/** A request to save the chat: what to save, and where to go afterwards (when it came from the leave question). */
private data class SaveRequest(val after: Screen?, val lastAnswerOnly: Boolean = false)

/** The whole conversation as Markdown: a heading per speaker. */
private fun transcript(messages: List<ChatMessage>, model: String): String =
    messages.filter { it.role != "tool" && !(it.toolCalls.isNotEmpty() && it.content.isBlank()) }.joinToString("\n\n") {
        val attached = (it.imageNames + it.files.map { f -> f.name } + it.noteNames.map { n -> "[[${n.removeSuffix(".md")}]]" }).let { a -> if (a.isEmpty()) "" else "\n\n_Attached: ${a.joinToString(", ")}_" }
        val sources = if (it.sources.isEmpty()) "" else "\n\n_Sources: ${it.sources.joinToString(", ") { s -> "[[${s.removeSuffix(".md")}]]" }}_"
        "## ${if (it.role == "user") "You" else model}\n\n${it.content.trim()}$attached$sources"
    }

/** The buttons under the message field. */
private fun chatActions(browsing: Boolean, hasEntries: Boolean): List<ActionItem> =
    if (browsing) {
        listOf(ActionItem("Enter", "Type a message", accent = Palette.Cta), ActionItem("s", "Select & copy", hasEntries), ActionItem("↑↓", "Scroll"))
    } else {
        listOf(
            ActionItem("Enter", "Send"),
            ActionItem("@", "Note"),
            ActionItem("Ctrl+O", "Attach"),
            ActionItem("Ctrl+S", "Save to vault"),
            ActionItem("Ctrl+P", "Commands", accent = Palette.Cta),
        )
    }
