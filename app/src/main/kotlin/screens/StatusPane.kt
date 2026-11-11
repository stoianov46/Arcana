package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.ChatSession
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.Connection
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.app.design.*
import com.arcana.brain.core.Compaction
import com.arcana.brain.core.Sampling
import com.arcana.brain.core.UsageStats
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/*
 * The status pane: a column on the right of the Chat conversation that gathers what shapes the answers, in three sections: Model (its name, where it runs), Sampling (every option as
 * a value and a small slider, editable in place with Ctrl+R) and Context (how full the window is, drawn as ▰▰▰▰▰▰▰▱▱▱, and the compaction state: where auto-compact starts, how much room is
 * left before it, a summary being written). `{` makes it wider (and switches it on), `}` narrower (and, at the smallest size, off): the keys point the way the column's left edge moves.
 */

/** What `{` / `}` do to the pane: whether it is on, its width in percent, and what to tell the user (empty = nothing). */
internal class PaneStep(val on: Boolean, val percent: Int, val message: String)

/**
 * `{` (wider = true) switches the pane on when it is off, then widens it by 5 % up to [THINKING_MAX]; `}` narrows it by 5 % and, at [THINKING_MIN], switches it off. `}` does nothing while it is off.
 * The messages name the keys of the dock: on the left the keys swap (`}` points right, the way the pane's right edge moves).
 */
internal fun statusPaneStep(on: Boolean, percent: Int, wider: Boolean, left: Boolean = false): PaneStep {
    val w = if (left) '}' else '{'
    val n = if (left) '{' else '}'
    val now = percent.coerceIn(THINKING_MIN, THINKING_MAX)
    if (!on) {
        if (!wider) return PaneStep(false, now, "")
        return PaneStep(true, now, "Status pane on at $now % ($w wider, $n narrower, step 5 %; $n at $THINKING_MIN % hides it).")
    }
    if (wider && now >= THINKING_MAX) return PaneStep(true, now, "The status pane is already at its widest: $now %.")
    if (!wider && now <= THINKING_MIN) return PaneStep(false, THINKING_MIN, "Status pane off ($w turns it on again at $THINKING_MIN %).")
    val next = (if (wider) (now / 5 + 1) * 5 else ((now + 4) / 5 - 1) * 5).coerceIn(THINKING_MIN, THINKING_MAX)
    return PaneStep(true, next, "Status pane: $next % ($w wider, $n narrower, step 5 %; $n at $THINKING_MIN % hides it).")
}

/** How many of [cells] a slider fills for [value] inside [min]..[max]: none at the minimum, at least one for anything above it, all at the maximum. */
internal fun sliderCells(value: Double, min: Double, max: Double, cells: Int): Int {
    if (cells <= 0 || max <= min) return 0
    val share = ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    val n = Math.round(share * cells).toInt().coerceIn(0, cells)
    return if (n == 0 && value > min + 1e-9) 1 else n
}

/** A bar of [cells] cells with [filled] on: the slim `▰▱` (the plain-glyph mode uses `#` and `-`). */
internal fun slimBar(filled: Int, cells: Int, plain: Boolean): Pair<String, String> {
    val n = filled.coerceIn(0, cells)
    return (if (plain) "#" else Chart.BarStyle.Slim.on.toString()).repeat(n) to (if (plain) "-" else Chart.BarStyle.Slim.off.toString()).repeat(cells - n)
}

/** The cells of the context bar: [used] are filled (`▰`), [room] is the stretch from the fill up to the auto-compact line (`▱` in its own colour), [free] is the rest of the window (`▱`, quiet). */
internal class ContextCells(val used: Int, val room: Int, val free: Int)

/**
 * Splits a bar of [cells] cells for a window that is [share] full (0 to 1) with auto-compact at [thresholdPercent] % (0 = off, so no room cells). Anything above zero fills at least one cell; once the
 * fill has reached the auto-compact line there is no room left and the rest is free.
 */
internal fun contextCells(share: Double, thresholdPercent: Int, cells: Int): ContextCells {
    if (cells <= 0) return ContextCells(0, 0, 0)
    val used = if (share <= 0.0) 0 else Math.round(share.coerceIn(0.0, 1.0) * cells).toInt().coerceIn(1, cells)
    val line = if (thresholdPercent <= 0) 0 else Math.round(thresholdPercent.coerceIn(0, 100) / 100.0 * cells).toInt().coerceIn(0, cells)
    val room = (line - used).coerceAtLeast(0)
    return ContextCells(used, room, cells - used - room)
}

/** What the context section says about compaction; each field is already text, [warn] says the window is at or past the auto-compact line. */
internal class CompactionView(val windowText: String, val usedText: String, val ruleText: String, val roomText: String, val warn: Boolean)

/**
 * Compaction in words: [used] tokens of a [window] (0 = not known), auto-compact at [autoPercent] % (0 = off), [estimated] after a compaction. The room is what is left before the line, or
 * "due" when the next message compacts first.
 */
internal fun compactionView(used: Long?, estimated: Boolean, window: Int, autoPercent: Int): CompactionView {
    val windowText = if (window > 0) "window ${UsageStats.contextSize(window.toLong())}" else "window unknown"
    val usedText = if (used == null) "no reply yet" else "${if (estimated) "~" else ""}${UsageStats.compact(used)} used"
    if (autoPercent <= 0) return CompactionView(windowText, usedText, "auto-compact off", "/compact does it by hand", false)
    val line = window.toLong() * autoPercent / 100
    return when {
        window <= 0 || used == null -> CompactionView(windowText, usedText, "auto-compact at $autoPercent %", "", false)
        used >= line -> CompactionView(windowText, usedText, "auto-compact at $autoPercent %", "due: the next message compacts first", true)
        else -> CompactionView(windowText, usedText, "auto-compact at $autoPercent %", "~${UsageStats.compact(line - used)} left before it", false)
    }
}

/** The colour of the context bar: green while there is room, orange from 60 %, red from 85 % (the same as the bottom bar's gauge). */
internal fun contextColor(share: Double): Color = when {
    share >= 0.85 -> Palette.Red
    share >= 0.60 -> Palette.Orange
    else -> Palette.Green
}

/** Where the thinking pane can go: a strip under or over the conversation, or a column on a side. */
internal val THINKING_PLACES = listOf("bottom", "top", "left", "right")

/** The rows of the pane that can be selected, top to bottom: model, sampling mode and options, thinking, then the context settings. */
internal object PaneRows {
    const val MODEL = 0
    const val DOCK = 1
    const val MODE = 2
    const val FIRST_PARAM = 3
    val EFFORT = FIRST_PARAM + Sampling.params.size
    val VIEW = EFFORT + 1
    val PANE = EFFORT + 2
    val PLACE = EFFORT + 3
    val WINDOW = EFFORT + 4
    val AUTO = EFFORT + 5
    val KEEP = EFFORT + 6

    /** The rows that a short window may leave out, most useful first. The rest (model, mode, the options) are always there. */
    val OPTIONAL = listOf(EFFORT, WINDOW, AUTO, KEEP, VIEW, PANE, PLACE)
    val THINKING = listOf(EFFORT, VIEW, PANE, PLACE)
}

/** Which of [PaneRows.OPTIONAL] fit in [budget] screen rows: the effort costs two (its row and its bar) and the Thinking section one more (its title) when its first row is taken. */
internal fun pickRows(budget: Int, rows: List<Int> = PaneRows.OPTIONAL): Set<Int> {
    var left = budget
    var thinking = false
    val out = mutableSetOf<Int>()
    for (r in rows) {
        val cost = 1 + (if (r == PaneRows.EFFORT) 1 else 0) + (if (r in PaneRows.THINKING && !thinking) 1 else 0)
        if (cost <= left) { left -= cost; out += r; if (r in PaneRows.THINKING) thinking = true }
    }
    return out
}

/**
 * How many of [cells] the effort bar fills. One step per level the model lists (`low · medium · high`; `off` is the empty bar, `on` for a model with a switch only is the full one). A value of
 * `default` is drawn as the level the model really uses ([defaultWord]), so the bar always says where the reasoning stands; 0 when that is not known.
 */
internal fun effortCells(options: List<String>, value: String, defaultWord: String, cells: Int): Int {
    val target = if (value == "default") defaultWord else value
    if (cells <= 0 || target.isEmpty() || target == "off") return 0
    val levels = options.filter { it != "default" && it != "off" }
    val at = levels.indexOf(target)
    if (at < 0) return 0
    return Math.round((at + 1) * cells / levels.size.toDouble()).toInt().coerceIn(1, cells)
}

/** The context window sizes Settings offers (0 = the model's own), and the auto-compact choices (0 = off). */
internal val WINDOW_CHOICES = listOf(0, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576)
internal val AUTO_COMPACT_CHOICES = listOf(0, 70, 80, 90)
internal val THINKING_VIEW_CHOICES = listOf("summary", "full", "hidden")

/** The choice [dir] steps from [now], wrapping round; a value that is not in the list steps from the first one. */
internal fun <T> nextChoice(choices: List<T>, now: T, dir: Int): T = choices[(choices.indexOf(now).coerceAtLeast(0) + dir).mod(choices.size)]

/** The installed model [dir] steps from [current], wrapping round; null when no model is known. A model that is not installed steps from the first. */
internal fun nextModel(models: List<String>, current: String, dir: Int): String? =
    if (models.isEmpty()) null else models[(models.indexOf(current).coerceAtLeast(0) + dir).mod(models.size)]

/** `Model default`, `8k`, `128k`, `1M`: how a context window is written (the same words as Settings). */
internal fun windowLabel(tokens: Int): String = when {
    tokens <= 0 -> "Model default"
    tokens % 1_048_576 == 0 -> "${tokens / 1_048_576}M"
    tokens % 1024 == 0 -> "${tokens / 1024}k"
    else -> tokens.toString()
}

internal fun autoCompactLabel(percent: Int): String = if (percent <= 0) "Off" else "$percent %"

/**
 * What is edited in the status pane: the model, the sampling options and the context settings, one row each, saved at once like Settings (and used from the next message). The sampling rows do what the
 * `Ctrl+R` dialog does; the model and the context rows step through the same choices as Settings. [visible] is set by the pane: the rows that fit on screen, so the cursor never moves onto a row
 * that is cut off.
 */
class PaneEditor {
    var index by mutableIntStateOf(PaneRows.MODE)
    var typing by mutableStateOf(false)
    var notice by mutableStateOf<Notice?>(null)
    /** The rows on screen, in order, set by the pane: the cursor moves only between them, so a short window never puts it on a row that is cut off. */
    var visible: List<Int> = (0..PaneRows.KEEP).toList()
    val field = TextFieldState()

    fun open(at: Int = PaneRows.MODE) { index = if (at in visible) at else visible.firstOrNull { it >= at } ?: visible.last(); typing = false; notice = null; field.clear() }

    private fun param(at: Int = index) = Sampling.params.getOrNull(at - PaneRows.FIRST_PARAM)

    private fun installed(app: AppContext): List<String> = when (val c = app.connection) {
        is Connection.Online -> c.models
        is Connection.ModelMissing -> c.models
        else -> emptyList()
    }

    private fun toggleMode(app: AppContext) {
        val custom = app.config.samplingMode == Sampling.CUSTOM
        app.update(if (custom) Sampling.toModel(app.config) else Sampling.toCustom(app.config, app.modelParameters))
        notice = Notice(if (custom) "Sampling: model defaults, nothing is sent" else "Sampling: custom, your values are sent")
    }

    /** Moves a pane to [side] (the thinking pane also to the top or bottom strip); a side another pane holds is refused with the reason. */
    private fun dock(app: AppContext, id: PaneId, side: String) {
        val c = app.config
        val refused = if (Docks.enabled(c, id)) Docks.refusal(c, id, side) else null
        if (refused != null) return run { notice = Notice(refused, isError = true) }
        app.update(if (id == PaneId.Status) c.copy(statusPanePlacement = side) else c.copy(thinkingPanePlacement = side))
        notice = Notice("${id.title.replaceFirstChar { it.uppercase() }}: ${side}")
    }

    private fun change(app: AppContext, dir: Int) {
        val c = app.config
        val p = param()
        when {
            index == PaneRows.MODEL -> {
                val models = installed(app)
                val next = nextModel(models, c.model, dir) ?: return run { notice = Notice("No installed model is known: is Ollama running?", isError = true) }
                app.update(c.copy(model = next))
                notice = Notice("Model $next (${models.indexOf(next) + 1} of ${models.size})")
            }
            index == PaneRows.DOCK -> dock(app, PaneId.Status, nextChoice(Docks.SIDES, c.statusPanePlacement, dir))
            index == PaneRows.PLACE -> dock(app, PaneId.Thinking, nextChoice(THINKING_PLACES, c.thinkingPanePlacement, dir))
            index == PaneRows.MODE -> toggleMode(app)
            p != null -> {
                val now = Sampling.step(Sampling.toCustom(c, app.modelParameters), p, dir, app.modelParameters)
                app.update(now)
                notice = Notice("${p.label} ${Sampling.get(now, p.key)?.let(p::format) ?: "is the model's own again"}")
            }
            index == PaneRows.EFFORT -> {
                if (app.thinkingValues.isEmpty()) return run { notice = Notice("${c.model} does not offer a thinking switch (Ollama lists none), so nothing is sent.", isError = true) }
                val n = nextChoice(thinkOptions(app.thinkingValues), c.thinking, dir)
                app.update(c.copy(thinking = n))
                notice = Notice("Thinking effort ${thinkLabel(n).lowercase()}")
            }
            index == PaneRows.VIEW -> { val n = nextChoice(THINKING_VIEW_CHOICES, c.thinkingView, dir); app.update(c.copy(thinkingView = n)); notice = Notice("Thinking in Chat: $n") }
            index == PaneRows.PANE -> {
                val refused = if (c.thinkingPane) null else Docks.refusal(c, PaneId.Thinking)
                if (refused != null) notice = Notice(refused, isError = true)
                else { app.update(c.copy(thinkingPane = !c.thinkingPane)); notice = Notice("Thinking pane ${if (c.thinkingPane) "off" else "on"}") }
            }
            index == PaneRows.WINDOW -> { val n = nextChoice(WINDOW_CHOICES, c.contextTokens, dir); app.update(c.copy(contextTokens = n)); notice = Notice("Context window ${windowLabel(n)}") }
            index == PaneRows.AUTO -> { val n = nextChoice(AUTO_COMPACT_CHOICES, c.autoCompactPercent, dir); app.update(c.copy(autoCompactPercent = n)); notice = Notice("Auto-compact ${autoCompactLabel(n).lowercase()}") }
            index == PaneRows.KEEP -> { val n = (c.compactKeepPercent + dir * 5).coerceIn(0, 60); app.update(c.copy(compactKeepPercent = n)); notice = Notice("Keep after compacting $n %") }
        }
    }

    /** Enter: a number is typed for a sampling option and for Keep after compacting; every other row steps to its next choice. */
    private fun enter(app: AppContext) {
        val p = param()
        when {
            p != null -> { field.set(if (app.config.samplingMode == Sampling.CUSTOM) Sampling.get(app.config, p.key)?.let(p::format).orEmpty() else ""); typing = true; notice = null }
            index == PaneRows.KEEP -> { field.set(app.config.compactKeepPercent.toString()); typing = true; notice = null }
            else -> change(app, 1)
        }
    }

    private fun commit(app: AppContext) {
        val p = param()
        if (p != null) {
            val v = Sampling.parse(field.text, p)
            app.update(Sampling.set(Sampling.toCustom(app.config, app.modelParameters), p.key, v))
            notice = Notice("${p.label} ${v?.let(p::format) ?: "is the model's own again"}")
        } else {
            val n = field.text.trim().toIntOrNull() ?: throw IllegalArgumentException("Keep after compacting must be a whole number")
            require(n in 0..60) { "Keep after compacting must be 0 to 60" }
            app.update(app.config.copy(compactKeepPercent = n))
            notice = Notice("Keep after compacting $n %")
        }
        typing = false
    }

    /** What a key does; [close] leaves the editing. True when the key was used. */
    fun handle(key: Key, app: AppContext, close: () -> Unit): Boolean {
        if (typing) {
            when {
                key == Key.Escape -> { typing = false; notice = null }
                key == Key.Enter -> try { commit(app) } catch (e: IllegalArgumentException) { notice = Notice(e.message ?: "Invalid value", isError = true) }
                else -> return field.onKey(key)
            }
            return true
        }
        when {
            key == Key.Escape -> close()
            key == Key.Up || key.char == 'k' -> { index = visible.lastOrNull { it < index } ?: index; notice = null }
            key == Key.Down || key.char == 'j' -> { index = visible.firstOrNull { it > index } ?: index; notice = null }
            key.char == 'm' -> toggleMode(app)
            key.char == 'r' -> { app.update(Sampling.toModel(app.config)); notice = Notice("Sampling: back to the model's defaults") }
            key == Key.Left -> change(app, -1)
            key == Key.Right -> change(app, 1)
            key == Key.Enter -> enter(app)
            else -> return false
        }
        return true
    }
}

/** A shorter name for an option, for a narrow column. */
internal fun paneName(p: Sampling.Param): String = when (p.key) {
    "temperature" -> "Temp"
    "presence_penalty" -> "Presence"
    "repeat_penalty" -> "Repeat"
    else -> p.label
}

/** A section title with a rule after it and [right] set at the end: `Sampling ───────── Custom`. */
@Composable
private fun SectionRule(title: String, right: String = "", rightColor: Color = Palette.Muted, selected: Boolean = false) {
    val width = panelWidth()
    val plain = LocalPlainGlyphs.current
    val rule = ((width - title.length - right.length - (if (right.isEmpty()) 1 else 2)).coerceAtLeast(1))
    Row {
        Label(if (selected) "» " else "", color = Palette.Accent)
        Label(title, color = if (selected) Palette.Accent else Palette.Muted, textStyle = TextStyle.Bold)
        Label(" " + (if (plain) "-" else "─").repeat((rule - if (selected) 2 else 0).coerceAtLeast(1)) + (if (right.isEmpty()) "" else " "), color = Palette.Dim)
        if (right.isNotEmpty()) Label(right, color = rightColor, textStyle = TextStyle.Bold)
    }
}

/** One selectable `name   value` row of the pane. */
@Composable
private fun ValueRow(name: String, value: String, selected: Boolean, tone: Color = Palette.Text) {
    val width = panelWidth()
    val bg = if (selected) Palette.Selected else Color.Unspecified
    val left = (width - value.length).coerceAtLeast(2)
    Row {
        Label(((if (selected) "» " else "  ") + name).padEnd(left).take(left - 1) + " ", color = if (selected) Palette.Text else Palette.Muted, background = bg, textStyle = if (selected) TextStyle.Bold else TextStyle.Unspecified)
        Label(value, color = tone, background = bg, textStyle = TextStyle.Bold)
    }
}

/**
 * The right-side status column. [rows] is the height it is given; [focused] is true while it is being edited ([d] holds the highlighted row and the typed value): the model, the sampling options
 * and the context settings can all be changed here, and the highlighted row is marked and the frame lit.
 */
@Composable
internal fun StatusPane(app: AppContext, chat: ChatSession, d: PaneEditor, focused: Boolean, rows: Int, modifier: Modifier = Modifier) {
    val plain = app.config.plainGlyphs
    val width = panelWidth()
    val c = app.config
    val own = app.modelParameters
    val custom = c.samplingMode == Sampling.CUSTOM
    // the height decides how much is shown: roomy = a slider under every option; compact = one row per option; tight = no blank rows, the model's state and the key hints are left out and the context rows that do not fit are dropped
    val avail = rows - 2
    val roomy = avail >= 37
    val tight = avail < 28
    // in a tight column only as many of the optional rows (effort, window, auto-compact, keep, the thinking display) as fit are drawn; the cursor moves between the drawn rows only
    val shown: Set<Int> = if (tight) pickRows(avail - 13 - 2) else PaneRows.OPTIONAL.toSet()
    SideEffect { d.visible = (0 until PaneRows.EFFORT).toList() + shown.sorted() }
    Panel(if (focused) "Status · editing" else "Status", modifier.height(rows), accent = if (focused) Palette.Accent else Palette.Muted) {
        // Model: selectable, ←→ step through the installed models
        SectionRule("Model")
        val modelSelected = focused && d.index == PaneRows.MODEL
        val model = modelsSegment(ModelIcon.label(c.model, plain), chat.consulting, c, plain, width, false)
        Row {
            Label(if (modelSelected) "» " else "", color = Palette.Accent)
            model.parts.forEachIndexed { i, part -> Label(if (i == 0 && !modelSelected) part.text.trim() else part.text.trim() + " ", color = part.color, background = if (modelSelected && i == 0) Palette.Selected else part.background, textStyle = if (part.bold) TextStyle.Bold else TextStyle.Unspecified) }
        }
        ValueRow("Side", c.statusPanePlacement.replaceFirstChar { it.uppercase() }, focused && d.index == PaneRows.DOCK)
        if (modelSelected) {
            val models = (app.connection as? Connection.Online)?.models ?: (app.connection as? Connection.ModelMissing)?.models.orEmpty()
            Label(if (models.isEmpty()) "  no models known" else "  ←→ choose · ${models.indexOf(c.model) + 1} of ${models.size}", color = Palette.Dim)
        } else if (!tight) modelStateSegment(app, plain)?.let { st -> Row { st.parts.forEach { Label(it.text.trim() + " ", color = it.color) } } }
        if (!tight) VGap()

        // Sampling: the header is the mode row, then one row per option
        // the same words as Settings and the dialog; "Defaults" only when the column is too narrow for "Model defaults"
        val modeText = Sampling.modeLabel(c.samplingMode).let { if (width - "Sampling".length - 3 >= it.length) it else Sampling.modeShort(c.samplingMode) }
        SectionRule("Sampling", modeText, if (custom) Palette.Accent else Palette.Green, selected = focused && d.index == PaneRows.MODE)
        val cells = (width - 2).coerceIn(8, 20)
        Sampling.params.forEachIndexed { i, p ->
            val selected = focused && d.index == PaneRows.FIRST_PARAM + i
            val set = if (custom) Sampling.get(c, p.key) else null
            val ownValue = own[p.key]?.toDoubleOrNull()
            val shown = set ?: ownValue
            val valueText = shown?.let(p::format) ?: "—"
            val tone = when { set != null -> Palette.Accent; ownValue != null -> Palette.Muted; else -> Palette.Dim }
            val bg = if (selected) Palette.Selected else Color.Unspecified
            val marker = if (selected) "» " else "  "
            if (roomy) {
                val name = marker + p.label
                Row {
                    Label(name.padEnd(width - valueText.length).take(width - valueText.length), color = if (selected) Palette.Text else Palette.Muted, background = bg, textStyle = if (selected) TextStyle.Bold else TextStyle.Unspecified)
                    Label(valueText, color = tone, background = bg, textStyle = if (set != null) TextStyle.Bold else TextStyle.Unspecified)
                }
                val (on, off) = slimBar(if (shown == null) 0 else sliderCells(shown, p.min, p.max, cells), cells, plain)
                Row { Label("  "); Label(on, color = tone); Label(off, color = Palette.Dim) }
            } else {
                val bar = 6
                val (on, off) = slimBar(if (shown == null) 0 else sliderCells(shown, p.min, p.max, bar), bar, plain)
                val left = width - valueText.length - bar - 2
                val name = marker + (if (marker.length + p.label.length > left) paneName(p) else p.label)
                Row {
                    Label(name.padEnd(left).take(left.coerceAtLeast(1)) + " ", color = if (selected) Palette.Text else Palette.Muted, background = bg, textStyle = if (selected) TextStyle.Bold else TextStyle.Unspecified)
                    Label(valueText + " ", color = tone, background = bg, textStyle = if (set != null) TextStyle.Bold else TextStyle.Unspecified)
                    Label(on, color = tone); Label(off, color = Palette.Dim)
                }
            }
        }
        if (!tight) VGap()

        // Thinking: the effort (what Ollama lists for this model), how the thoughts are shown in the chat, and the live pane
        if (PaneRows.THINKING.any { it in shown }) {
            SectionRule("Thinking")
            val options = thinkOptions(app.thinkingValues)
            val offered = app.thinkingValues.isNotEmpty()
            if (PaneRows.EFFORT in shown) {
                val defaultWord = thinkDefaultWord(app.thinkingDefault)
                val value = c.thinking.takeIf { it in options } ?: "default"
                val text = when {
                    !offered -> "not offered"
                    value == "default" -> autoWord(defaultWord).let { if (it.length + 9 > width) "auto" else it }
                    else -> thinkLabel(value)
                }
                ValueRow("Effort", text, focused && d.index == PaneRows.EFFORT, if (offered) thinkColor(value, options, defaultWord) else Palette.Dim)
                if (offered) {
                    // a progress bar of the effort: one step per level the model lists, filled up to the chosen one (the model's own level when it is left to the model), in the colour of that level
                    val barCells = (width - 2).coerceIn(8, 20)
                    val tone = thinkColor(value, options, defaultWord)
                    val filled = effortCells(options, value, defaultWord, barCells)
                    val (on, off) = slimBar(filled, barCells, plain)
                    Row { Label("  "); Label(on, color = tone); Label(off, color = Palette.Dim) }
                }
                if (chat.thinkNext.isNotEmpty() && !tight) Label("  next message: ${thinkLabel(chat.thinkNext).lowercase()}", color = Palette.Blue)
            }
            if (PaneRows.VIEW in shown) ValueRow("In chat", c.thinkingView.replaceFirstChar { it.uppercase() }, focused && d.index == PaneRows.VIEW)
            if (PaneRows.PANE in shown) ValueRow("Pane", if (c.thinkingPane) "On" else "Off", focused && d.index == PaneRows.PANE, if (c.thinkingPane) Palette.Green else Palette.Muted)
            if (PaneRows.PLACE in shown) ValueRow("Side", c.thinkingPanePlacement.replaceFirstChar { it.uppercase() }, focused && d.index == PaneRows.PLACE)
            if (!tight) VGap()
        }

        // Context: the bar, a key to it, and the three settings that decide it
        SectionRule("Context")
        val window = effectiveContext(c.contextTokens, app.modelContext, app.loadedContext)
        val usage = contextTokens(chat.messages)
        val view = compactionView(usage?.first?.toLong(), usage?.second ?: false, window, c.autoCompactPercent)
        val share = if (usage != null && window > 0) (usage.first.toDouble() / window).coerceIn(0.0, 1.0) else 0.0
        val barCells = (width - 6).coerceIn(10, 20)
        val known = usage != null && window > 0
        val cellsOf = contextCells(if (known) share else 0.0, c.autoCompactPercent, barCells)
        val tone = if (!known) Palette.Dim else contextColor(share)
        val ink = if (plain) "#" else Chart.BarStyle.Slim.on.toString()
        val hollow = if (plain) "-" else Chart.BarStyle.Slim.off.toString()
        // three tones in one bar: the filled context (green, orange, red by how full), the room up to the auto-compact line (blue), the rest of the window (quiet)
        Row {
            Label(ink.repeat(cellsOf.used), color = tone)
            Label(hollow.repeat(cellsOf.room), color = Palette.Blue)
            Label(hollow.repeat(cellsOf.free), color = Palette.Dim)
            Label(if (!known) "  —" else "  ${"%.0f".format(share * 100)}%", color = tone, textStyle = TextStyle.Bold)
        }
        // the key to the bar, each line starting with the glyph and colour of its part
        if (!tight) Row { Label(ink + " ", color = tone); Label(view.usedText + " of " + view.windowText.removePrefix("window "), color = Palette.Muted) }
        if (c.autoCompactPercent > 0 && !chat.compacting) {
            Row {
                Label(hollow + " ", color = if (view.warn) Palette.Orange else Palette.Blue)
                Label(if (view.warn) "due: compacts next" else if (view.roomText.isNotEmpty()) view.roomText.removeSuffix(" left before it") + " to compact" else "auto-compact ${c.autoCompactPercent} %", color = if (view.warn) Palette.Orange else Palette.Muted, textStyle = if (view.warn) TextStyle.Bold else TextStyle.Unspecified)
            }
        }
        if (chat.compacting) {
            val secs = ((System.currentTimeMillis() - chat.compactingSince) / 1000).coerceAtLeast(0)
            Label((if (plain) "" else "◌ ") + "summarizing the chat… ${secs}s" + if (chat.compactingAuto) " (auto)" else "", color = Palette.Orange, textStyle = TextStyle.Bold, wrap = true)
        }
        val ctxSettings = listOf(
            Triple(PaneRows.WINDOW, "Window", windowLabel(c.contextTokens).let { if (it.length + 9 > width) "Default" else it }),
            Triple(PaneRows.AUTO, "Auto-compact", autoCompactLabel(c.autoCompactPercent)),
            Triple(PaneRows.KEEP, "Keep", if (c.compactKeepPercent <= 0) "last 2" else "${c.compactKeepPercent} %"),
        )
        ctxSettings.filter { it.first in shown }.forEach { (row, name, value) -> ValueRow(name, value, focused && d.index == row, if (row == PaneRows.AUTO && c.autoCompactPercent <= 0) Palette.Muted else Palette.Text) }
        val summaries = chat.messages.count { Compaction.isSummary(it) }
        if (summaries > 0 && !tight) Label("compacted ×$summaries · older messages are summarized for the model", color = Palette.Dim, wrap = true)

        // typing a value, the result of the last change, and how to use it
        if (focused && d.typing) {
            val p = Sampling.params.getOrNull(d.index - PaneRows.FIRST_PARAM)
            TextInput(if (p != null) p.label + " · blank = the model's own" else "Keep after compacting · 0 to 60 %", d.field, true)
        }
        NoticeLine(d.notice)
        if (!tight) VGap()
        if (focused) Label(if (tight) "↑↓ ←→ Enter m r Esc" else "↑↓ move  ←→ change  Enter type  m mode  r reset  Esc done", color = Palette.Dim, wrap = true)
        else if (!tight) {
            Label("Ctrl+R  edit the values", color = Palette.Dim)
            Label("{ wider  } narrower", color = Palette.Dim)
        }
    }
}
