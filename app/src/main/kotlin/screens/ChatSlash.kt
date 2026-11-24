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

// The Chat's "/" menu (commands, skills, tools, their state badges) and its key map. Split out of Chat.kt (proposal 0007, phase 8).

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
internal val ToolExamples = mapOf(
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
    SlashItem("/context", "Context inspector: what the model was sent with the last request, part by part (system prompt, memory, tools, excerpts, history)", false),
    SlashItem("/thinking", "Thoughts pane: watch the model's thoughts under the conversation (toggle; not the effort, see /effort)", false),
    SlashItem("/status", "Status pane: model, sampling (editable) and context window in a right-side column (toggle; { wider, } narrower, off at the smallest)", false),
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
    "/status" -> if (config.statusPane) "ON" to Palette.Green else "OFF" to Palette.Dim
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
        "/status" to "status pane: model, sampling options (Ctrl+R edits them in it) and context window in a right-side column ({ wider and on, } narrower and off)",
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
        "/context" to "what the model was sent, part by part",
    )),
)
