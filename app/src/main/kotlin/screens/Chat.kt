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
import com.arcana.brain.app.Generation
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
    /** An example of how to type it with its text (`/revise focus on the typo`), shown under the highlighted entry; blank for a command that takes no text. */
    val usage: String = "",
    /** Other names that find it in the menu and run it (`/think` for `/effort`: the old name keeps working). */
    val aliases: List<String> = emptyList(),
)

/** What a request for each tool might look like when typed after its name (`/read_note Trip Planner`); blank = it needs nothing. */
private val ToolExamples = mapOf(
    "search_notes" to "oracul second opinion", "list_notes" to "Skills", "read_note" to "Trip Planner", "create_note" to "Ideas/Weekend plan with three bullet points",
    "append_to_note" to "Trip Planner: add a packing list", "set_properties" to "Trip Planner status: done", "add_tags" to "Trip Planner travel, 2026",
    "remember" to "I prefer short answers", "use_skill" to "coingecko-forecast", "wait" to "5 seconds", "web_search" to "ollama context window size",
    "fetch_url" to "https://example.com/page", "http_request" to "GET https://api.example.com/status", "run_command" to "ls -la ~/Downloads",
    "git_status" to "in ~/projects/app", "git_log" to "last 5 commits in ~/projects/app", "git_diff" to "in ~/projects/app", "list_dir" to "~/Downloads",
    "find_files" to "*.md in ~/Documents", "grep_files" to "TODO in ~/projects/app", "read_file" to "~/projects/app/README.md", "cat" to "~/projects/app/README.md",
    "write_file" to "~/notes.txt with the text hello", "edit_file" to "README.md: change the title to Arcana", "delete_path" to "~/old/notes.txt", "move_path" to "a.txt to ~/Archive/a.txt",
)

/**
 * The usage line of a tool in the slash menu: `e.g. /read_note Trip Planner` from [ToolExamples], else its required arguments in angle
 * brackets (`/name <path> <content>`), else a note that it needs no text.
 */
internal fun toolUsage(name: String, required: List<String>): String = when {
    name in ToolExamples -> "e.g. /$name ${ToolExamples.getValue(name)}"
    required.isNotEmpty() -> "usage: /$name ${required.joinToString(" ") { "<$it>" }}"
    else -> "needs no text: just /$name"
}

internal val SlashCommands = listOf(
    SlashItem("/new", "Start a new chat", false),
    SlashItem("/history", "Reopen an earlier chat", false),
    SlashItem("/save", "Save this chat to the vault as a note", false),
    SlashItem("/note", "Attach a note to your next message", false),
    SlashItem("/image", "Attach a file or picture", false),
    SlashItem("/vault", "Answer from your vault notes (toggle)", false),
    SlashItem("/tools", "Let the model use tools (toggle)", false),
    SlashItem("/oracul", "Ask the Oracul model for a second opinion on the last answer", false, usage = "e.g. /oracul qwen3:30b   (ask that model just this once)"),
    SlashItem("/revise", "The main model revises its answer using the Oracul's opinion", false, usage = "e.g. /revise focus on the edge case   (your note is added)"),
    SlashItem("/takeover", "The Oracul model finishes the turn the main model failed", false),
    SlashItem("/compare", "The Oracul answers too and a judge compares the answers", false, usage = "e.g. /compare qwen3:30b   (answer with that model instead)"),
    SlashItem("/compact", "Summarize the older part of this chat to free the context window", false),
    SlashItem("/mode", "Tools mode: ask, auto, plan (cycles)", false),
    SlashItem("/autorun", "Run shell commands without asking (toggle)", false),
    SlashItem("/effort", "Reasoning effort for the next message: pick from the levels this model offers (Ctrl+G)", false, usage = "/effort xhigh   or just /effort to pick from the list", aliases = listOf("/think")),
    SlashItem("/sampling", "How the model picks its next word: temperature, top P, top K, min P, penalties (Ctrl+R)", false, usage = "/sampling   or /sampling model  (the model's own defaults)  or /sampling custom"),
    SlashItem("/memory", "Open ARCANA.md, your standing instructions", false, usage = "e.g. /memory check   (finds repeated lines and dead note paths, asks before removing)"),
    SlashItem("/skills", "Skills from your vault: list them, on/off (toggle)", false),
    SlashItem("/reindex", "Build or refresh the semantic search index of your vault (Settings → Search → Embedding model (for meaning))", false),
    SlashItem("/helpers", "All helper models on or off: Oracul, judge, flow, specialists, router (toggle). Off = only the chat model works", false),
    SlashItem("/roles", "Skill roles on or off: the worker, planner and reviewer a skill uses (toggle)", false),
    SlashItem("/flow", "Flow: every message goes analyst, planner, worker, reviewer, controller (toggle)", false, usage = "e.g. /flow careful   or   /flow off   (without a name it switches)"),
    SlashItem("/goal", "Set a goal: Arcana keeps working until a checker says it is met", false, usage = "e.g. /goal all notes in Inbox are tagged   (/goal alone shows it, /goal clear removes it)"),
    SlashItem("/artifacts", "Files and notes the model wrote or changed in this chat", false),
    SlashItem("/thinking", "Thoughts pane: watch the model's thoughts under the conversation (toggle; not the effort, see /effort)", false),
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
    "/oracul" -> if (!config.oraculEnabled) "HELPERS OFF" to Palette.Dim else if (config.oraculModel.isBlank()) "NO MODEL" to Palette.Dim else (if (config.oraculAuto == "off") "MANUAL" else "AUTO ${config.oraculAuto.uppercase()}") to Palette.Green
    "/compact" -> if (config.autoCompactPercent > 0) "AUTO ${config.autoCompactPercent}%" to Palette.Green else "MANUAL" to Palette.Dim
    "/effort" -> (if (config.thinking == "default") autoWord(ThinkLevels.defaultWord).uppercase() else thinkText(config.thinking)) to if (config.thinking == "default") Palette.Dim else Palette.Accent
    "/autorun" -> if (!config.toolShell) "SHELL OFF" to Palette.Dim else if (config.toolAutoShell) "ON" to Palette.Red else "OFF" to Palette.Dim
    "/memory" -> if (config.memoryEnabled) "ON" to Palette.Green else "OFF" to Palette.Dim
    "/skills" -> if (config.skillsEnabled) "ON" to Palette.Green else "OFF" to Palette.Dim
    "/thinking" -> if (config.thinkingPane) "ON" to Palette.Green else "OFF" to Palette.Dim
    "/helpers" -> if (config.oraculEnabled) "ON" to Palette.Green else "OFF" to Palette.Dim
    "/roles" -> if (config.skillRolesEnabled) "ON" to Palette.Green else "OFF" to Palette.Dim
    "/flow" -> if (config.flow == Flow.OFF) "OFF" to Palette.Dim else config.flow.uppercase() to Palette.Green
    else -> null
}

/** A typed line that starts with a slash, split into the command word (a trailing `:` or `,` dropped, so `/revise:` works) and the text after it. */
internal fun splitSlash(text: String): Pair<String, String> {
    val line = text.trim()
    val word = line.substringBefore(' ').substringBefore('\n').trimEnd(':', ',')
    return word to line.removePrefix(line.substringBefore(' ').substringBefore('\n')).trim()
}

/** The message for a slash word that is no command, skill or tool: says what was typed and offers the closest names. */
internal fun unknownCommand(word: String, items: List<SlashItem>): String {
    val close = slashMatches(items, word).take(3).map { it.name }
    return "Unknown command $word." + (if (close.isEmpty()) "" else " Did you mean ${close.joinToString(", ")}?") + " Type / to see the commands and tools."
}

/** The entries that fit what is typed after the slash, grouped commands, skills, tools; in each group names that start with it first, then names that contain it. */
internal fun slashMatches(items: List<SlashItem>, query: String): List<SlashItem> {
    val q = query.trim().lowercase().removePrefix("/")
    val names = { item: SlashItem -> (listOf(item.name) + item.aliases).map { it.lowercase().removePrefix("/") } }
    // commands, then skills, then tools (so each kind sits together and is easy to tell apart); inside a kind the names that start with the text come first
    return (items.filter { n -> names(n).any { it.startsWith(q) } } + items.filter { n -> names(n).none { it.startsWith(q) } && names(n).any { q in it } }).sortedBy { it.kind.category.ordinal }
}

/** The Chat key map (`?`): every key and command by what it is for, the ones about the model first. */
fun chatKeySections(): List<com.arcana.brain.app.navigation.KeySection> = listOf(
    com.arcana.brain.app.navigation.KeySection("The model: thinking, tools, memory", listOf(
        "/effort" to "reasoning effort for the next message: pick from what the model offers (Ctrl+G; /effort xhigh sets it directly)",
        "/sampling" to "temperature, top P, top K, min P and the penalties: model defaults or your values (Ctrl+R)",
        "/thinking" to "thoughts pane under the conversation ([ and ] also switch it on and off and resize it)",
        "/mode" to "tools: ask, auto, plan",
        "/tools" to "tools on or off",
        "/autorun" to "shell commands without asking",
        "/vault" to "answer from your vault notes",
        "/compact" to "summarize old messages, free the context",
        "/memory" to "ARCANA.md, your standing instructions",
    )),
    com.arcana.brain.app.navigation.KeySection("More models: opinion, flow, goal", listOf(
        "/oracul [model]" to "second opinion on the last answer",
        "/revise [note]" to "the main model revises with it",
        "/takeover" to "the Oracul finishes a failed turn",
        "/compare [model]" to "the Oracul answers, a judge compares",
        "/flow [name]" to "analyst, planner, worker, reviewer, controller",
        "/goal [text]" to "keep working until a checker says done",
        "/helpers, /roles" to "helper models, skill roles on or off",
        "/skills" to "skills from your vault on or off",
        "/reindex" to "build or refresh the semantic search index",
    )),
    com.arcana.brain.app.navigation.KeySection("The message field", listOf(
        "Enter" to "send",
        "Alt+Enter, \\ Enter" to "new line",
        "/" to "commands and tools (↑↓ Tab Enter)",
        "@" to "attach a note",
        Shortcuts.label("Ctrl+O") to "attach a file or picture",
        Shortcuts.label("Ctrl+S") to "save the chat to the vault",
        "↑↓ PgUp PgDn" to "scroll the conversation",
        "Backspace" to "empty field: undo attach, then queued",
        "Esc" to "browse the conversation",
    )),
    com.arcana.brain.app.navigation.KeySection("While the model works", listOf(
        "Enter" to "queue a message for after the reply",
        "Esc" to "cancel the reply (asks first)",
        Shortcuts.label("Ctrl+G") to "reasoning effort: choose what the model offers (off, low, medium, xhigh…) for the next message",
        Shortcuts.label("Ctrl+R") to "sampling: temperature, top P, top K, min P, penalties (model defaults or your values)",
        "↑↓" to "scroll while it writes",
    )),
    com.arcana.brain.app.navigation.KeySection("Browsing (Esc from the field)", listOf(
        "Enter" to "back to typing",
        "↑↓ j k" to "scroll;  Space PgDn: a page;  g G: top, end",
        "s" to "select & copy (Space tick, c copy, b code)",
        "f" to "files and notes the model changed",
    )),
    com.arcana.brain.app.navigation.KeySection("The chat and its notes", listOf(
        "/new, /history" to "a new chat, an earlier chat",
        "/clear" to "erase this chat",
        "/save" to "save it as a note",
        "/append, /replace" to "put the last answer into a note",
        "/note, /image" to "attach a note, a file or picture",
        "/artifacts" to "files and notes written in this chat",
    )),
)

/** The values of [model] `think` as the setting names them: `default`, then `off`, `on` and levels, only what the model lists. */
internal fun thinkOptions(values: List<String>): List<String> =
    if (values.isEmpty()) listOf("default") else listOf("default") + values.map { when (it) { "false" -> "off"; "true" -> "on"; else -> it } }.distinct()

/** The `/think` choices of the model in use (what [thinkOptions] gives for the values Ollama lists), for the Settings row, which cannot see the app. Set when the model's details are read. */
internal object ThinkLevels {
    @Volatile var options: List<String> = listOf("default")

    /** What the model does when nothing is sent, as Ollama reports it (`medium`, `on`, `off`), or blank when it does not say. */
    @Volatile var defaultWord: String = ""
}

/** The model's own default as a word: Ollama's `false`/`true` are off/on, a level stays as it is; blank when Ollama does not say. */
internal fun thinkDefaultWord(raw: String): String = when (raw) { "" -> ""; "false" -> "off"; "true" -> "on"; else -> raw }

/**
 * What a choice does to the work with the model, in plain words. [options] is the model's own list (`thinkOptions`), so a level is described by where it stands in THAT list:
 * the lowest is light, the highest is the deepest, those between are balanced. More reasoning means more thinking tokens, which take time and count against the context window.
 */
internal fun thinkEffect(value: String, options: List<String>, defaultWord: String): String {
    val levels = options.filter { it != "default" && it != "off" && it != "on" }
    return when (value) {
        "off" -> "No reasoning: the fastest and cheapest, and the whole window is left for the conversation. Right for simple questions and chat; weaker on hard, multi-step or tool-heavy tasks."
        "on" -> "The model reasons before it answers: slower, and its thinking uses tokens of the context window. Better on hard questions."
        "default" -> "Nothing is sent: the model decides" + (if (defaultWord.isEmpty()) "." else " ($defaultWord). " + thinkEffect(defaultWord, options, "").takeIf { defaultWord != "default" }.orEmpty())
        else -> when {
            levels.size > 1 && value == levels.first() -> "Light reasoning: a short think before the answer. Quicker and cheaper than the higher levels; good for everyday questions."
            levels.size > 1 && value == levels.last() -> "The deepest reasoning: the most thinking tokens, so the slowest and the most context used, but the best on hard, multi-step or agentic tasks."
            else -> "Balanced reasoning: a fair amount of thinking before the answer. A good default when you do not know how hard the task is."
        }
    }
}

/**
 * The colour of a reasoning choice, from calm to hot, so the level can be read at a glance (static: no animation): off is blue (no reasoning, fast), the model's lowest level green, its
 * middle levels orange, its highest red; `on` (a model with only on/off) is orange; `auto` takes the colour of what the model does by default, muted when that is not known.
 * Where a level stands is judged in the model's OWN list, so `low · medium · xhigh` and `low · medium · high` both run green, orange, red.
 */
internal fun thinkColor(value: String, options: List<String>, defaultWord: String): com.jakewharton.mosaic.ui.Color {
    val levels = options.filter { it != "default" && it != "off" && it != "on" }
    return when (value) {
        "off" -> Palette.Blue
        "on" -> Palette.Orange
        "default" -> if (defaultWord.isEmpty() || defaultWord == "default") Palette.Muted else thinkColor(defaultWord, options, "")
        else -> {
            val at = levels.indexOf(value)
            if (at < 0) Palette.Muted
            else {
                val t = if (levels.size <= 1) 0.5 else at.toDouble() / (levels.size - 1)
                if (t < 0.34) Palette.Green else if (t < 0.8) Palette.Orange else Palette.Red
            }
        }
    }
}

/** `auto (medium)`: the model default with what it really is, so "auto" is never a mystery; just `auto` when Ollama does not say. */
internal fun autoWord(defaultWord: String): String = if (defaultWord.isEmpty()) "auto" else "auto ($defaultWord)"

/** The name of a thinking choice as a person reads it: `Model default`, `Off`, `On`, `Xhigh`. */
internal fun thinkLabel(value: String): String = when (value) { "default" -> "Model default"; "off" -> "Off"; "on" -> "On"; else -> value.replaceFirstChar { it.uppercase() } }

internal fun thinkText(setting: String): String = if (setting == "default") "MODEL DEFAULT" else setting.uppercase()

/** What the reasoning-effort control says and how it looks: [text] is `✦ think xhigh`, [width] the columns it takes with its key hint and a gap before it. */
private class EffortView(val text: String, val color: com.jakewharton.mosaic.ui.Color, val width: Int)

/**
 * The reasoning-effort control at the right end of the message field's first row: `Ctrl+G ✦ think xhigh`. Its colour says the level at a glance, calm to hot ([thinkColor]: off blue,
 * lowest level green, middle orange, highest red), and it does not move. A level picked for the next message says so. Null for a model that cannot think, or when the field is too
 * narrow to share its row.
 */
private fun effortView(app: AppContext, thinkingNow: Boolean, fieldWidth: Int): EffortView? {
    // [thinkingNow] only decides, like a running reply does, that the level in use is the one the message was sent with
    if (app.thinkingValues.isEmpty()) return null
    val plain = app.config.plainGlyphs
    val options = thinkOptions(app.thinkingValues)
    val next = app.chat.thinkNext
    val inUse = if (next.isEmpty() && thinkingNow || next.isEmpty() && app.chat.gen.state == GenState.Running) app.chat.thinkNow() else ""
    val setting = next.ifEmpty { inUse.ifEmpty { app.config.thinking } }
    val effective = if (setting in options) setting else "default"
    val word = (if (effective == "default") autoWord(thinkDefaultWord(app.thinkingDefault)) else effective) + if (next.isNotEmpty()) " · next" else if (inUse.isNotEmpty()) " · this" else ""
    val text = "${if (effective == "off") (if (plain) "" else "◌ ") else (if (plain) "" else "✦ ")}think $word"
    val color = thinkColor(effective, options, thinkDefaultWord(app.thinkingDefault))
    val width = Shortcuts.label("Ctrl+G").length + 1 + text.length + 2
    // the typed text keeps at least this much of the row
    if (fieldWidth < width + 30) return null
    return EffortView(text, color, width)
}

@Composable
private fun EffortInline(view: EffortView) {
    Row {
        Label("  " + Shortcuts.label("Ctrl+G") + " ", color = Palette.Hint)
        Label(view.text, color = view.color, textStyle = com.jakewharton.mosaic.ui.TextStyle.Bold)
    }
}

/** `✦ think auto`, `◌ think off` or `✦ think low`: what is sent as `think`; off is blue (fast), the rest accent. */
internal fun thinkingSegment(app: AppContext, plain: Boolean): StatusSegment {
    // a level picked for the next message wins until that message is sent
    val next = app.chat.thinkNext
    // while a reply runs, the level that message was sent with (a /think pick) is the one in use
    val inUse = if (next.isEmpty() && app.chat.gen.state == GenState.Running) app.chat.thinkNow() else ""
    val options = thinkOptions(app.thinkingValues)
    val setting = next.ifEmpty { inUse.ifEmpty { app.config.thinking } }
    // a setting the model does not list is not sent: the model decides
    val effective = if (setting in thinkOptions(app.thinkingValues)) setting else "default"
    val word = (if (effective == "default") autoWord(thinkDefaultWord(app.thinkingDefault)) else effective) + if (next.isNotEmpty()) " · next message" else if (inUse.isNotEmpty()) " · this message" else ""
    val glyph = if (plain) "" else if (effective == "off") "◌ " else "✦ "
    return statusSegment("", "${glyph}think $word", thinkColor(effective, options, thinkDefaultWord(app.thinkingDefault)), 8, bold = true)
}

/**
 * Tokens the context held: the last reply's prompt plus its answer, or, when the chat was compacted after that reply, an estimate of
 * what the model is sent now (the summary and the latest messages, about 4 characters a token). The flag says it is an estimate.
 */
internal fun contextTokens(messages: List<ChatMessage>): Pair<Int, Boolean>? {
    val statsAt = messages.indexOfLast { (it.stats?.promptTokens ?: 0) > 0 }
    if (statsAt < 0) return null
    // the counts of the last reply are stale when a summary came after it, or was inserted before it while that reply was made from the whole chat (a manual or automatic compaction)
    val summaryAt = messages.indexOfLast { Compaction.isSummary(it) }
    if (summaryAt > statsAt || (summaryAt >= 0 && statsAt < messages[summaryAt].contextAt)) {
        val chars = Compaction.forModel(messages).sumOf { it.content.length + it.toolCalls.sumOf { c -> c.function.arguments.toString().length } }
        return (chars / 4) to true
    }
    val stats = messages[statsAt].stats!!
    return (stats.promptTokens + stats.completionTokens) to false
}

/** How full the model's context was after the last reply (its prompt plus its answer) as a share and a `13.6k/131k` text (`~` after a compaction: an estimate); null before the first reply or when the size is unknown. */
internal fun contextUsage(messages: List<ChatMessage>, limit: Int): Pair<Double, String>? {
    val (used, estimated) = contextTokens(messages) ?: return null
    if (limit <= 0) return null
    return (used.toDouble() / limit).coerceIn(0.0, 1.0) to "${if (estimated) "~" else ""}${UsageStats.compact(used.toLong())}/${UsageStats.contextSize(limit.toLong())}"
}

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
    app.tokenRoom?.takeIf { config.contextTokens > 0 && config.contextTokens > it && it > 0 }?.let { out += "$mark ctx ${UsageStats.contextSize(config.contextTokens.toLong())} > ~${UsageStats.contextSize(it)} fits in memory" to Palette.Orange }
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
    val meaningOn = chat.semanticOn(app.config)
    val glyph = (if (plain) "search " else "⌕ ") + (if (toolOnly) "model: " else "")
    return when {
        mode == RetrievalMode.Keywords -> statusSegment("", "${glyph}words$tag", Palette.Blue, 5, second = true)
        !meaningOn -> statusSegment("", "${glyph}words (no model)$tag", Palette.Orange, 5, second = true)
        mode == RetrievalMode.Meaning -> statusSegment("", "${glyph}meaning$tag", Palette.Purple, 5, second = true)
        else -> statusSegment("", "${glyph}words+meaning$tag", Palette.Green, 5, second = true)
    }
}

/** What the Chat status bar says: model, chat, context gauge, tokens, estimated price, session time and the tool state. */
private fun chatStatus(app: AppContext, chat: ChatSession, plain: Boolean, memoryChars: Int, width: Int): List<StatusSegment> {
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
        contextUsage(chat.messages, window)?.let { (share, detail) -> add(gaugeSegment("ctx", share, detail, 10, plain)) }
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

/** How many queued messages are listed above the message field; the rest are counted. */
private const val QUEUE_SHOWN = 3

private enum class ChatMode { Chat, History, PickNote, PickTarget, ConfirmReplace, Save, Leave, ConfirmClear, ConfirmCancel, ConfirmMemory, Artifacts, Think, Sampling }

/** What happens to the last answer when it is sent to an existing note. */
private enum class Target(val verb: String) { Append("Append"), Replace("Replace") }

/** One row of the `/think` picker: [value] is what is stored (blank = the Settings value), [label] and [note] are what is shown. */
private class ThinkChoice(val value: String, val label: String, val note: String)

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
    val thinkPicker = remember { PickerState() }
    val sampling = remember { SamplingDialog() }
    var thinkChoices by remember { mutableStateOf(emptyList<ThinkChoice>()) }
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

    fun toggleThinkingPane() {
        val on = !app.config.thinkingPane
        app.update(app.config.copy(thinkingPane = on))
        toast(if (on) "Thinking pane on: it follows the model's thoughts under the conversation ([ and ] change its size and hide it; needs a model that can think and a terminal at least 26 rows tall)." else "Thinking pane off.")
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

    /** `/sampling` (Ctrl+R): the dialog for temperature, top P, top K, min P and the penalties; `/sampling model` sets the model's own defaults, `/sampling custom` switches to your values. */
    fun openSampling(args: String = "") {
        when (args.trim().lowercase()) {
            "" -> { sampling.open(); mode = ChatMode.Sampling }
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
        if (args.isNotEmpty() && command !in setOf("/oracul", "/compare", "/revise", "/flow", "/goal", "/memory", "/effort", "/think", "/sampling")) return toast("$command takes no text after it. Type just $command.", error = true)
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
            "/reindex" -> chat.reindex { text, error -> toast(text, error = error) }
            "/thinking" -> toggleThinkingPane()
            "/flow" -> setFlow(args)
            "/helpers" -> { val on = !app.config.oraculEnabled; app.update(app.config.copy(oraculEnabled = on)); toast(if (on) "Helper models on." else "Helper models off: only ${app.config.model} works (skill roles are separate: /roles).") }
            "/roles" -> { val on = !app.config.skillRolesEnabled; app.update(app.config.copy(skillRolesEnabled = on)); toast(if (on) "Skill roles on: skills use the worker, planner and reviewer they name." else "Skill roles off: a skill is run by ${app.config.model} alone.") }
            "/artifacts" -> if (chat.artifacts.isEmpty()) toast("Nothing written or changed in this chat yet.") else mode = ChatMode.Artifacts
            "/goal" -> runGoal(args)
            "/mode" -> cycleMode()
            "/autorun" -> toggleAutoRun()
            "/effort", "/think" -> if (args.isBlank()) openThinkPicker() else setEffortFromText(args)
            "/sampling" -> openSampling(args)
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
            Command("Semantic search: build or refresh the index of the vault", "Chat", "/reindex") { chat.reindex { text, error -> toast(text, error = error) } },
            Command("Reasoning effort: choose for the next message (what the model offers)", "Chat", "Ctrl+G") { openThinkPicker() },
            Command("Sampling: temperature, top P, top K, min P, penalties", "Chat", "Ctrl+R") { openSampling() },
            Command(if (app.config.thinkingPane) "Thinking pane: turn off" else "Thinking pane: show the model's thoughts live", "Chat", "/thinking") { toggleThinkingPane() },
            Command("Tools mode: ${app.config.toolMode} → next (ask, auto, plan)", "Chat", "/mode", app.config.toolsEnabled) { cycleMode() },
            Command("Oracul: second opinion on the last answer", "Chat", "/oracul", lastAnswer != null) { runOracul(compare = false) },
            Command("Oracul: compare with another model's answer", "Chat", "/compare", lastAnswer != null) { runOracul(compare = true) },
            Command("Oracul: take over the turn the main model failed", "Chat", "/takeover", gen.state == GenState.Failed) { runTakeover() },
            Command("Oracul: have the main model revise its answer", "Chat", "/revise", Oracul.hasOpenOpinion(chat.messages.toList())) { runCatching { chat.revise() }.onFailure { toast(it.message ?: "Nothing to revise.", error = true) } },
            Command("Compact the chat: summarize the older part", "Chat", "/compact", chat.messages.size >= Compaction.MIN_MESSAGES) { runCompact() },
            Command(if (app.config.toolAutoShell) "Shell commands: ask before running again" else "Shell commands: run without asking", "Chat", "/autorun", app.config.toolsEnabled && app.config.toolShell) { toggleAutoRun() },
            Command(if (app.config.toolsEnabled) "Tools: turn off" else "Tools: turn on (read and write notes and files)", "Chat", "/tools") { toggleTools() },
            Command(if (app.config.chatUseVault) "Answer from my vault: turn off" else "Answer from my vault: turn on", "Chat", "/vault") { toggleVault() },
            Command("Files and notes changed in this chat", "Chat", "Esc then f", chat.artifacts.isNotEmpty()) { mode = ChatMode.Artifacts },
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
            ChatMode.Sampling -> sampling.typing
            ChatMode.PickNote, ChatMode.PickTarget -> notePicker.filtering
            else -> true
        },
    )
    if (question != null) Hints(*(if (question.typing) arrayOf("Enter  send the answer", *(if (question.options.isNotEmpty()) arrayOf("↑  back to options") else emptyArray()), "Esc  skip") else arrayOf("↑↓  move", if (question.multiple) "Space  tick" else "Enter  choose", *(if (question.multiple) arrayOf("Enter  confirm") else emptyArray()), "1-${question.options.size}  pick", "t  type my own", "Esc  skip")))
    else if (approval != null) Hints("y  ${approval.yes.lowercase()}", "n  no", *(if (approval.always) arrayOf("a  always this session") else emptyArray())) else if (attachments.browsing) Hints(*attachBrowserHints(attachments).toTypedArray()) else when (mode) {
        ChatMode.Save -> Hints("Enter  save", "Tab  whole chat / last answer", "Ctrl+O  overwrite a note", "Esc  cancel")
        ChatMode.Leave -> Hints("w  save to vault", "Esc  leave chat", "any other key  stay")
        ChatMode.ConfirmClear -> Hints("y  erase this chat", "any other key  keep")
        ChatMode.Artifacts -> Hints("1-9  open the note in the Library", "any other key  back")
        ChatMode.ConfirmMemory -> Hints("y  remove the repeated lines", "any other key  leave it as it is")
        ChatMode.ConfirmCancel -> Hints("Enter  cancel it", "y  cancel it", "any other key  keep going")
        ChatMode.ConfirmReplace -> Hints("y  replace", "any other key  keep the note")
        ChatMode.History -> Hints("↑↓  move", "Enter  open", "/  filter", "Esc  back")
        ChatMode.Think -> Hints("↑↓  move", "Enter  use for the next message", "d  make it the Settings value", "Esc  back")
        ChatMode.Sampling -> if (sampling.typing) Hints("Enter  save", "blank  the model's own", "Ctrl+U  clear line", "Esc  cancel") else Hints("↑↓  move", "←→  change", "Enter  type a value", "m  mode", "r  model defaults", "Esc  back")
        ChatMode.PickNote -> Hints("type  search", "↑↓  move", "Enter  attach", "Esc  cancel")
        ChatMode.PickTarget -> Hints("type  search", "↑↓  move", "Enter  ${target.verb.lowercase()}", "Esc  cancel")
        ChatMode.Chat -> when {
            output.selecting -> Hints("↑↓  move", "Space  tick", "c  copy", "b  code only", "Esc  leave")
            chat.compacting -> Hints("Wait: summarizing the chat, then you can send", "↑↓  scroll")
            gen.state == GenState.Running -> Hints("Enter  queue a message", *(if (chat.queued.isNotEmpty()) arrayOf("Backspace  remove queued") else emptyArray()), "Esc  cancel reply", "↑↓  scroll", "[ ]  thinking pane")
            browsing -> Hints("Enter  type a message", "s  select & copy", "↑↓/Space  scroll", "1-9  screens")
            slashMatches.isNotEmpty() -> Hints("↑↓  choose", "Tab  complete", "Enter  run", "Esc  close")
            else -> Hints("Enter  send", "Alt+Enter  new line", "/  commands", "@  note", "Ctrl+O  attach", "Ctrl+S  save", "↑↓  scroll", "Esc  browse")
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
            ChatMode.Artifacts -> {
                val list = chat.artifacts.toList().asReversed()
                val picked = key.char?.takeIf { it in '1'..'9' }?.let { list.getOrNull(it - '1') }
                mode = ChatMode.Chat
                if (picked != null) {
                    if (picked.path.endsWith(".md") && picked.action != "deleted") navigator.openLibrary(LibraryFilter.All, note = picked.path)
                    else toast("${picked.path} is not a note in the Library.", error = picked.action == "deleted")
                }
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
                // [ and ] are the pane's keys (on, size, off): only with nothing typed yet (or while browsing), and [ only when the pane is on, so a bracket opening a message is still typed
                (key.char == ']' || (key.char == '[' && app.config.thinkingPane)) && (input.text.isEmpty() || browsing) -> { stepThinkingPane(key.char == ']'); true }
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
                    key.char == 'f' && chat.artifacts.isNotEmpty() -> { mode = ChatMode.Artifacts; true }
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
                    style = ActionStyle.Button,
                )
            }
            ChatMode.Leave -> {
                ScreenHeader("Chat", chat.title)
                VGap()
                Panel("Leave this chat?", accent = Palette.Warn) {
                    Warning("This conversation (${chat.messages.size - chat.savedMessages} new messages) is not saved to your vault as a note.")
                    Hint("Chats are not being kept (Settings → Console → Keep chats), so it is gone once you leave.")
                }
                ActionBar(listOf(ActionItem("w", "Save to vault first"), ActionItem("Esc", "Leave chat", accent = Palette.Red), ActionItem("any other key", "Stay", accent = Palette.Cta)), style = ActionStyle.Button)
            }
            ChatMode.ConfirmClear -> {
                ScreenHeader("Chat", chat.title)
                VGap()
                Panel("Erase this chat?", accent = Palette.Red) {
                    Warning("“${chat.title}” (${chat.messages.size} messages) is deleted from the history. /new keeps it and starts a fresh chat instead.")
                }
                ActionBar(listOf(ActionItem("y", "Erase chat", accent = Palette.Red), ActionItem("any other key", "Keep it", accent = Palette.Cta)), style = ActionStyle.Button)
            }
            ChatMode.Artifacts -> {
                val list = chat.artifacts.toList().asReversed()
                ScreenHeader("Chat", "Written or changed in this chat")
                VGap()
                Panel("${list.map { it.path }.distinct().size} files and notes · newest first", accent = Palette.Purple) {
                    list.take(9).forEachIndexed { i, a -> Label("${i + 1}  ${Artifacts.line(a)}", color = if (a.action == "deleted") Palette.Red else Palette.Text) }
                    if (list.size > 9) Hint("… and ${list.size - 9} older")
                }
                ActionBar(listOf(ActionItem("1-9", "Open in the Library", accent = Palette.Cta), ActionItem("any other key", "Back")), style = ActionStyle.Button)
            }
            ChatMode.ConfirmMemory -> {
                val audit = memoryAudit
                ScreenHeader("Chat", "Memory check")
                VGap()
                Panel("Repeated lines · ${audit?.duplicates?.size ?: 0}", accent = if (audit?.duplicates.isNullOrEmpty()) Palette.Dim else Palette.Orange) {
                    if (audit == null || audit.duplicates.isEmpty()) Hint("None.")
                    audit?.duplicates?.take(8)?.forEach { (dup, kept) -> Label("✕ ${dup.take(70)}", color = Palette.Orange); Label("  same as: ${kept.take(60)}", color = Palette.Dim) }
                    if ((audit?.duplicates?.size ?: 0) > 8) Hint("… and ${audit!!.duplicates.size - 8} more")
                }
                Panel("Notes that are not there · ${audit?.deadPaths?.size ?: 0}", accent = if (audit?.deadPaths.isNullOrEmpty()) Palette.Dim else Palette.Red) {
                    if (audit == null || audit.deadPaths.isEmpty()) Hint("None.")
                    audit?.deadPaths?.take(8)?.forEach { (line, ref) -> Label("✕ ${ref.take(70)}", color = Palette.Red); Label("  in: ${line.take(64)}", color = Palette.Dim) }
                    if (audit?.deadPaths?.isNotEmpty() == true) Hint("These are not removed: fix the path in the memory file (/memory).")
                }
                ActionBar(listOf(ActionItem("y", "Remove repeated lines", accent = Palette.Orange), ActionItem("any other key", "Leave it", accent = Palette.Cta)), style = ActionStyle.Button)
            }
            ChatMode.ConfirmCancel -> {
                ScreenHeader("Chat", chat.title)
                VGap()
                Panel(if (gen.state == GenState.Running) "Cancel this reply?" else "Cancel the Oracul?", accent = Palette.Orange) {
                    Warning(if (gen.state == GenState.Running) "The reply stops where it is and what was written so far is kept. It keeps running while you decide." else "The Oracul or the judge stops and nothing is added to the chat. It keeps running while you decide.")
                }
                ActionBar(listOf(ActionItem("Enter", "Cancel it", accent = Palette.Orange), ActionItem("any other key", "Keep going", accent = Palette.Cta)), style = ActionStyle.Button)
            }
            ChatMode.ConfirmReplace -> {
                ScreenHeader("Chat", "Replace “${replaceTarget}”")
                VGap()
                Panel("Replace this note with ${if (overwriting == null || overwriting?.lastAnswerOnly == true) "the last answer" else "this chat"}?", accent = Palette.Orange) {
                    Warning("The text of the note is replaced by ${if (overwriting == null || overwriting?.lastAnswerOnly == true) "the answer" else "the chat"}. Its frontmatter is kept, and the old text is saved first in the vault's .trash folder.")
                }
                ActionBar(listOf(ActionItem("y", "Replace note", accent = Palette.Orange), ActionItem("any other key", "Keep the note", accent = Palette.Cta)), style = ActionStyle.Button)
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
            ChatMode.Sampling -> SamplingPanel(app, sampling)
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
            ChatMode.Chat -> {
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
                val statusRow = (if (chat.compacting || gen.state == GenState.Running || gen.state == GenState.Failed || gen.state == GenState.Cancelled || chat.consulting != null) 1 else 0) + (if (chat.indexing != null) 1 else 0)
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
                        ReplyStatus(app, gen, compactingSince = chat.compactingSince.takeIf { chat.compacting }, compactingAuto = chat.compactingAuto, consulting = chat.consulting, consultingSince = chat.consultingSince, consultingStage = chat.consultingStage)
                        chat.indexing?.let { Label((if (app.config.plainGlyphs) "" else "◌ ") + it, color = Palette.Blue) }
                    }
                }
                if (pane != null && side) {
                    val total = LocalContentWidth.current
                    val convWidth = total - pane.size - 1
                    val paneHeight = scrollRows + statusRow + 2
                    val paneBox: @Composable RowScope.() -> Unit = {
                        Box(Modifier.weight(pane.size.toFloat() / total)) {
                            CompositionLocalProvider(LocalContentWidth provides pane.size) { ThinkingPane(app, gen, chat, paneHeight - 2, app.config.model, Modifier.height(paneHeight)) }
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        if (pane.placement == "left") { paneBox(); HGap() }
                        Box(Modifier.weight(convWidth.toFloat() / total)) { CompositionLocalProvider(LocalContentWidth provides convWidth) { conversation() } }
                        if (pane.placement == "right") { HGap(); paneBox() }
                    }
                } else {
                    if (pane?.placement == "top") ThinkingPane(app, gen, chat, pane.size, app.config.model)
                    conversation()
                    if (pane != null && pane.placement == "bottom") ThinkingPane(app, gen, chat, pane.size, app.config.model)
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
                    TextInput("Message", input, !browsing, if (browsing) "Press Enter to type" else "Ask, think, or give an instruction…", maxRows = maxInputRows(), attachments = chat.attachments, trailing = effort?.let { v -> { EffortInline(v) } })
                    StatusBar(statusSegments)
                    ActionBar(chatActions(browsing, entries.isNotEmpty(), chat.artifacts.isNotEmpty()))
                }
                // one empty row under the whole screen, so the last row does not touch the footer (counted in the conversation's height above)
                if (bottomGap > 0) Label(" ")
            }
        }
    }
}

/** [text] cut into rows of at most [width] characters at spaces (a word longer than a row is cut), a line break starting a new row. */
internal fun wrapWords(text: String, width: Int): List<String> {
    val w = width.coerceAtLeast(8)
    val rows = ArrayList<String>()
    for (line in text.lines()) {
        var row = StringBuilder()
        for (word in line.split(' ').filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.length > w) {
                if (row.isNotEmpty()) { rows += row.toString(); row = StringBuilder() }
                rows += rest.take(w); rest = rest.drop(w)
            }
            if (row.isNotEmpty() && row.length + 1 + rest.length > w) { rows += row.toString(); row = StringBuilder() }
            if (row.isNotEmpty()) row.append(' ')
            row.append(rest)
        }
        rows += row.toString()
    }
    return rows
}

/** The most rows (frame included) the thinking strip takes while a dialog is open; the dialog gives up as many lines. */
private const val DIALOG_STRIP_MAX = 6

/** The thinking pane's size range in percent of the main axis (`[` `]` step 5; the same as the Settings stepper). */
private const val THINKING_MIN = 10
private const val THINKING_MAX = 90

/** Where the thinking pane goes and how big: [size] is its text rows for "top" and "bottom", its whole width in columns for "left" and "right". */
internal class ThinkingPaneLayout(val placement: String, val size: Int)

/**
 * The pane's place for a [width] x [height] content area, or null when it does not fit. [blockRows] is the height the conversation has when there is no pane
 * (its frame and status row included): the strip takes [percent] of it, and the conversation keeps at least 5 rows. A column takes [percent] of [width]
 * and leaves the conversation at least 40 columns; a window under 80 columns or 16 rows has no side column, so the pane goes to the bottom there.
 */
internal fun thinkingPaneLayout(placement: String, percent: Int, width: Int, height: Int, blockRows: Int, dialog: Boolean = false): ThinkingPaneLayout? {
    val p = percent.coerceIn(10, 90)
    if ((placement == "left" || placement == "right") && width >= 80 && height >= 16) {
        return ThinkingPaneLayout(placement, (width * p / 100).coerceIn(24, width - 41))
    }
    if (height < 26) return null
    // a dialog takes most of the window: the strip then stays at a few rows (its text rows are 2 to 4) instead of going away with the conversation's room
    if (dialog) return ThinkingPaneLayout(if (placement == "top") "top" else "bottom", ((blockRows * p / 100).coerceIn(4, DIALOG_STRIP_MAX)) - 2)
    val outer = (blockRows * p / 100).coerceAtMost(blockRows - 2 - 5 - 2)
    return if (outer < 4) null else ThinkingPaneLayout(if (placement == "top") "top" else "bottom", outer - 2)
}

/**
 * What the thinking pane says while a reply runs and no thoughts have come yet: not a generic "waiting", but what is true for THIS message. The level is the one the message was
 * sent with (`/think` for that message, else the Settings value, as the model lists it); before the first token the model is still reading the prompt, which can take a while.
 */
private fun thinkingWaitText(app: AppContext, gen: Generation, model: String): String {
    if (app.thinkingValues.isEmpty()) return "$model has no thinking mode, so no thoughts will appear here; the answer comes directly."
    val options = thinkOptions(app.thinkingValues)
    val sent = app.chat.thinkNow().ifEmpty { app.config.thinking }
    val effective = if (sent in options) sent else "default"
    if (effective == "off") return "Thinking is off for this message, so no thoughts will appear. Ctrl+G picks a level for the next one."
    val level = if (effective == "default") autoWord(thinkDefaultWord(app.thinkingDefault)) else effective
    return if (gen.streamed == 0) "$model is reading the conversation first (think $level). Its thoughts appear here as soon as it starts."
    else "$model has started answering without thoughts (think $level): a model may skip thinking on an easy question."
}

/**
 * ` · think xhigh` for the title of the thinking pane: the level of the message whose thoughts it shows (while a reply runs, the one it was sent with; when idle, the last message's),
 * with the model default spelled out (`auto (medium)`); nothing for a model that cannot think.
 */
private fun thinkTitleWord(app: AppContext, chat: ChatSession, running: Boolean): String {
    if (app.thinkingValues.isEmpty()) return ""
    val options = thinkOptions(app.thinkingValues)
    val own = if (running) chat.thinkNow() else chat.messages.lastOrNull { Oracul.isRealUser(it) }?.think.orEmpty()
    val setting = own.ifEmpty { app.config.thinking }
    val effective = if (setting in options) setting else "default"
    return " · think " + if (effective == "default") autoWord(thinkDefaultWord(app.thinkingDefault)) else effective
}

/** The split window under the conversation: the model's thoughts as they stream (the end of them, [rows] rows), or the last thoughts it had when it is idle. */
@Composable
private fun ThinkingPane(app: AppContext, gen: Generation, chat: ChatSession, rows: Int, model: String, modifier: Modifier = Modifier) {
    val running = gen.state == GenState.Running
    val text = (if (running) gen.liveThinking else chat.messages.lastOrNull { it.role == "assistant" && it.thinking.isNotBlank() }?.thinking.orEmpty()).trim()
    val width = panelWidth()
    val wrapped = if (text.isEmpty()) emptyList() else wrapWords(sanitizeForTerminal(text), width)
    val shown = wrapped.takeLast(rows)
    Panel("Thinking · $model" + thinkTitleWord(app, chat, running) + (if (running) " · live" else "") + (if (text.isEmpty()) "" else " · ${UsageStats.compact(text.length.toLong())} characters")) {
        // nothing to show yet: say so, wrapped to the pane (a side column is narrow)
        val empty = if (shown.isNotEmpty()) emptyList() else wrapWords(if (running) thinkingWaitText(app, gen, model) else "The thoughts of a reasoning model appear here while it works.", width)
        for (i in 0 until rows) {
            if (empty.isNotEmpty()) Label(empty.getOrNull(i) ?: " ", color = Palette.Dim)
            else if (shown.isNotEmpty()) Label(shown.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: " ", color = Palette.Muted)
        }
    }
}

/** The lines of one message; [entry] is the number of its first select-mode entry (a reply uses one per Markdown block). */
internal fun messageLines(app: AppContext, message: ChatMessage, entry: Int, width: Int, applyHint: Boolean = false): List<Line> {
    if (Compaction.isSummary(message)) return summaryLines(message, entry, app.config.plainGlyphs, width)
    OraculSteps.parse(message)?.let { return oraculStepLines(it, entry, app.config.plainGlyphs, width) }
    if (Oracul.isOpinion(message)) return opinionLines(message, entry, app.config, width, applyHint)
    // what Arcana sent in the user's name: marked as a system message (or left out), never shown as "You"
    if (Oracul.isSystemPrompt(message)) {
        if (app.config.systemMessages == "hidden") return emptyList()
        return buildList {
            add(Line(if (app.config.plainGlyphs) "[system] Arcana to the model" else "⚙ System · Arcana to the model", Palette.ConsoleDim, bold = true))
            wrap(sanitizeForTerminal(message.content), (width - 2).coerceAtLeast(10)).forEach { add(Line("  $it", Palette.ConsoleDim)) }
            add(Line(""))
        }
    }
    val user = message.role == "user"
    val config = app.config
    if (message.role == "tool") return toolResultLines(message, config.plainGlyphs, width, entry)
    return buildList {
        if (user) {
            // Your messages sit on an amber band, so they stand apart from the model's answers.
            val band = Palette.UserBg
            add(Line("${ModelIcon.user(config.plainGlyphs)} You" + if (message.think.isEmpty()) "" else "  ·  ${if (config.plainGlyphs) "" else "✦ "}think ${message.think}", Palette.UserName, bold = true, background = band, entry = entry))
            wrap(sanitizeForTerminal(message.content), width).forEach { add(Line(it, Palette.UserText, background = band, entry = entry)) }
            message.noteNames.forEach { add(Line(Skills.chipLabel(it)?.let { chip -> sanitizeForTerminal(chip) } ?: "▤ ${sanitizeForTerminal(it.removeSuffix(".md"))}", Palette.UserName, background = band, entry = entry)) }
            message.files.forEach { add(Line("❐ ${sanitizeForTerminal(it.name)}", Palette.UserName, background = band, entry = entry)) }
            message.imageNames.forEach { name ->
                val kept = if (message.images.isEmpty()) " (picture not kept)" else ""
                add(Line("▣ ${sanitizeForTerminal(name)}$kept", Palette.UserName, background = band, entry = entry))
            }
            add(Line("", background = band, entry = entry))
        } else {
            // the header belongs to the reply's first entry (its first block, else its first tool call); a reply with neither has none, so it never borrows the next message's entry
            val blocks = parseMarkdown(message.content).size
            add(Line(ModelIcon.label(config.model, config.plainGlyphs), Palette.Accent, bold = true, entry = if (blocks > 0 || message.toolCalls.isNotEmpty()) entry else -1))
            addAll(thinkingLines(message.thinking, config.thinkingView, config.plainGlyphs, width))
            addAll(renderMarkdown(parseMarkdown(message.content), width, entry = { entry + it }, rich = config.renderMarkdown, plainGlyphs = config.plainGlyphs))
            // the tools it asked for, one line and one select entry each
            message.toolCalls.forEachIndexed { i, call -> add(Line("${if (config.plainGlyphs) "[tool]" else "⚙"} ${call.name}  ${sanitizeForTerminal(callSummary(call, config))}".take(width), Palette.ConsoleTool, bold = true, entry = entry + blocks + i)) }
            // the notes the answer was built from, when the vault was searched
            if (message.sources.isNotEmpty()) {
                addAll(sourceLines(message, config.plainGlyphs, width))
                // notes longer than Chars per note were shown to the model only in part: the answer may miss what is further down
                if (message.sourcesCut.isNotEmpty()) wrap("${if (config.plainGlyphs) "!" else "⚠"} Only the start was read of: " + message.sourcesCut.joinToString(", ") { sanitizeForTerminal(it.removeSuffix(".md")) } + " (Settings → Search → Chars per note)", width).forEach { add(Line(it, Palette.Warn)) }
            }
            // the tools the model used for this answer, so it is plain which instruments were at work
            if (message.toolsUsed.isNotEmpty()) add(Line("${if (config.plainGlyphs) "tools" else "⚙ tools"}: ${message.toolsUsed.joinToString(" · ")}".take(width), Palette.ConsoleTool))
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

/**
 * The notes an answer is built from, one per line, so the list can be read: `Sources (3) · vault search: keywords + meaning`, then `▤ Title` with, in a dim run, how it came to the answer
 * (`read` = the model opened it, `context` = it was sent with the question; and how it was found, with its evidence: `keywords + meaning · words 3/3 · similarity 0.64`).
 */
internal fun sourceLines(message: ChatMessage, plain: Boolean, width: Int): List<Line> = buildList {
    val head = "Sources (${message.sources.size})" + (if (message.sourcesBy.isEmpty()) "" else "  ·  vault search: ${message.sourcesBy}")
    add(Line(head.take(width), Palette.Accent, bold = true))
    message.sources.forEachIndexed { i, path ->
        val title = sanitizeForTerminal(path.removeSuffix(".md").substringAfterLast('/'))
        val info = message.sourcesInfo.getOrNull(i).orEmpty()
        val mark = if (plain) "-" else "▤"
        val text = "  $mark $title" + if (info.isEmpty()) "" else "   $info"
        add(Line(text.take(width), Palette.Accent, runs = listOf(Run("  $mark $title", Palette.Accent), Run(if (info.isEmpty()) "" else "   $info", Palette.Muted))))
    }
}

/** A tool call of the Oracul, quiet and dim like a tool's own lines but in the Oracul's colour: `Ψ model  tool  what it was about`, then `↳ ✓ how it ended`. */
private fun oraculStepLines(step: OraculSteps.Step, entry: Int, plain: Boolean, width: Int): List<Line> {
    val failed = step.status != OraculSteps.OK
    val head = "${if (plain) "*" else Oracul.GLYPH} ${sanitizeForTerminal(step.model)}  ${step.tool}  ${sanitizeForTerminal(step.summary)}".trim()
    val mark = if (plain) (if (failed) "x" else "ok") else if (failed) "✕" else "✓"
    val size = if (step.status == OraculSteps.OK && step.size > 80) "  (${step.size} characters)" else ""
    val said = when (step.status) {
        OraculSteps.DENIED -> "you did not allow it"
        else -> sanitizeForTerminal(step.outcome).ifEmpty { "done" }
    }
    val result = "  ${if (plain) "->" else "↳"} $mark ${said.take((width - 8 - size.length).coerceAtLeast(10))}$size"
    return listOf(
        Line(head.take(width), Palette.Blue, entry = entry),
        Line(result.take(width), if (failed) Palette.Red else Palette.Muted, entry = entry),
        Line(""),
    )
}

/** What the Oracul said: a header with the model(s), then the text as Markdown, dimmed apart from its own colours. The whole block is one entry for select mode. */
private fun opinionLines(message: ChatMessage, entry: Int, config: AppConfig, width: Int, applyHint: Boolean): List<Line> = buildList {
    // a comparison in the window is drawn as columns when the setting says so and every column is wide enough to read
    val comparison = if (config.comparisonView == "side") Oracul.comparisonParts(message) else null
    val columns = comparison != null && columnCount(comparison.answers.size, width) >= 2
    // a verdict is the judge model's own words: it wears that model's family glyph, like a reply does; the other helper blocks keep the Oracul's
    val glyph = if (Oracul.isVerdict(message)) ModelIcon.of(Oracul.title(message).substringAfter("· ", "").trim(), config.plainGlyphs) else if (config.plainGlyphs) "*" else Oracul.GLYPH
    val title = "$glyph ${sanitizeForTerminal(Oracul.title(message))}"
    // over columns the model names in the title wear the colours of their columns
    if (columns && comparison != null) add(Line(title, Palette.Blue, bold = true, entry = entry, runs = comparisonTitleRuns(title, comparison)))
    else add(Line(title, Palette.Blue, bold = true, entry = entry))
    if (comparison != null && columns) {
        addAll(sideBySide(comparison, entry, config, width))
        add(Line(""))
        return@buildList
    }
    // the worker's own sentence that introduces a review is for the model, not for the reader
    val body = Oracul.body(message)
    addAll(renderMarkdown(parseMarkdown(body), width, entry = { entry }, rich = config.renderMarkdown, plainGlyphs = config.plainGlyphs))
    // while nobody has answered it: how to act on it (nothing is applied by itself)
    if (applyHint) add(Line("› /revise: the main model corrects its answer with this  ·  or just carry on".take(width), Palette.Hint, entry = -1))
    add(Line(""))
}

/** A compaction summary: a dim divider, the summary, and a line saying the model no longer sees what is above. */
private fun summaryLines(message: ChatMessage, entry: Int, plain: Boolean, width: Int): List<Line> = buildList {
    val rule = if (plain) "-" else "─"
    val title = " ${if (plain) "" else "◌ "}compacted: the model sees this summary instead of the messages above "
    add(Line((rule.repeat(3) + title + rule.repeat(width)).take(width), Palette.ConsoleDim, bold = true, entry = entry))
    wrap(sanitizeForTerminal(Compaction.summaryText(message)), width).forEach { add(Line(it, Palette.ConsoleDim, entry = entry)) }
    add(Line(rule.repeat(width), Palette.ConsoleDim, entry = entry))
    add(Line(""))
}

/** What select mode can pick in [m]: your message as one entry, a reply as its Markdown blocks, tool results as none. */
internal fun entriesOf(m: ChatMessage): List<String> = when (m.role) {
    // what the Oracul did with a tool: one entry, so it can be copied
    OraculSteps.ROLE -> listOf(OraculSteps.parse(m)?.let { OraculSteps.text(it) } ?: m.content)
    "user" -> listOf(m.content)
    // a tool result is one entry, so it can be selected and copied like an answer
    "tool" -> listOf("${m.toolName ?: "tool"} result:\n${m.content.trim()}")
    // a reply: its Markdown blocks, then one entry per tool call (what it asked for), in the order they are drawn
    else -> parseMarkdown(m.content).map { it.raw } + m.toolCalls.map { "${it.name}  ${callSummary(it)}".trim() }
}

/** The one thing a tool call is about, for its line in the chat: its path, query or title. */
private fun callSummary(call: ToolCall, config: AppConfig? = null): String {
    val args = call.arguments
    if (call.name == com.arcana.brain.core.CONSULT && config != null) {
        val stage = (args["stage"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().lowercase()
        val kind = com.arcana.brain.core.Flow.consultKind(stage) ?: return stage
        val model = com.arcana.brain.core.Flow.consultModel(config, kind)
        return if (model.isEmpty()) "$stage → no model set" else "$stage → $model (${com.arcana.brain.core.Flow.consultSource(config, kind)})"
    }
    if (call.name == com.arcana.brain.core.TODO) {
        val list = com.arcana.brain.core.Todos.parse((args["items"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        return if (list.isEmpty()) "clear the list" else "${list.size} items, ${com.arcana.brain.core.Todos.doneCount(list)} done"
    }
    val key = listOf("command", "url", "question", "from", "path", "query", "pattern", "name", "glob", "title", "folder", "reason", "seconds").filter { args[it] != null }
    if (key.isEmpty()) return ""
    // a search names what it looks for and where; the others have one subject
    return key.take(2).joinToString("  ") { (args[it] as? JsonPrimitive)?.contentOrNull.orEmpty().replace('\n', ' ') }.trim()
}

/** The result of a tool, as a quiet line (or two) under the call: ✓ with what happened, ✕ with why not. */
private fun toolResultLines(message: ChatMessage, plain: Boolean, width: Int, entry: Int = -1): List<Line> {
    val text = message.content.trim()
    // the task list is shown whole: it is the plan the user follows along
    if (message.toolName == com.arcana.brain.core.TODO && !text.startsWith("Error:")) {
        val items = text.lineSequence().takeWhile { it.firstOrNull()?.isDigit() == true }.map { sanitizeForTerminal(it) }.toList()
        val summary = text.lineSequence().drop(items.size).firstOrNull().orEmpty()
        return items.map { l -> Line("    ${if (plain) l else l.replace("[x]", "☑").replace("[~]", "▸").replace("[ ]", "☐")}".take(width), if ("[x]" in l) Palette.Green else if ("[~]" in l) Palette.Cta else Palette.Muted, entry = entry) } +
            listOf(Line("    ${sanitizeForTerminal(summary)}".take(width), Palette.Muted, entry = entry), Line(""))
    }
    val failed = text.startsWith("Error:") || text.startsWith("The user denied") || text.startsWith("The user did not allow") ||
        text.startsWith("Timed out") || (text.startsWith("exit code ") && !text.startsWith("exit code 0"))
    val first = sanitizeForTerminal(text.lineSequence().firstOrNull().orEmpty())
    val size = if (text.length > 80 || '\n' in text) "  (${text.length} characters)" else ""
    val mark = if (plain) (if (failed) "x" else "ok") else if (failed) "✕" else "✓"
    val body = "  ${if (plain) "->" else "↳"} $mark ${first.take((width - 8 - size.length).coerceAtLeast(10))}$size"
    // what the tool left out, said plainly: the model was not given it, and a character count hides that
    val cut = com.arcana.brain.core.ToolCuts.marker(text)
    val lines = mutableListOf(Line(body.take(width), if (failed) Palette.Red else Palette.Muted, entry = entry))
    if (cut != null) lines += Line("    ${if (plain) "[cut]" else "✂"} ${sanitizeForTerminal(cut)}".take(width), Palette.Orange, entry = entry)
    return lines + Line("")
}

/** The lines of a change waiting for approval, as many as fit the panel. */
/** The lines of a change waiting for an answer, cut to [max] (with a note of how many are left out); a long diff is cut short on a small screen. */
private fun approvalLines(a: ChatSession.Approval, max: Int): List<String> =
    if (a.lines.size <= max) a.lines else a.lines.take(max - 1) + "… ${a.lines.size - max + 1} more lines"

/** How many lines of a change fit above the buttons: about a third of the screen, at least 8. */
@Composable
private fun approvalMax(reserve: Int = 0): Int = (LocalContentHeight.current / 3 - reserve).coerceIn(5, 24)

private fun approvalActions(a: ChatSession.Approval) = listOfNotNull(
    ActionItem("y", a.yes, accent = Palette.Cta),
    ActionItem("n", if (a.tool == "access" || a.tool == "steps" || a.tool == "memory" || a.tool == "mode" || a.tool == "pull") "Not now" else "Deny"),
    if (a.always) ActionItem("a", "Always this session") else null,
)

/** A request to save the chat: what to save, and where to go afterwards (when it came from the leave question). */
private data class SaveRequest(val after: Screen?, val lastAnswerOnly: Boolean = false)

/** The whole conversation as Markdown: a heading per speaker. */
private fun transcript(messages: List<ChatMessage>, model: String): String =
    messages.filter { it.role != "tool" && !it.displayOnly && !(it.toolCalls.isNotEmpty() && it.content.isBlank()) }.joinToString("\n\n") {
        val attached = (it.imageNames + it.files.map { f -> f.name } + it.noteNames.map { n -> "[[${n.removeSuffix(".md")}]]" }).let { a -> if (a.isEmpty()) "" else "\n\n_Attached: ${a.joinToString(", ")}_" }
        val sources = if (it.sources.isEmpty()) "" else "\n\n_Sources: ${it.sources.joinToString(", ") { s -> "[[${s.removeSuffix(".md")}]]" }}_"
        if (Oracul.isOpinion(it)) "## ${Oracul.title(it)}\n\n${Oracul.body(it)}"
        else if (Compaction.isSummary(it)) "## Summary of the earlier conversation (compacted)\n\n${Compaction.summaryText(it)}"
        else "## ${if (it.role == "user") "You" else model}\n\n${it.content.trim()}$attached$sources"
    }

/** The buttons under the message field. */
private fun chatActions(browsing: Boolean, hasEntries: Boolean, hasChanges: Boolean = false): List<ActionItem> =
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

/** The narrowest an answer column may be before the comparison is stacked instead. */
internal const val MIN_COLUMN = 38

/** How many answers fit in a row of [width] cells: up to three, each at least [MIN_COLUMN] wide with a 3-cell gutter between them. */
internal fun columnCount(answers: Int, width: Int): Int = minOf(answers, 3, (width + 3) / (MIN_COLUMN + 3))

/** What a line is made of, as runs (so two lines can be joined into one row), and how many cells it takes. */
private fun runsOf(line: Line): List<Run> = line.runs ?: listOf(Run(line.text, if (line.color == Color.Unspecified) Palette.ConsoleText else line.color, line.background, if (line.bold) TextStyle.Bold else TextStyle.Unspecified))

/** The colour of the n-th answer of a comparison: the main model's is the accent, the Oracul models' follow the palette. Used by the column pills and the title. */
private fun partColor(comparison: Oracul.Comparison, index: Int): Color {
    val palette = listOf(Palette.Blue, Palette.Cta, Palette.Green)
    return if (comparison.answers[index].main) Palette.Accent else palette[(index - 1).coerceAtLeast(0) % palette.size]
}

/** The comparison's title with each model name in the colour of its column and the judge's in the verdict's orange; `Ψ Comparison` keeps the title's blue and the words between the names are muted. */
internal fun comparisonTitleRuns(title: String, comparison: Oracul.Comparison): List<Run> {
    // `Ψ Comparison` keeps the title's blue; what follows is muted, with the names coloured
    val head = title.indexOf(" · ").let { if (it < 0) 0 else it }
    val judgedAt = title.lastIndexOf("judged by ").let { if (it < 0) title.length else it + "judged by ".length }
    val spans = ArrayList<Triple<Int, Int, Color>>()
    spans += Triple(0, head, Palette.Blue)
    var cursor = head
    comparison.answers.forEachIndexed { i, part ->
        val at = title.indexOf(part.name, cursor)
        if (at >= 0 && at + part.name.length <= judgedAt) { spans += Triple(at, at + part.name.length, partColor(comparison, i)); cursor = at + part.name.length }
    }
    if (judgedAt < title.length) spans += Triple(judgedAt, minOf(title.length, judgedAt + comparison.judge.length), Palette.Orange)
    val runs = ArrayList<Run>()
    var at = 0
    for ((from, to, color) in spans) {
        if (to <= from) continue
        if (from > at) runs += Run(title.substring(at, from), Palette.Muted, Color.Unspecified, TextStyle.Bold)
        runs += Run(title.substring(from, to), color, Color.Unspecified, TextStyle.Bold)
        at = to
    }
    if (at < title.length) runs += Run(title.substring(at), Palette.Muted, Color.Unspecified, TextStyle.Bold)
    return runs
}

/**
 * A comparison as columns: a coloured pill with the model's name over each answer (the main model's, then the Oracul models', each in its
 * own colour), the answers next to each other with a dim bar between them, and the judge's verdict under them across the whole width.
 * More answers than columns continue in further rows of columns.
 */
internal fun sideBySide(comparison: Oracul.Comparison, entry: Int, config: AppConfig, width: Int): List<Line> = buildList {
    val plain = config.plainGlyphs
    val columns = columnCount(comparison.answers.size, width)
    val gutter = 3
    val cw = (width - gutter * (columns - 1)) / columns
    fun pill(text: String, color: Color) = Run(" $text ", Palette.Ink, color, TextStyle.Bold)
    comparison.answers.chunked(columns).forEach { rowOfParts ->
        val cells = rowOfParts.map { part ->
            val index = comparison.answers.indexOf(part)
            val color = partColor(comparison, index)
            val glyph = if (plain) "*" else if (part.main) ModelIcon.of(part.name) else Oracul.GLYPH
            val label = sanitizeForTerminal("$glyph ${part.name} · ${if (part.main) "main" else "oracul"}").take(cw - 2)
            val rule = Line((if (plain) "-" else "─").repeat(cw), color, entry = entry)
            val body = renderMarkdown(parseMarkdown(part.text), cw, entry = { entry }, rich = config.renderMarkdown, plainGlyphs = plain)
                .dropWhile { it.text.isBlank() && it.runs == null }.dropLastWhile { it.text.isBlank() && it.runs == null }
            listOf(Line(label, color, bold = true, entry = entry, runs = listOf(pill(label, color)))) + rule + body
        }
        for (row in 0 until (cells.maxOf { it.size })) {
            val runs = ArrayList<Run>()
            val text = StringBuilder()
            cells.forEachIndexed { c, cell ->
                val line = cell.getOrNull(row)
                val own = if (line == null) emptyList() else runsOf(line)
                val used = line?.let { l -> if (l.runs != null) l.runs.sumOf { it.text.length } else l.text.length } ?: 0
                if (c > 0) { runs += Run(" ${if (plain) "|" else "│"} ", Palette.ConsoleDim); text.append(" | ") }
                runs += own
                line?.let { text.append(it.text) }
                if (c < cells.size - 1 && used < cw) { runs += Run(" ".repeat(cw - used), Palette.ConsoleText, line?.fill ?: Color.Unspecified); text.append(" ".repeat(cw - used)) }
            }
            add(Line(text.toString(), entry = entry, runs = runs))
        }
        add(Line("", entry = entry))
    }
    // the judge speaks across the whole width, in a colour of its own so it is the thing the eye goes to
    val verdictLabel = sanitizeForTerminal("${if (plain) "=" else "⚖"} Verdict · ${comparison.judge}").take(width - 2)
    add(Line(verdictLabel, Palette.Orange, bold = true, entry = entry, runs = listOf(pill(verdictLabel, Palette.Orange))))
    addAll(renderMarkdown(parseMarkdown(comparison.verdict), width, entry = { entry }, rich = config.renderMarkdown, plainGlyphs = plain))
}
