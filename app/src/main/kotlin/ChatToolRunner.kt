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

// Running the tools the model asks for: the chat tools, the turn's call cache and loop guard, folders, shell and web gates, approvals, and the questions to the user. Split out of ChatSession.kt (proposal 0007, phase 7): extension functions over the session's state.

/** Remembers a tool call for the line under the answer: which tool, which search method it really used, and which note it read. */
internal fun ChatSession.noteToolUse(call: ToolCall, result: String) {
    val failed = result.startsWith("Error:")
    val method = when (call.name) {
        "search_vault" -> Regex("by (keywords \\+ meaning|keywords|meaning)").find(result)?.groupValues?.get(1)
        "semantic_search" -> "meaning"
        "search_notes" -> "keywords"
        else -> null
    }
    turnTools += call.name + (if (failed) " ✕" else method?.let { " ($it)" }.orEmpty())
    if (call.name == "read_note" && !failed) {
        val path = Regex("^\\(found at ([^)]+)\\)").find(result)?.groupValues?.get(1) ?: (call.arguments["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        path?.trim()?.takeIf { it.isNotEmpty() }?.let { turnReads += it }
    }
}

/** The question text, whatever the model called it, or what it wrote next to the call; empty when it gave none. */
internal fun ChatSession.questionText(call: ToolCall, beside: String): String {
    val args = call.arguments
    val named = listOf("question", "prompt", "text", "message", "title", "query", "ask").firstNotNullOfOrNull { key ->
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
    return named ?: beside.trim().lines().lastOrNull { it.isNotBlank() }?.trim()?.take(300).orEmpty()
}

internal suspend fun ChatSession.askUser(call: ToolCall, beside: String): String {
    val args = call.arguments
    val text = questionText(call, beside).ifEmpty { return "Error: ask_user needs the question itself. Call it again with question set to the text you want to ask, and options (2 to 5 short answers)." }
    val options = (args["options"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::cleanOption)?.takeIf { s -> s.isNotEmpty() } }.orEmpty().take(9)
    val asksMultiple = (args["multiple"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    // a question that says "choose one" (or multiple=true) but came without options would show only a text field: a small model forgets the options, so it is sent back once
    if (options.size < 2 && (asksMultiple || ChoiceWords.containsMatchIn(text)) && bouncedQuestions.add(text)) {
        return "Error: this question asks the user to choose, but options is missing. Call ask_user again with the same question and options: 2 to 5 short answers (an array of strings)" +
            (if (asksMultiple) " and multiple=true." else ".")
    }
    val multiple = asksMultiple && options.size > 1
    val q = Question(text, options, multiple)
    question = q
    val answer = try { q.answer.await() } finally { question = null }
    return when {
        answer == null -> "The user skipped the question. Carry on with your best judgement, or ask again in plain words."
        options.isNotEmpty() && (answer in options || multiple) -> "The user chose: $answer"
        else -> "The user answered: $answer"
    }
}

/** Runs the tools the model asked for (asking the user first when needed) and returns the messages to continue with. */
internal suspend fun ChatSession.runTools(text: String, calls: List<ToolCall>, stats: ReplyStats?): List<ChatMessage> {
    val added = ArrayList<ChatMessage>()
    val request = ChatMessage.assistantCalls(text, calls, stats, gen.takeThinking())
    add(request)
    added += request
    var loopStep = LoopGuard.Step.None
    for (call in calls) {
        // the first semantic_search without an embedding model: set it up now (the user is asked once), then the call goes on with a toolbox that has the index
        if ((call.name == "semantic_search" || (call.name == "search_vault" && (call.arguments["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content.equals("meaning", ignoreCase = true))) && toolbox != null && !semantic.semanticOn()) {
            val problem = semantic.ensureSemanticModel()
            if (problem != null) {
                val message = ChatMessage.toolResult(call.name, "Error: $problem")
                add(message)
                added += message
                continue
            }
            toolbox = toolboxFor(app.config)
        }
        // read again for every call: an earlier call of this round may have switched the mode, which rebuilds the toolbox
        val box = toolbox
        // a tool the chat answers itself (ChatTools); with tools off nothing else can run. turn_on_tools needs a toolbox to turn things on in
        val handler = chatToolHandlers[call.name]?.takeIf { !(call.name == com.arcana.brain.core.TURN_ON_TOOLS && box == null) }
        if (handler != null || box == null) {
            val message = ChatMessage.toolResult(call.name, handler?.invoke(call, text) ?: "Error: tools are off. Call request_access to ask the user for them.")
            add(message)
            added += message
            skillRun?.let { it.observe(call.name, call.arguments.toString(), message.content); skillTick++ }
            continue
        }
        val spec = box.find(call.name)
        // the same read, search or page asked for again in this turn, with nothing changed since: answer it from what the turn already has (no run, no dialog)
        val callKey = call.name + " " + call.arguments
        if (spec != null && spec.name !in NEVER_REMEMBERED) loopCalls.note(callKey).let { if (it > loopStep) loopStep = it }
        val earlier = if (spec != null && repeatable(spec)) turnCalls[callKey]?.takeIf { System.currentTimeMillis() - it.first < app.config.repeatMemorySeconds * 1000L }?.second else null
        if (earlier != null) {
            val message = ChatMessage.toolResult(call.name, "You already made this exact call earlier in this turn and nothing has changed since, so the result is the same as the one above in this conversation (it began: $earlier). Use that result instead of asking again; if you need something different, change the arguments.")
            add(message)
            added += message
            // answered from the earlier call, nothing ran: that call already counted for the skill's steps
            skillRun?.let { it.observe(call.name, call.arguments.toString(), message.content, ran = false); skillTick++ }
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
        // a web page or search result is in this turn: it may try to steer the model, so changes, commands and new addresses ask even in Auto mode (Settings → Tools → Ask after web)
        val tainted = app.config.askAfterWeb && webInTurn && spec != null && spec.risk != ToolRisk.Read &&
            (spec.risk != ToolRisk.Web || (call.name != "web_search" && WebTaint.newPlace(address, webHosts)))
        val ask = spec != null && refusal == null && ApprovalPolicy.mustAsk(spec, Actor.Worker, ApprovalSettings.of(app.config), allowedAlways = call.name in alwaysAllowed, tainted = tainted, command = command)
        var allowed = !folderDenied && refusal == null
        if (ask && allowed) {
            val shell = spec!!.risk == ToolRisk.Shell
            // why it asks although Auto mode or an earlier "always" would let it run
            val preview = spec.preview(call.arguments) + if (tainted) listOf(WebTaint.NOTE) else emptyList()
            val pending = if (shell) Approval(call.name, "run_command", preview, heading = ApprovalPolicy.heading(spec), icon = "$", yes = "Run", always = false)
            else if (spec.risk == ToolRisk.Web) Approval(call.name, call.name, preview, heading = ApprovalPolicy.heading(spec), icon = "↗", yes = if (call.name == "web_search") "Search" else if (call.name == "http_request") "Send" else "Fetch", always = false)
            else Approval(
                call.name, "${call.name}  ${spec.summary(call.arguments)}", preview,
                heading = ApprovalPolicy.heading(spec), icon = if (spec.alwaysAsk || tainted) "!" else "⚙", always = ApprovalPolicy.offersAlways(spec, tainted),
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
            if (!repeatable(spec) || spec.name in CLEARS_MEMORY) {
                turnCalls.clear()
                // a change or a command that worked makes an older call new again; one that failed changed nothing, so asking it again is still a repeat
                if (!Oracul.isFailure(ChatMessage.toolResult(call.name, result))) loopCalls.forget()
            }
            if (repeatable(spec) && !FAILURE_LOOK.containsMatchIn(result.take(300))) turnCalls[callKey] = System.currentTimeMillis() to result.take(160).replace('\n', ' ')
        }
        if (allowed && spec != null && !result.startsWith("Error:")) {
            Artifacts.of(call.name, { (call.arguments[it] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }, result, System.currentTimeMillis())?.let { artifacts += it; app.recordEvent(Events.ARTIFACT, it.action) }
            if (spec.risk != ToolRisk.Read) app.brain.changed()
        }
        // what each tool costs: time, outcome and how much text it put into the context (shown on the Usage screen's Tools page)
        app.recordToolUse(ToolUseRecord(startedAt, call.name, System.currentTimeMillis() - startedAt, if (spec == null) "unknown" else if (!allowed) "denied" else if (result.startsWith("Error:")) "error" else "ok", result.length, workerModel))
        // what came from the web: marked as material, never instructions, and from now on in this turn changes and new addresses ask (see tainted above)
        val fromWeb = spec?.risk == ToolRisk.Web && allowed && !result.startsWith("Error:")
        if (fromWeb) { webInTurn = true; WebTaint.host(address)?.let { webHosts += it } }
        val message = ChatMessage.toolResult(call.name, if (fromWeb) WebTaint.wrap(if (call.name == "web_search") "web search: $address" else address, result) else result)
        add(message)
        added += message
        noteToolUse(call, result)
        if (call.name == "remember") when {
            result.startsWith("Already remembered") -> app.recordEvent(Events.MEMORY, "duplicate")
            result.startsWith("Error:") && "dead path" in result -> app.recordEvent(Events.MEMORY, "refused")
        }
        // the skill run: a skill the model loaded itself starts it; every call and result is ticked off against the required steps
        if (call.name == "use_skill" && !result.startsWith("Error:")) {
            val skill = Skills.find(box.skills, (call.arguments["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
            val vault = app.openVault().getOrNull()
            if (skill != null && vault != null && skillRun?.name != skill.name) startSkillRun(skill.name, Skills.requirementsOf(vault, skill.path))
            if (skill != null && runWorker == null && runReviewer == null) applyRoles(skill.path) // a plan is only written at the start of a message, not here
        }
        skillRun?.let { it.observe(call.name, call.arguments.toString(), result, ran = allowed && spec != null); skillTick++ }
    }
    // the same calls again and again: a warning first, then the tools are taken away so the model has to answer (out of sight, like Generation's own nudges)
    if (loopStep != LoopGuard.Step.None) {
        val warn = loopStep == LoopGuard.Step.Warn
        app.recordEvent(Events.LOOP, if (warn) "calls" else "halt")
        added += ChatMessage.user(if (warn) LoopGuard.CALLS_NUDGE else LoopGuard.HALT_NUDGE)
    }
    // the turn keeps failing: the Oracul reads the attempts and its advice joins the loop, so the next round starts from it
    stuckAdvice()?.let { add(it); added += it }
    // what you typed while it worked joins the turn now, so the next round already knows
    if (queued.isNotEmpty()) {
        val text = takeQueued()
        val message = ChatMessage.user(text)
        add(message)
        added += message
    }
    return added
}

/**
 * The first time Chat opens with a vault that has no ARCANA.md: create it (with rules for using tools) and offer to open it to fill in.
 * A note that already exists is left alone, and a file the user deletes later is not created again (it comes back with /memory).
 */
fun ChatSession.offerMemory() {
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
internal suspend fun ChatSession.requestAccess(call: ToolCall): String {
    val reason = (call.arguments["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().ifBlank { "Work with your notes and files." }
    if (!offerTools(reason)) return "The user did not allow it. Do not ask again; tell them they can turn tools on with /tools (or change the mode with /mode) and ask again."
    val box = toolbox
    gen.useTools(box?.definitions())
    return "Done: tools are now on (${app.config.toolMode} mode). Carry on with the user's request using the tools. ${box?.describe().orEmpty()}"
}

/**
 * The model asks to switch on shell commands or the web (`turn_on_tools`): the same question as when it used to call a switched-off tool. When the user agrees, the setting
 * changes and the model gets the tools at once.
 */
internal suspend fun ChatSession.turnOnTools(call: ToolCall): String {
    val what = (call.arguments["what"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().trim().lowercase()
    val reason = (call.arguments["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().trim()
    val pending = when (what) {
        "shell" -> if (app.config.toolShell) return "Shell commands are already on: call run_command." else Approval(
            "shell-on", reason.ifBlank { "The model wants to run shell commands." },
            listOf("The model wants to run shell commands (builds, tests, git…).", "You still approve every single command before it runs.", "Turn it off again in Settings → Tools → Shell commands."),
            heading = "Turn on shell commands?", icon = "$", yes = "Turn on", always = false,
        )
        "web" -> if (app.config.toolWeb) return "The web is already on: call web_search or fetch_url." else Approval(
            "web-on", reason.ifBlank { "The model wants to use the web." },
            listOf("The model wants to search the web and read web pages.", "You approve every search and address before it is sent; only public pages are reachable.", "Turn it off again in Settings → Tools → Web pages."),
            heading = "Turn on web pages?", icon = "↗", yes = "Turn on", always = false,
        )
        else -> return "Error: what must be \"shell\" or \"web\", not \"$what\"."
    }
    if (awaitApproval(pending) == ApprovalChoice.Deny) return "The user did not allow it. Do not ask again; tell them they can turn it on in Settings → Tools."
    app.update(if (what == "shell") app.config.copy(toolShell = true) else app.config.copy(toolWeb = true))
    val box = toolboxFor(app.config).also { toolbox = it }
    gen.useTools(box?.let { definitionsFor(it) })
    return if (what == "shell") "Done: shell commands are on. Call run_command now; the user approves each command." else "Done: the web is on. Call web_search or fetch_url now; the user approves each search and address."
}

/** What the model is offered when it has a toolbox: the tools, a way to ask for another mode, and (in plan mode) a way to ask to leave it. */
internal fun ChatSession.definitionsFor(box: ToolBox): JsonArray =
    JsonArray(box.definitions() + com.arcana.brain.core.ChatTools.definitions(com.arcana.brain.core.OfferState(box.switchedOff, app.config.toolMode == "plan", app.config.flowTools && app.config.oraculEnabled)))

/** The model asks to switch the tool mode: the user always decides; when they agree the mode, the toolbox and the model's tools change at once. */
internal suspend fun ChatSession.requestMode(call: ToolCall): String {
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
internal suspend fun ChatSession.offerTools(reason: String): Boolean {
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
internal suspend fun ChatSession.confirmTextCalls(calls: List<ToolCall>): Boolean {
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
internal suspend fun ChatSession.stepLimit(used: Int): Int {
    when (app.config.stepLimit) {
        "stop" -> return 0
        // more steps at once, a few times; each call inside them keeps its own approval (a change, a command and a web address ask as usual)
        "auto" -> if (used < app.config.toolSteps * (1 + STEP_LIMIT_AUTO_ROUNDS)) {
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

/** What may be answered from [turnCalls]: tools that only look (and the web reads, which would ask the user again for the same address). */
internal fun ChatSession.repeatable(spec: com.arcana.brain.core.ToolSpec) = spec.name !in NEVER_REMEMBERED && (spec.risk == ToolRisk.Read || spec.name == "fetch_url" || spec.name == "web_search")
