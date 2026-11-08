package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.core.Sampling
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/**
 * The Chat dialog for how the model picks its next word (`Ctrl+R`, `/sampling`): the same choices as Settings → Ollama → Sampling, changed from where the answers are read. The first row is
 * the mode (model defaults or custom), the others are the options of [Sampling.params]. Every change is saved at once, like in Settings, and used for the next message.
 */
class SamplingDialog {
    var index by mutableIntStateOf(0)
    var typing by mutableStateOf(false)
    var notice by mutableStateOf<Notice?>(null)
    val field = TextFieldState()

    fun open() { index = 0; typing = false; notice = null; field.clear() }

    /** The row's parameter; null for the first row, the mode. */
    private fun param(at: Int = index) = Sampling.params.getOrNull(at - 1)

    private fun toggleMode(app: AppContext, toast: (String) -> Unit) {
        val custom = app.config.samplingMode == Sampling.CUSTOM
        app.update(if (custom) Sampling.toModel(app.config) else Sampling.toCustom(app.config, app.modelParameters))
        toast(if (custom) "Model defaults: nothing is sent, the model's own settings apply." else "Custom: your values are sent with every message (blank = the model's own).")
    }

    /** What a key does; [close] leaves the dialog. True when the key was used. */
    fun handle(key: Key, app: AppContext, toast: (String) -> Unit, close: () -> Unit): Boolean {
        val p = param()
        if (typing) {
            when {
                key == Key.Escape -> { typing = false; notice = null }
                key == Key.Enter -> try {
                    val v = Sampling.parse(field.text, p!!)
                    app.update(Sampling.set(Sampling.toCustom(app.config, app.modelParameters), p.key, v))
                    typing = false; notice = Notice("${p.label} ${v?.let(p::format) ?: "is the model's own again"}")
                } catch (e: IllegalArgumentException) { notice = Notice(e.message ?: "Invalid value", isError = true) }
                else -> return field.onKey(key)
            }
            return true
        }
        when {
            key == Key.Escape -> close()
            key == Key.Up || key.char == 'k' -> { index = (index - 1).coerceAtLeast(0); notice = null }
            key == Key.Down || key.char == 'j' -> { index = (index + 1).coerceAtMost(Sampling.params.size); notice = null }
            key.char == 'm' -> toggleMode(app, toast)
            key.char == 'r' -> { app.update(Sampling.toModel(app.config)); notice = Notice("Back to the model's defaults") }
            key == Key.Left || key == Key.Right -> {
                val dir = if (key == Key.Right) 1 else -1
                if (p == null) toggleMode(app, toast)
                else {
                    val c = Sampling.step(Sampling.toCustom(app.config, app.modelParameters), p, dir, app.modelParameters)
                    app.update(c)
                    notice = Notice("${p.label} ${Sampling.get(c, p.key)?.let(p::format) ?: "is the model's own again"}")
                }
            }
            key == Key.Enter -> if (p == null) toggleMode(app, toast) else {
                field.set(if (app.config.samplingMode == Sampling.CUSTOM) Sampling.get(app.config, p.key)?.let(p::format).orEmpty() else "")
                typing = true; notice = null
            }
            else -> return false
        }
        return true
    }
}

/** What the dialog says under its rows for the highlighted one. */
private fun samplingHelp(app: AppContext, p: Sampling.Param?): String {
    val own = app.modelParameters
    val ownLine = if (own.isEmpty()) "${app.config.model} lists no sampling defaults of its own, so Ollama's general ones apply."
        else "${app.config.model}'s own: " + own.entries.joinToString(", ") { "${it.key} ${it.value}" } + "."
    val what = p?.help ?: "Model defaults sends nothing, so the model's own settings apply. Custom sends your values for the chat model (helper models always use their own)."
    return "$what $ownLine"
}

@Composable
fun SamplingPanel(app: AppContext, d: SamplingDialog) {
    val c = app.config
    val own = app.modelParameters
    val custom = c.samplingMode == Sampling.CUSTOM
    ScreenHeader("Sampling", "${c.model} · how the next word is chosen · saved at once, used from the next message")
    VGap()
    Panel("Sampling") {
        val rows = listOf<Sampling.Param?>(null) + Sampling.params
        rows.forEachIndexed { i, p ->
            val selected = i == d.index
            val set = if (p == null || !custom) null else Sampling.get(c, p.key)
            val ownText = p?.let { own[it.key] }
            val name = p?.label ?: "Mode"
            val value = if (p == null) (if (custom) "Custom" else "Model defaults") else (set?.let(p::format) ?: ownText ?: "—")
            val meta = when {
                p == null -> if (custom) "your values are sent" else "nothing is sent"
                set != null -> if (ownText == null) "set by you" else if (ownText.toDoubleOrNull() == set) "same as the model's" else "set by you · model $ownText"
                ownText != null -> "the model's own"
                else -> "Ollama's default"
            }
            Row {
                Label(if (selected) "» " else "  ", color = Palette.Accent)
                Label(name.padEnd(18), color = if (selected) Palette.Text else Palette.Muted, textStyle = if (selected) TextStyle.Bold else TextStyle.Unspecified)
                Label(value.padEnd(16), color = if (p == null) (if (custom) Palette.Accent else Palette.Green) else if (set != null) Palette.Accent else Palette.Muted, textStyle = if (set != null || p == null) TextStyle.Bold else TextStyle.Unspecified)
                Label(meta, color = Palette.Dim)
            }
        }
        VGap()
        if (d.typing) {
            TextInput(Sampling.params[d.index - 1].label + "  ·  a number; blank = the model's own", d.field, true)
            VGap()
        }
        Hint(samplingHelp(app, Sampling.params.getOrNull(d.index - 1)))
        NoticeLine(d.notice)
    }
}

/**
 * The status-bar indicator of the sampling that shapes the answers: `sampling model T1 P0.95 K20 pres1.5` (the model's own values, quiet) or `sampling custom T0.7 P0.9 K20` (what Settings or the
 * `Ctrl+R` dialog set is bright, what is left to the model stays quiet). It shows the values the model really works with, so a quality change is traceable to a number.
 */
internal fun samplingSegment(app: AppContext): StatusSegment {
    val custom = app.config.samplingMode == Sampling.CUSTOM
    val parts = ArrayList<StatusPart>()
    parts += StatusPart("sampling ", Palette.Muted)
    parts += StatusPart(if (custom) "custom" else "model", if (custom) Palette.Accent else Palette.Green, bold = custom)
    val eff = Sampling.effective(app.config, app.modelParameters)
    if (eff.isEmpty()) parts += StatusPart(" · Ollama defaults", Palette.Muted)
    else eff.forEach { parts += StatusPart(" ${it.param.short}${it.param.format(it.value)}", if (it.fromSettings) Palette.Accent else Palette.Muted, bold = it.fromSettings) }
    return StatusSegment(parts, 6)
}
