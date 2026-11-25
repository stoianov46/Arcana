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

// The Chat's status bars and the bottom bar's model facts: models, context, memory, warnings, search mode. Split out of Chat.kt (proposal 0007, phase 8).

/** `ctx ▰▰▱▱▱▱▱▱ 12% 13k/131k`: how full the model's context was after the last reply (its prompt plus its answer); empty before the first reply or when the size is unknown. */
internal fun contextMeter(messages: List<ChatMessage>, limit: Int): String {
    val (used, estimated) = contextTokens(messages) ?: return ""
    if (limit <= 0) return ""
    val share = (used * 100.0 / limit).coerceIn(0.0, 100.0)
    val filled = (share / 12.5).toInt().coerceIn(0, 8).let { if (it == 0 && used > 0) 1 else it }
    val full = com.arcana.brain.core.UsageStats::compact
    return " · ctx " + "▰".repeat(filled) + "▱".repeat(8 - filled) + " ${"%.0f".format(share)}% ${if (estimated) "~" else ""}${full(used.toLong())}/${com.arcana.brain.core.UsageStats.contextSize(limit.toLong())}" +
        if (share >= 80) " ⚠ nearly full: /new starts fresh" else ""
}

/**
 * The worker and the Oracul in one segment, so it is clear which model works now: that one is the filled pill (accent for the worker, orange for the Oracul or the judge, with `…`),
 * the other follows it quietly (the Oracul's setup while the worker works, `waiting` for the worker while the Oracul answers). The quiet part is dropped when the bar is narrow.
 */
internal fun modelsSegment(workerLabel: String, asking: String?, config: AppConfig, plain: Boolean, width: Int, oraculTooBig: Boolean = false): StatusSegment {
    val setup = Oracul.statusText(config, plain)
    if (asking != null) {
        val judging = asking == Oracul.judge(config) && asking != Oracul.model(config)
        val pill = " ${if (plain) "${if (judging) "judge" else "oracul"} $asking" else "${if (judging) "⚖" else Oracul.GLYPH} $asking…"} "
        val waiting = " $workerLabel ${if (plain) "waits" else "waiting"}"
        val parts = mutableListOf(StatusPart(pill, Palette.Ink, bold = true, background = Palette.Orange))
        parts += StatusPart(waiting, Palette.Muted)
        return StatusSegment(parts, 10)
    }
    // the model is a plain fact (bold accent text); a fill is kept for what needs attention
    val parts = mutableListOf(StatusPart(workerLabel, Palette.Accent, bold = true))
    if (setup.isNotEmpty()) {
        parts += StatusPart(" $setup", Palette.Blue)
        // the Oracul does not fit this machine's memory: it would swap to disk or run on the CPU (Settings → Helpers says what to choose instead)
        if (oraculTooBig) parts += StatusPart(if (plain) " !" else " ⚠", Palette.Orange, bold = true)
    }
    return StatusSegment(parts, 10)
}

/** The model-state indicator of the Chat status bar: the reason for a wait (orange), or `● loaded 7.7 GB 100% GPU` when the chat model is loaded, or `○ not loaded` (it loads with the next message); with more than one model in memory, `· 2 models 17.3 GB` is what they take together; null until Ollama has been asked. */
internal fun modelStateSegment(app: AppContext, plain: Boolean): StatusSegment? {
    val loaded = app.loaded
    val mine = loaded?.firstOrNull { com.arcana.brain.core.ModelStatus.sameModel(it.name, app.config.model) }
    // the memory figure lives in the ram meter; this says only where the model sits
    val detail = if (mine == null || mine.sizeBytes <= 0) "" else " ${100 - mine.cpuPercent}% GPU"
    val all = "" // what all the loaded models take together is the memory meter (see memoryMeter)
    // one indicator, one place: while a wait lasts it says what is going on (orange), after it the state of the model
    if (app.modelWhy.isNotEmpty()) {
        val why = app.modelWhy.substringBefore(";").replace("reading about ", "reading ").replace(" tokens of conversation", " tokens")
        val text = if (mine != null) "loaded$detail$all · " + why.removePrefix("loaded, ") else why // the whole sentence is in the reply line
        return statusSegment("", (if (plain) "wait: " else "◌ ") + text, Palette.Orange, 10, second = false)
    }
    if (loaded == null) return null
    if (mine == null) return statusSegment("", if (plain) "not loaded" else "○ not loaded", Palette.Muted, 4)
    // loaded: where it sits is one word (GPU), with the share only when part of it has spilled to the CPU; the memory is the ram meter's business
    val where = if (mine.sizeBytes <= 0) "" else if (mine.cpuPercent == 0) " GPU" else " ${100 - mine.cpuPercent}% GPU"
    return statusSegment("", (if (plain) "loaded" else "● loaded") + where, if (mine.cpuPercent >= 10) Palette.Orange else Palette.Green, 6)
}

/** `◇ embed 0.6 GB`: the semantic search's embedding model is loaded too (it takes memory next to the chat model); null when it is not loaded or none is set. */
internal fun embedSegment(app: AppContext, plain: Boolean): StatusSegment? {
    val m = com.arcana.brain.core.ModelStatus.embedLoaded(app.loaded, app.config.embedModel, app.config.model) ?: return null
    val size = if (m.sizeBytes > 0) " %.1f GB".format(m.sizeBytes / 1e9) else ""
    return statusSegment("", (if (plain) "embed" else "◇ embed") + size, Palette.Blue, 4)
}

/**
 * The memory meter of the bottom bar: what every model Arcana has loaded (the chat model, the Oracul, the judge, the flow's stages) takes of this machine's memory, as a quiet bar,
 * with the models' count and, when the model's cache size is known, about how many tokens of context fit next to the chat model's weights (an estimate). Null while nothing is loaded.
 * The processor and where the model sits stay percentages.
 */
internal fun memoryMeter(app: AppContext, plain: Boolean): StatusSegment? {
    val loaded = app.loaded.orEmpty()
    val total = loaded.sumOf { it.sizeBytes }
    val ram = com.arcana.brain.core.ModelFit.physicalMemory()
    if (total <= 0 || ram <= 0) return null
    val gb = { b: Long -> "%.1f".format(b / 1e9).removeSuffix(".0") }
    // the machine's whole use, never less than what the models take (the reading and the models' report come at different moments)
    val used = maxOf(app.usedMemory ?: total, total)
    val detail = buildString {
        append("models ${gb(total)} · ${if (plain) "all" else "Σ"} ${gb(used)} | ${com.arcana.brain.core.ModelFit.gb(ram)}")
        if (loaded.size > 1) append(" · ${loaded.size} models")
        app.tokenRoom?.let { append(" · ~${UsageStats.contextSize(it)} ctx fits") }
    }
    return memoryBarSegment(if (plain) "mem" else "ram", total.toDouble() / ram, (used - total).toDouble() / ram, detail, warn = used > com.arcana.brain.core.ModelFit.budget(ram) + total / 2 && total > com.arcana.brain.core.ModelFit.budget(ram), plain = plain)
}

/**
 * The warnings about the model and the machine, short, for the footer bar (on every screen, next to the model badge): a loaded model that runs partly on the CPU (red), a context window
 * larger than the model is built for, or one so small that notes and tool results get cut (orange). The explanations are in Settings → Ollama → Context window.
 */
internal fun hardwareWarnings(app: AppContext, plain: Boolean): List<Pair<String, com.jakewharton.mosaic.ui.Color>> {
    val mark = if (plain) "!" else "⚠"
    val config = app.config
    val out = ArrayList<Pair<String, com.jakewharton.mosaic.ui.Color>>()
    app.offloads.firstOrNull()?.let { out += "$mark ${it.cpuPercent}% on CPU: slow, lower the context" to Palette.Red }
    if (config.contextTokens > 0 && app.modelContext > 0 && config.contextTokens > app.modelContext)
        out += "$mark ctx ${UsageStats.contextSize(config.contextTokens.toLong())} > model ${UsageStats.contextSize(app.modelContext.toLong())}" to Palette.Orange
    val window = effectiveContext(config.contextTokens, app.modelContext, app.loadedContext)
    // the window asked for needs more cache than this machine's memory leaves next to the model's weights
    app.tokenRoom?.takeIf { config.contextTokens > 0 && config.contextTokens > it && it > 0 }?.let { out += "$mark ctx ${UsageStats.contextSize(config.contextTokens.toLong())} > ~${UsageStats.contextSize(it)} fits in ${if (app.gpuCap != null) "GPU memory" else "memory"}" to Palette.Orange }
    if (window in 1 until COMFORTABLE_CONTEXT) out += "$mark ctx ${UsageStats.contextSize(window.toLong())} is small" to Palette.Orange
    return out
}

/** The memory pill when the file is longer than what is sent: how much goes to the model of how much there is, in warning colours (the end is not read). */
internal fun memoryCutText(total: Int, plain: Boolean): String =
    "${if (plain) "!" else "⚠"} memory ${UsageStats.compact(Vault.MEMORY_LIMIT.toLong())} of ${UsageStats.compact(total.toLong())}: end cut"

/**
 * How the vault is searched right now, next to `◉ vault` (for `/vault` answers) or, with `/vault` off and tools on, on its own (`⌕ model: …`: nothing is searched in advance; the model searches by itself with `search_vault`): `⌕ words+meaning`, `⌕ words` or `⌕ meaning`. It shows what will REALLY run: with Both (or Meaning) and no embedding model set up the
 * words carry on alone, so it says `⌕ words (no model)` in orange, not what the setting asks for.
 */
internal fun searchModeSegment(app: AppContext, chat: ChatSession, plain: Boolean, toolOnly: Boolean = false): StatusSegment {
    // the same setting (Settings → Search → Search by) decides for /vault answers and for the model's own searches
    val mode = RetrievalMode.of(app.config.searchBy)
    val tag = ""
    val meaningOn = chat.semantic.semanticOn(app.config)
    val glyph = (if (plain) "search " else "⌕ ") + (if (toolOnly) "model: " else "")
    return when {
        mode == RetrievalMode.Keywords -> statusSegment("", "${glyph}words$tag", Palette.Blue, 5, second = true)
        !meaningOn -> statusSegment("", "${glyph}words (no model)$tag", Palette.Orange, 5, second = true)
        mode == RetrievalMode.Meaning -> statusSegment("", "${glyph}meaning$tag", Palette.Purple, 5, second = true)
        else -> statusSegment("", "${glyph}words+meaning$tag", Palette.Green, 5, second = true)
    }
}

/** What the Chat status bar says: model, chat, context gauge, tokens, estimated price, session time and the tool state. */
internal fun chatStatus(app: AppContext, chat: ChatSession, plain: Boolean, memoryChars: Int, width: Int): List<StatusSegment> {
    val config = app.config
    val stats = chat.messages.mapNotNull { it.stats }
    val sessionRecords = UsageStats.since(app.usage.toList(), app.sessionStart)
    val cost = UsageStats.estimatedCost(sessionRecords, config.estPriceIn, config.estPriceOut) + app.liveTokens * config.estPriceOut / 1_000_000.0
    val compact = UsageStats::compact
    return buildList {
        // the models, one segment: whoever works now comes first as a filled pill, the other one after it, quiet
        add(modelsSegment(ModelIcon.label(config.model, plain), chat.consulting, config, plain, width, app.memoryAdvice(Oracul.model(config), com.arcana.brain.core.ModelFit.Role.Oracul)?.startsWith("⚠") == true))
        // how the next word is chosen (model defaults or custom): quality-relevant, so it shows the values the model really works with (Ctrl+R changes them)
        add(samplingSegment(app))
        // the model's thinking: a core fact of how it answers (off is much faster); shown when the model can think
        if (app.thinkingValues.isNotEmpty()) add(thinkingSegment(app, plain))
        if (config.chatUseVault) {
            add(statusSegment("", "◉ vault", Palette.Cta, 6, bold = true, second = true))
            add(searchModeSegment(app, chat, plain))
        } else if (config.toolsEnabled && app.openVault().isSuccess) {
            // /vault is off, but the model can still search the vault with search_vault (words and meaning by default): the indicator says which search that is
            add(searchModeSegment(app, chat, plain, toolOnly = true))
        }
        // the goal of this chat (/goal): shown so it is clear what the work is for
        chat.goal?.let { add(pillSegment("◎ goal: ${it.replace('\n', ' ').take(40)}${if (it.length > 40) "…" else ""}", Palette.Cta, 8)) }
        // what the tools wrote or changed in this chat (/artifacts lists it)
        Artifacts.pill(chat.artifacts.toList(), plain)?.let { add(pillSegment(it, Palette.Purple, 5)) }
        // the memory file the assistant reads in every chat: it takes room in the context, so it sits before the gauge (orange when its end is cut)
        if (config.memoryEnabled) {
            if (memoryChars > Vault.MEMORY_LIMIT) add(statusSegment("", memoryCutText(memoryChars, plain), Palette.Orange, 9, bold = true))
            else if (memoryChars > 0) add(statusSegment("", "✎ memory ${UsageStats.compact(memoryChars.toLong())}", Palette.Green, 6))
            else add(statusSegment("", "✎ no ARCANA.md", Palette.Muted, 3))
        }
        val window = effectiveContext(config.contextTokens, app.modelContext, app.loadedContext)
        contextUsage(chat.messages, window, chat.overheadTokens)?.let { (share, detail) -> add(gaugeSegment("ctx", share, detail, 10, plain)) }
        // a small window drops the oldest text without a word: say so before it matters (Settings → Ollama → Context window)
        // ↑ (prompt) is exact and moves when a request ends; ↓ (reply) also counts the pieces streaming right now, until the exact figure replaces them
        if (stats.isNotEmpty() || app.liveTokens > 0) add(StatusSegment(listOf(StatusPart("↑${compact(stats.sumOf { it.promptTokens.toLong() })}", Palette.Blue), StatusPart(" ↓${compact(stats.sumOf { it.completionTokens.toLong() } + app.liveTokens)}", Palette.Green)), 10))
        // everything this session spent, every model and feature (the advisors, the flow, compaction): the figure the price below is worked out from; the arrows above are this chat's own model only
        add(statusSegment("Σ", "${compact(app.sessionTokens)} all models", Palette.Blue, 7))
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
            // plain while it runs, filled only when something went wrong (skipped steps)
            if (run.skippedCount > 0) add(pillSegment(text, state, 7)) else add(statusSegment("", text, state, 7, bold = true, second = true))
        }
        // the model's own task list: how many items are done
        chat.todos.takeIf { it.isNotEmpty() }?.let { list ->
            val done = com.arcana.brain.core.Todos.doneCount(list)
            add(statusSegment("", (if (plain) "todo " else "☑ ") + "$done/${list.size}" + (if (done == list.size) " ✓" else ""), if (done == list.size) Palette.Green else Palette.Cta, 7, second = true))
        }
        if (config.toolsEnabled) {
            if (app.tools == false) add(pillSegment("⚠ no tools", Palette.Red, 8))
            else add(statusSegment("", "⚙ ${config.toolMode}", when (config.toolMode) { "auto" -> Palette.Orange; "plan" -> Palette.Blue; else -> Palette.Green }, 8, bold = true, second = true))
            // the project the model works in: its folder name, so it is clear where relative paths and commands start
            if (config.projectFolder.isNotBlank()) add(statusSegment("", (if (plain) "project " else "▣ ") + java.nio.file.Path.of(config.projectFolder.trimEnd('/')).fileName, Palette.Cta, 5, second = true))
            // commands run without asking: said in the colour of risk, but as bold text, not a filled block that shouts over the bar
            if (config.toolWeb && config.toolAutoSearch && config.toolMode != "plan") add(statusSegment("", if (plain) "auto-search" else "↗ auto-search", Palette.Orange, 8, bold = true, second = true))
            if (config.toolShell && config.toolAutoShell && config.toolMode != "plan") add(statusSegment("", if (plain) "! auto-run" else "▶ auto-run", Palette.Red, 9, bold = true, second = true))
        }
    }
}
