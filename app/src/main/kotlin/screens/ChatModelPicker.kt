package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.core.ModelDetails
import com.arcana.brain.core.ModelFit
import com.idea.ink.input.Key
import com.idea.ink.theme.*
import com.idea.ink.ui.*

/**
 * `/model` in the Chat: the installed models in a list, as Settings → Ollama → Model shows them (size, MoE or dense, tools, vision, thinking, whether it fits this
 * machine's memory), to change the chat model without leaving the conversation. Enter switches; `r` switches and sends your last task again to the new model.
 */
internal class ModelPickerState {
    val picker = PickerState()
    var models by mutableStateOf(emptyList<ModelDetails>())
        private set
    /** Opened as `/model --retry`: Enter also sends the last task again. */
    var retry by mutableStateOf(false)
        private set
    /** This machine's memory, for the fit notes (0 = not known: no notes). */
    var ram = 0L
        private set

    fun open(models: List<ModelDetails>, current: String, retry: Boolean, ram: Long = ModelFit.physicalMemory()) {
        this.models = models
        this.retry = retry
        this.ram = ram
        picker.reset()
        picker.index = models.indexOfFirst { com.arcana.brain.core.ModelStatus.sameModel(it.name, current) }.coerceAtLeast(0)
    }

    /** The note after a model's name: `current · 20 GB · MoE · tools · thinking · fits`, red words when it is too big. */
    fun note(d: ModelDetails, current: String): String = buildString {
        if (com.arcana.brain.core.ModelStatus.sameModel(d.name, current)) append("current · ")
        append(d.summary())
        if (ram > 0 && d.sizeBytes > 0) {
            val fit = ModelFit.fit(d.sizeBytes, 0L, ram)
            append(" · ").append(if (fit == ModelFit.Fit.TooBig) ModelFit.label(fit) else "fits")
        }
    }

    /** One key: [pick] gets the chosen model and whether to send the last task again; [close] leaves the list. True when the key was used. */
    fun handle(key: Key, pick: (String, Boolean) -> Unit, close: () -> Unit): Boolean = when {
        picker.handle(key, models) { it.name } -> true
        key == Key.Enter -> { picker.selected(models) { it.name }?.let { pick(it.name, retry) }; close(); true }
        key.char == 'r' && !picker.filtering -> { picker.selected(models) { it.name }?.let { pick(it.name, true) }; close(); true }
        key == Key.Escape -> { close(); true }
        else -> false
    }
}

@Composable
internal fun ModelPickerView(state: ModelPickerState, current: String, url: String, rows: Int) {
    val machine = if (state.ram > 0) "  ·  this machine ${ModelFit.gb(state.ram)}, ~${ModelFit.gb(ModelFit.budget(state.ram))} for models" else ""
    ScreenHeader("Chat model", "Installed on $url$machine")
    VGap()
    Panel("Models") {
        Picker(
            state.models, state.picker,
            label = { it.name },
            rows = rows,
            icon = { if (com.arcana.brain.core.ModelStatus.sameModel(it.name, current)) "●" else "◆" },
            iconColor = { Palette.Accent },
            meta = { state.note(it, current) },
            metaColor = { val note = state.note(it, current); when { "too big" in note -> Palette.Red; note.startsWith("current") -> Palette.Green; else -> Palette.Muted } },
        )
    }
    Hint(
        "The chat model for the next message, the same setting as Settings → Ollama → Model; the conversation stays. " +
            if (state.retry) "Enter switches and sends your last message again to it." else "Enter switches; r switches and sends your last message again, so the new model does the same task.",
    )
}
