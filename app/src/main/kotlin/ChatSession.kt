package com.arcana.brain.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.Requirement
import com.arcana.brain.core.Skills
import com.arcana.brain.core.SkillRun
import com.arcana.brain.core.FileAttachment
import com.arcana.brain.core.ImageAttachment
import com.arcana.brain.core.Prompts
import com.arcana.brain.core.ReplyStats
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.StoredChat
import com.arcana.brain.core.ToolBox
import com.arcana.brain.core.ToolCall
import com.arcana.brain.core.ASK_USER
import com.arcana.brain.core.ASK_USER_DEFINITION
import com.arcana.brain.core.REQUEST_ACCESS
import com.arcana.brain.core.looksLikeToolTask
import kotlinx.coroutines.launch
import com.arcana.brain.core.REQUEST_ACCESS_DEFINITION
import com.arcana.brain.core.REQUEST_MODE
import com.arcana.brain.core.REQUEST_MODE_DEFINITION
import com.arcana.brain.core.TOOL_MODES
import com.arcana.brain.core.ShellPolicy
import com.arcana.brain.core.cleanOption
import com.arcana.brain.core.WebPolicy
import com.arcana.brain.core.ToolRisk
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import com.arcana.brain.core.Vault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The conversation on the Chat screen. It lives here, not in the screen, so it survives switching to another screen, and
 * so does the reply being written: it keeps streaming while you look at something else. With [AppConfig.keepChats] on it
 * is saved after every message, so the last chat is back when Arcana starts again.
 *
 * A message can carry pictures and notes from the vault (their text is read when the message is sent), and with
 * [AppConfig.chatUseVault] on, the vault is searched for each question and the answer cites the notes it used.
 */
class ChatSession(private val app: AppContext, private val store: ChatStore) {
    val messages = mutableStateListOf<ChatMessage>()

    /** What is typed in the message field but not sent yet: kept too, so leaving the screen does not lose it. */
    val input = TextFieldState(multiline = true)
    var id by mutableStateOf(newId())
        private set

    /** Pictures, files and notes chosen for the next message; they go out with it and are cleared. */
    val attachments = app.attachments()

    /** How many messages were already saved to the vault as a note; anything after that is unsaved. */
    var savedMessages by mutableIntStateOf(0)

    /** Messages that exist only in the chat history, not in a vault note. */
    val unsaved: Boolean get() = messages.size > savedMessages

    private var pendingSources: List<String> = emptyList()

    /** The reply being written. It lives as long as the app does. */
    val gen: Generation by lazy {
        Generation(
            app.scope, app.client,
            onBusy = { busy -> app.busy = (app.busy + if (busy) 1 else -1).coerceAtLeast(0) },
            onUsage = { chunk -> app.recordUsage("Chat", chunk) },
            onComplete = { text, stats -> finishReply(text, stats) },
            onToolRound = ::runTools,
            onStepLimit = ::stepLimit,
            textCalls = { app.config.textCalls },
            confirmTextCalls = ::confirmTextCalls,
            gate = { skillRun?.nudge() },
        )
    }

    /**
     * The skill being run, with its required steps ticked off as the model does them (null when no skill with `requires:` is active). It is
     * started by a skill chip on a message or by the model's `use_skill` call; the Chat status bar shows its progress (see [skillTick]).
     */
    var skillRun by androidx.compose.runtime.mutableStateOf<SkillRun?>(null)
        private set

    /** Changes whenever [skillRun] makes progress, so the status bar redraws. */
    var skillTick by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private fun startSkillRun(name: String, requirements: List<Requirement>) {
        skillRun = if (requirements.isEmpty()) null else SkillRun(name, requirements)
        skillTick++
    }

    /** A change the model wants to make, waiting for the user's answer. */
    class Approval(
        val tool: String, val title: String, val lines: List<String>,
        val heading: String = "Allow this change?", val icon: String = "⚙", val yes: String = "Allow", val always: Boolean = true,
    ) {
        val answer = CompletableDeferred<ApprovalChoice>()
    }

    enum class ApprovalChoice { Allow, Deny, Always }

    /** A question the model put to the user with ask_user: pick an option (or several), or type an answer. */
    class Question(val text: String, val options: List<String>, val multiple: Boolean) {
        /** The answer as text for the model; null when the user skipped it. */
        val answer = CompletableDeferred<String?>()
        val picked = androidx.compose.runtime.mutableStateListOf<Int>()

        /** The highlighted row: an option, or the last row, where the user types an answer of their own (right there, inside the dialog). */
        var cursor by androidx.compose.runtime.mutableIntStateOf(0)

        /** What is typed on the last row. */
        val field = com.arcana.brain.app.input.TextFieldState()

        /** The cursor is on the text row (an open question has only that row). */
        val typing: Boolean get() = cursor == options.size
    }

    var question by mutableStateOf<Question?>(null)
        private set

    fun answerQuestion(text: String?) { question?.answer?.complete(text) }

    /** Option [number] (1-based): chosen at once, or ticked when several may be chosen. */
    fun pick(number: Int) {
        val q = question ?: return
        if (q.typing || number !in 1..q.options.size) return
        if (!q.multiple) return answerQuestion(q.options[number - 1])
        if ((number - 1) in q.picked) q.picked.remove(number - 1) else q.picked.add(number - 1)
    }

    fun move(delta: Int) {
        val q = question ?: return
        q.cursor = (q.cursor + delta).mod(q.options.size + 1)
    }

    /** Enter: the highlighted option is the answer (several may be chosen: the ticked ones, or this one when none is ticked); the last row opens the text field. */
    fun activate() {
        val q = question ?: return
        when {
            q.typing -> q.field.text.trim().takeIf { it.isNotEmpty() }?.let { answerQuestion(it) }
            !q.multiple -> answerQuestion(q.options[q.cursor])
            q.picked.isEmpty() -> answerQuestion(q.options[q.cursor])
            else -> confirmPicks()
        }
    }

    /** Space, when several may be chosen: tick or untick the highlighted option. */
    fun tickCurrent() {
        val q = question ?: return
        if (q.multiple && q.cursor < q.options.size) pick(q.cursor + 1)
    }

    fun confirmPicks() {
        val q = question ?: return
        if (q.picked.isNotEmpty()) answerQuestion(q.picked.sorted().joinToString("; ") { q.options[it] })
    }

    /** The question text, whatever the model called it, or what it wrote next to the call; empty when it gave none. */
    private fun questionText(call: ToolCall, beside: String): String {
        val args = call.arguments
        val named = listOf("question", "prompt", "text", "message", "title", "query", "ask").firstNotNullOfOrNull { key ->
            (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        }
        return named ?: beside.trim().lines().lastOrNull { it.isNotBlank() }?.trim()?.take(300).orEmpty()
    }

    private suspend fun askUser(call: ToolCall, beside: String): String {
        val args = call.arguments
        val text = questionText(call, beside).ifEmpty { return "Error: ask_user needs the question itself. Call it again with question set to the text you want to ask, and options (2 to 5 short answers)." }
        val options = (args["options"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::cleanOption)?.takeIf { s -> s.isNotEmpty() } }.orEmpty().take(9)
        val multiple = (args["multiple"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" && options.size > 1
        val q = Question(text, options, multiple)
        question = q
        val answer = try { q.answer.await() } finally { question = null }
        return when {
            answer == null -> "The user skipped the question. Carry on with your best judgement, or ask again in plain words."
            options.isNotEmpty() && (answer in options || multiple) -> "The user chose: $answer"
            else -> "The user answered: $answer"
        }
    }

    /** The tool call waiting for an answer (shown by Chat), or null. */
    var approval by mutableStateOf<Approval?>(null)
        private set

    /** Tools the user allowed "always" for this session. */
    private val alwaysAllowed = mutableSetOf<String>()
    private var toolbox: ToolBox? = null

    fun answer(choice: ApprovalChoice) {
        approval?.answer?.complete(choice)
    }

    /** The tools for the current settings, or null when tools are off. */
    fun toolboxFor(config: AppConfig): ToolBox? {
        if (!config.toolsEnabled) return null
        val folders = config.toolFolders.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { AppContext.expandHome(it) }
        return ToolBox(
            app.openVault().getOrNull(), folders,
            backups = ConfigStore.defaultPath().resolveSibling("backups"),
            outputFolder = config.outputFolder,
            model = config.model,
            readOnly = config.toolMode == "plan",
            shell = config.toolShell,
            web = config.toolWeb,
            searchBackend = config.searchBackend, searchUrl = config.searchUrl, searchKey = config.searchKey,
            skillsFolder = if (config.skillsEnabled) config.skillsFolder else "",
        )
    }

    /** Runs the tools the model asked for (asking the user first when needed) and returns the messages to continue with. */
    private suspend fun runTools(text: String, calls: List<ToolCall>, stats: ReplyStats?): List<ChatMessage> {
        val added = ArrayList<ChatMessage>()
        val request = ChatMessage.assistantCalls(text, calls, stats, gen.takeThinking())
        add(request)
        added += request
        for (call in calls) {
            // read again for every call: an earlier call of this round may have switched the mode, which rebuilds the toolbox
            val box = toolbox
            if (call.name == ASK_USER || call.name == REQUEST_ACCESS || call.name == REQUEST_MODE || box == null) {
                val message = ChatMessage.toolResult(call.name, when {
                    call.name == ASK_USER -> askUser(call, text)
                    call.name == REQUEST_ACCESS -> requestAccess(call)
                    call.name == REQUEST_MODE -> requestMode(call)
                    else -> "Error: tools are off. Call request_access to ask the user for them."
                })
                add(message)
                added += message
                skillRun?.let { it.observe(call.name, call.arguments.toString(), message.content); skillTick++ }
                continue
            }
            val spec = box.find(call.name)
            // a folder that is not allowed yet: ask the user, and when they agree remember it for good
            val grant = box.folderToGrant(call)
            var folderDenied = false
            if (grant != null) {
                val pending = Approval(
                    "folder", "Allow Arcana to use this folder?",
                    listOf("${grant}", "The model may read, and (with your approval each time) change, files inside it.", "You can remove it later in Settings → Tools → Extra folders."),
                    heading = "Allow this folder?", icon = "▤",
                )
                approval = pending
                val choice = try { pending.answer.await() } finally { approval = null }
                if (choice == ApprovalChoice.Deny) {
                    folderDenied = true
                } else {
                    box.allow(grant)
                    val kept = app.config.toolFolders.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                    if (grant.toString() !in kept) app.update(app.config.copy(toolFolders = (kept + grant.toString()).joinToString(";")))
                }
            }
            // a shell command: never run what is blocked, and when shell commands are off, ask for them here and now
            val command = if (spec?.risk == ToolRisk.Shell) (call.arguments["command"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().trim() else ""
            var refusal: String? = null
            if (spec?.risk == ToolRisk.Shell) {
                val blocked = ShellPolicy.blockedReason(command)
                if (blocked != null) refusal = "Error: Arcana never runs this kind of command: $blocked."
                else if (!app.config.toolShell && !folderDenied) {
                    val pending = Approval(
                        "shell-on", command,
                        listOf("The model wants to run shell commands (builds, tests, git…).", "You still approve every single command before it runs.", "Turn it off again in Settings → Tools → Shell commands."),
                        heading = "Turn on shell commands?", icon = "$", yes = "Turn on", always = false,
                    )
                    approval = pending
                    val choice = try { pending.answer.await() } finally { approval = null }
                    if (choice == ApprovalChoice.Deny) refusal = "The user did not allow shell commands. Do not ask again; tell them they can turn them on in Settings → Tools → Shell commands."
                    else app.update(app.config.copy(toolShell = true))
                }
            }
            // a web address: refuse the private ones before asking anything, and when web access is off ask for it here and now
            val address = if (spec?.risk == ToolRisk.Web) (call.arguments[if (call.name == "web_search") "query" else "url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().trim() else ""
            if (spec?.risk == ToolRisk.Web && refusal == null) {
                val blocked = if (call.name == "web_search") null else WebPolicy.refusal(address)
                if (blocked != null) refusal = "Error: $blocked"
                else if (!app.config.toolWeb && !folderDenied) {
                    val pending = Approval(
                        "web-on", address.chunked(76).first(),
                        listOf("The model wants to search the web and read web pages.", "You approve every search and address before it is sent; only public pages are reachable.", "Turn it off again in Settings → Tools → Web pages."),
                        heading = "Turn on web pages?", icon = "↗", yes = "Turn on", always = false,
                    )
                    approval = pending
                    val choice = try { pending.answer.await() } finally { approval = null }
                    if (choice == ApprovalChoice.Deny) refusal = "The user did not allow web access. Do not ask again; tell them they can turn it on in Settings → Tools → Web pages."
                    else app.update(app.config.copy(toolWeb = true))
                }
            }
            val ask = spec != null && refusal == null && call.name !in alwaysAllowed && when (spec.risk) {
                ToolRisk.Write -> spec.alwaysAsk || app.config.toolMode != "auto" // deleting and moving ask even in Auto mode
                ToolRisk.Read -> !app.config.toolAutoRead
                ToolRisk.Shell -> !app.config.toolAutoShell && !(app.config.toolAutoRead && ShellPolicy.isSafe(command)) // never "always": each command is looked at, unless auto-run is on
                ToolRisk.Web -> true // never "always": the address itself can carry data out
            }
            var allowed = !folderDenied && refusal == null
            if (ask && allowed) {
                val shell = spec!!.risk == ToolRisk.Shell
                val pending = if (shell) Approval(call.name, "run_command", spec.preview(call.arguments), heading = "Run this command?", icon = "$", yes = "Run", always = false)
                else if (spec.risk == ToolRisk.Web) Approval(call.name, call.name, spec.preview(call.arguments), heading = if (call.name == "web_search") "Search the web?" else "Fetch this web page?", icon = "↗", yes = if (call.name == "web_search") "Search" else "Fetch", always = false)
                else Approval(
                    call.name, "${call.name}  ${spec.summary(call.arguments)}", spec.preview(call.arguments),
                    heading = spec.confirmHeading ?: "Allow this change?", icon = if (spec.alwaysAsk) "!" else "⚙", always = !spec.alwaysAsk,
                )
                approval = pending
                val choice = try {
                    pending.answer.await()
                } finally {
                    approval = null
                }
                if (choice == ApprovalChoice.Always) alwaysAllowed += call.name
                allowed = choice != ApprovalChoice.Deny
            }
            val result = when {
                allowed -> runInterruptible(Dispatchers.IO) { box.execute(call) }
                refusal != null -> refusal
                else -> "The user denied this action. Do not try it again; ask what they want instead."
            }
            if (allowed && spec != null && !result.startsWith("Error:")) {
                app.session.log(SessionKind.Tool, "${call.name} ${spec.summary(call.arguments)}")
                if (spec.risk != ToolRisk.Read) app.brain.changed()
            }
            val message = ChatMessage.toolResult(call.name, result)
            add(message)
            added += message
            // the skill run: a skill the model loaded itself starts it; every call and result is ticked off against the required steps
            if (call.name == "use_skill" && !result.startsWith("Error:")) {
                val skill = Skills.find(box.skills, (call.arguments["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
                val vault = app.openVault().getOrNull()
                if (skill != null && vault != null && skillRun?.name != skill.name) startSkillRun(skill.name, Skills.requirementsOf(vault, skill.path))
            }
            skillRun?.let { it.observe(call.name, call.arguments.toString(), result); skillTick++ }
        }
        return added
    }

    /**
     * The first time Chat opens with a vault that has no ARCANA.md: create it (with rules for using tools) and offer to open it to fill in.
     * A note that already exists is left alone, and a file the user deletes later is not created again (it comes back with /memory).
     */
    fun offerMemory() {
        val config = app.config
        if (!config.memoryEnabled || config.memoryOffered || approval != null) return
        val vault = app.openVault().getOrNull() ?: return
        val existed = java.nio.file.Files.exists(vault.root.resolve(Vault.MEMORY_FILE))
        app.update(config.copy(memoryOffered = true))
        if (existed) return
        val file = runCatching { vault.ensureMemory() }.getOrNull() ?: return
        app.scope.launch {
            val pending = Approval(
                "memory", "${Vault.MEMORY_FILE} is in your vault now",
                listOf(
                    "It is read in every chat and already holds rules that make the assistant use its tools.",
                    "Add who you are, where your projects are, how you like answers. /memory opens it any time.",
                ),
                heading = "Memory file created", icon = "✎", yes = "Open it to fill in", always = false,
            )
            approval = pending
            val choice = try { pending.answer.await() } finally { approval = null }
            if (choice != ApprovalChoice.Deny) SystemOpen.open(file.toString())
        }
    }

    /** The model found tools off (or plan mode on) and asked for what it needs: ask the user on the spot, and apply the answer at once. */
    private suspend fun requestAccess(call: ToolCall): String {
        val reason = (call.arguments["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().ifBlank { "Work with your notes and files." }
        if (!offerTools(reason)) return "The user did not allow it. Do not ask again; tell them they can turn tools on with /tools (or change the mode with /mode) and ask again."
        val box = toolbox
        gen.useTools(box?.definitions())
        return "Done: tools are now on (${app.config.toolMode} mode). Carry on with the user's request using the tools. ${box?.describe().orEmpty()}"
    }

    /** What the model is offered when it has a toolbox: the tools, a way to ask for another mode, and (in plan mode) a way to ask to leave it. */
    private fun definitionsFor(box: ToolBox): JsonArray =
        JsonArray(box.definitions() + REQUEST_MODE_DEFINITION + (if (app.config.toolMode == "plan") listOf(REQUEST_ACCESS_DEFINITION) else emptyList()))

    /** The model asks to switch the tool mode: the user always decides; when they agree the mode, the toolbox and the model's tools change at once. */
    private suspend fun requestMode(call: ToolCall): String {
        val mode = (call.arguments["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().trim().lowercase()
        val reason = (call.arguments["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().ifBlank { "No reason given." }
        if (mode !in TOOL_MODES) return "Error: mode must be one of ${TOOL_MODES.joinToString()}, not \"$mode\"."
        if (mode == app.config.toolMode) return "Already in $mode mode; nothing to change."
        val pending = Approval(
            "mode", reason,
            listOf(when (mode) {
                "auto" -> "Auto: changes run at once, without asking; replaced files are backed up. Deleting and moving still ask."
                "plan" -> "Plan: read-only. The model investigates and writes a plan; nothing is changed."
                else -> "Ask: every change waits for your y / n."
            }, "Now: ${app.config.toolMode}. Switch back any time with /mode."),
            heading = "Switch to ${mode.replaceFirstChar { it.uppercase() }} mode?", icon = "⚙", yes = "Switch to ${mode.replaceFirstChar { it.uppercase() }}", always = false,
        )
        approval = pending
        val choice = try { pending.answer.await() } finally { approval = null }
        if (choice == ApprovalChoice.Deny) return "The user did not allow the switch; you stay in ${app.config.toolMode} mode. Do not ask again; carry on in this mode or tell them what you need."
        app.update(app.config.copy(toolMode = mode))
        val box = toolboxFor(app.config).also { toolbox = it }
        gen.useTools(box?.let { definitionsFor(it) })
        return "Done: now in $mode mode. ${box?.describe().orEmpty()}"
    }

    /** Asks to turn tools on (or to leave plan mode); when the user agrees the settings change at once and [toolbox] is rebuilt. */
    private suspend fun offerTools(reason: String): Boolean {
        val off = !app.config.toolsEnabled
        val pending = if (off) Approval(
            "access", reason,
            listOf("Searching and reading run at once; every change asks you first (Ask mode).", "Only your vault and the folders in Settings → Tools are reachable.", "Turn tools off again any time with /tools; switch mode with /mode."),
            heading = "Turn on tools?", icon = "⚙", yes = "Turn on", always = false,
        ) else Approval(
            "access", reason,
            listOf("Plan mode is read-only, so the model cannot make this change.", "Ask mode lets it try, and you approve every change before it happens.", "Switch back any time with /mode."),
            heading = "Leave plan mode?", icon = "⚙", yes = "Switch to Ask mode", always = false,
        )
        approval = pending
        val choice = try { pending.answer.await() } finally { approval = null }
        if (choice == ApprovalChoice.Deny) return false
        app.update(app.config.copy(toolsEnabled = true, toolMode = if (app.config.toolMode == "plan") "ask" else app.config.toolMode))
        toolbox = toolboxFor(app.config)
        return true
    }

    /** "Ask" mode for a call the model only wrote out as text: show it and let the user decide whether it runs. */
    private suspend fun confirmTextCalls(calls: List<ToolCall>): Boolean {
        val lines = calls.take(3).map { call -> "${call.name}  " + call.arguments.entries.joinToString(", ") { (k, v) -> "$k=${(v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v}" }.take(90) }
        val pending = Approval(
            "textcall", "The model wrote ${if (calls.size == 1) "a tool call" else "${calls.size} tool calls"} as text",
            lines + listOf("It did not make a real call, so nothing has run. Run it as one?", "Change this in Settings → Tools → Calls written as text."),
            heading = "Run this tool call?", icon = "⚙", yes = "Run it", always = false,
        )
        approval = pending
        val choice = try { pending.answer.await() } finally { approval = null }
        return choice != ApprovalChoice.Deny
    }

    /** The step limit was reached while the model still wanted tools: let the user decide whether it may go on. */
    private suspend fun stepLimit(used: Int): Int {
        val pending = Approval(
            "steps", "The model has used $used steps and wants to continue.",
            listOf("Allow ${app.config.toolSteps} more steps, or stop here and let it answer with what it has.", "Change the default in Settings → Tools → Steps per question."),
            heading = "Step limit reached", icon = "⏱", yes = "${app.config.toolSteps} more steps", always = false,
        )
        approval = pending
        val choice = try { pending.answer.await() } finally { approval = null }
        return if (choice == ApprovalChoice.Deny) 0 else app.config.toolSteps
    }

    init {
        if (app.config.keepChats) store.latest()?.let { open(it) }
    }

    val title: String get() = ChatStore.titleOf(messages)

    fun add(message: ChatMessage) {
        messages += message
        persist()
    }

    /** Sends [text] with the attached pictures and notes; the reply streams into [gen]. */
    fun send(text: String) {
        val sent = attachments.drain()
        val body = text.ifEmpty {
            if (sent.pictures.isEmpty() && sent.files.isNotEmpty()) "Explain the attached file${if (sent.files.size == 1) "" else "s"}."
            else "Describe the attached image${if (sent.pictures.size == 1) "" else "s"}."
        }
        add(ChatMessage.user(body, sent.pictures, sent.notes, sent.files))
        app.session.log(SessionKind.Asked, body)
        // a job that clearly needs file tools, with tools off: offer them now instead of letting the model say it cannot
        if (!app.config.toolsEnabled && app.tools != false && !toolsDeclined && looksLikeToolTask(body)) {
            app.scope.launch {
                if (!offerTools("It looks like this needs your notes or files.")) toolsDeclined = true
                ask(body)
            }
        } else ask(body)
    }

    private var toolsDeclined = false

    private fun ask(body: String) {
        val history = messages.toList()
        val modelCanUseTools = app.tools != false
        val box = if (!modelCanUseTools) null else toolboxFor(app.config)
        toolbox = box
        // a new message of the user ends any earlier run (the gate must not drag an unrelated question back into an unfinished skill)...
        if (skillRun != null) { skillRun = null; skillTick++ }
        // ...and a skill chip on it starts a run of that skill (its required steps are then watched)
        history.lastOrNull { it.role == "user" }?.noteNames?.firstOrNull { Skills.isEntry(it) }?.let { entry ->
            val vault = app.openVault().getOrNull()
            if (vault != null) startSkillRun(Skills.nameOfEntry(entry), Skills.requirementsOf(vault, entry))
        }
        val canRequest = modelCanUseTools && box == null
        val offered = when {
            box == null -> if (canRequest) JsonArray(listOf(REQUEST_ACCESS_DEFINITION, ASK_USER_DEFINITION)) else null
            else -> definitionsFor(box)
        }
        gen.start(tools = offered, maxSteps = Skills.stepsFor(history, app.config.toolSteps)) {
            val config = app.config
            val vault = app.openVault().getOrNull()
            val hits = if (config.chatUseVault && vault != null) vault.search(body, config.contextNotes, config.contextCharsPerNote) else null
            pendingSources = hits?.map { it.note.path }.orEmpty()
            Prompts.chatMessages(config, history, hits, noteTexts(vault, history.takeLast(config.chatHistory)), tools = box != null, toolsNote = box?.describe().orEmpty(), canRequestTools = canRequest, memory = if (config.memoryEnabled) vault?.memory().orEmpty() else "")
        }
    }

    /** The text of every note attached to [window], read from the vault now (so a note that changed is seen as it is). */
    private fun noteTexts(vault: Vault?, window: List<ChatMessage>): Map<String, String> {
        if (vault == null) return emptyMap()
        return window.flatMap { it.noteNames }.distinct().mapNotNull { path ->
            // a skill's entry note goes in as the skill loader gives it (folder, file list, instructions); any other note as its text
            Skills.loadEntry(vault, path)?.let { path to it }
                ?: runCatching { path to Vault.splitFrontmatter(vault.read(path)).second.take(MAX_NOTE_CHARS) }.getOrNull()
        }.toMap()
    }

    private fun finishReply(text: String, stats: ReplyStats?) {
        add(ChatMessage.assistant(text, stats, pendingSources, gen.takeThinking()))
        pendingSources = emptyList()
        gen.consume()
    }

    /** Starts an empty conversation. The old one stays saved and can be opened again from the history. */
    fun startNew() {
        skillRun = null
        toolsDeclined = false
        messages.clear()
        input.clear()
        attachments.clear()
        savedMessages = 0
        id = newId()
    }

    fun open(chat: StoredChat) {
        skillRun = null // a run belongs to the conversation it started in (see docs/skills.md)
        skillTick++
        messages.clear()
        messages += chat.messages
        attachments.clear()
        id = chat.id
        // A chat brought back from the history is not nagged about: it was already kept, and the user chose to open it.
        savedMessages = messages.size
    }

    /** Erases this conversation (also from disk) and starts an empty one. */
    fun deleteCurrent() {
        store.delete(id)
        startNew()
    }

    fun history(): List<StoredChat> = if (app.config.keepChats) store.list() else emptyList()

    private fun persist() {
        if (!app.config.keepChats || messages.isEmpty()) return
        runCatching {
            // pictures are sent from memory; only their names are kept on disk
            store.save(StoredChat(id, title, System.currentTimeMillis(), app.config.model, messages.map { it.withoutImageData() }))
            store.prune(app.config.chatsKept)
        }
    }

    private fun newId() = System.currentTimeMillis().toString(36) + (0..99999).random().toString(36)

    private companion object {
        const val MAX_NOTE_CHARS = 60_000
    }
}
