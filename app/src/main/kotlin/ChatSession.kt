package com.arcana.brain.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.STEP_LIMIT_AUTO_ROUNDS
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.Requirement
import com.arcana.brain.core.SkillRoles
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
import com.arcana.brain.core.ToolUseRecord
import com.arcana.brain.core.ASK_USER
import com.arcana.brain.core.ASK_USER_DEFINITION
import com.arcana.brain.core.REQUEST_ACCESS
import com.arcana.brain.core.looksLikeToolTask
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import com.arcana.brain.core.REQUEST_ACCESS_DEFINITION
import com.arcana.brain.app.screens.contextUsage
import com.arcana.brain.app.screens.effectiveContext
import com.arcana.brain.core.CHAT_STATS
import com.arcana.brain.core.TODO
import com.arcana.brain.core.TODO_DEFINITION
import com.arcana.brain.core.TodoItem
import com.arcana.brain.core.Todos
import com.arcana.brain.core.Compaction
import com.arcana.brain.core.Oracul
import com.arcana.brain.core.OllamaClient
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.CHAT_STATS_DEFINITION
import com.arcana.brain.core.ChatStats
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

    /**
     * Messages typed while the model works. A tool turn picks them up at its next round (they join it as your messages, so a correction or a
     * clarification reaches the model before it goes further); a turn without tool rounds sends them one by one when its reply is done.
     * Cancelling or a failure gives them back to the message field (see [takeQueued]).
     */
    val queued = mutableStateListOf<String>()

    fun enqueue(text: String) { queued += text }

    /** Drops the newest queued message (it is not sent and not kept); false when nothing was queued. */
    fun dismissQueued(): Boolean {
        if (queued.isEmpty()) return false
        queued.removeAt(queued.lastIndex)
        return true
    }

    /** Empties the queue and returns what was in it, joined; empty when nothing waited. */
    fun takeQueued(): String = queued.joinToString("\n\n").also { queued.clear() }

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
    private var pendingCut: List<String> = emptyList()

    /** Notes the user chose to send whole, and notes they accepted cut, for this chat (the history is re-read on every turn, so the choice must stay). */
    private val wholeNotes = mutableSetOf<String>()
    private val cutAccepted = mutableSetOf<String>()

    /** The reply being written. It lives as long as the app does. */
    val gen: Generation by lazy {
        Generation(
            app.scope, OllamaClient { app.config.let { c -> runWorker?.let { m -> c.copy(model = m) } ?: c } },
            onBusy = { busy -> app.busy = (app.busy + if (busy) 1 else -1).coerceAtLeast(0) },
            onUsage = { chunk -> app.recordUsage("Chat", chunk, runWorker ?: app.config.model) },
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

    /** The model the running skill asked for instead of the chat model (`worker:`), and the one that reviews its answer (`reviewer:`); null = the settings decide. Cleared by the next message. */
    private var runWorker: String? = null
    private var runReviewer: String? = null

    /** What the skill's roles did that the user should know (a model that is not installed, or cannot call tools); the Chat shows it once. */
    var roleNote by androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * Applies the roles of the skill whose entry note is [entry]: an installed model replaces the chat model for the rest of this message
     * (it must be able to call tools when tools are on), the reviewer is asked when the answer is done. A model that is not usable is
     * skipped with a note, and the settings' model is used. Nothing is changed in the settings. A null [entry] is a message without a skill
     * ([AppConfig.rolesScope] = "all"): only the role table in the settings applies.
     */
    private suspend fun applyRoles(entry: String?): String? {
        val roles = if (entry == null) SkillRoles().withDefaults(app.config) else {
            val vault = app.openVault().getOrNull() ?: return null
            runCatching { Skills.roles(vault.read(entry)).withDefaults(app.config) }.getOrNull() ?: return null
        }.takeIf { it.any } ?: return null
        val installed = runCatching { app.client.listModels() }.getOrNull() ?: return null
        val onOracul = if (app.config.oraculUrl.isBlank()) installed else runCatching { clientFor("").listModels() }.getOrDefault(emptyList())
        fun has(m: String, among: List<String> = installed) = among.any { it == m || it == "$m:latest" }
        val skill = if (entry == null) "roles" else "Skill ${Skills.nameOfEntry(entry)}"
        if (roles.worker.isNotEmpty()) when {
            !has(roles.worker) -> roleNote = "$skill asks for the worker model ${roles.worker}, which is not installed: ${app.config.model} is used."
            app.config.toolsEnabled && "tools" !in runCatching { app.client.capabilities(roles.worker) }.getOrDefault(listOf("tools")) -> roleNote = "$skill asks for ${roles.worker}, which cannot use tools: ${app.config.model} is used."
            else -> runWorker = roles.worker.takeIf { it != app.config.model }
        }
        if (roles.reviewer.isNotEmpty()) {
            if (has(roles.reviewer, onOracul)) runReviewer = roles.reviewer else roleNote = "$skill asks ${roles.reviewer} to review, which is not installed: no review."
        }
        // the planner is returned, not remembered: a plan is written once, when the run starts
        if (roles.planner.isEmpty()) return null
        if (has(roles.planner, onOracul)) return roles.planner
        roleNote = "$skill asks ${roles.planner} to plan, which is not installed: no plan."
        return null
    }

    /** The planner model writes a short plan for [question] from the skill's instructions; it is added to the chat as a block the worker then sees. A failure is only a note. */
    private suspend fun plan(planner: String, entry: String?, question: String) {
        val vault = app.openVault().getOrNull()
        if (entry != null && vault == null) return
        consulting = planner
        try {
            val skillText = if (entry == null || vault == null) "" else Skills.loadEntry(vault, entry).orEmpty()
            val text = Oracul.ask(clientFor(planner), Oracul.planRequest(question, skillText, oraculBackground())) { app.recordUsage("Planner", it, planner) }
            if (text.isEmpty()) roleNote = "$planner returned an empty plan: the worker starts without one." else add(Oracul.planMessage(planner, text))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            roleNote = "The planner $planner failed (${e.message}): the worker starts without a plan."
        } finally {
            consulting = null
        }
    }

    private fun clearRoles() { runWorker = null; runReviewer = null }

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
    class Question(val text: String, val options: List<String>, val multiple: Boolean, val fromArcana: Boolean = false) {
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

    /** Only one dialog is on screen at a time: the worker's and the Oracul's wait for each other here, in the order they ask. */
    private val approvalLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun awaitApproval(pending: Approval): ApprovalChoice = approvalLock.withLock {
        approval = pending
        try { pending.answer.await() } finally { approval = null }
    }

    /** Tools the user allowed "always" for this session. */
    private val alwaysAllowed = mutableSetOf<String>()
    private var toolbox: ToolBox? = null

    fun answer(choice: ApprovalChoice) {
        approval?.answer?.complete(choice)
    }

    /** The project folder from Settings (a missing or non-folder path counts as none). */
    fun projectFolder(config: AppConfig): java.nio.file.Path? =
        config.projectFolder.trim().takeIf { it.isNotEmpty() }?.let { AppContext.expandHome(it) }?.takeIf { java.nio.file.Files.isDirectory(it) }

    /** The tools for the current settings, or null when tools are off. */
    fun toolboxFor(config: AppConfig): ToolBox? {
        if (!config.toolsEnabled) return null
        val folders = config.toolFolders.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { AppContext.expandHome(it) }
        return ToolBox(
            app.openVault().getOrNull(), folders,
            backups = ConfigStore.defaultPath().resolveSibling("backups"),
            outputFolder = config.outputFolder,
            project = projectFolder(config),
            model = config.model,
            readOnly = config.toolMode == "plan",
            shell = config.toolShell,
            web = config.toolWeb,
            searchBackend = config.searchBackend, searchUrl = config.searchUrl, searchKey = config.searchKey,
            skillsFolder = if (config.skillsEnabled) app.skillsFolder(config) else "",
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
            if (call.name == ASK_USER || call.name == REQUEST_ACCESS || call.name == REQUEST_MODE || call.name == CHAT_STATS || call.name == TODO || box == null) {
                val message = ChatMessage.toolResult(call.name, when {
                    call.name == ASK_USER -> askUser(call, text)
                    call.name == REQUEST_ACCESS -> requestAccess(call)
                    call.name == REQUEST_MODE -> requestMode(call)
                    call.name == TODO -> Todos.describe(Todos.parse((call.arguments["items"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()))
                    call.name == CHAT_STATS -> ChatStats.describe(messages.toList(), app.config.model, effectiveContext(app.config.contextTokens, app.modelContext, app.loadedContext), app.config.estPriceIn, app.config.estPriceOut, System.currentTimeMillis() - app.sessionStart, Oracul.setupLine(app.config), app.usage.filter { it.time >= app.sessionStart }, app.usage.toList())
                    else -> "Error: tools are off. Call request_access to ask the user for them."
                })
                add(message)
                added += message
                skillRun?.let { it.observe(call.name, call.arguments.toString(), message.content); skillTick++ }
                continue
            }
            val spec = box.find(call.name)
            // the same read, search or page asked for again in this turn, with nothing changed since: answer it from what the turn already has (no run, no dialog)
            val callKey = call.name + " " + call.arguments
            val earlier = if (spec != null && repeatable(spec)) turnCalls[callKey]?.takeIf { System.currentTimeMillis() - it.first < app.config.repeatMemorySeconds * 1000L }?.second else null
            if (earlier != null) {
                val message = ChatMessage.toolResult(call.name, "You already made this exact call earlier in this turn and nothing has changed since, so the result is the same as the one above in this conversation (it began: $earlier). Use that result instead of asking again; if you need something different, change the arguments.")
                add(message)
                added += message
                skillRun?.let { it.observe(call.name, call.arguments.toString(), message.content); skillTick++ }
                continue
            }
            // a folder that is not allowed yet: ask the user, and when they agree remember it for good
            val grant = box.folderToGrant(call)
            var folderDenied = false
            if (grant != null) {
                val pending = Approval(
                    "folder", "Allow Arcana to use this folder?",
                    listOf("${grant}", "The model may read, and (with your approval each time) change, files inside it.", "You can remove it later in Settings → Tools → Allowed folders."),
                    heading = "Allow this folder?", icon = "▤",
                )
                val choice = awaitApproval(pending)
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
                    val choice = awaitApproval(pending)
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
                    val choice = awaitApproval(pending)
                    if (choice == ApprovalChoice.Deny) refusal = "The user did not allow web access. Do not ask again; tell them they can turn it on in Settings → Tools → Web pages."
                    else app.update(app.config.copy(toolWeb = true))
                }
            }
            val ask = spec != null && refusal == null && call.name !in alwaysAllowed && when (spec.risk) {
                ToolRisk.Write -> spec.alwaysAsk || app.config.toolMode != "auto" // deleting and moving ask even in Auto mode
                ToolRisk.Read -> !app.config.toolAutoRead
                ToolRisk.Shell -> !app.config.toolAutoShell && !(app.config.toolAutoRead && ShellPolicy.isSafe(command)) // never "always": each command is looked at, unless auto-run is on
                ToolRisk.Web -> !(call.name in AUTO_WEB && app.config.toolAutoSearch) // never "always": the address itself can carry data out; only a search or a page read can be let through (Settings → Tools → Search and fetch without asking); http_request always asks
            }
            var allowed = !folderDenied && refusal == null
            if (ask && allowed) {
                val shell = spec!!.risk == ToolRisk.Shell
                val pending = if (shell) Approval(call.name, "run_command", spec.preview(call.arguments), heading = "Run this command?", icon = "$", yes = "Run", always = false)
                else if (spec.risk == ToolRisk.Web) Approval(call.name, call.name, spec.preview(call.arguments), heading = webHeading(call.name), icon = "↗", yes = if (call.name == "web_search") "Search" else if (call.name == "http_request") "Send" else "Fetch", always = false)
                else Approval(
                    call.name, "${call.name}  ${spec.summary(call.arguments)}", spec.preview(call.arguments),
                    heading = spec.confirmHeading ?: "Allow this change?", icon = if (spec.alwaysAsk) "!" else "⚙", always = !spec.alwaysAsk,
                )
                val choice = awaitApproval(pending)
                if (choice == ApprovalChoice.Always) alwaysAllowed += call.name
                allowed = choice != ApprovalChoice.Deny
            }
            val startedAt = System.currentTimeMillis()
            val result = when {
                allowed -> runInterruptible(Dispatchers.IO) { box.execute(call) }
                refusal != null -> refusal
                else -> "The user denied this action. Do not try it again; ask what they want instead."
            }
            // remember a read for the rest of the turn; anything that changes or runs something makes an older read untrustworthy
            if (allowed && spec != null) {
                // a change, a command or a pause (time passes, a rate limit resets) makes older reads untrustworthy; a failed result is never remembered, so a retry really runs
                if (!repeatable(spec) || spec.name in CLEARS_MEMORY) turnCalls.clear()
                if (repeatable(spec) && !FAILURE_LOOK.containsMatchIn(result.take(300))) turnCalls[callKey] = System.currentTimeMillis() to result.take(160).replace('\n', ' ')
            }
            if (allowed && spec != null && !result.startsWith("Error:")) {
                app.session.log(SessionKind.Tool, "${call.name} ${spec.summary(call.arguments)}")
                if (spec.risk != ToolRisk.Read) app.brain.changed()
            }
            // what each tool costs: time, outcome and how much text it put into the context (shown on the Usage screen's Tools page)
            if (spec != null) app.recordToolUse(ToolUseRecord(startedAt, call.name, System.currentTimeMillis() - startedAt, if (!allowed) "denied" else if (result.startsWith("Error:")) "error" else "ok", result.length))
            val message = ChatMessage.toolResult(call.name, result)
            add(message)
            added += message
            // the skill run: a skill the model loaded itself starts it; every call and result is ticked off against the required steps
            if (call.name == "use_skill" && !result.startsWith("Error:")) {
                val skill = Skills.find(box.skills, (call.arguments["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
                val vault = app.openVault().getOrNull()
                if (skill != null && vault != null && skillRun?.name != skill.name) startSkillRun(skill.name, Skills.requirementsOf(vault, skill.path))
                if (skill != null && runWorker == null && runReviewer == null) applyRoles(skill.path) // a plan is only written at the start of a message, not here
            }
            skillRun?.let { it.observe(call.name, call.arguments.toString(), result); skillTick++ }
        }
        // the turn keeps failing: the Oracul reads the attempts and its advice joins the loop, so the next round starts from it
        stuckAdvice()?.let { add(it); added += it }
        // what you typed while it worked joins the turn now, so the next round already knows
        if (queued.isNotEmpty()) {
            val text = takeQueued()
            val message = ChatMessage.user(text)
            add(message)
            added += message
            app.session.log(SessionKind.Asked, text)
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
        val existed = java.nio.file.Files.exists(vault.memoryFile())
        app.update(config.copy(memoryOffered = true))
        if (existed) return
        val file = runCatching { vault.ensureMemory() }.getOrNull() ?: return
        app.scope.launch {
            val pending = Approval(
                "memory", "${vault.memoryName()} was created${if (file.startsWith(vault.root)) " in your vault" else " at ${file.toString().replace(System.getProperty("user.home"), "~")}"}",
                listOf(
                    "It is read in every chat and already holds rules that make the assistant use its tools.",
                    "Add who you are, where your projects are, how you like answers. /memory opens it any time.",
                ),
                heading = "Memory file created", icon = "✎", yes = "Open it to fill in", always = false,
            )
            val choice = awaitApproval(pending)
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
        JsonArray(box.definitions() + REQUEST_MODE_DEFINITION + CHAT_STATS_DEFINITION + TODO_DEFINITION + (if (app.config.toolMode == "plan") listOf(REQUEST_ACCESS_DEFINITION) else emptyList()))

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
        val choice = awaitApproval(pending)
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
        val choice = awaitApproval(pending)
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
        val choice = awaitApproval(pending)
        return choice != ApprovalChoice.Deny
    }

    /** The step limit was reached while the model still wanted tools: let the user decide whether it may go on. */
    private suspend fun stepLimit(used: Int): Int {
        when (app.config.stepLimit) {
            "stop" -> return 0
            // more steps at once, a few times; each call inside them keeps its own approval (a change, a command and a web address ask as usual)
            "auto" -> if (used < app.config.toolSteps * (1 + STEP_LIMIT_AUTO_ROUNDS)) {
                app.session.log(SessionKind.Tool, "step limit: ${app.config.toolSteps} more steps (automatic, $used used)")
                return app.config.toolSteps
            }
        }
        val pending = Approval(
            "steps", "The model has used $used steps and wants to continue.",
            listOf("Allow ${app.config.toolSteps} more steps, or stop here and let it answer with what it has.", "Change the default in Settings → Tools → Steps per question."),
            heading = "Step limit reached", icon = "⏱", yes = "${app.config.toolSteps} more steps", always = false,
        )
        val choice = awaitApproval(pending)
        return if (choice == ApprovalChoice.Deny) 0 else app.config.toolSteps
    }

    init {
        if (app.config.keepChats) store.latest()?.let { open(it) }
    }

    val title: String get() = ChatStore.titleOf(messages)

    /** The model's own task list: what its latest `todo` call said (so it survives reopening the chat). */
    val todos: List<TodoItem> get() = Todos.last(messages)

    fun add(message: ChatMessage) {
        messages += message
        persist()
    }

    /**
     * Sends [text] with the attached pictures and notes; the reply streams into [gen]. A note longer than [MAX_NOTE_CHARS] would reach the model
     * cut, so the user is asked first (send the start, send it whole, or cancel); [onDeclined] runs when they cancel, so the screen can give back what they typed.
     */
    fun send(text: String, onDeclined: () -> Unit = {}) {
        val long = longNotes()
        if (long.isEmpty()) return sendNow(text)
        app.scope.launch {
            for ((path, length) in long) {
                val q = Question(
                    "“${path.substringAfterLast('/').removeSuffix(".md")}” has ${UsageStats.compact(length.toLong())} characters, and Arcana sends at most ${UsageStats.compact(MAX_NOTE_CHARS.toLong())} of a note. What should be sent?",
                    listOf("Send the first ${UsageStats.compact(MAX_NOTE_CHARS.toLong())} (the model is told the rest is missing)", "Send the whole note (it may not fit the context window)", "Cancel: do not send"), false, fromArcana = true,
                )
                question = q
                val answer = try { q.answer.await() } finally { question = null }
                when {
                    answer == q.options[0] -> cutAccepted += path
                    answer == q.options[1] -> wholeNotes += path
                    else -> { onDeclined(); return@launch }
                }
            }
            sendNow(text)
        }
    }

    /** The notes attached to the message being written that are longer than [MAX_NOTE_CHARS] and have not been decided on yet in this chat: path and length. */
    private fun longNotes(): List<Pair<String, Int>> {
        val vault = app.openVault().getOrNull() ?: return emptyList()
        return attachments.peek().notes.filter { it !in wholeNotes && it !in cutAccepted && !Skills.isEntry(it) }.mapNotNull { path ->
            val length = runCatching { Vault.splitFrontmatter(vault.read(path)).second.length }.getOrDefault(0)
            if (length > MAX_NOTE_CHARS) path to length else null
        }
    }

    private fun sendNow(text: String) {
        val sent = attachments.drain()
        val body = text.ifEmpty {
            if (sent.pictures.isEmpty() && sent.files.isNotEmpty()) "Explain the attached file${if (sent.files.size == 1) "" else "s"}."
            else "Describe the attached image${if (sent.pictures.size == 1) "" else "s"}."
        }
        // an automatic opinion on the previous answer, still being written, is out of date now: the user has moved on (and it must not keep the Oracul pill lit through the new turn)
        autoConsult?.cancel()
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

    /** The model being consulted right now (a second opinion or a comparison is being written); null otherwise. */
    private var consultingNow by mutableStateOf<String?>(null)
    var consulting: String?
        get() = consultingNow
        private set(value) {
            if (value != consultingNow) consultingSince = System.currentTimeMillis()
            consultingNow = value
        }

    /** When [consulting] last changed, so the reply line can count the Oracul's own seconds. */
    var consultingSince = 0L
        private set

    /**
     * The Oracul's tools: the same toolbox as the worker's (so plan mode, the allowed folders, the shell and web switches all apply), or null when
     * tools are off, the setting says no, or the Oracul model cannot call tools (then it only talks).
     */
    private suspend fun oraculToolbox(model: String): ToolBox? {
        if (!app.config.toolsEnabled || !app.config.oraculTools) return null
        if ("tools" !in runCatching { clientFor(model).capabilities(model) }.getOrDefault(emptyList())) return null
        return toolboxFor(app.config)
    }

    /**
     * The Oracul works with its tools: asks [model] with [request], runs the tool calls it makes and asks again, up to the step limit, then
     * returns its final text and the tools it used. Reads follow *Read without asking*; **everything else always asks the user**, in Auto mode
     * too, in a dialog marked as the Oracul's. It has no `ask_user`, no mode or access requests: it cannot ask for more power.
     */
    private suspend fun oraculAgent(model: String, box: ToolBox, request: List<ChatMessage>): Pair<String, List<String>> {
        val client = clientFor(model)
        val definitions = box.definitions()
        val limit = app.config.toolSteps.coerceIn(1, 10)
        val used = ArrayList<String>()
        var messages = request
        for (step in 0..limit) {
            val text = StringBuilder()
            val calls = ArrayList<ToolCall>()
            // the last round offers no tools, so it has to answer with what it has
            client.chat(messages, false, if (step < limit) definitions else null).collect { chunk ->
                text.append(chunk.content)
                calls += chunk.toolCalls
                if (chunk.done && (chunk.promptTokens > 0 || chunk.completionTokens > 0)) app.recordUsage("Oracul", chunk, model)
            }
            if (calls.isEmpty()) return text.toString().trim() to used
            messages = messages + ChatMessage.assistantCalls(text.toString().trim(), calls)
            for (call in calls) {
                used += call.name
                messages = messages + ChatMessage.toolResult(call.name, oraculCall(model, box, call))
            }
        }
        return "" to used
    }

    private fun webHeading(tool: String) = when (tool) { "web_search" -> "Search the web?"; "http_request" -> "Send this HTTP request?"; else -> "Fetch this web page?" }

    /** One tool call of the Oracul: asked about first unless it only reads, then run on the IO thread; recorded like the worker's calls. */
    private suspend fun oraculCall(model: String, box: ToolBox, call: ToolCall): String {
        val spec = box.find(call.name) ?: return "Error: there is no tool called ${call.name}. Available: ${box.specs.joinToString { it.name }}."
        val ask = if (spec.risk == ToolRisk.Read) !app.config.toolAutoRead else true // changes, commands and web: always, whatever the mode
        var allowed = true
        if (ask) {
            val heading = when (spec.risk) {
                ToolRisk.Shell -> "Run this command?"
                ToolRisk.Web -> webHeading(call.name)
                else -> spec.confirmHeading ?: if (spec.risk == ToolRisk.Read) "Allow this read?" else "Allow this change?"
            }
            val pending = Approval(call.name, "$model (Oracul)  ${call.name}  ${spec.summary(call.arguments)}", spec.preview(call.arguments), heading = "${Oracul.GLYPH} Oracul · $heading", icon = Oracul.GLYPH, always = false)
            allowed = awaitApproval(pending) != ApprovalChoice.Deny
        }
        val startedAt = System.currentTimeMillis()
        val result = if (allowed) runInterruptible(Dispatchers.IO) { box.execute(call) } else "The user denied this action. Do not try it again; say what you would have done instead."
        app.recordToolUse(ToolUseRecord(startedAt, call.name, System.currentTimeMillis() - startedAt, if (!allowed) "denied" else if (result.startsWith("Error:")) "error" else "ok", result.length))
        if (allowed && !result.startsWith("Error:")) {
            app.session.log(SessionKind.Tool, "Oracul: ${call.name} ${spec.summary(call.arguments)}")
            if (spec.risk != ToolRisk.Read) app.brain.changed()
        }
        return result
    }

    /** How many times the Oracul has already stepped in during this turn because the worker kept failing (reset by the next message). */
    private var stuckAdvised = 0

    /** The reads, searches and page fetches of the current turn, with the start of what each returned: an identical repeat is not run (or asked about) again. Cleared with every new question and by anything that changes or runs something. */
    private val turnCalls = HashMap<String, Pair<Long, String>>()

    /** What may be answered from [turnCalls]: tools that only look (and the web reads, which would ask the user again for the same address). */
    private fun repeatable(spec: com.arcana.brain.core.ToolSpec) = spec.name !in NEVER_REMEMBERED && (spec.risk == ToolRisk.Read || spec.name == "fetch_url" || spec.name == "web_search")

    /**
     * The worker is in its tool loop and keeps failing (every [AppConfig.stuckCalls] failed calls of this turn): the Oracul reads the attempts so far and
     * says what is going wrong and what to do differently. Returns the advice as a review block for the loop to carry on from, or null when it is not
     * due, the Oracul is off or busy, or it could not answer (a failure here must never break the worker's turn). Same settings as the other automatic
     * second opinions (`oraculAuto` not `off`), same tools and the same asking.
     */
    private suspend fun stuckAdvice(): ChatMessage? {
        val config = app.config
        val model = Oracul.model(config)
        if (model.isEmpty() || config.oraculAuto == "off" || consulting != null) return null
        val level = Oracul.stuckLevel(Oracul.turnFacts(messages.toList(), 0), config)
        if (level <= stuckAdvised) return null
        stuckAdvised = level
        consulting = model
        try {
            val turn = Oracul.lastTurn(messages.toList())
            val question = turn.firstOrNull()?.content.orEmpty()
            val box = oraculToolbox(model)
            val (text, used) = if (box != null) oraculAgent(model, box, Oracul.stuckRequest(question, turn, withTools = true, background = oraculBackground()))
            else Oracul.ask(clientFor(model), Oracul.stuckRequest(question, turn, background = oraculBackground())) { app.recordUsage("Oracul", it, model) } to emptyList()
            return if (text.isEmpty()) null else Oracul.stuckMessage(model, text + Oracul.toolsLine(used))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            consulting = null
        }
    }

    /** A client for [model] with the current settings, so another model can be asked without touching the main one. */
    private fun clientFor(model: String) = OllamaClient { Oracul.serverConfig(app.config, model) }

    /**
     * A second opinion on the last answer from the Oracul model: it reads the question and the answer (and may check with the worker's tools, see [oraculAgent]) and says what is wrong or
     * missing. The result is added to the chat as a block of its own; returns a line for the user. Throws with a message when it cannot.
     */
    suspend fun secondOpinion(reviewer: String? = null): String {
        val model = (reviewer ?: Oracul.model(app.config)).ifEmpty { error("Choose an Oracul model first: Settings → Oracul → Model.") }
        val (question, answer) = Oracul.lastExchange(messages.toList()) ?: error("There is no answer to review yet.")
        check(consulting == null) { "The Oracul is already working." }
        consulting = model
        try {
            val facts = Oracul.turnFacts(messages.toList(), skillRun?.skippedCount ?: 0)
            val box = oraculToolbox(model)
            val used: List<String>
            val text = if (box != null) {
                oraculAgent(model, box, Oracul.reviewRequest(question, answer, facts.toolNames, withTools = true, background = oraculBackground())).also { used = it.second }.first
            } else {
                used = emptyList()
                Oracul.ask(clientFor(model), Oracul.reviewRequest(question, answer, facts.toolNames, background = oraculBackground())) { app.recordUsage("Oracul", it, model) }
            }
            require(text.isNotEmpty()) { "$model returned an empty opinion; nothing was added." }
            add(Oracul.opinionMessage(model, text + Oracul.toolsLine(used)))
            return "Second opinion from $model added."
        } finally {
            consulting = null
        }
    }

    /**
     * The Oracul model (and the extra models of Settings → Oracul → *More Oracul models*) answer the same question as the worker did, and the
     * judge model compares all the answers and says which is best (with a best final answer). One block is added to the chat with every answer
     * and the verdict. An extra model that cannot answer is left out with a line saying so; the Oracul model itself must answer.
     */
    suspend fun compare(only: String? = null): String {
        val models = (only?.let { listOf(it) } ?: Oracul.models(app.config)).ifEmpty { error("Choose an Oracul model first: Settings → Oracul → Model.") }
        val judge = Oracul.judge(app.config)
        val (question, answer) = Oracul.lastExchange(messages.toList()) ?: error("There is no answer to compare yet.")
        check(consulting == null) { "The Oracul is already working." }
        try {
            val answers = ArrayList<Pair<String, String>>()
            val skipped = ArrayList<String>()
            for ((i, model) in models.withIndex()) {
                consulting = model
                try {
                    val box = oraculToolbox(model)
                    val usedTools: List<String>
                    val other = if (box != null) {
                        oraculAgent(model, box, Oracul.answerRequest(question, withTools = true, background = oraculBackground())).also { usedTools = it.second }.first
                    } else {
                        usedTools = emptyList()
                        Oracul.ask(clientFor(model), Oracul.answerRequest(question, background = oraculBackground())) { app.recordUsage("Oracul", it, model) }
                    }
                    require(other.isNotEmpty()) { "$model returned an empty answer." }
                    answers += model to other + Oracul.toolsLine(usedTools)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (i == 0) throw IllegalStateException("${e.message} Nothing was added.")
                    skipped += "$model (${e.message})"
                }
            }
            consulting = judge
            val verdict = Oracul.ask(clientFor(judge), Oracul.judgeRequest(question, app.config.model, answer, answers, oraculBackground())) { app.recordUsage("Judge", it, judge) }
            require(verdict.isNotEmpty()) { "$judge returned an empty verdict; nothing was added." }
            add(Oracul.comparisonMessage(app.config.model, answers, judge, answer, verdict + if (skipped.isEmpty()) "" else "\n\n_Left out: ${skipped.joinToString("; ")}_"))
            return "Comparison added: ${answers.joinToString(", ") { it.first }} against ${app.config.model}, judged by $judge." + if (skipped.isEmpty()) "" else " Left out: ${skipped.joinToString { it.substringBefore(" (") }}."
        } finally {
            consulting = null
        }
    }

    /**
     * Escalation: the worker failed this turn (an error, an empty reply), so the Oracul model continues it from the same history, with its own model
     * and the worker's tools (every change still asks, see [oraculAgent]). Its answer is added as an ordinary reply headed with the model's name.
     * Returns a line for the user; throws with a message when it cannot.
     */
    suspend fun takeOver(): String {
        val model = Oracul.model(app.config).ifEmpty { error("Choose an Oracul model first: Settings → Oracul → Model.") }
        check(consulting == null) { "The Oracul is already working." }
        check(messages.lastOrNull()?.role == "user" || messages.lastOrNull()?.role == "tool") { "There is no unanswered message to take over." }
        consulting = model
        try {
            val config = app.config.copy(model = model)
            val vault = app.openVault().getOrNull()
            val history = Compaction.forModel(messages.toList())
            val box = oraculToolbox(model)
            val request = Prompts.chatMessages(config, history, null, noteTexts(vault, Prompts.window(config.chatHistory, history)), tools = box != null, toolsNote = box?.describe().orEmpty(), memory = if (config.memoryEnabled) vault?.memory().orEmpty() else "")
            val (text, used) = if (box != null) oraculAgent(model, box, request) else Oracul.ask(clientFor(model), request) { app.recordUsage("Oracul", it, model) } to emptyList()
            require(text.isNotEmpty()) { "$model returned an empty answer; nothing was added." }
            add(ChatMessage.assistant(Oracul.takeoverHeading(model) + text + Oracul.toolsLine(used)))
            return "$model took over and answered."
        } finally {
            consulting = null
        }
    }

    /** Applies the latest second opinion: the worker is asked to revise its last answer with it (the opinion is already in the history). */
    fun revise(extra: String = "") {
        check(Oracul.hasOpenOpinion(messages.toList())) { "There is no second opinion to apply: ask for one with /oracul first." }
        send(Oracul.REVISE_PROMPT + if (extra.isBlank()) "" else "\nThe user adds: ${extra.trim()}")
    }

    /** After a finished reply: ask for a second opinion by itself when the settings say a turn like this deserves one. A failure is ignored. */
    private fun maybeConsult() {
        val config = app.config
        // a skill that names a reviewer asks for the review itself, whatever the automatic setting says
        runReviewer?.let { reviewer -> if (consulting == null) autoConsult = app.scope.launch { runCatching { secondOpinion(reviewer) } }; return }
        // the Oracul already stepped in during this turn because the worker kept failing: one consultation per turn is enough
        if (stuckAdvised > 0) return
        if (Oracul.model(config).isEmpty() || config.oraculAuto == "off" || consulting != null) return
        if (!Oracul.due(config, Oracul.turnFacts(messages.toList(), skillRun?.skippedCount ?: 0))) return
        // opt-in (Settings → Oracul → Judge automatically): a full comparison with a verdict instead of one opinion
        autoConsult = if (config.oraculJudgeAuto) app.scope.launch { runCatching { compare() } } else app.scope.launch { runCatching { secondOpinion() } }
    }

    /** The recent conversation the Oracul, the judge and the planner are shown (Settings → Oracul → Chat context). */
    private fun oraculBackground() = Oracul.background(messages.toList(), app.config.oraculContextMessages, app.config.oraculContextChars)

    /** The automatic opinion or comparison that runs after a reply; a new message of the user cancels it. */
    private var autoConsult: kotlinx.coroutines.Job? = null

    /** An Oracul run the user started (`/oracul`, `/compare`, `/takeover`); set by the screen that launches it. */
    var manualConsult: kotlinx.coroutines.Job? = null

    /** Esc: stops whatever the Oracul or the judge is doing outside the worker's turn. True when something was running. */
    fun cancelOracul(): Boolean {
        if (consulting == null) return false
        autoConsult?.cancel()
        manualConsult?.cancel()
        return true
    }

    /** True while a summary is being written (compacting runs apart from replies). */
    var compacting by mutableStateOf(false)
        private set

    /**
     * Compacts the chat: the model summarizes everything before the user's last [Compaction.KEEP_USER_TURNS] turns, and the summary is
     * inserted at that point. Nothing is removed from the chat; the model just stops being sent what is above the summary.
     * Returns what to tell the user; throws with a message when it cannot be done (too short, empty summary, no model).
     */
    suspend fun compact(): String {
        check(!compacting) { "Already compacting." }
        val all = messages.toList()
        val cut = Compaction.cutPoint(all) ?: error("The chat is too short to compact: your last ${Compaction.KEEP_USER_TURNS} messages stay as they are, and there must be a few messages before them.")
        val part = Compaction.toSummarize(all, cut)
        compacting = true
        try {
            val text = StringBuilder()
            app.client.chat(listOf(ChatMessage.system(Compaction.INSTRUCTIONS), ChatMessage.user(Compaction.transcript(part)))).collect { chunk ->
                text.append(chunk.content)
                if (chunk.done && (chunk.promptTokens > 0 || chunk.completionTokens > 0)) app.recordUsage("Compact", chunk)
            }
            val summary = text.toString().trim()
            require(summary.isNotEmpty()) { "The model returned an empty summary; nothing was changed." }
            // the chat may have grown while the model wrote (the cut is counted from the start, so it is still the right place)
            val at = cut.coerceAtMost(messages.size)
            messages.add(at, Compaction.summaryMessage(summary))
            if (savedMessages >= at) savedMessages++
            persist()
            return "Compacted ${part.size} messages into a summary of about ${UsageStats.compact(((summary.length + 3) / 4).toLong())} tokens. The model now sees the summary and your last ${Compaction.KEEP_USER_TURNS} messages; everything stays visible here."
        } finally {
            compacting = false
        }
    }

    /** True when [all] fills the context window past the auto-compact threshold and has enough older messages to summarize. */
    private fun autoCompactDue(all: List<ChatMessage>): Boolean {
        val percent = app.config.autoCompactPercent
        if (percent <= 0 || compacting || Compaction.cutPoint(all) == null) return false
        val usage = contextUsage(all, effectiveContext(app.config.contextTokens, app.modelContext, app.loadedContext)) ?: return false
        return usage.first * 100 >= percent
    }

    private fun ask(body: String) {
        val history = messages.toList()
        val modelCanUseTools = app.tools != false
        val box = if (!modelCanUseTools) null else toolboxFor(app.config)
        toolbox = box
        // a new message of the user ends any earlier run (the gate must not drag an unrelated question back into an unfinished skill)...
        if (skillRun != null) { skillRun = null; skillTick++ }
        clearRoles()
        stuckAdvised = 0
        turnCalls.clear()
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
            // a skill that names its models: the chip's roles apply from the first round
            val chipEntry = history.lastOrNull { it.role == "user" }?.noteNames?.firstOrNull { Skills.isEntry(it) }
            if (chipEntry != null) applyRoles(chipEntry)?.let { plan(it, chipEntry, body) }
            // every message, not only skills: the role table runs as a pipeline (a short message is not planned)
            else if (app.config.rolesScope == "all") applyRoles(null)?.takeIf { body.length >= PLAN_MIN_CHARS }?.let { plan(it, null, body) }
            // a nearly full window: summarize the older part first, so this question is not sent with a chat that no longer fits
            if (autoCompactDue(messages.toList())) runCatching { compact() }
            val history = Compaction.forModel(messages.toList())
            val config = app.config
            val vault = app.openVault().getOrNull()
            val hits = if (config.chatUseVault && vault != null) vault.search(body, config.contextNotes, config.contextCharsPerNote) else null
            pendingSources = hits?.map { it.note.path }.orEmpty()
            pendingCut = hits?.filter { it.missing > 0 }?.map { it.note.path }.orEmpty()
            Prompts.chatMessages(config, history, hits, noteTexts(vault, Prompts.window(config.chatHistory, history)), tools = box != null, toolsNote = box?.describe().orEmpty(), canRequestTools = canRequest, memory = if (config.memoryEnabled) vault?.memory().orEmpty() else "")
        }
    }

    /** The text of every note attached to [window], read from the vault now (so a note that changed is seen as it is). */
    private fun noteTexts(vault: Vault?, window: List<ChatMessage>): Map<String, String> {
        if (vault == null) return emptyMap()
        return window.flatMap { it.noteNames }.distinct().mapNotNull { path ->
            // a skill's entry note goes in as the skill loader gives it (folder, file list, instructions); any other note as its text
            Skills.loadEntry(vault, path)?.let { path to it }
                ?: runCatching { path to Vault.splitFrontmatter(vault.read(path)).second.let { if (path in wholeNotes) it else Prompts.cutNote(it, MAX_NOTE_CHARS) } }.getOrNull()
        }.toMap()
    }

    private fun finishReply(text: String, stats: ReplyStats?) {
        add(ChatMessage.assistant(text, stats, pendingSources, gen.takeThinking(), pendingCut))
        maybeConsult()
        // the model is loaded now: ask Ollama which window it really got
        app.scope.launch { app.refreshLoadedContext() }
        pendingSources = emptyList()
        pendingCut = emptyList()
        gen.consume()
        // typed while this reply was written and no tool round took it: it is the next question, sent after this one is out of the way
        if (queued.isNotEmpty()) {
            val next = queued.removeAt(0)
            app.scope.launch { yield(); sendNow(next) }
        }
    }

    /** Starts an empty conversation. The old one stays saved and can be opened again from the history. */
    fun startNew() {
        skillRun = null
        clearRoles()
        toolsDeclined = false
        messages.clear(); wholeNotes.clear(); cutAccepted.clear()
        queued.clear()
        input.clear()
        attachments.clear()
        savedMessages = 0
        id = newId()
    }

    fun open(chat: StoredChat) {
        clearRoles()
        skillRun = null // a run belongs to the conversation it started in (see docs/skills.md)
        skillTick++
        messages.clear(); wholeNotes.clear(); cutAccepted.clear()
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

        /** With roles for every message, a message shorter than this ("thanks", "yes") is answered without a plan. */
        const val PLAN_MIN_CHARS = 60

        /** Tools whose answer changes with time, or that are meant to be repeated: never answered from memory. */
        val NEVER_REMEMBERED = setOf("wait", "current_time", "elapsed_time")

        /** After these the rest of the turn starts with an empty memory. */
        val CLEARS_MEMORY = setOf("wait")

        /** A result that looks like a failure (including a web service's own refusal) is not remembered. */
        val FAILURE_LOOK = Regex("""(?i)^error:|\bHTTP (4|5)\d\d\b|rate.?limit|too many requests|timed out|temporarily unavailable|try again""")

        /** The web tools that *Search and fetch without asking* lets through; `http_request` is not one of them (it can send a body and headers). */
        val AUTO_WEB = setOf("web_search", "fetch_url")
    }
}
