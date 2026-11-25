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

// Thinking: the levels a model lists, the effort shown by the message field, the thinking pane and where it docks. Split out of Chat.kt (proposal 0007, phase 8).

/** The `/think` choices of the model in use (what [thinkOptions] gives for the values Ollama lists), for the Settings row, which cannot see the app. Set when the model's details are read. */
internal object ThinkLevels {
    @Volatile var options: List<String> = listOf("default")

    /** What the model does when nothing is sent, as Ollama reports it (`medium`, `on`, `off`), or blank when it does not say. */
    @Volatile var defaultWord: String = ""

    /** The level `on` really runs at for a model whose template names it (`xhigh` for a Qwen3.8 GGUF, which Ollama cannot send by name), or blank. */
    @Volatile var onLevel: String = ""
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
    // a theme with a think accent (Monochrome Accent) shades it from low to high instead of the green / orange / red scale
    val shaded = Palette.thinkShade(0.5) != null
    return when (value) {
        "off" -> Palette.Blue
        "on" -> if (shaded) Palette.thinkShade(0.5)!! else if (ThinkLevels.onLevel.isNotEmpty()) Palette.Red else Palette.Orange
        "default" -> if (defaultWord.isEmpty() || defaultWord == "default") Palette.Muted else thinkColor(defaultWord, options, "")
        else -> {
            val at = levels.indexOf(value)
            if (value.isNotEmpty() && value == ThinkLevels.onLevel) Palette.thinkShade(1.0) ?: Palette.Red
            else if (at < 0) Palette.Muted
            else {
                val t = if (levels.size <= 1) 0.5 else at.toDouble() / (levels.size - 1)
                if (shaded) Palette.thinkShade(t)!! else if (t < 0.34) Palette.Green else if (t < 0.8) Palette.Orange else Palette.Red
            }
        }
    }
}

/** `auto (medium)`: the model default with what it really is, so "auto" is never a mystery; just `auto` when Ollama does not say. */
internal fun autoWord(defaultWord: String): String = if (defaultWord.isEmpty()) "auto" else "auto ($defaultWord)"

/** The name of a thinking choice as a person reads it: `Model default`, `Off`, `On`, `Xhigh`. */
internal fun thinkLabel(value: String): String = when (value) { "default" -> "Model default"; "off" -> "Off"; "on" -> "On" + onSuffix(); else -> value.replaceFirstChar { it.uppercase() } }

internal fun onSuffix(): String = ThinkLevels.onLevel.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()

internal fun thinkText(setting: String): String = if (setting == "default") "MODEL DEFAULT" else setting.uppercase() + if (setting == "on") onSuffix() else ""

/** What the reasoning-effort control says and how it looks: [text] is `✦ think xhigh`, [width] the columns it takes with its key hint and a gap before it. */
internal class EffortView(val text: String, val color: com.jakewharton.mosaic.ui.Color, val width: Int)

/**
 * The reasoning-effort control at the right end of the message field's first row: `Ctrl+G ✦ think xhigh`. Its colour says the level at a glance, calm to hot ([thinkColor]: off blue,
 * lowest level green, middle orange, highest red), and it does not move. A level picked for the next message says so. Null for a model that cannot think, or when the field is too
 * narrow to share its row.
 */
internal fun effortView(app: AppContext, thinkingNow: Boolean, fieldWidth: Int): EffortView? {
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
internal fun EffortInline(view: EffortView) {
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

/** The most rows (frame included) the thinking strip takes while a dialog is open; the dialog gives up as many lines. */
internal const val DIALOG_STRIP_MAX = 6

/** The thinking pane's size range in percent of the main axis (`[` `]` step 5; the same as the Settings stepper). */
internal const val THINKING_MIN = 10

internal const val THINKING_MAX = 90

/** One docked column: its [side], its [width] in columns and what it draws. */
internal class DockColumn(val side: String, val width: Int, val content: @Composable () -> Unit)

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
internal fun thinkingWaitText(app: AppContext, gen: Generation, model: String): String {
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
internal fun thinkTitleWord(app: AppContext, chat: ChatSession, running: Boolean): String {
    if (app.thinkingValues.isEmpty()) return ""
    val options = thinkOptions(app.thinkingValues)
    val own = if (running) chat.thinkNow() else chat.messages.lastOrNull { Oracul.isRealUser(it) }?.think.orEmpty()
    val setting = own.ifEmpty { app.config.thinking }
    val effective = if (setting in options) setting else "default"
    return " · think " + if (effective == "default") autoWord(thinkDefaultWord(app.thinkingDefault)) else effective
}

/** The split window under the conversation: the model's thoughts as they stream (the end of them, [rows] rows), or the last thoughts it had when it is idle. */
@Composable
internal fun ThinkingPane(app: AppContext, gen: Generation, chat: ChatSession, rows: Int, model: String, modifier: Modifier = Modifier) {
    val running = gen.state == GenState.Running
    val text = (if (running) gen.liveThinking else chat.messages.lastOrNull { it.role == "assistant" && it.thinking.isNotBlank() }?.thinking.orEmpty()).trim()
    val width = panelWidth()
    val wrapped = if (text.isEmpty()) emptyList() else wrapWords(sanitizeForTerminal(text), width)
    val shown = wrapped.takeLast(rows)
    Panel("Thinking · $model" + thinkTitleWord(app, chat, running) + (if (running) " · live" else "") + (if (text.isEmpty()) "" else " · ${UsageStats.compact((if (running) maxOf(gen.liveThinkingChars, text.length) else text.length).toLong())} characters")) {
        // nothing to show yet: say so, wrapped to the pane (a side column is narrow)
        val empty = if (shown.isNotEmpty()) emptyList() else wrapWords(if (running) thinkingWaitText(app, gen, model) else "The thoughts of a reasoning model appear here while it works.", width)
        for (i in 0 until rows) {
            if (empty.isNotEmpty()) Label(empty.getOrNull(i) ?: " ", color = Palette.Dim)
            else if (shown.isNotEmpty()) Label(shown.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: " ", color = Palette.Muted)
        }
    }
}
