package com.arcana.brain.app

import com.arcana.brain.core.effectiveContext
import com.arcana.brain.core.contextTokens
import com.arcana.brain.core.contextUsage
import com.arcana.brain.core.thinkOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.idea.ink.ui.TextFieldState
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.SystemFreeze
import com.arcana.brain.core.WebTaint
import com.arcana.brain.core.STEP_LIMIT_AUTO_ROUNDS
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.Requirement
import com.arcana.brain.core.Artifact
import com.arcana.brain.core.CheckCommand
import com.arcana.brain.core.CheckRun
import com.arcana.brain.core.Artifacts
import com.arcana.brain.core.CONSULT
import com.arcana.brain.core.CONSULT_DEFINITION
import com.arcana.brain.core.Events
import com.arcana.brain.core.LoopGuard
import com.arcana.brain.core.Flow
import com.arcana.brain.core.MAX_CONSULTS
import com.arcana.brain.core.Route
import com.arcana.brain.core.Router
import com.arcana.brain.core.OraculSteps
import com.arcana.brain.core.FolderSource
import com.arcana.brain.core.HybridSearch
import com.arcana.brain.core.LexicalIndex
import com.arcana.brain.core.RetrievalMode
import com.arcana.brain.core.PassageSource
import com.arcana.brain.core.ProjectSearch
import com.arcana.brain.core.SearchHit
import com.arcana.brain.core.SearchScope
import com.arcana.brain.core.SearchTuning
import com.arcana.brain.core.SemanticSetup
import com.arcana.brain.core.SkillRoles
import com.arcana.brain.core.StageKind
import com.arcana.brain.core.Skills
import com.arcana.brain.core.SkillRun
import com.arcana.brain.core.FileAttachment
import com.arcana.brain.core.ImageAttachment
import com.arcana.brain.core.ReportMeta
import com.arcana.brain.core.Prompts
import com.arcana.brain.core.ReplyStats
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
import com.arcana.brain.core.CHAT_STATS
import com.arcana.brain.core.CHAT_STATS_JSON
import com.arcana.brain.core.CHAT_STATS_DEFINITION
import com.arcana.brain.core.CHAT_STATS_JSON_DEFINITION
import com.arcana.brain.core.TODO
import com.arcana.brain.core.TODO_DEFINITION
import com.arcana.brain.core.TodoItem
import com.arcana.brain.core.Todos
import com.arcana.brain.core.Compaction
import com.arcana.brain.core.Oracul
import com.arcana.brain.core.Origin
import com.arcana.brain.core.Provenance
import com.arcana.brain.core.OllamaClient
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.ChatStats
import com.arcana.brain.core.ApprovalPolicy
import com.arcana.brain.core.ApprovalSettings
import com.arcana.brain.core.Actor
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
import com.arcana.brain.core.EmbeddingIndex
import com.arcana.brain.core.Embedder
import com.arcana.brain.core.SemanticSearch
import com.arcana.brain.core.ModelStatus
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
class ChatSession(internal val app: AppContext, internal val store: ChatStore) {
    val messages = mutableStateListOf<ChatMessage>()

    /** The chat's system prompt, kept the same from message to message (see [SystemFreeze]). */
    internal val systemFreeze = SystemFreeze()

    /**
     * Messages typed while the model works. A tool turn picks them up at its next round (they join it as your messages, so a correction or a
     * clarification reaches the model before it goes further); a turn without tool rounds sends them all together, as one message, when its reply is done.
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
        internal set

    /** Pictures, files and notes chosen for the next message; they go out with it and are cleared. */
    val attachments = app.attachments()

    /** How many messages were already saved to the vault as a note; anything after that is unsaved. */
    var savedMessages by mutableIntStateOf(0)

    /** Messages that exist only in the chat history, not in a vault note. */
    val unsaved: Boolean get() = messages.size > savedMessages

    internal var pendingSources: List<String> = emptyList()
    internal var pendingBy: String = ""
    internal var pendingInfo: Map<String, String> = emptyMap()

    /** What this message's answer is built from besides the notes sent with the question: the tools the model used (`search_vault (keywords + meaning)`) and the notes it opened with `read_note`. Cleared by the next message. */
    internal val turnTools = ArrayList<String>()
    internal val turnReads = LinkedHashSet<String>()
    internal var pendingCut: List<String> = emptyList()

    /** Notes the user chose to send whole, and notes they accepted cut, for this chat (the history is re-read on every turn, so the choice must stay). */
    internal val wholeNotes = mutableSetOf<String>()
    internal val cutAccepted = mutableSetOf<String>()

    /** Builds the request for the reply being written from the chat as it is now; set while a reply runs, so a compaction between tool rounds can rebuild it. */
    internal var refreshRequest: (suspend () -> List<ChatMessage>)? = null

    /** The reply being written. It lives as long as the app does. */
    val gen: Generation by lazy {
        Generation(
            app.scope, OllamaClient { app.config.let { c -> (runWorker?.let { m -> c.forModel(m) } ?: c).let { w -> thinkNow().takeIf { it.isNotEmpty() }?.let { level -> w.copy(thinking = level) } ?: w } } },
            onBusy = { busy -> app.busy = (app.busy + if (busy) 1 else -1).coerceAtLeast(0) },
            onUsage = { chunk -> app.recordUsage("Chat", chunk, runWorker ?: app.config.model) },
            onComplete = { text, stats -> finishReply(text, stats) },
            onToolRound = ::runTools,
            onStepLimit = ::stepLimit,
            textCalls = { app.config.textCalls },
            confirmTextCalls = ::confirmTextCalls,
            gate = { skillRun?.nudge() },
            loopRepeats = { app.config.loopRepeats },
            toolsHalted = { loopCalls.halted },
            onLoop = { app.recordEvent(Events.LOOP, it) },
            onBadCall = { app.recordToolUse(ToolUseRecord(System.currentTimeMillis(), com.arcana.brain.core.MALFORMED, 0, "malformed", 0, runWorker ?: app.config.model)) },
            refresh = { refreshRequest?.takeIf { autoCompactDue(messages.toList()) }?.let { rebuild -> runCatching { compact(auto = true) }.getOrNull()?.let { rebuild() } } },
        )
    }

    /**
     * The skill being run, with its required steps ticked off as the model does them (null when no skill with `requires:` is active). It is
     * started by a skill chip on a message or by the model's `use_skill` call; the Chat status bar shows its progress (see [skillTick]).
     */
    var skillRun by androidx.compose.runtime.mutableStateOf<SkillRun?>(null)
        internal set

    /**
     * The reasoning level picked for the next message only (`/effort`: a value of the model's own list, such as `xhigh`, `off` or `default`); blank = the Settings value. It
     * goes onto the message when it is sent ([ChatMessage.think]) and is cleared then.
     */
    var thinkNext by androidx.compose.runtime.mutableStateOf("")

    /** The level the message being answered was sent with (every round of its tool use too); blank = the Settings value. */
    fun thinkNow(): String = messages.lastOrNull { Oracul.isRealUser(it) }?.think.orEmpty()

    /**
     * The research backlog line this chat was started from (the Brain screen's `Enter` on a question): blank for any other chat. Every report this chat creates gets it as its `backlog`
     * property, so the line is ticked when the report is done, however the model worded the report's own `question` (it writes "the question in one sentence").
     */
    var backlogQuestion by androidx.compose.runtime.mutableStateOf("")

    /** The marker of that line (see [BacklogItem.id]), blank when it has none. */
    var backlogId by androidx.compose.runtime.mutableStateOf("")

    /** The work item this chat was started for (Brain: Enter on a question, a todo or a research line); blank for any other chat. Its runs and the notes they create are linked to the item. */
    var workItemId by androidx.compose.runtime.mutableStateOf("")

    /** Changes whenever [skillRun] makes progress, so the status bar redraws. */
    var skillTick by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    /** The model the running skill asked for instead of the chat model (`worker:`), and the one that reviews its answer (`reviewer:`); null = the settings decide. Cleared by the next message. */
    internal var runWorker: String? = null
    internal var runReviewer: String? = null

    /** True while the selected flow handles this message and ends with the controller's check; false for a short message that goes straight to the worker. */
    internal var flowControl = false

    /** About how many tokens the request being written is (characters / 4): for the waiting line, since Ollama does not say how far it has read. */
    var promptEstimate = 0
        internal set

    /** About how many tokens the system prompt and the tool definitions of the last request took (they are in every request, but in no message): for the context meter. */
    var overheadTokens by mutableStateOf(0)
        internal set

    /** What the chat model was sent with the latest request of this chat, part by part (`/context`, the context inspector); null until something is sent. */
    var lastContext by mutableStateOf<com.arcana.brain.core.ContextSnapshot?>(null)
        internal set

    /** The request [lastContext] describes, kept to tell what the next one has in common with it (what Ollama can reuse). */
    internal var lastRequest: List<ChatMessage>? = null

    /** The model that answers right now: the one a skill, the flow or the router chose, else the chat model. */
    val workerModel: String get() = runWorker ?: app.config.model

    /** What the router decided for this message ("Routed to qwen2.5vl:7b: the message has an image"); the Chat shows it once. */
    var routeNote by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The files and notes the model's tools wrote or changed in this chat, oldest first (the status bar pill and `/artifacts`); cleared by a new chat. */
    val artifacts = androidx.compose.runtime.mutableStateListOf<Artifact>()

    /** What the model in [consulting] is doing for a flow ("analysing the request", "planning", "reviewing the answer", "checking the answer"); null = just answering. */
    var consultingStage by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set

    /** How many times the controller has sent the worker back for the message being worked on; reset by a message of the user. */
    internal var flowRedos = 0

    /** How many times a verdict has sent the chat model to revise, and how many times it was sent back to its open todo steps, for the message being worked on (one each at most). */
    internal var autoRevises = 0
    internal var todoNudges = 0

    /** The `/goal` of this chat: after every finished reply a checker compares the answer with it and, while it is not met, the work goes on by itself; null = no goal. */
    var goal by androidx.compose.runtime.mutableStateOf<String?>(null)
        internal set
    internal var goalTurns = 0

    /** What the skill's roles did that the user should know (a model that is not installed, or cannot call tools); the Chat shows it once. */
    var roleNote by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** How many times the model has consulted a specialist in this message; reset by a message of the user. */
    internal var consults = 0

    /** When the current skill run started (what the report's `tokens` and `work_time` are measured from) and the reports of this run already stamped. */
    internal var skillRunStart = 0L
    internal val stampedReports = HashSet<String>()

    /**
     * Report metadata (R7, see [ReportMeta]): when the skill run has all its steps done (or given up on), the notes it created that are `type: report` get `tokens`
     * (every model, since the run started), `work_time` and `model`, written by Arcana, never composed by the model. Only missing keys are written. Best effort:
     * a failure leaves the report as it is.
     */
    /** The notes this chat created get the backlog line they answer (see [backlogQuestion]); for every finished reply, so it does not depend on the skill run ending cleanly. */
    /** The notes this chat created are linked to its work item: a report for research, an answer for a question, a draft for the rest. */
    internal fun linkWorkItem() {
        if (workItemId.isBlank()) return
        val vault = app.openVault().getOrNull() ?: return
        runCatching {
            val store = com.arcana.brain.core.WorkStore(vault)
            val service = com.arcana.brain.core.WorkService(store)
            val kind = service.item(workItemId)?.kind ?: return
            val role = when (kind) { com.arcana.brain.core.WorkKind.Research -> com.arcana.brain.core.WorkRole.Report; com.arcana.brain.core.WorkKind.Question -> com.arcana.brain.core.WorkRole.Answer; else -> com.arcana.brain.core.WorkRole.Draft }
            var linked = false
            for (a in artifacts.toList()) if (a.tool == "create_note" && a.action == "created") { service.linkNote(workItemId, a.path.substringAfterLast('/').removeSuffix(".md"), role); linked = true }
            if (linked) { com.arcana.brain.core.WorkNotes.sync(vault, store); app.brain.changed() }
        }
    }

    internal fun stampBacklog() {
        linkWorkItem()
        if (backlogQuestion.isBlank()) return
        val vault = app.openVault().getOrNull() ?: return
        for (a in artifacts.toList()) if (a.tool == "create_note" && a.action == "created") ReportMeta.stampBacklog(vault, a.path, backlogQuestion, backlogId.ifBlank { null })
    }

    internal fun stampReports() {
        val run = skillRun ?: return
        if (!run.finished || skillRunStart == 0L) return
        val vault = app.openVault().getOrNull() ?: return
        val now = System.currentTimeMillis()
        val tokens = app.usage.filter { it.time >= skillRunStart }.sumOf { it.totalTokens.toLong() }
        for (a in artifacts.toList()) {
            if (a.tool != "create_note" || a.action != "created" || a.at < skillRunStart || !stampedReports.add(a.path)) continue
            ReportMeta.stamp(vault, a.path, tokens, now - skillRunStart, app.config.model)
        }
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
        val field = com.idea.ink.ui.TextFieldState()

        /** The cursor is on the text row (an open question has only that row). */
        val typing: Boolean get() = cursor == options.size
    }

    var question by mutableStateOf<Question?>(null)
        internal set

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

    internal val bouncedQuestions = mutableSetOf<String>()
    internal val ChoiceWords = Regex("""choose (one|an? |from)|select (one|an? )|pick (one|an? )|which of (these|the following)""", RegexOption.IGNORE_CASE)

    /** The tool call waiting for an answer (shown by Chat), or null. */
    var approval by mutableStateOf<Approval?>(null)
        internal set

    /** Only one dialog is on screen at a time: the worker's and the Oracul's wait for each other here, in the order they ask. */
    internal val approvalLock = kotlinx.coroutines.sync.Mutex()

    internal suspend fun awaitApproval(pending: Approval): ApprovalChoice = approvalLock.withLock {
        approval = pending
        try { pending.answer.await() } finally { approval = null }
    }

    /** Tools the user allowed "always" for this session. */
    internal val alwaysAllowed = mutableSetOf<String>()
    internal var toolbox: ToolBox? = null

    fun answer(choice: ApprovalChoice) {
        approval?.answer?.complete(choice)
    }

    /** The project folder from Settings (a missing or non-folder path counts as none). */
    fun projectFolder(config: AppConfig): java.nio.file.Path? = com.arcana.brain.core.ToolSettings.projectFolder(config)

    /** The run now going on, or the last one: one request of the user and everything done to answer it. Every note its tools write is recorded against it. */
    var run: Origin? = null
        internal set

    internal var provenance: Pair<java.nio.file.Path, Provenance>? = null

    /** The record of runs of [vault], kept for as long as the vault is the same (its de-duplication lives in the instance). */
    internal fun provenanceFor(vault: Vault): Provenance =
        provenance?.takeIf { it.first == vault.root }?.second ?: Provenance(vault).also { provenance = vault.root to it }

    internal fun beginRun(request: String) {
        val origin = Origin.newRun(id)
        run = origin
        app.openVault().getOrNull()?.let { v ->
            provenanceFor(v).started(origin, request, app.config.model)
            // a chat started for a work item: this run is work on it (the item is in progress from now on)
            if (workItemId.isNotBlank()) {
                runCatching { val store = com.arcana.brain.core.WorkStore(v); com.arcana.brain.core.WorkService(store).linkRun(workItemId, origin.run); com.arcana.brain.core.WorkNotes.sync(v, store) }
                app.brain.changed()
            }
        }
    }

    /** The tools for the current settings, or null when tools are off. */
    fun toolboxFor(config: AppConfig): ToolBox? {
        if (!config.toolsEnabled) return null
        val vault = app.openVault().getOrNull()
        val settings = com.arcana.brain.core.ToolSettings.of(config, ConfigStore.defaultPath().resolveSibling("backups"), app.skillsFolder(config), semantic.notesSearched(config))
        return ToolBox(
            vault, settings,
            semantic = semantic.semanticIndexFor(config, vault)?.let { SemanticSearch(it) },
            projectSearch = semantic.projectRoots(config).takeIf { it.isNotEmpty() }?.let { roots ->
                ProjectSearch(roots, { source -> semantic.projectIndexFor(config, source) }, SearchTuning.of(config), config.searchMaxFileKb.coerceIn(10, 5000) * 1000L, vaultRoot = vault?.root?.toAbsolutePath()?.normalize())
            },
            origin = run, provenance = vault?.let { provenanceFor(it) },
        )
    }

    /** Semantic search: the indexes, `/reindex` and the embedding model's download (proposal 0007, phase 7). */
    val semantic = SemanticService(app, { pending -> awaitApproval(pending) }, { config -> projectFolder(config) })

    /** A web result is in the turn under way (see [WebTaint]), and the hosts it came from: a later fetch to one of them without a query is not a new place. */
    internal var webInTurn = false
    internal val webHosts = HashSet<String>()

    /**
     * How the chat answers each of [com.arcana.brain.core.ChatTools.names]: the call and the text of the reply that made it in, the result for the model.
     * Internal so a test can check that every chat tool has a handler.
     */
    internal val chatToolHandlers: Map<String, suspend (ToolCall, String) -> String> = mapOf(
        ASK_USER to { call, text -> askUser(call, text) },
        com.arcana.brain.core.TURN_ON_TOOLS to { call, _ -> turnOnTools(call) },
        REQUEST_ACCESS to { call, _ -> requestAccess(call) },
        REQUEST_MODE to { call, _ -> requestMode(call) },
        CONSULT to { call, _ -> consult(call) },
        TODO to { call, _ -> Todos.describe(Todos.parse((call.arguments["items"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())) },
        CHAT_STATS to { _, _ -> ChatStats.describe(messages.toList(), app.config.model, effectiveContext(app.config.contextTokens, app.modelContext, app.loadedContext), app.config.estPriceIn, app.config.estPriceOut, System.currentTimeMillis() - app.sessionStart, Oracul.setupLine(app.config), app.usage.filter { it.time >= app.sessionStart }, app.usage.toList(), compactionState()) },
        CHAT_STATS_JSON to { _, _ -> ChatStats.describeJson(messages.toList(), app.config.model, effectiveContext(app.config.contextTokens, app.modelContext, app.loadedContext), app.config.estPriceIn, app.config.estPriceOut, System.currentTimeMillis() - app.sessionStart, Oracul.setupLine(app.config), app.usage.filter { it.time >= app.sessionStart }, app.usage.toList(), compactionState()) },
    )

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
    internal fun longNotes(): List<Pair<String, Int>> {
        val vault = app.openVault().getOrNull() ?: return emptyList()
        return attachments.peek().notes.filter { it !in wholeNotes && it !in cutAccepted && !Skills.isEntry(it) }.mapNotNull { path ->
            val length = runCatching { Vault.splitFrontmatter(vault.read(path)).second.length }.getOrDefault(0)
            if (length > MAX_NOTE_CHARS) path to length else null
        }
    }

    internal fun sendNow(typed: String) {
        // a path to a picture in the text (pasted, or dropped into the terminal) is attached as the picture itself: a model cannot open a path
        val text = attachments.takePastedPictures(typed)
        val sent = attachments.drain()
        val body = text.ifEmpty {
            if (sent.pictures.isEmpty() && sent.files.isNotEmpty()) "Explain the attached file${if (sent.files.size == 1) "" else "s"}."
            else "Describe the attached image${if (sent.pictures.size == 1) "" else "s"}."
        }
        // an automatic opinion on the previous answer, still being written, is out of date now: the user has moved on (and it must not keep the Oracul pill lit through the new turn)
        autoConsult?.cancel()
        // a level picked with /effort counts for this message only, and only when the model lists it (the model may have been changed since)
        val level = thinkNext.takeIf { it in thinkOptions(app.thinkingValues) }.orEmpty()
        thinkNext = ""
        add(ChatMessage.user(body, sent.pictures, sent.notes, sent.files).copy(think = level))
        // a job that clearly needs file tools, with tools off: offer them now instead of letting the model say it cannot
        if (!app.config.toolsEnabled && app.tools != false && !toolsDeclined && looksLikeToolTask(body)) {
            app.scope.launch {
                if (!offerTools("It looks like this needs your notes or files.")) toolsDeclined = true
                ask(body)
            }
        } else ask(body)
    }

    internal var toolsDeclined = false

    /** The model being consulted right now (a second opinion or a comparison is being written); null otherwise. */
    internal var consultingNow by mutableStateOf<String?>(null)
    var consulting: String?
        get() = consultingNow
        internal set(value) {
            if (value != consultingNow) consultingSince = System.currentTimeMillis()
            // a helper model at work keeps the logo animated, like a reply does (the count goes up once when the first one starts and down once when none is left)
            if (consultingNow == null && value != null) app.busy++ else if (consultingNow != null && value == null) { app.busy = (app.busy - 1).coerceAtLeast(0); app.scope.launch { app.refreshLoadedContext() } }
            consultingNow = value
            if (value == null) consultingStage = null
        }

    /** When [consulting] last changed, so the reply line can count the Oracul's own seconds. */
    var consultingSince = 0L
        internal set

    /** How many times the Oracul has already stepped in during this turn because the worker kept failing (reset by the next message). */
    internal var stuckAdvised = 0

    /** The reads, searches and page fetches of the current turn, with the start of what each returned: an identical repeat is not run (or asked about) again. Cleared with every new question and by anything that changes or runs something. */
    internal val turnCalls = HashMap<String, Pair<Long, String>>()

    /** The loop guard's count of identical calls in this turn, a failed one too (see [LoopGuard.Calls]): a warning to the model, then the tools are taken away. Cleared with every new question. */
    internal val loopCalls = LoopGuard.Calls { app.config.loopCalls }

    /** The automatic opinion or comparison that runs after a reply; a new message of the user cancels it. */
    internal var autoConsult: kotlinx.coroutines.Job? = null

    /** An Oracul run the user started (`/oracul`, `/compare`, `/takeover`); set by the screen that launches it. */
    var manualConsult: kotlinx.coroutines.Job? = null

    /** True while a summary is being written (compacting runs apart from replies). */
    var compacting by mutableStateOf(false)
        internal set

    /** When the running compaction began, and whether Arcana started it (before a message) rather than `/compact`. */
    var compactingSince = 0L
        internal set
    var compactingAuto = false

    /** While a long chat is summarized in pieces: `2 of 5`; blank otherwise. */
    var compactingPart by mutableStateOf("")
        internal set

    /** The run was cut off: the message before the one being answered is not a final answer of the model (it is a tool call or result, or your own message that got no reply). */
    internal fun runInterrupted(history: List<ChatMessage>): Boolean {
        if (skillRun?.finished != false) return false
        val before = history.dropLast(1).lastOrNull() ?: return false
        return before.role != "assistant" || before.toolCalls.isNotEmpty()
    }

    internal fun ask(body: String) {
        val history = messages.toList()
        // a new request is a new run (what it writes is recorded against it: Provenance); a cut-off run that this message only continues keeps its run
        val newChip = history.lastOrNull { it.role == "user" }?.noteNames?.any { Skills.isEntry(it) } == true
        if (run == null || !(skillRun != null && runInterrupted(history) && !newChip)) beginRun(body)
        val modelCanUseTools = app.tools != false
        val box = if (!modelCanUseTools) null else toolboxFor(app.config)
        toolbox = box
        // a new message of the user ends any earlier run (the gate must not drag an unrelated question back into an unfinished skill)...
        // ...unless the run was cut off (the server did not answer, a failed reply: no final answer followed its last step) and this message has no skill chip of its own:
        // then it is the same job going on ("continue"), and its progress and its gate stay
        if (skillRun != null && !(runInterrupted(history) && !newChip)) { skillRun = null; skillTick++ }
        clearRoles()
        stuckAdvised = 0
        turnCalls.clear()
        turnTools.clear()
        turnReads.clear()
        webInTurn = false
        webHosts.clear()
        loopCalls.reset()
        // ...and a skill chip on it starts a run of that skill (its required steps are then watched)
        history.lastOrNull { it.role == "user" }?.noteNames?.firstOrNull { Skills.isEntry(it) }?.let { entry ->
            val vault = app.openVault().getOrNull()
            if (vault != null) startSkillRun(Skills.nameOfEntry(entry), Skills.requirementsOf(vault, entry))
        }
        val canRequest = modelCanUseTools && box == null
        val offered = when {
            box == null -> com.arcana.brain.core.ChatTools.withoutToolbox(canRequest)?.let { JsonArray(it) }
            else -> definitionsFor(box)
        }
        gen.start(tools = offered, maxSteps = Skills.stepsFor(history, app.config.toolStepLimit)) {
            // a skill that names its models: the chip's roles apply from the first round
            val chipEntry = history.lastOrNull { it.role == "user" }?.noteNames?.firstOrNull { Skills.isEntry(it) }
            // a selected flow, or the role table for every message, runs for plain messages too (a short message is neither analysed nor planned)
            val flow = Flow.of(app.config)
            // the controller's redo is the worker's own turn: same models, no new analysis or plan
            val redo = body.startsWith(Oracul.REDO_PROMPT) || body.startsWith(Oracul.TODO_PROMPT)
            if (!redo) flowRedos = 0
            // a message of yours (not one of Arcana's own prompts) starts the counts again
            if (!redo && !body.startsWith(Oracul.REVISE_PROMPT) && !body.startsWith(Oracul.GOAL_PROMPT)) { autoRevises = 0; todoNudges = 0 }
            if (!redo) consults = 0
            if (!redo && !body.startsWith(Oracul.GOAL_PROMPT)) goalTurns = 0
            val planned = !redo && (chipEntry != null || body.length >= PLAN_MIN_CHARS)
            val planner = when {
                chipEntry != null -> applyRoles(chipEntry)
                flow != null || app.config.rolesScope == "all" -> applyRoles(null)
                else -> null
            }
            // a short message of a flow goes straight to the worker: no review and no check either
            flowControl = flow != null && (redo || planned)
            if (flow != null && planned) app.recordEvent(Events.FLOW)
            if (flow != null && !flowControl) runReviewer = null
            route(history)
            val analysis = if (flow != null && planned && body.length >= PLAN_MIN_CHARS) analyse(flow, body) else ""
            if (planner != null && planned) plan(planner, chipEntry, body, analysis)
            // a nearly full window: summarize the older part first, so this question is not sent with a chat that no longer fits
            if (autoCompactDue(messages.toList())) runCatching { compact(auto = true) }
            // built again after a compaction between tool rounds: a long run of tools fills the window too, not only a long chat
            suspend fun compose(): List<ChatMessage> {
                val config = app.config
                val vault = app.openVault().getOrNull()
                val effortNote = if (runWorker == null) Prompts.effortNote(config.model, app.thinkingValues, app.thinkingDefault) else ""
                val vaultAnswers = config.chatUseVault && vault != null && semantic.notesSearched(config)
                val toolsNote = box?.describe().orEmpty()
                val parts = Prompts.SystemParts(Prompts.dateLine(java.time.ZonedDateTime.now()), toolsNote, if (config.memoryEnabled) vault?.memory().orEmpty() else "", goal.orEmpty())
                // the message this turn answers: frozen the first time it is sent (excerpts, attached notes, what changed), then sent the same way every round and every later turn
                val at = messages.indexOfLast { it.role == "user" && !it.displayOnly }
                val fresh = at >= 0 && messages[at].sent.isEmpty()
                val key = listOf(config.systemPrompt, config.model, box != null, canRequest, effortNote, vaultAnswers).joinToString("\u0000")
                val (system, changed) = systemFreeze.take(key, parts, commit = fresh) { p ->
                    Prompts.systemPrompt(config, box != null, p.toolsNote, canRequest, p.memory, p.goal, effortNote, p.date, vaultAnswers)
                }
                if (fresh) {
                    val last = messages[at]
                    // Arcana's own follow-ups (redo, revise, a goal or todo nudge) are not questions: the excerpts of the question they follow are still in the chat
                    val ownPrompt = Oracul.isArcanaPrompt(body)
                    val found = if (vaultAnswers && !ownPrompt) vaultHits(vault!!, body, config) else null
                    val hits = found?.hits
                    pendingBy = found?.let { it.label + if (it.leftOut > 0) " · ${it.leftOut} weak left out" else "" }.orEmpty()
                    pendingInfo = found?.info.orEmpty()
                    pendingSources = hits?.map { it.note.path }.orEmpty()
                    pendingCut = if (found?.passages == true) emptyList() else hits?.filter { it.missing > 0 }?.map { it.note.path }.orEmpty()
                    val sent = Prompts.userContent(last, hits, noteTexts(vault, listOf(last)), box != null, changed)
                    messages[at] = last.copy(sent = sent)
                }
                val history = Compaction.forModel(messages.toList())
                return Prompts.chatMessages(config, history, null, noteTexts(vault, Prompts.window(config.chatHistory, history)), tools = box != null, system = system)
                    .also { request ->
                        promptEstimate = request.sumOf { Compaction.messageTokens(it) }
                        overheadTokens = Compaction.tokens(system) + (offered?.let { Compaction.tokens(it.toString()) } ?: 0)
                        val typed = messages.getOrNull(at)?.content
                        lastContext = com.arcana.brain.core.ContextInspector.inspect(request, offered, config.model, effectiveContext(config.contextTokens, app.modelContext, app.loadedContext), parts.memory, parts.toolsNote, parts.goal, typed, lastRequest)
                        lastRequest = request
                    }
            }
            refreshRequest = ::compose
            compose()
        }
    }

    /**
     * The notes for a `/vault` answer, found by the method the setting *Search by* names (`both` by default: words and meaning fused; words only while no embedding model is set up),
     * with the name of the method that really ran, shown on the answer's Sources line, and whether the excerpts are the matching passages (true) or the start of the note (the old search, a last
     * resort when nothing is found by words): only the latter can be "only the start was read".
     */
    internal class VaultContext(val hits: List<SearchHit>, val label: String, val passages: Boolean, val info: Map<String, String>, val leftOut: Int = 0)

    internal fun vaultHits(vault: Vault, query: String, config: AppConfig): VaultContext {
        val mode = RetrievalMode.of(config.searchBy)
        val hybrid = HybridSearch(LexicalIndex.forVault(vault), semantic.semanticIndexFor(config, vault), SearchTuning.of(config))
        val (hits, r) = hybrid.hits(vault, query, config.contextNotes, config.contextCharsPerNote, mode)
        if (hits.isNotEmpty() || mode == RetrievalMode.Meaning) return VaultContext(hits, r.label, true, r.found.associate { it.path to (it.via + if (it.evidence.isEmpty()) "" else " · " + it.evidence) }, r.leftOut)
        val old = vault.search(query, config.contextNotes, config.contextCharsPerNote)
        return VaultContext(old, "keywords", false, old.associate { it.note.path to "keywords" })
    }

    /** The text of every note attached to [window], read from the vault now (so a note that changed is seen as it is). */
    internal fun noteTexts(vault: Vault?, window: List<ChatMessage>): Map<String, String> {
        if (vault == null) return emptyMap()
        return window.flatMap { it.noteNames }.distinct().mapNotNull { path ->
            // a skill's entry note goes in as the skill loader gives it (folder, file list, instructions); any other note as its text
            Skills.loadEntry(vault, path)?.let { path to it }
                ?: runCatching { path to Vault.splitFrontmatter(vault.read(path)).second.let { if (path in wholeNotes) it else Prompts.cutNote(it, MAX_NOTE_CHARS) } }.getOrNull()
        }.toMap()
    }

    internal fun finishReply(text: String, stats: ReplyStats?) {
        // what the answer is built from: the notes the model opened itself and the notes sent with the question, each with how it got there, and the tools it used
        val reads = turnReads.toList()
        val context = pendingSources
        val all = (reads + context).distinct()
        val info = all.map { p ->
            val how = if (p in reads && p in context) "read · context" else if (p in reads) "read" else "context"
            listOfNotNull(how, pendingInfo[p]?.takeIf { p in context }).joinToString(" · ")
        }
        val tools = turnTools.groupingBy { it }.eachCount().map { (name, n) -> if (n > 1) "$name ×$n" else name }
        add(ChatMessage.assistant(text, stats, all, gen.takeThinking(), pendingCut, if (context.isEmpty()) "" else pendingBy, info, tools))
        maybeConsult()
        // a goal: once the reviewers are done, a checker says whether it is met; if not, the work goes on
        if (goal != null) app.scope.launch { autoConsult?.join(); runCatching { checkGoal() } }
        // the model is loaded now: ask Ollama which window it really got
        app.scope.launch { app.refreshLoadedContext() }
        pendingSources = emptyList()
        pendingBy = ""
        pendingInfo = emptyMap()
        pendingCut = emptyList()
        gen.consume()
        stampBacklog()
        stampReports()
        // the model ends its turn with open steps on its own todo list: back once, to finish them or close the list
        val open = todos.filter { !it.done }
        if (app.config.todoGuard && open.isNotEmpty() && todoNudges < 1 && toolbox != null && skillRun == null) {
            todoNudges++
            app.recordEvent(Events.TODO_NUDGE, open.size.toString())
            app.scope.launch { yield(); send(Oracul.todoNudge(open.map { it.text })) }
        }
        // typed while this reply was written and no tool round took it: it is the next question, sent after this one is out of the way. All of it goes at once, as one message
        // (what a tool round does too): a later message is often a correction or an addition to an earlier one, and answering them one by one would answer the first without it
        if (queued.isNotEmpty()) {
            val next = takeQueued()
            app.scope.launch { yield(); sendNow(next) }
        }
    }

    /** Starts an empty conversation. The old one stays saved and can be opened again from the history. */
    /** Set by the Prompts screen: the Chat screen sends what is in the message field as soon as it is shown, then clears this. */
    var autoSend by mutableStateOf(false)

    init {
        if (app.config.keepChats) store.latest()?.let { open(it) }
    }

    internal companion object {
        const val MAX_NOTE_CHARS = 60_000

        /** With roles for every message, a message shorter than this ("thanks", "yes") is answered without a plan. */
        const val PLAN_MIN_CHARS = 60

        /** Tools whose answer changes with time, or that are meant to be repeated: never answered from memory. */
        val NEVER_REMEMBERED = setOf("wait", "current_time", "elapsed_time")

        /** After these the rest of the turn starts with an empty memory. */
        val CLEARS_MEMORY = setOf("wait")

        /** A result that looks like a failure (including a web service's own refusal) is not remembered. */
        val FAILURE_LOOK = Regex("""(?i)^error:|\bHTTP (4|5)\d\d\b|rate.?limit|too many requests|timed out|temporarily unavailable|try again""")

    }
}
