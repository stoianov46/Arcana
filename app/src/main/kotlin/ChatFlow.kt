package com.arcana.brain.app

import com.arcana.brain.app.ChatSession.Approval
import com.arcana.brain.app.ChatSession.ApprovalChoice
import com.arcana.brain.app.ChatSession.Question
import com.arcana.brain.app.ChatSession.VaultContext
import com.arcana.brain.app.ChatSession.Companion.MAX_NOTE_CHARS
import com.arcana.brain.app.ChatSession.Companion.PLAN_MIN_CHARS
import com.arcana.brain.app.ChatSession.Companion.NEVER_REMEMBERED
import com.arcana.brain.app.ChatSession.Companion.CLEARS_MEMORY
import com.arcana.brain.app.ChatSession.Companion.FAILURE_LOOK
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

// The flow and the roles around a message: analysis, planning, the controller's check, the goal, the router, consulting a specialist, skill runs. Split out of ChatSession.kt (proposal 0007, phase 7): extension functions over the session's state.

/**
 * Applies the roles of the skill whose entry note is [entry]: an installed model replaces the chat model for the rest of this message
 * (it must be able to call tools when tools are on), the reviewer is asked when the answer is done. A model that is not usable is
 * skipped with a note, and the settings' model is used. Nothing is changed in the settings. A null [entry] is a message without a skill
 * ([AppConfig.rolesScope] = "all"): only the role table in the settings applies.
 */
internal suspend fun ChatSession.applyRoles(entry: String?): String? {
    val roles = when {
        // skill roles switched off: a skill is run by the chat model alone; only a selected flow still names its models
        !app.config.skillRolesEnabled -> Flow.of(app.config)?.roles() ?: return null
        entry == null -> SkillRoles().withDefaults(app.config)
        else -> {
            val vault = app.openVault().getOrNull() ?: return null
            runCatching { Skills.roles(vault.read(entry)).withDefaults(app.config) }.getOrNull() ?: return null
        }
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
internal suspend fun ChatSession.plan(planner: String, entry: String?, question: String, analysis: String = "") {
    val vault = app.openVault().getOrNull()
    if (entry != null && vault == null) return
    consulting = planner; consultingStage = "planning"
    try {
        val skillText = if (entry == null || vault == null) "" else Skills.loadEntry(vault, entry).orEmpty()
        val text = Oracul.ask(clientFor(planner), Oracul.planRequest(question, skillText, oraculBackground(), analysis)) { app.recordUsage("Planner", it, planner) }
        if (text.isEmpty()) roleNote = "$planner returned an empty plan: the worker starts without one." else add(Oracul.planMessage(planner, text))
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        roleNote = "The planner $planner failed (${e.message}): the worker starts without a plan."
    } finally {
        consulting = null
    }
}

/**
 * The analyst stage of the selected [Flow]: its model reads [question] and writes what is really asked, what is unclear and what a good result looks like.
 * The text is added to the chat as an analysis block (the later stages and the worker see it) and returned for the planner. A model that is not installed,
 * an empty text or a failure is only a note: the flow goes on without it.
 */
internal suspend fun ChatSession.analyse(flow: Flow, question: String): String {
    val analyst = flow.model(StageKind.Analyst).ifEmpty { return "" }
    if (missingOnOracul(analyst)) { roleNote = "The analyst $analyst is not installed: the flow goes on without an analysis."; return "" }
    consulting = analyst; consultingStage = "analysing the request"
    try {
        val text = Oracul.ask(clientFor(analyst), Oracul.analysisRequest(question, oraculBackground())) { app.recordUsage("Analyst", it, analyst) }
        if (text.isEmpty()) { roleNote = "$analyst returned an empty analysis: the flow goes on without one."; return "" }
        add(Oracul.analysisMessage(analyst, text))
        return text
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        roleNote = "The analyst $analyst failed (${e.message}): the flow goes on without an analysis."
        return ""
    } finally {
        consulting = null
    }
}

/**
 * The controller stage of the selected [Flow], after the reviewer: its model reads the request, the analysis, the answer and the review and says `done` or
 * `redo`. The verdict is added to the chat as a block. On `redo` the worker is sent back with [Oracul.REDO_PROMPT] (the verdict is in its history), at most
 * [Flow.redos] times per message of the user; an answer that is still refused then stands, with a note. A failure, an empty text or an unreadable verdict never starts a loop.
 */
internal suspend fun ChatSession.control(flow: Flow) {
    val (question, answer) = Oracul.lastExchange(messages.toList()) ?: return
    val check = runCheckCommand()
    // an objective failure needs no second opinion: the output is the verdict
    if (check != null) app.recordEvent(Events.CHECK, if (check.second.passed) "pass" else "fail")
    if (check != null && !check.second.passed) {
        val run = check.second
        add(Oracul.controlMessage(check.first, Oracul.Control(false, "The check command failed (" + (run.code?.let { "exit code $it" } ?: "timed out") + "):\n" + run.output.takeLast(1_500))))
        requestRedo(flow)
        return
    }
    val controller = flow.model(StageKind.Controller).ifEmpty { return }
    if (missingOnOracul(controller)) { roleNote = "The controller $controller is not installed: the answer stands without a check."; return }
    val all = messages.toList()
    val review = all.lastOrNull { Oracul.isReview(it) }?.let { Oracul.body(it) }.orEmpty()
    val analysis = all.lastOrNull { Oracul.isAnalysis(it) }?.let { Oracul.body(it) }.orEmpty()
    consulting = controller; consultingStage = "checking the answer"
    try {
        val passed = check?.let { "`${it.first}` passed (exit code 0)." + if (it.second.output.isBlank()) "" else "\n" + it.second.output.takeLast(600) }.orEmpty()
        val text = Oracul.askVerdict(clientFor(controller), Oracul.controlRequest(question, answer, review, analysis, oraculBackground(), passed), Oracul.CONTROL_SCHEMA) { app.recordUsage("Controller", it, controller) }
        if (text.isEmpty()) { roleNote = "$controller returned an empty verdict: the answer stands."; return }
        val verdict = Oracul.parseControl(text)
        add(Oracul.controlMessage(controller, verdict))
        app.recordEvent(Events.CONTROL, if (verdict.done) "done" else "redo", controller)
        if (verdict.done) return
        requestRedo(flow)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        roleNote = "The controller $controller failed (${e.message}): the answer stands."
    } finally {
        consulting = null
    }
}

/** `/goal text`: sets the goal and starts the work toward it; the checker runs after every reply. */
fun ChatSession.startGoal(text: String) {
    goal = text.trim().ifEmpty { return }
    goalTurns = 0
    app.recordEvent(Events.GOAL, "set")
    send("Goal: ${goal}\nWork toward it until it is reached; tell me when you think it is.")
}

fun ChatSession.clearGoal() { goal = null; goalTurns = 0 }

/**
 * The `/goal` check, after a finished reply: the Oracul model (else the chat model) compares the answer with the goal. Met: the goal is cleared. Not met: the notes are added
 * and the worker is sent on with [Oracul.goalContinue], at most [AppConfig.goalTurns] times for one goal (a message of yours resets the count); then it stops with a note.
 * Nothing runs while a reply is being written, so a redo or a queued message is never talked over.
 */
internal suspend fun ChatSession.checkGoal() {
    val wanted = goal ?: return
    if (gen.state == GenState.Running || consulting != null) return
    val answer = Oracul.lastExchange(messages.toList())?.second ?: return
    val oracul = Oracul.model(app.config)
    val model = oracul.ifEmpty { app.config.model }
    if (oracul.isNotEmpty() && missingOnOracul(model)) { roleNote = "The goal checker $model is not installed: the goal is not checked."; return }
    consulting = model; consultingStage = "checking the goal"
    try {
        val client = if (oracul.isEmpty()) app.client else clientFor(model)
        val text = Oracul.askVerdict(client, Oracul.goalRequest(wanted, answer, oraculBackground()), Oracul.GOAL_SCHEMA) { app.recordUsage("Controller", it, model) }
        if (text.isEmpty()) { roleNote = "$model returned an empty goal check: the goal stays set."; return }
        val check = Oracul.parseGoal(text)
        add(Oracul.goalMessage(model, check))
        if (check.met) { clearGoal(); app.recordEvent(Events.GOAL, "met", model); return }
        if (goalTurns >= app.config.goalTurns) { clearGoal(); app.recordEvent(Events.GOAL, "stopped", model); roleNote = "The goal was not met after ${app.config.goalTurns} automatic turns: stopped. Set it again with /goal, or carry on yourself."; return }
        goalTurns++
        app.scope.launch { yield(); send(Oracul.goalContinue(wanted)) }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        roleNote = "The goal check failed (${e.message}): the goal stays set."
    } finally {
        consulting = null
    }
}

/**
 * The router: a message with an image goes to the vision model, one with code to the code model (Settings → Helpers → *Route*), unless a skill or the flow already named the
 * worker. The model must be installed, have the `vision` capability for an image and be able to use tools when tools are on; otherwise the chat model answers, with a note.
 * Only the first round of a message routes, and a redo or a goal turn looks at the message it continues.
 */
internal suspend fun ChatSession.route(history: List<ChatMessage>) {
    if (runWorker != null || !app.config.oraculEnabled) return
    val last = history.lastOrNull { it.role == "user" && !Oracul.isOpinion(it) && !Compaction.isSummary(it) && !it.content.startsWith(Oracul.REDO_PROMPT) && !it.content.startsWith(Oracul.TODO_PROMPT) && !it.content.startsWith(Oracul.GOAL_PROMPT) && !it.content.startsWith(Oracul.REVISE_PROMPT) } ?: return
    val kind = Router.kindOf(last.content, last.images.isNotEmpty()) ?: return
    val model = Router.modelFor(app.config, kind).ifEmpty { return }
    if (model == app.config.model) return
    val installed = runCatching { app.client.listModels() }.getOrNull() ?: return
    if (installed.none { it == model || it == "$model:latest" }) { roleNote = "The ${kind.label} model $model is not installed: ${app.config.model} answers."; return }
    val capabilities = runCatching { app.client.capabilities(model) }.getOrNull()
    when {
        kind == Route.Vision && capabilities != null && "vision" !in capabilities -> { roleNote = "$model cannot read images: ${app.config.model} answers."; return }
        app.config.toolsEnabled && capabilities != null && "tools" !in capabilities -> { roleNote = "$model cannot use tools: ${app.config.model} answers."; return }
    }
    runWorker = model
    app.recordEvent(Events.ROUTE, if (kind == Route.Vision) "image" else "code", model)
    routeNote = "Routed to $model: the message has ${kind.label}."
}

/** Sends the worker back (the verdict is already in the chat), unless [Flow.redos] are used up: then the last answer stands, with a note. */
internal fun ChatSession.requestRedo(flow: Flow) {
    if (flowRedos >= flow.redos) { if (flow.redos > 0) { roleNote = "The controller still wanted a redo after ${flow.redos} tries: the last answer stands."; app.recordEvent(Events.REDO_LIMIT) }; return }
    flowRedos++
    app.recordEvent(Events.REDO)
    app.scope.launch { yield(); send(Oracul.REDO_PROMPT) }
}

/**
 * The flow's objective check ([AppConfig.flowCheckCommand]): runs the command in the project folder and returns the command with what it did, or null when there is none or it
 * may not run. It runs without asking, so it needs the shell tools **and** auto-run to be on (the same trust), and a command [ShellPolicy] refuses never runs.
 */
internal suspend fun ChatSession.runCheckCommand(): Pair<String, CheckRun>? {
    val config = app.config
    val command = config.flowCheckCommand.trim().ifEmpty { return null }
    if (!config.toolShell || !config.toolAutoShell) { roleNote = "The flow's check command is not run: it needs Settings → Tools → shell commands and auto-run."; return null }
    CheckCommand.refusal(command)?.let { roleNote = "The flow's check command is not run: $it"; return null }
    val dir = projectFolder(config) ?: app.openVault().getOrNull()?.root ?: run { roleNote = "The flow's check command is not run: there is no project folder or vault to run it in."; return null }
    consulting = command; consultingStage = "running the check command"
    try {
        return command to withContext(Dispatchers.IO) { CheckCommand.run(command, dir) }
    } finally {
        consulting = null
    }
}

/**
 * The model calls a stage as a specialist (`consult`): the stage's model gets only the brief the model wrote, with the stage's own instructions, and its text is the tool's
 * answer. Nothing is added to the chat besides the tool call. Not installed, no model for the stage or too many calls are errors the model can read.
 */
internal suspend fun ChatSession.consult(call: ToolCall): String {
    fun arg(name: String) = (call.arguments[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().trim()
    if (!app.config.oraculEnabled) return "Error: the helper models are switched off; work without a specialist."
    val kind = Flow.consultKind(arg("stage")) ?: return "Error: stage must be analyst, planner or reviewer."
    val brief = arg("brief").ifEmpty { return "Error: give a brief: everything the specialist needs." }
    if (consults >= MAX_CONSULTS) return "Error: you have already consulted $MAX_CONSULTS times in this message; carry on with what you have."
    val model = Flow.consultModel(app.config, kind).ifEmpty { return "Error: no model is set for the ${kind.label.lowercase()} and no Oracul model to fall back on (Settings → Helpers → Model, or the ${Flow.consultSource(app.config, kind).takeIf { it != "the Oracul model" } ?: "stage"} setting); work without it." }
    if (consulting != null) return "Error: another model is working right now; try again in a moment."
    if (missingOnOracul(model)) return "Error: the model $model is not installed; work without the ${kind.label.lowercase()}."
    consults++
    consulting = model; consultingStage = "advising as the ${kind.label.lowercase()}"
    try {
        val feature = when (kind) { StageKind.Analyst -> "Analyst"; StageKind.Planner -> "Planner"; else -> "Oracul" }
        val text = Oracul.ask(clientFor(model), Oracul.consultRequest(kind, brief, oraculBackground())) { app.recordUsage(feature, it, model) }
        if (text.isNotEmpty()) app.recordEvent(Events.CONSULT, kind.label.lowercase(), model)
        return if (text.isEmpty()) "Error: $model returned nothing." else "${kind.label} ($model) says:\n$text"
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        return "Error: the ${kind.label.lowercase()} failed (${e.message})."
    } finally {
        consulting = null
    }
}

internal fun ChatSession.clearRoles() { runWorker = null; runReviewer = null; flowControl = false }

internal fun ChatSession.startSkillRun(name: String, requirements: List<Requirement>) {
    skillRun = if (requirements.isEmpty()) null else SkillRun(name, requirements)
    skillRunStart = System.currentTimeMillis()
    stampedReports.clear()
    skillTick++
}
