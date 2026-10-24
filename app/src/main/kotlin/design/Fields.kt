package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.Selection
import com.arcana.brain.app.input.TextFieldState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/** Labelled input box; highlighted while it has keyboard focus. */
@Composable
fun LabeledField(label: String, focused: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (rememberDensity().inline) {
        InlineField(label.substringBefore("  ·"), focused, modifier, content = content)
        return
    }
    Stack(modifier) {
        FieldTitle(label, focused)
        Box(Modifier.fillMaxWidth().background(if (focused) Palette.Focus else Palette.SurfaceHigh).padding(horizontal = 1)) {
            content()
        }
    }
}

/** The title line of a field; what follows the first "  ·  " is an explanation, so it is quiet grey and not bold. */
@Composable
fun FieldTitle(label: String, focused: Boolean) {
    val title = label.substringBefore("  ·")
    val hint = label.substringAfter("  ·", "").trim()
    Row {
        Label(title, color = if (focused) Palette.Cta else Palette.FieldLabel, textStyle = TextStyle.Bold)
        if (hint.isNotEmpty()) {
            Label("  ·  ", color = Palette.Dim)
            Label(hint, color = Palette.Muted)
        }
    }
}

/** Text value with a caret when focused and a muted placeholder when empty; with [maxRows] set it wraps and scrolls. */
@Composable
fun TextValue(value: String, focused: Boolean, placeholder: String = "", caretAt: Int = -1, maxRows: Int = 0, selection: Selection? = null) {
    if (value.isEmpty()) {
        Label(if (focused) "▏$placeholder" else placeholder, color = Palette.Muted, fill = true)
        return
    }
    if (maxRows > 0) {
        // Wraps over several rows and scrolls with the cursor, so nothing is ever cut off.
        WrappedText(value, maxRows, Modifier.fillMaxWidth(), Palette.Text, caret = focused, caretAt = caretAt, selection = selection)
        return
    }
    // Always the part around the caret, cropped to whatever room the field really has.
    TailText(value, Modifier.fillMaxWidth(), Palette.Text, caret = focused, caretAt = caretAt, selection = selection)
}

@Composable
fun TextValue(field: TextFieldState, focused: Boolean, placeholder: String = "", maxRows: Int = 0) =
    TextValue(field.text, focused, placeholder, field.caretAt, maxRows, field.selection)

/** A labelled text input: the common case of [LabeledField] with a [TextValue]; [maxRows] > 0 lets it wrap over several rows. */
@Composable
fun TextInput(label: String, field: TextFieldState, focused: Boolean = true, placeholder: String = "", maxRows: Int = 0, attachments: Attachments? = null) {
    if (attachments == null) {
        LabeledField(label, focused) { TextValue(field, focused, placeholder, maxRows) }
        return
    }
    // The attachment line belongs to the input: it sits right above it and goes with it.
    Stack {
        AttachmentLine(attachments)
        LabeledField(label, focused) { TextValue(field, focused, placeholder, maxRows) }
    }
}

/**
 * Keys for a field that carries [attachments]: Ctrl+O opens the file browser, and Backspace on an empty field takes the last
 * attachment back. Returns true when the key was used. [notify] shows what happened (text, isError).
 */
fun handleAttachmentKey(attachments: Attachments, field: TextFieldState, key: Key, notify: (String, Boolean) -> Unit): Boolean = when {
    key == Key.Browse -> { attachments.open(notify); true }
    key == Key.Backspace && field.text.isEmpty() -> attachments.removeLast()
    else -> false
}

const val INLINE_LABEL_WIDTH = 13

/** One-row `Label  value` field for compact layouts; the value sits on a strip that lights up while focused. */
@Composable
fun InlineField(label: String, focused: Boolean, modifier: Modifier = Modifier, labelWidth: Int = INLINE_LABEL_WIDTH, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().background(if (focused) Palette.Focus else Palette.SurfaceHigh).padding(horizontal = 1)) {
        Label(fit(label, labelWidth), color = if (focused) Palette.Cta else Palette.FieldLabel, textStyle = TextStyle.Bold)
        Box(Modifier.weight(1f)) { content() }
    }
}

/** [text] cut or padded to exactly [width] columns. */
fun fit(text: String, width: Int): String =
    if (text.length > width) text.take((width - 1).coerceAtLeast(0)) + "…" else text.padEnd(width)
