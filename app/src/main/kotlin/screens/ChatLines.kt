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

// How messages are drawn in the conversation: replies, thoughts, sources, tool results, the Oracul's blocks, comparisons side by side, approvals. Split out of Chat.kt (proposal 0007, phase 8).

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

/** The lines of one message; [entry] is the number of its first select-mode entry (a reply uses one per Markdown block). */
internal fun messageLines(app: AppContext, message: ChatMessage, entry: Int, width: Int, applyHint: Boolean = false): List<Line> {
    if (Compaction.isSummary(message)) return summaryLines(message, entry, app.config.plainGlyphs, width)
    OraculSteps.parse(message)?.let { return oraculStepLines(it, entry, app.config.plainGlyphs, width) }
    if (Oracul.isOpinion(message)) return opinionLines(message, entry, app.config, width, applyHint)
    // what Arcana sent in the user's name: marked as a system message (or left out), never shown as "You"
    if (Oracul.isSystemPrompt(message)) {
        if (app.config.systemMessages == "hidden") return emptyList()
        return buildList {
            val source = Oracul.systemSource(message) ?: "Arcana"
            add(Line(if (app.config.plainGlyphs) "[system] $source -> model" else "⚙ System · $source → model", Palette.ConsoleTool, bold = true))
            wrapText(sanitizeForTerminal(message.content), (width - 2).coerceAtLeast(10)).forEach { add(Line("  $it", Palette.Muted)) }
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
            wrapText(sanitizeForTerminal(message.content), width).forEach { add(Line(it, Palette.UserText, background = band, entry = entry)) }
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
            message.toolCalls.forEachIndexed { i, call -> add(Line("${if (config.plainGlyphs) "[tool]" else "⚙"} ${call.name}  ${sanitizeForTerminal(callSummary(call, config))}".take(width), if (call.name == com.arcana.brain.core.TODO) Palette.Todo else Palette.ConsoleTool, bold = true, entry = entry + blocks + i)) }
            // the notes the answer was built from, when the vault was searched
            if (message.sources.isNotEmpty()) {
                addAll(sourceLines(message, config.plainGlyphs, width))
                // notes longer than Chars per note were shown to the model only in part: the answer may miss what is further down
                if (message.sourcesCut.isNotEmpty()) wrapText("${if (config.plainGlyphs) "!" else "⚠"} Only the start was read of: " + message.sourcesCut.joinToString(", ") { sanitizeForTerminal(it.removeSuffix(".md")) } + " (Settings → Search → Chars per note)", width).forEach { add(Line(it, Palette.Warn)) }
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
    return listOf(Line(header, Palette.ConsoleDim, bold = true)) + wrapText(sanitizeForTerminal(text), (width - 2).coerceAtLeast(10)).map { Line("  $it", Palette.ConsoleDim) } + Line("")
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
internal fun oraculStepLines(step: OraculSteps.Step, entry: Int, plain: Boolean, width: Int): List<Line> {
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
internal fun opinionLines(message: ChatMessage, entry: Int, config: AppConfig, width: Int, applyHint: Boolean): List<Line> = buildList {
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
internal fun summaryLines(message: ChatMessage, entry: Int, plain: Boolean, width: Int): List<Line> = buildList {
    val rule = if (plain) "-" else "─"
    val title = " ${if (plain) "" else "◌ "}compacted: the model sees this summary instead of the messages above "
    add(Line((rule.repeat(3) + title + rule.repeat(width)).take(width), Palette.ConsoleDim, bold = true, entry = entry))
    wrapText(sanitizeForTerminal(Compaction.summaryText(message)), width).forEach { add(Line(it, Palette.ConsoleDim, entry = entry)) }
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
internal fun callSummary(call: ToolCall, config: AppConfig? = null): String {
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
internal fun toolResultLines(message: ChatMessage, plain: Boolean, width: Int, entry: Int = -1): List<Line> {
    val text = message.content.trim()
    // the task list is shown whole: it is the plan the user follows along
    if (message.toolName == com.arcana.brain.core.TODO && !text.startsWith("Error:")) {
        val items = text.lineSequence().takeWhile { it.firstOrNull()?.isDigit() == true }.map { sanitizeForTerminal(it) }.toList()
        val summary = text.lineSequence().drop(items.size).firstOrNull().orEmpty()
        return items.map { l -> Line("    ${if (plain) l else l.replace("[x]", "☑").replace("[~]", "▸").replace("[ ]", "☐")}".take(width), Palette.Todo, bold = "[~]" in l, entry = entry) } +
            listOf(Line("    ${sanitizeForTerminal(summary)}".take(width), Palette.Todo, entry = entry), Line(""))
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
internal fun approvalLines(a: ChatSession.Approval, max: Int): List<String> =
    if (a.lines.size <= max) a.lines else a.lines.take(max - 1) + "… ${a.lines.size - max + 1} more lines"

/** How many lines of a change fit above the buttons: about a third of the screen, at least 8. */
@Composable
internal fun approvalMax(reserve: Int = 0): Int = (LocalContentHeight.current / 3 - reserve).coerceIn(5, 24)

internal fun approvalActions(a: ChatSession.Approval) = listOfNotNull(
    ActionItem("y", a.yes, accent = Palette.Cta),
    ActionItem("n", if (a.tool == "access" || a.tool == "steps" || a.tool == "memory" || a.tool == "mode" || a.tool == "pull") "Not now" else "Deny"),
    if (a.always) ActionItem("a", "Always this session") else null,
)

/** The whole conversation as Markdown: a heading per speaker. */
internal fun transcript(messages: List<ChatMessage>, model: String): String =
    messages.filter { it.role != "tool" && !it.displayOnly && !(it.toolCalls.isNotEmpty() && it.content.isBlank()) }.joinToString("\n\n") {
        val attached = (it.imageNames + it.files.map { f -> f.name } + it.noteNames.map { n -> "[[${n.removeSuffix(".md")}]]" }).let { a -> if (a.isEmpty()) "" else "\n\n_Attached: ${a.joinToString(", ")}_" }
        val sources = if (it.sources.isEmpty()) "" else "\n\n_Sources: ${it.sources.joinToString(", ") { s -> "[[${s.removeSuffix(".md")}]]" }}_"
        if (Oracul.isOpinion(it)) "## ${Oracul.title(it)}\n\n${Oracul.body(it)}"
        else if (Compaction.isSummary(it)) "## Summary of the earlier conversation (compacted)\n\n${Compaction.summaryText(it)}"
        else "## ${if (it.role == "user") "You" else model}\n\n${it.content.trim()}$attached$sources"
    }

/** The narrowest an answer column may be before the comparison is stacked instead. */
internal const val MIN_COLUMN = 38

/** How many answers fit in a row of [width] cells: up to three, each at least [MIN_COLUMN] wide with a 3-cell gutter between them. */
internal fun columnCount(answers: Int, width: Int): Int = minOf(answers, 3, (width + 3) / (MIN_COLUMN + 3))

/** What a line is made of, as runs (so two lines can be joined into one row), and how many cells it takes. */
internal fun runsOf(line: Line): List<Run> = line.runs ?: listOf(Run(line.text, if (line.color == Color.Unspecified) Palette.ConsoleText else line.color, line.background, if (line.bold) TextStyle.Bold else TextStyle.Unspecified))

/** The colour of the n-th answer of a comparison: the main model's is the accent, the Oracul models' follow the palette. Used by the column pills and the title. */
internal fun partColor(comparison: Oracul.Comparison, index: Int): Color {
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
                val used = line?.let { l -> l.runs?.sumOf { it.text.length } ?: l.text.length } ?: 0
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
