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

// Keeping chats: new, open, delete, the history, saving. Split out of ChatSession.kt (proposal 0007, phase 7): extension functions over the session's state.

fun ChatSession.startNew() {
    backlogQuestion = ""
    backlogId = ""
    workItemId = ""
    run = null
    skillRun = null
    thinkNext = ""
    clearRoles()
    clearGoal()
    artifacts.clear()
    toolsDeclined = false
    messages.clear(); wholeNotes.clear(); cutAccepted.clear(); systemFreeze.reset(); lastContext = null; lastRequest = null
    queued.clear()
    input.clear()
    attachments.clear()
    savedMessages = 0
    id = newId()
}

fun ChatSession.open(chat: StoredChat) {
    clearRoles()
    clearGoal()
    artifacts.clear()
    backlogQuestion = ""
    backlogId = ""
    workItemId = ""
    run = null
    skillRun = null // a run belongs to the conversation it started in (see docs/skills.md)
    skillTick++
    messages.clear(); wholeNotes.clear(); cutAccepted.clear(); systemFreeze.reset(); lastContext = null; lastRequest = null
    messages += chat.messages
    attachments.clear()
    id = chat.id
    // A chat brought back from the history is not nagged about: it was already kept, and the user chose to open it.
    savedMessages = messages.size
}

/** Erases this conversation (also from disk) and starts an empty one. */
fun ChatSession.deleteCurrent() {
    store.delete(id)
    startNew()
}

fun ChatSession.history(): List<StoredChat> = if (app.config.keepChats) store.list() else emptyList()

internal fun ChatSession.persist() {
    if (!app.config.keepChats || messages.isEmpty()) return
    runCatching {
        // pictures are sent from memory; only their names are kept on disk
        store.save(StoredChat(id, title, System.currentTimeMillis(), app.config.model, messages.map { it.withoutImageData() }))
        store.prune(app.config.chatsKept)
    }
}

internal fun ChatSession.newId() = System.currentTimeMillis().toString(36) + (0..99999).random().toString(36)
