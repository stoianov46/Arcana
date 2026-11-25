package com.arcana.brain.app

import com.arcana.brain.app.ChatSession.Approval
import com.arcana.brain.app.ChatSession.ApprovalChoice
import com.arcana.brain.app.ChatSession.Question
import com.arcana.brain.app.ChatSession.VaultContext
import com.arcana.brain.app.ChatSession.Companion.MAX_NOTE_CHARS
import com.arcana.brain.app.ChatSession.Companion.PLAN_MIN_CHARS
import com.arcana.brain.app.ChatSession.Companion.NEVER_REMEMBERED
import com.arcana.brain.app.ChatSession.Companion.CLEARS_MEMORY
import com.arcana.brain.app.ChatSession.Companion.FAILURE_LOOK
import com.arcana.brain.core.effectiveContext
import com.arcana.brain.core.contextTokens
import com.arcana.brain.core.contextUsage
import com.arcana.brain.core.thinkOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.idea.ink.ui.TextFieldState
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.SystemFreeze
import com.arcana.brain.core.WebTaint
import com.arcana.brain.core.STEP_LIMIT_AUTO_ROUNDS
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.Requirement
import com.arcana.brain.core.Artifact
import com.arcana.brain.core.CheckCommand
import com.arcana.brain.core.CheckRun
import com.arcana.brain.core.Artifacts
import com.arcana.brain.core.CONSULT
import com.arcana.brain.core.CONSULT_DEFINITION
import com.arcana.brain.core.Events
import com.arcana.brain.core.LoopGuard
import com.arcana.brain.core.Flow
import com.arcana.brain.core.MAX_CONSULTS
import com.arcana.brain.core.Route
import com.arcana.brain.core.Router
import com.arcana.brain.core.OraculSteps
import com.arcana.brain.core.FolderSource
import com.arcana.brain.core.HybridSearch
import com.arcana.brain.core.LexicalIndex
import com.arcana.brain.core.RetrievalMode
import com.arcana.brain.core.PassageSource
import com.arcana.brain.core.ProjectSearch
import com.arcana.brain.core.SearchHit
import com.arcana.brain.core.SearchScope
import com.arcana.brain.core.SearchTuning
import com.arcana.brain.core.SemanticSetup
import com.arcana.brain.core.SkillRoles
import com.arcana.brain.core.StageKind
import com.arcana.brain.core.Skills
import com.arcana.brain.core.SkillRun
import com.arcana.brain.core.FileAttachment
import com.arcana.brain.core.ImageAttachment
import com.arcana.brain.core.ReportMeta
import com.arcana.brain.core.Prompts
import com.arcana.brain.core.ReplyStats
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.StoredChat
import com.arcana.brain.core.ToolBox
import com.arcana.brain.core.ToolCall
import com.arcana.brain.core.ToolUseRecord
import com.arcana.brain.core.ASK_USER
import com.arcana.brain.core.ASK_USER_DEFINITION
import com.arcana.brain.core.REQUEST_ACCESS
import com.arcana.brain.core.looksLikeToolTask
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import com.arcana.brain.core.REQUEST_ACCESS_DEFINITION
import com.arcana.brain.core.CHAT_STATS
import com.arcana.brain.core.CHAT_STATS_JSON
import com.arcana.brain.core.CHAT_STATS_DEFINITION
import com.arcana.brain.core.CHAT_STATS_JSON_DEFINITION
import com.arcana.brain.core.TODO
import com.arcana.brain.core.TODO_DEFINITION
import com.arcana.brain.core.TodoItem
import com.arcana.brain.core.Todos
import com.arcana.brain.core.Compaction
import com.arcana.brain.core.Oracul
import com.arcana.brain.core.Origin
import com.arcana.brain.core.Provenance
import com.arcana.brain.core.OllamaClient
import com.arcana.brain.core.UsageStats
import com.arcana.brain.core.ChatStats
import com.arcana.brain.core.ApprovalPolicy
import com.arcana.brain.core.ApprovalSettings
import com.arcana.brain.core.Actor
import com.arcana.brain.core.REQUEST_MODE
import com.arcana.brain.core.REQUEST_MODE_DEFINITION
import com.arcana.brain.core.TOOL_MODES
import com.arcana.brain.core.ShellPolicy
import com.arcana.brain.core.cleanOption
import com.arcana.brain.core.WebPolicy
import com.arcana.brain.core.ToolRisk
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import com.arcana.brain.core.Vault
import com.arcana.brain.core.EmbeddingIndex
import com.arcana.brain.core.Embedder
import com.arcana.brain.core.SemanticSearch
import com.arcana.brain.core.ModelStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Compaction: summarizing the older part of the chat, and when it is due. Split out of ChatSession.kt (proposal 0007, phase 7): extension functions over the session's state.

/**
 * Compacts the chat: the model summarizes everything before the user's last [Compaction.KEEP_USER_TURNS] turns, and the summary is
 * inserted at that point. Nothing is removed from the chat; the model just stops being sent what is above the summary.
 * Returns what to tell the user; throws with a message when it cannot be done (too short, empty summary, no model).
 */
suspend fun ChatSession.compact(auto: Boolean = false): String {
    check(!compacting) { "Already compacting." }
    val all = messages.toList()
    val cut = Compaction.cutPoint(all, keepTokens()) ?: error("Nothing to compact yet: your latest messages (and the latest tool rounds) stay as they are, and there must be a few messages before them.")
    val part = Compaction.toSummarize(all, cut)
    compactingSince = System.currentTimeMillis()
    compactingAuto = auto
    compacting = true
    app.busy++
    try {
        // all of it is summarized, piece by piece (each piece with the summary so far), without thinking: a summary needs no reasoning, and thinking only makes it slower
        var summary = ""
        val pieces = Compaction.pieces(part)
        for ((i, piece) in pieces.withIndex()) {
            compactingPart = if (pieces.size > 1) "${i + 1} of ${pieces.size}" else ""
            val text = StringBuilder()
            app.client.chat(Compaction.pieceRequest(piece, summary), think = "off").collect { chunk ->
                text.append(chunk.content)
                if (chunk.done && (chunk.promptTokens > 0 || chunk.completionTokens > 0)) app.recordUsage("Compact", chunk)
            }
            summary = text.toString().trim()
            require(summary.isNotEmpty()) { "The model returned an empty summary; nothing was changed." }
        }
        compactingPart = ""
        require(summary.isNotEmpty()) { "The model returned an empty summary; nothing was changed." }
        // the chat may have grown while the model wrote (the cut is counted from the start, so it is still the right place)
        val at = cut.coerceAtMost(messages.size)
        messages.add(at, Compaction.summaryMessage(summary))
        // the conversation the model reads starts anew from the summary: a good moment to bring the system prompt up to date too
        systemFreeze.reset()
        // the last reply's token counts are from before this: the context meter estimates until the next reply says (see contextTokens)
        messages[at] = messages[at].copy(contextAt = messages.size)
        if (savedMessages >= at) savedMessages++
        persist()
        app.recordEvent(Events.COMPACT, if (auto) "auto" else "manual")
        return "Compacted ${part.size} messages into a summary of about ${UsageStats.compact(((summary.length + 3) / 4).toLong())} tokens. The model now sees the summary and your latest messages; everything stays visible here."
    } finally {
        compacting = false
        app.busy = (app.busy - 1).coerceAtLeast(0)
    }
}

/** How many tokens of the latest turns stay word for word when the chat is compacted ([AppConfig.compactKeepPercent] of the window; 0 = the last two turns, also while the window is not known). */
internal fun ChatSession.keepTokens(): Int {
    val percent = app.config.compactKeepPercent.takeIf { it > 0 } ?: return 0
    return effectiveContext(app.config.contextTokens, app.modelContext, app.loadedContext) * percent / 100
}

/** Where this chat stands with compaction, for `chat_stats`: the context in use now (as the status pane counts it) and the auto-compact setting. */
internal fun ChatSession.compactionState(): ChatStats.CompactionState {
    val used = contextTokens(messages.toList(), overheadTokens)
    return ChatStats.CompactionState(app.config.autoCompactPercent, used?.first, used?.second ?: false)
}

/** True when [all] fills the context window past the auto-compact threshold and has enough older messages to summarize. */
internal fun ChatSession.autoCompactDue(all: List<ChatMessage>): Boolean {
    val percent = app.config.autoCompactPercent
    if (percent <= 0 || compacting || Compaction.cutPoint(all, keepTokens()) == null) return false
    val usage = contextUsage(all, effectiveContext(app.config.contextTokens, app.modelContext, app.loadedContext), overheadTokens, aheadTokens(all)) ?: return false
    return usage.first * 100 >= percent
}

/**
 * About how many tokens the next request adds that the chat does not hold yet: for a message of yours not sent so far, the notes attached to it and the vault excerpts that
 * will be found for it (at most *Context notes* × *Chars per note*). Lets a compaction start before a big attachment pushes the request past the window.
 */
internal fun ChatSession.aheadTokens(all: List<ChatMessage>): Int {
    val last = all.lastOrNull { it.role == "user" && !it.displayOnly } ?: return 0
    if (last.sent.isNotEmpty()) return 0
    val config = app.config
    val vault = app.openVault().getOrNull()
    val notes = if (vault == null) 0L else last.noteNames.sumOf { path -> runCatching { java.nio.file.Files.size(vault.root.resolve(path)) }.getOrDefault(0L).coerceAtMost(MAX_NOTE_CHARS.toLong()) }
    val excerpts = if (config.chatUseVault && vault != null && semantic.notesSearched(config) && !Oracul.isArcanaPrompt(last.content)) config.contextNotes.toLong() * config.contextCharsPerNote else 0L
    return ((notes + excerpts) / 4).toInt()
}
