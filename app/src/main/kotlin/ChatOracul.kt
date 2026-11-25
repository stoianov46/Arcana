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

// The Oracul's side of a chat: second opinions, comparisons, takeovers, advice when the worker is stuck, its tools and its calls. Split out of ChatSession.kt (proposal 0007, phase 7): extension functions over the session's state.

/** True when [model] is known not to be installed on the server the Oracul's models run on; false when it is, or when the list cannot be read. */
internal suspend fun ChatSession.missingOnOracul(model: String): Boolean {
    val installed = runCatching { (if (app.config.oraculUrl.isBlank()) app.client else clientFor("")).listModels() }.getOrNull() ?: return false
    return installed.none { it == model || it == "$model:latest" }
}

/**
 * After an automatic opinion: the judge model ([AppConfig.judgeModel], when it is set and [AppConfig.oraculVerdict] is on) says whether the opinion is right
 * (`AGREE`, `PARTLY`, `DISAGREE` and why). Advisory, shown as a block under the opinion; the worker sees it if the user goes on. A failure is ignored.
 */
internal suspend fun ChatSession.verdictOnOpinion() {
    val judge = app.config.judgeModel.trim().takeIf { it.isNotEmpty() && app.config.oraculVerdict && app.config.oraculEnabled } ?: return
    val all = messages.toList()
    val opinion = all.lastOrNull { Oracul.isReview(it) } ?: return
    val (question, answer) = Oracul.lastExchange(all) ?: return
    if (missingOnOracul(judge)) return
    consulting = judge; consultingStage = "judging the opinion"
    try {
        val text = Oracul.ask(clientFor(judge), Oracul.verdictRequest(question, answer, Oracul.body(opinion), oraculBackground())) { app.recordUsage("Judge", it, judge) }
        if (text.isNotEmpty()) {
            add(Oracul.verdictMessage(judge, text))
            val said = Regex("^\\W*(AGREE|PARTLY|DISAGREE)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.lowercase() ?: "unclear"
            app.recordEvent(Events.VERDICT, said, judge)
            // the judge says the opinion is right: by the setting, a dialog with choices, a revision by itself (once per message), or nothing
            if ((said == "agree" || said == "partly") && autoRevises < 1 && Oracul.hasOpenOpinion(messages.toList())) when (app.config.verdictAction) {
                "auto" -> { autoRevises++; app.recordEvent(Events.REVISE, "auto", judge); app.scope.launch { yield(); runCatching { revise("The judge agreed with the reviewer: fix what it names.") } } }
                "ask" -> { autoRevises++; app.scope.launch { askWhatNow(judge, said) } }
            }
        }
    } finally {
        consulting = null
    }
}

/**
 * The explicit alternative to typing `/revise`: after the judge agrees with an opinion, a dialog says what was found and offers the choices. Pick one (↑↓ Enter, or its digit), or type
 * a note of your own on the last row and the chat model revises with it. Nothing is asked while a reply is being written or another dialog is open.
 */
internal suspend fun ChatSession.askWhatNow(judge: String, verdict: String) {
    if (question != null || approval != null || gen.state == GenState.Running) return
    val options = listOf(Oracul.CHOICE_REVISE, Oracul.CHOICE_LEAVE, Oracul.CHOICE_ALWAYS)
    val q = Question("The reviewer found problems with the last answer and the judge ($judge) ${if (verdict == "agree") "agrees" else "partly agrees"}. What now? You can also type a note of your own below: the model then revises with it.", options, false, fromArcana = true)
    question = q
    val answer = try { q.answer.await() } finally { question = null }
    when {
        answer == null || answer == Oracul.CHOICE_LEAVE -> Unit
        answer == Oracul.CHOICE_REVISE -> { app.recordEvent(Events.REVISE, "chosen", judge); runCatching { revise("") } }
        answer == Oracul.CHOICE_ALWAYS -> { app.update(app.config.copy(verdictAction = "auto")); app.recordEvent(Events.REVISE, "chosen", judge); runCatching { revise("") } }
        else -> { app.recordEvent(Events.REVISE, "note", judge); runCatching { revise(answer) } }
    }
}

/**
 * The Oracul's tools: the same toolbox as the worker's (so plan mode, the allowed folders, the shell and web switches all apply), or null when
 * tools are off, the setting says no, or the Oracul model cannot call tools (then it only talks).
 */
internal suspend fun ChatSession.oraculToolbox(model: String): ToolBox? {
    if (!app.config.toolsEnabled || !app.config.oraculTools) return null
    if ("tools" !in runCatching { clientFor(model).capabilities(model) }.getOrDefault(emptyList())) return null
    return toolboxFor(app.config)
}

/**
 * The Oracul works with its tools: asks [model] with [request], runs the tool calls it makes and asks again, up to the step limit, then
 * returns its final text and the tools it used. Reads follow *Read without asking*; **everything else always asks the user**, in Auto mode
 * too, in a dialog marked as the Oracul's. It has no `ask_user`, no mode or access requests: it cannot ask for more power.
 */
internal suspend fun ChatSession.oraculAgent(model: String, box: ToolBox, request: List<ChatMessage>): Pair<String, List<String>> {
    val limit = if (app.config.toolSteps <= 0) 10 else app.config.toolSteps.coerceIn(1, 10)
    val used = ArrayList<String>()
    // onBusy(false) comes before the final state is set and before onComplete hands over the reply: wait for both, in that order
    val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
    val completed = kotlinx.coroutines.CompletableDeferred<String>()
    // the same loop as the worker's (proposal 0007, phase 6): the loop guard, the retries after an empty reply or a malformed call, a tool call written as text, the
    // nudge after an announced step, and the last round without tools. It is not shown: the Oracul's block and its steps in the chat are what the reader sees
    val loop = Generation(
        app.scope, clientFor(model),
        onBusy = { busy -> if (!busy) stopped.complete(Unit) },
        onUsage = { chunk -> if (chunk.promptTokens > 0 || chunk.completionTokens > 0) app.recordUsage("Oracul", chunk, model) },
        onComplete = { text, _ -> completed.complete(text.trim()) },
        onToolRound = { text, calls, _ ->
            if (calls.isEmpty()) listOfNotNull(text.takeIf { it.isNotBlank() }?.let { ChatMessage.assistant(it) })
            else buildList {
                add(ChatMessage.assistantCalls(text, calls))
                for (call in calls) {
                    // what it did is told twice: live in the reply line while the tool runs (see oraculCall), and in the closing "Checked with" line of its block, with the arguments
                    used += call.name + (box.find(call.name)?.summary(call.arguments)?.trim()?.takeIf { it.isNotEmpty() }?.let { " (" + it.replace(Regex("\\s+"), " ").take(70) + ")" } ?: "")
                    add(ChatMessage.toolResult(call.name, oraculCall(model, box, call)))
                }
            }
        },
        textCalls = { app.config.textCalls },
        loopRepeats = { app.config.loopRepeats },
        onLoop = { app.recordEvent(Events.LOOP, "oracul $it") },
        onBadCall = { app.recordToolUse(ToolUseRecord(System.currentTimeMillis(), com.arcana.brain.core.MALFORMED, 0, "malformed", 0, model)) },
    )
    loop.start(tools = box.definitions(), maxSteps = limit) { request }
    try {
        stopped.await()
        while (loop.state == GenState.Running) kotlinx.coroutines.yield()
    } finally {
        // the Oracul was cancelled (Esc, a new message): stop its request too
        if (loop.state == GenState.Running) loop.cancel()
    }
    return when (loop.state) {
        GenState.Done -> completed.await() to used
        // as before: nothing to say is an empty answer, not an error
        GenState.Failed -> if (loop.error == "The model returned an empty response.") "" to used else throw IllegalStateException(loop.error ?: "The Oracul failed.")
        else -> throw kotlinx.coroutines.CancellationException("The Oracul was cancelled.")
    }
}

/** One tool call of the Oracul: asked about first unless it only reads, then run on the IO thread; recorded like the worker's calls. */
internal suspend fun ChatSession.oraculCall(model: String, box: ToolBox, call: ToolCall): String {
    val spec = box.find(call.name) ?: return "Error: there is no tool called ${call.name}. Available: ${box.specs.joinToString { it.name }}."
    val ask = ApprovalPolicy.mustAsk(spec, Actor.Oracul, ApprovalSettings.of(app.config)) // changes, commands and web: always, whatever the mode
    var allowed = true
    if (ask) {
        val heading = ApprovalPolicy.heading(spec)
        val pending = Approval(call.name, "$model (Oracul)  ${call.name}  ${spec.summary(call.arguments)}", spec.preview(call.arguments), heading = "${Oracul.GLYPH} Oracul · $heading", icon = Oracul.GLYPH, always = false)
        allowed = awaitApproval(pending) != ApprovalChoice.Deny
    }
    val startedAt = System.currentTimeMillis()
    // the reply line names the call while it runs: "Ψ model is using read_file …" (without this the Oracul's tools were invisible until its block was added)
    val stage = consultingStage
    if (allowed) consultingStage = "using ${call.name} ${spec.summary(call.arguments).replace(Regex("\\s+"), " ").take(60)}".trim()
    val result = if (allowed) try { runInterruptible(Dispatchers.IO) { box.execute(call) } } finally { consultingStage = stage } else "The user denied this action. Do not try it again; say what you would have done instead."
    app.recordToolUse(ToolUseRecord(startedAt, call.name, System.currentTimeMillis() - startedAt, if (!allowed) "denied" else if (result.startsWith("Error:")) "error" else "ok", result.length))
    // the reader sees every call in the chat (what it was about, how it ended); no model is sent it: see OraculSteps
    add(OraculSteps.message(model, call.name, spec.summary(call.arguments), when { !allowed -> OraculSteps.DENIED; Oracul.isFailureText(result) -> OraculSteps.ERROR; else -> OraculSteps.OK }, result))
    if (allowed && !result.startsWith("Error:")) {
        if (spec.risk != ToolRisk.Read) app.brain.changed()
    }
    return result
}

/**
 * The worker is in its tool loop and keeps failing (every [AppConfig.stuckCalls] failed calls of this turn): the Oracul reads the attempts so far and
 * says what is going wrong and what to do differently. Returns the advice as a review block for the loop to carry on from, or null when it is not
 * due, the Oracul is off or busy, or it could not answer (a failure here must never break the worker's turn). Same settings as the other automatic
 * second opinions (`oraculAuto` not `off`), same tools and the same asking.
 */
internal suspend fun ChatSession.stuckAdvice(): ChatMessage? {
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
internal fun ChatSession.clientFor(model: String) = OllamaClient { Oracul.serverConfig(app.config, model) }

/**
 * A second opinion on the last answer from the Oracul model: it reads the question and the answer (and may check with the worker's tools, see [oraculAgent]) and says what is wrong or
 * missing. The result is added to the chat as a block of its own; returns a line for the user. Throws with a message when it cannot.
 */
suspend fun ChatSession.secondOpinion(reviewer: String? = null, stage: String? = null, source: String = "manual"): String {
    val model = (reviewer ?: Oracul.model(app.config)).ifEmpty { error(Oracul.missingModelMessage(app.config)) }
    val (question, answer) = Oracul.lastExchange(messages.toList()) ?: error("There is no answer to review yet.")
    check(consulting == null) { "The Oracul is already working." }
    consulting = model; consultingStage = stage
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
        app.recordEvent(Events.OPINION, source, model)
        return "Second opinion from $model added."
    } finally {
        consulting = null
    }
}

/**
 * The Oracul model (and the extra models of Settings → Helpers → *More Oracul models*) answer the same question as the worker did, and the
 * judge model compares all the answers and says which is best (with a best final answer). One block is added to the chat with every answer
 * and the verdict. An extra model that cannot answer is left out with a line saying so; the Oracul model itself must answer.
 */
suspend fun ChatSession.compare(only: String? = null): String {
    val models = (only?.let { listOf(it) } ?: Oracul.models(app.config)).ifEmpty { error(Oracul.missingModelMessage(app.config)) }
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
        // the answers go to the judge without names and in a random order, so neither the first place nor a model's name sways it; the labels get their models back for the reader
        val order = (0..answers.size).shuffled()
        val verdict = Oracul.judgeLabels(Oracul.ask(clientFor(judge), Oracul.judgeRequest(question, app.config.model, answer, answers, oraculBackground(), order)) { app.recordUsage("Judge", it, judge) }, app.config.model, answers, order)
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
suspend fun ChatSession.takeOver(): String {
    val model = Oracul.model(app.config).ifEmpty { error(Oracul.missingModelMessage(app.config)) }
    check(consulting == null) { "The Oracul is already working." }
    check(messages.lastOrNull { !it.displayOnly }?.role.let { it == "user" || it == "tool" }) { "There is no unanswered message to take over." }
    consulting = model
    try {
        val config = app.config.copy(model = model)
        val vault = app.openVault().getOrNull()
        val history = Compaction.forModel(messages.toList())
        val box = oraculToolbox(model)
        val request = Prompts.chatMessages(config, history, null, noteTexts(vault, Prompts.window(config.chatHistory, history)), tools = box != null, toolsNote = box?.describe().orEmpty(), memory = if (config.memoryEnabled) vault?.memory().orEmpty() else "", goal = goal.orEmpty())
        val (text, used) = if (box != null) oraculAgent(model, box, request) else Oracul.ask(clientFor(model), request) { app.recordUsage("Oracul", it, model) } to emptyList()
        require(text.isNotEmpty()) { "$model returned an empty answer; nothing was added." }
        add(ChatMessage.assistant(Oracul.takeoverHeading(model) + text + Oracul.toolsLine(used)).copy(model = model))
        return "$model took over and answered."
    } finally {
        consulting = null
    }
}

/** Applies the latest second opinion: the worker is asked to revise its last answer with it (the opinion is already in the history). */
fun ChatSession.revise(extra: String = "") {
    check(Oracul.hasOpenOpinion(messages.toList())) { "There is no second opinion to apply: ask for one with /oracul first." }
    send(Oracul.REVISE_PROMPT + if (extra.isBlank()) "" else "\nThe user adds: ${extra.trim()}")
}

/** After a finished reply: ask for a second opinion by itself when the settings say a turn like this deserves one. A failure is ignored. */
internal fun ChatSession.maybeConsult() {
    val config = app.config
    // the flow: the reviewer (when it has a model), then the controller, whatever the automatic setting says
    val flow = Flow.of(config)
    if (flow != null && flowControl && consulting == null) {
        autoConsult = app.scope.launch {
            runReviewer?.let { runCatching { secondOpinion(it, stage = "reviewing the answer", source = "flow") } }
            control(flow)
        }
        return
    }
    // a skill that names a reviewer asks for the review itself, whatever the automatic setting says
    runReviewer?.let { reviewer -> if (consulting == null) autoConsult = app.scope.launch { runCatching { secondOpinion(reviewer, source = "auto") } }; return }
    // the Oracul already stepped in during this turn because the worker kept failing: one consultation per turn is enough
    if (stuckAdvised > 0) return
    if (Oracul.model(config).isEmpty() || config.oraculAuto == "off" || consulting != null) return
    if (!Oracul.due(config, Oracul.turnFacts(messages.toList(), skillRun?.skippedCount ?: 0))) return
    // opt-in (Settings → Opinions → Judge automatically): a full comparison with a verdict instead of one opinion
    autoConsult = if (config.oraculJudgeAuto) app.scope.launch { runCatching { compare() } } else app.scope.launch {
        runCatching { secondOpinion(source = "auto") }.onSuccess { runCatching { verdictOnOpinion() } }
    }
}

/** The recent conversation the Oracul, the judge and the planner are shown (Settings → Helpers → Chat context). */
internal fun ChatSession.oraculBackground() = Oracul.background(messages.toList(), app.config.oraculContextMessages, app.config.oraculContextChars)

/** Esc: stops whatever the Oracul or the judge is doing outside the worker's turn. True when something was running. */
fun ChatSession.cancelOracul(): Boolean {
    if (consulting == null) return false
    autoConsult?.cancel()
    manualConsult?.cancel()
    return true
}
