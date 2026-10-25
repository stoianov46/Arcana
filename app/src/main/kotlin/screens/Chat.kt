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
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.Vault
import com.arcana.brain.app.SystemOpen
import com.jakewharton.mosaic.ui.Row
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How full the model's context was after the last reply (its prompt plus its answer) as a share and a `13.6k/131k` text; null before the first reply or when the size is unknown. */
internal fun contextUsage(messages: List<ChatMessage>, limit: Int): Pair<Double, String>? {
    val stats = messages.mapNotNull { it.stats }.lastOrNull { it.promptTokens > 0 } ?: return null
    if (limit <= 0) return null
    val used = stats.promptTokens + stats.completionTokens
    return (used.toDouble() / limit).coerceIn(0.0, 1.0) to "${UsageStats.compact(used.toLong())}/${UsageStats.compact(limit.toLong())}"
}

/** `ctx ▰▰▱▱▱▱▱▱ 12% 13k/131k`: how full the model's context was after the last reply (its prompt plus its answer); empty before the first reply or when the size is unknown. */
internal fun contextMeter(messages: List<ChatMessage>, limit: Int): String {
    val stats = messages.mapNotNull { it.stats }.lastOrNull { it.promptTokens > 0 } ?: return ""
    if (limit <= 0) return ""
    val used = stats.promptTokens + stats.completionTokens
    val share = (used * 100.0 / limit).coerceIn(0.0, 100.0)
    val filled = (share / 12.5).toInt().coerceIn(0, 8).let { if (it == 0 && used > 0) 1 else it }
    val full = com.arcana.brain.core.UsageStats::compact
    return " · ctx " + "▰".repeat(filled) + "▱".repeat(8 - filled) + " ${"%.0f".format(share)}% ${full(used.toLong())}/${full(limit.toLong())}" +
        if (share >= 80) " ⚠ nearly full: /new starts fresh" else ""
}

/** What the Chat status bar says: model, chat, context gauge, tokens, estimated price, session time and the tool state. */
private fun chatStatus(app: AppContext, chat: ChatSession, plain: Boolean, memoryChars: Int): List<StatusSegment> {
    val config = app.config
    val stats = chat.messages.mapNotNull { it.stats }
    val sessionRecords = UsageStats.since(app.usage.toList(), app.sessionStart)
    val cost = UsageStats.estimatedCost(sessionRecords, config.estPriceIn, config.estPriceOut)
    val compact = UsageStats::compact
    return buildList {
        add(pillSegment("◆ ${config.model}", Palette.Accent, 9))
        if (config.chatUseVault) add(pillSegment("◉ vault", Palette.Cta, 6))
        // the memory file the assistant reads in every chat: shown, so you can see that it is in use (and how much room it takes)
        if (config.memoryEnabled) {
            if (memoryChars > 0) add(statusSegment("", "✎ memory ${UsageStats.compact(memoryChars.toLong())}", Palette.Green, 6))
            else add(statusSegment("", "✎ no ARCANA.md", Palette.Muted, 3))
        }
        contextUsage(chat.messages, config.contextTokens.takeIf { it > 0 } ?: app.modelContext)?.let { (share, detail) -> add(gaugeSegment("ctx", share, detail, 10, plain)) }
        if (stats.isNotEmpty()) add(StatusSegment(listOf(StatusPart("↑${compact(stats.sumOf { it.promptTokens.toLong() })}", Palette.Blue), StatusPart(" ↓${compact(stats.sumOf { it.completionTokens.toLong() })}", Palette.Green)), 3))
        add(statusSegment("est.", priceText(cost), Palette.Orange, 7, bold = true))
        add(statusSegment("", "◷ ${spanText(System.currentTimeMillis() - app.sessionStart)}", Palette.Accent, 2))
        if (config.toolsEnabled) {
            if (app.tools == false) add(pillSegment("⚠ no tools", Palette.Red, 8))
            else add(pillSegment("⚙ ${config.toolMode}", when (config.toolMode) { "auto" -> Palette.Orange; "plan" -> Palette.Blue; else -> Palette.Green }, 8))
        }
    }
}

/** The question and its options as one row each (what the panel shows and what its height is counted from). */
private fun questionLines(q: ChatSession.Question, width: Int): List<String> {
    val text = wrap(sanitizeForTerminal(q.text), width).take(6)
    if (q.typing) return text
    val options = q.options.mapIndexed { i, option ->
        val mark = if (q.multiple) (if (i in q.picked) "[x] " else "[ ] ") else ""
        (if (i == q.cursor) "▸ " else "  ") + "$mark${i + 1}  ${sanitizeForTerminal(option)}".take(width - 2)
    }
    return text + options + ((if (q.cursor == q.options.size) "▸ " else "  ") + "✎  Type my own answer")
}

private fun questionActions(q: ChatSession.Question) =
    if (q.typing) listOf(ActionItem("Enter", "Send answer", accent = Palette.Cta), ActionItem("Esc", "Skip"))
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
    // Esc leaves the message field: then j/k/g/s and the number keys are shortcuts, until Enter goes back to typing.
    var browsing by remember { mutableStateOf(false) }
    var past by remember { mutableStateOf<List<StoredChat>>(emptyList()) }
    var leaving by remember { mutableStateOf<Screen?>(null) }
    var saving by remember { mutableStateOf<SaveRequest?>(null) }
    val saveTitle = remember { TextFieldState() }
    var target by remember { mutableStateOf(Target.Append) }
    var replaceTarget by remember { mutableStateOf<String?>(null) }

    fun toast(text: String, error: Boolean = false) = navigator.showToast(text, error)

    val latestUnsaved by rememberUpdatedState(chat.unsaved && app.config.confirmLeaveChat)
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
            app.session.log(SessionKind.Saved, "Chat saved as a note", path)
            app.brain.changed()
            toast("Saved as $path")
            saving = null
            mode = ChatMode.Chat
            request.after?.let { navigator.navigate(it, force = true) }
        }.onFailure { toast(it.message ?: "Could not save.", error = true) }
    }

    fun applyToNote(path: String) {
        val answer = lastAnswer ?: return
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
        }.onFailure { toast(it.message ?: "Could not write to the note.", error = true) }
        replaceTarget = null
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

    fun openMemory() {
        val vault = app.openVault().getOrNull() ?: return toast("Choose a vault first (Settings → Vault).", error = true)
        val file = runCatching { vault.ensureMemory() }.getOrElse { return toast("Could not create ${Vault.MEMORY_FILE}.", error = true) }
        val opened = SystemOpen.open(file.toString())
        toast(if (opened) "${Vault.MEMORY_FILE} opened in your editor. Save it; the next message uses it." else "Edit ${file} (it is read in every chat).")
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
            "/mode" -> cycleMode()
            "/append" -> openTargetPick(Target.Append)
            "/replace" -> openTargetPick(Target.Replace)
            else -> toast("Unknown command. Try /new /history /clear /save /image /note /vault /tools /mode /memory /append /replace", error = true)
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
            Command("Tools mode: ${app.config.toolMode} → next (ask, auto, plan)", "Chat", "/mode", app.config.toolsEnabled) { cycleMode() },
            Command(if (app.config.toolsEnabled) "Tools: turn off" else "Tools: turn on (read and write notes and files)", "Chat", "/tools") { toggleTools() },
            Command(if (app.config.chatUseVault) "Answer from my vault: turn off" else "Answer from my vault: turn on", "Chat", "/vault") { toggleVault() },
            Command("Select & copy", "Chat", "Esc then s", entries.isNotEmpty()) { browsing = true; output.beginSelect { toast(it.text) } },
            Command("Append the last answer to a note…", "Chat", "/append", lastAnswer != null) { openTargetPick(Target.Append) },
            Command("Replace a note with the last answer…", "Chat", "/replace", lastAnswer != null) { openTargetPick(Target.Replace) },
            Command("Erase this chat…", "Chat", "/clear", chat.messages.isNotEmpty()) { mode = ChatMode.ConfirmClear },
        ),
    )

    val attachments = chat.attachments
    val request = saving
    val leave = leaving
    val approval = chat.approval
    val question = chat.question
    FocusMode(
        if (approval != null || question != null) true else when (mode) {
            ChatMode.Chat -> if (attachments.browsing) attachments.filtering else !browsing
            ChatMode.History -> historyPicker.filtering
            ChatMode.PickNote, ChatMode.PickTarget -> notePicker.filtering
            else -> true
        },
    )
    if (question != null) Hints(*(if (question.typing) arrayOf("Enter  send the answer", "Esc  skip") else arrayOf("↑↓  move", if (question.multiple) "Space  tick" else "Enter  choose", *(if (question.multiple) arrayOf("Enter  confirm") else emptyArray()), "1-${question.options.size}  pick", "t  type my own", "Esc  skip")))
    else if (approval != null) Hints("y  ${approval.yes.lowercase()}", "n  no", *(if (approval.always) arrayOf("a  always this session") else emptyArray())) else if (attachments.browsing) Hints(*attachBrowserHints(attachments).toTypedArray()) else when (mode) {
        ChatMode.Save -> Hints("Enter  save", "Tab  whole chat / last answer", "Esc  cancel")
        ChatMode.Leave -> Hints("w  save to vault", "Esc  leave chat", "any other key  stay")
        ChatMode.ConfirmClear -> Hints("y  erase this chat", "any other key  keep")
        ChatMode.ConfirmReplace -> Hints("y  replace", "any other key  keep the note")
        ChatMode.History -> Hints("↑↓  move", "Enter  open", "/  filter", "Esc  back")
        ChatMode.PickNote -> Hints("type  search", "↑↓  move", "Enter  attach", "Esc  cancel")
        ChatMode.PickTarget -> Hints("type  search", "↑↓  move", "Enter  ${target.verb.lowercase()}", "Esc  cancel")
        ChatMode.Chat -> when {
            output.selecting -> Hints("↑↓  move", "Space  tick", "c  copy", "b  code only", "Esc  leave")
            gen.state == GenState.Running -> Hints("Esc  cancel reply", "↑↓  scroll")
            browsing -> Hints("Enter  back to typing", "s  select & copy", "↑↓/Space  scroll", "1-9  screens")
            else -> Hints("Enter  send", "Alt+Enter  new line", "@  note", "Ctrl+O  attach", "Ctrl+S  save", "↑↓  scroll", "Esc  browse")
        }
    }

    // a vault without ARCANA.md gets one, with an offer to fill it in
    androidx.compose.runtime.LaunchedEffect(Unit) { chat.offerMemory() }

    KeyHandler { key ->
        if (question != null) {
            // a question from the model owns the keys: digits pick, t types an answer, Esc skips; other keys (Ctrl+C, screen numbers) still work
            if (question.typing) {
                return@KeyHandler when {
                    key == Key.Escape -> { chat.answerQuestion(null); input.clear(); true }
                    key == Key.Enter -> { input.text.trim().takeIf { it.isNotEmpty() }?.let { chat.answerQuestion(it); input.clear() }; true }
                    else -> input.onKey(key)
                }
            }
            return@KeyHandler when {
                key == Key.Up -> { chat.move(-1); true }
                key == Key.Down -> { chat.move(1); true }
                key == Key.Enter -> { chat.activate(); true }
                key.char == ' ' && question.multiple -> { chat.tickCurrent(); true }
                key.char != null && key.char!!.isDigit() && key.char != '0' -> { chat.pick(key.char!! - '0'); true }
                key.char == 't' || key.char == 'T' -> { question.typing = true; true }
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
                if (key.char == 'y' || key.char == 'Y') replaceTarget?.let { applyToNote(it) } else { replaceTarget = null; mode = ChatMode.Chat }
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
                    key == Key.Escape -> { mode = ChatMode.Chat; true }
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
                key == Key.Save -> { openSave(null); true }
                key == Key.Browse -> { openAttach(); true }
                output.selecting -> output.handle(key, entries, onNotice = { toast(it.text) })
                key == Key.Escape -> when {
                    gen.state == GenState.Running -> { gen.cancel(); true }
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
                        ActionItem("Esc", "Cancel", accent = Palette.Cta),
                    ),
                )
            }
            ChatMode.Leave -> {
                ScreenHeader("Chat", chat.title)
                VGap()
                Panel("Leave this chat?", accent = Palette.Warn) {
                    Warning("This conversation (${chat.messages.size - chat.savedMessages} new messages) is not saved to your vault as a note.")
                    Hint(if (app.config.keepChats) "It stays in the chat history either way: /history reopens it." else "Chats are not being kept, so it is gone once you leave.")
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
                Panel("Replace this note with the last answer?", accent = Palette.Orange) {
                    Warning("The text of the note is replaced by the answer. Its frontmatter is kept, and the old text is saved first in the vault's .trash folder.")
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
                    if (mode == ChatMode.PickNote) "Attach a note" else "${target.verb} a note",
                    if (mode == ChatMode.PickNote) "Its text goes to the model with your next message" else if (target == Target.Append) "The last answer is added to the end of the note you choose" else "The text of the note you choose is replaced by the last answer (the old text is kept in .trash)",
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
                ScreenHeader("Chat", "${chat.title} · ${if (count == 0) "empty" else "$count messages"}${if (chat.unsaved && count > 0) " · not saved to vault" else ""}")
                // the status bar sits under the message field (above the buttons): one row more in the count below
                val statusRows = if (rememberDensity().tiny) 0 else 1
                VGap()
                Panel("Conversation") {
                    val width = outputWidth()
                    val lines = buildList {
                        if (chat.messages.isEmpty() && gen.text.isEmpty()) {
                            add(Line(Shortcuts.label("› Ask your second brain anything. @ attaches a note, Ctrl+O a file or picture, /vault makes answers come from your notes."), Palette.Hint))
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
                    val below = (if (question != null) qLines.size + 3 + (if (question.typing) 2 + inputRows else 0) else if (approval != null) approvalLines(approval).size + 3 else 2 + inputRows + pendingRow) + statusRows + panelActionRows
                    ScrollView(lines, outputRows(fixed = 3 + 2 + statusRow + below, actions = actions), output)
                    ReplyStatus(app, gen)
                }
                if (question != null) {
                    val qLines = questionLines(question, panelWidth())
                    Panel("Question", accent = Palette.Cta) {
                        Label("? ${app.config.model} asks", color = Palette.Cta, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                        qLines.forEach { l -> Label(l, color = if (l.startsWith("▸")) Palette.Cta else if (l.contains("[x]")) Palette.Green else Palette.Text) }
                        ActionBar(questionActions(question), panelWidth())
                    }
                    if (question.typing) {
                        VGap()
                        TextInput("Your answer", input, true, "Type your answer and press Enter", maxRows = maxInputRows())
                    }
                    if (statusRows > 0) StatusBar(chatStatus(app, chat, app.config.plainGlyphs, memoryChars))
                } else if (approval != null) {
                    Panel(approval.heading, accent = Palette.Warn) {
                        Label("${approval.icon} ${approval.title}", color = Palette.Warn, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
                        approvalLines(approval).forEach { l ->
                            Label(l, color = when { l.startsWith("+ ") -> Palette.Green; l.startsWith("- ") -> Palette.Red; else -> Palette.Text })
                        }
                        ActionBar(approvalActions(approval), panelWidth())
                    }
                    if (statusRows > 0) StatusBar(chatStatus(app, chat, app.config.plainGlyphs, memoryChars))
                } else {
                    VGap()
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
            add(Line("You", Palette.UserName, bold = true, background = band, entry = entry))
            wrap(sanitizeForTerminal(message.content), width).forEach { add(Line(it, Palette.UserText, background = band, entry = entry)) }
            message.noteNames.forEach { add(Line("▤ ${sanitizeForTerminal(it.removeSuffix(".md"))}", Palette.UserName, background = band, entry = entry)) }
            message.files.forEach { add(Line("❐ ${sanitizeForTerminal(it.name)}", Palette.UserName, background = band, entry = entry)) }
            message.imageNames.forEach { name ->
                val kept = if (message.images.isEmpty()) " (picture not kept)" else ""
                add(Line("▣ ${sanitizeForTerminal(name)}$kept", Palette.UserName, background = band, entry = entry))
            }
            add(Line("", background = band, entry = entry))
        } else {
            add(Line(config.model, Palette.Accent, bold = true, entry = entry))
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

/** What select mode can pick in [m]: your message as one entry, a reply as its Markdown blocks, tool results as none. */
private fun entriesOf(m: ChatMessage): List<String> = when (m.role) {
    "user" -> listOf(m.content)
    "tool" -> emptyList()
    else -> parseMarkdown(m.content).map { it.raw }
}

/** The one thing a tool call is about, for its line in the chat: its path, query or title. */
private fun callSummary(call: ToolCall): String {
    val args = call.arguments
    val key = listOf("command", "url", "question", "from", "path", "query", "pattern", "name", "glob", "title", "folder", "reason").filter { args[it] != null }
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
private fun approvalLines(a: ChatSession.Approval): List<String> = a.lines.take(14)

private fun approvalActions(a: ChatSession.Approval) = listOfNotNull(
    ActionItem("y", a.yes, accent = Palette.Cta),
    ActionItem("n", if (a.tool == "access" || a.tool == "steps" || a.tool == "memory") "Not now" else "Deny"),
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
        listOf(ActionItem("Enter", "Back to typing", accent = Palette.Cta), ActionItem("s", "Select & copy", hasEntries), ActionItem("↑↓", "Scroll"))
    } else {
        listOf(
            ActionItem("Enter", "Send"),
            ActionItem("@", "Note"),
            ActionItem("Ctrl+O", "Attach"),
            ActionItem("Ctrl+S", "Save"),
            ActionItem("Ctrl+P", "Commands", accent = Palette.Cta),
        )
    }
