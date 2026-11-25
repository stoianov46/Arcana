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

// The Chat screen's dialogs, each a view of its own that takes only what it shows (proposal 0007, phase 8). Chat.kt keeps their state and keys.

@Composable
internal fun SaveDialog(saveTitle: TextFieldState, lastAnswerOnly: Boolean, messageCount: Int, outputFolder: String) {
        ScreenHeader("Save chat", "Write this conversation to your vault as a Markdown note")
        VGap()
        Panel("Save to vault") {
            TextInput("Note title", saveTitle, true)
            VGap()
            Row {
                Label("Save  ", color = Palette.Muted)
                Badge(if (lastAnswerOnly) "Last answer only" else "Whole chat · ${messageCount} messages")
                Label("   in  ${outputFolder.ifEmpty { "the vault root" }}", color = Palette.Muted)
            }
        }
        ActionBar(
            listOf(
                ActionItem("Enter", "Save note", saveTitle.text.isNotBlank()),
                ActionItem("Tab", if (lastAnswerOnly) "Save the whole chat" else "Save last answer only"),
                ActionItem("Ctrl+O", "Overwrite a note…"),
                ActionItem("Esc", "Cancel", accent = Palette.Cta),
            ),
            style = ActionStyle.Button,
        )
}

@Composable
internal fun LeaveDialog(title: String, unsaved: Int) {
        ScreenHeader("Chat", title)
        VGap()
        Panel("Leave this chat?", accent = Palette.Warn) {
            Warning("This conversation (${unsaved} new messages) is not saved to your vault as a note.")
            Hint("Chats are not being kept (Settings → Console → Keep chats), so it is gone once you leave.")
        }
        ActionBar(listOf(ActionItem("w", "Save to vault first"), ActionItem("Esc", "Leave chat", accent = Palette.Red), ActionItem("any other key", "Stay", accent = Palette.Cta)), style = ActionStyle.Button)
}

@Composable
internal fun ConfirmClearDialog(title: String, messageCount: Int) {
        ScreenHeader("Chat", title)
        VGap()
        Panel("Erase this chat?", accent = Palette.Red) {
            Warning("“${title}” (${messageCount} messages) is deleted from the history. /new keeps it and starts a fresh chat instead.")
        }
        ActionBar(listOf(ActionItem("y", "Erase chat", accent = Palette.Red), ActionItem("any other key", "Keep it", accent = Palette.Cta)), style = ActionStyle.Button)
}

@Composable
internal fun ContextPartView(part: com.arcana.brain.core.ContextPart?, output: OutputState) {
                        ScreenHeader("Context", part?.let { "${it.title} · ~${UsageStats.compact(it.tokens.toLong())} tokens · exactly as sent" } ?: "")
        VGap()
        // the view's scrollbar and a margin take four columns: wrapping to the full width would cut every long line
        val width = (LocalContentWidth.current - 4).coerceAtLeast(20)
        val lines = part?.text.orEmpty().let { sanitizeForTerminal(it) }.split('\n').flatMap { raw -> com.idea.ink.ui.wrapText(raw, width).ifEmpty { listOf("") } }
            .map { com.idea.ink.ui.Line(it, color = if (it.startsWith("── ")) Palette.Accent else Palette.Text, bold = it.startsWith("── ")) }
        ScrollView(lines, outputRows(fixed = 4), output)
}

@Composable
internal fun ArtifactsDialog(artifacts: List<com.arcana.brain.core.Artifact>, root: java.nio.file.Path?, selected: Int, plain: Boolean) {
        val list = Artifacts.entries(artifacts)
                        val openable = list.map { e -> root?.let { Artifacts.libraryNote(e.artifact, it) } != null }
        val sel = selected.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val rows = 12
        val first = (sel - rows + 1).coerceAtLeast(0)
        ScreenHeader("Chat", "Written or changed in this chat")
        VGap()
        Panel("${list.size} files and notes · newest first · ▤ opens in the Library", accent = Palette.Purple) {
            if (first > 0) Hint("… $first newer")
            list.drop(first).take(rows).forEachIndexed { j, e ->
                val i = first + j
                val a = e.artifact
                val mark = if (plain) (if (openable[i]) "[n]" else "[ ]") else if (openable[i]) "▤" else "❐"
                val num = if (i < 9) "${i + 1}" else " "
                val times = if (e.times > 1) "  ×${e.times}" else ""
                val color = if (a.action == "deleted") Palette.Red else if (openable[i]) Palette.Text else Palette.Muted
                Label("${if (i == sel) "›" else " "} $num $mark ${Artifacts.line(a)}$times", color = if (i == sel) Palette.Cta else color)
            }
            if (first + rows < list.size) Hint("… ${list.size - first - rows} older")
        }
        ActionBar(listOf(ActionItem("↑↓", "Choose"), ActionItem("Enter", "Open in the Library", openable.getOrNull(sel) == true, accent = Palette.Cta), ActionItem("1-9", "Open that one"), ActionItem("any other key", "Back")), style = ActionStyle.Button)
}

@Composable
internal fun ConfirmMemoryDialog(audit: MemoryAudit?) {
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

@Composable
internal fun ConfirmCancelDialog(title: String, reply: Boolean) {
        ScreenHeader("Chat", title)
        VGap()
        Panel(if (reply) "Cancel this reply?" else "Cancel the Oracul?", accent = Palette.Orange) {
            Warning(if (reply) "The reply stops where it is and what was written so far is kept. It keeps running while you decide." else "The Oracul or the judge stops and nothing is added to the chat. It keeps running while you decide.")
        }
        ActionBar(listOf(ActionItem("Enter", "Cancel it", accent = Palette.Orange), ActionItem("any other key", "Keep going", accent = Palette.Cta)), style = ActionStyle.Button)
}

@Composable
internal fun ConfirmReplaceDialog(note: String?, lastAnswer: Boolean) {
        ScreenHeader("Chat", "Replace “${note}”")
        VGap()
        Panel("Replace this note with ${if (lastAnswer) "the last answer" else "this chat"}?", accent = Palette.Orange) {
            Warning("The text of the note is replaced by ${if (lastAnswer) "the answer" else "the chat"}. Its frontmatter is kept, and the old text is saved first in the vault's .trash folder.")
        }
        ActionBar(listOf(ActionItem("y", "Replace note", accent = Palette.Orange), ActionItem("any other key", "Keep the note", accent = Palette.Cta)), style = ActionStyle.Button)
}

@Composable
internal fun HistoryDialog(past: List<StoredChat>, picker: PickerState, currentId: String) {
        ScreenHeader("Chat history", "Pick a conversation to continue. The current one is saved as it is")
        VGap()
        Panel("Saved chats") {
            Picker(
                past, picker,
                label = { it.title },
                rows = bodyRows(extra = 4),
                icon = { if (it.id == currentId) "●" else "»" },
                iconColor = { if (it.id == currentId) Palette.Green else Palette.Accent },
                meta = { "${it.messages.size} msgs · ${relativeTime(it.updated)}" },
            )
        }
}

/**
 * The context inspector (`/context`): what the chat model was sent with the latest request, one row per part with its share of the request, and a mark on the parts Ollama
 * could take from its cache (the start of the request that is the same as last time). Enter opens a part exactly as it was sent. Debugging a confused or slow answer starts here.
 */
@Composable
internal fun ContextInspectorView(snap: com.arcana.brain.core.ContextSnapshot?, selected: Int, plain: Boolean) {
    ScreenHeader("Context", if (snap == null) "Nothing sent yet" else "What ${snap.model} was sent with the last request · ${relativeTime(snap.at)}")
    VGap()
    if (snap == null) { Hint("Send a message first."); return }
    val compact = UsageStats::compact
    val window = if (snap.window > 0) " of ${UsageStats.contextSize(snap.window.toLong())} (${"%.0f".format(snap.share * 100)}%)" else ""
    val cache = if (snap.cachedTokens > 0) " · ~${compact(snap.cachedTokens.toLong())} the same as the request before (Ollama reuses it)" else " · all of it read anew"
    Label("~${compact(snap.total.toLong())} tokens$window$cache", color = Palette.Muted)
    VGap()
    val parts = snap.parts
    val sel = selected.coerceIn(0, (parts.size - 1).coerceAtLeast(0))
    val rows = 14
    val first = (sel - rows + 1).coerceAtLeast(0)
    val width = LocalContentWidth.current
    val titleWidth = (width - 34).coerceIn(16, 40)
    Panel("${parts.size} parts · estimates (Ollama's own count is under each reply)", accent = Palette.Blue) {
        if (first > 0) Hint("… $first above")
        parts.drop(first).take(rows).forEachIndexed { j, p ->
            val i = first + j
            val indent = if (p.depth > 0) (if (plain) "  - " else "  └ ") else ""
            val title = (indent + p.title).let { if (it.length > titleWidth) it.take(titleWidth - 1) + "…" else it.padEnd(titleWidth) }
            val tokens = "~${compact(p.tokens.toLong())}".padStart(7)
            val bar = com.arcana.brain.core.ContextInspector.bar(p.tokens, snap.total, 8, plain)
            val mark = if (p.reused) (if (plain) " cached" else " ✓ cached") else ""
            val color = if (i == sel) Palette.Cta else if (p.depth > 0) Palette.Muted else Palette.Text
            Row {
                Label("${if (i == sel) "›" else " "} $title $tokens ", color = color, textStyle = if (i == sel) TextStyle.Bold else TextStyle.Unspecified)
                Label(bar, color = if (p.depth > 0) Palette.Dim else Palette.Blue)
                Label(mark, color = Palette.Green)
            }
        }
        if (first + rows < parts.size) Hint("… ${parts.size - first - rows} below")
    }
    parts.getOrNull(sel)?.detail?.takeIf { it.isNotEmpty() }?.let { Hint(it) }
    ActionBar(listOf(ActionItem("↑↓", "Choose"), ActionItem("Enter", "Read it as sent", accent = Palette.Cta), ActionItem("c", "Copy"), ActionItem("Esc", "Back")))
}

/**
 * The context inspector's own state and keys (`/context`): the part chosen, whether one is open, and its scroll. [handle] returns false when the inspector closes;
 * [copied] is told what `c` copied (or null when the clipboard could not be reached).
 */
internal class ContextInspectorState {
    var index by androidx.compose.runtime.mutableIntStateOf(0)
    var reading by mutableStateOf(false)
        private set
    val output = OutputState()

    fun open() { index = 0; reading = false }

    fun part(snap: com.arcana.brain.core.ContextSnapshot?): com.arcana.brain.core.ContextPart? {
        val parts = snap?.parts.orEmpty()
        return parts.getOrNull(index.coerceIn(0, (parts.size - 1).coerceAtLeast(0)))
    }

    fun handle(key: Key, snap: com.arcana.brain.core.ContextSnapshot?, copied: (com.arcana.brain.core.ContextPart, Boolean) -> Unit): Boolean {
        val parts = snap?.parts.orEmpty()
        val part = part(snap)
        fun copy() { if (part != null) copied(part, com.idea.ink.input.Clipboard.writeText(part.text)) }
        if (reading) when {
            key == Key.Escape || key == Key.Left -> reading = false
            key.char == 'c' -> copy()
            else -> output.handle(key, letters = false)
        } else when {
            key == Key.Up -> index = (index - 1).coerceAtLeast(0)
            key == Key.Down -> index = (index + 1).coerceAtMost((parts.size - 1).coerceAtLeast(0))
            key == Key.Enter || key == Key.Right -> if (part != null) { output.showTop(); reading = true }
            key.char == 'c' -> copy()
            key == Key.Escape -> return false
        }
        return true
    }
}

/** The artifacts list's own state and keys (`/artifacts`): ↑↓ choose, Enter or 1-9 open in the Library ([open] gets the index); any other key closes it (false). */
internal class ArtifactsState {
    var index by androidx.compose.runtime.mutableIntStateOf(0)

    fun handle(key: Key, count: Int, open: (Int) -> Unit): Boolean {
        when {
            key == Key.Up -> index = (index - 1).coerceAtLeast(0)
            key == Key.Down -> index = (index + 1).coerceAtMost((count - 1).coerceAtLeast(0))
            key == Key.Enter -> open(index)
            key.char in '1'..'9' -> open(key.char!! - '1')
            else -> return false
        }
        return true
    }
}
