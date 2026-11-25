package com.arcana.brain.app

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

/**
 * Semantic search for the chat (docs/semantic-search.md): the vault's meaning index and the project folders' indexes, `/reindex`, and setting up the embedding model
 * (asking once, then downloading it through Ollama). Split out of [ChatSession] (proposal 0007, phase 7); [askApproval] is the chat's dialog, [projectFolder] its
 * working folder.
 */
class SemanticService(
    private val app: AppContext,
    private val askApproval: suspend (ChatSession.Approval) -> ChatSession.ApprovalChoice,
    private val projectFolder: (AppConfig) -> java.nio.file.Path?,
) {
    /** The text of a running `/reindex` ("Indexing 120 notes… 40/120"), null when none runs: shown under the conversation. */
    var indexing by mutableStateOf<String?>(null)
        private set

    private var semanticIndex: Triple<String, String, EmbeddingIndex>? = null

    /**
     * The folders `search_files` looks in: the working folder (Settings → Tools) and the folders of Settings → Search → Search in, each only when it exists and lies inside a folder the tools may
     * use (the vault, the working folder or the allowed folders): the search must never reach a place the model could not read.
     */
    fun projectRoots(config: AppConfig): List<java.nio.file.Path> {
        val scope = SearchScope.of(config.searchIn)
        val allowed = listOfNotNull(app.openVault().getOrNull()?.root, projectFolder(config)) + config.toolFolders.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { AppContext.expandHome(it) }
        val allowedNormal = allowed.map { it.toAbsolutePath().normalize() }
        val listed = scope.folders.map { AppContext.expandHome(it).toAbsolutePath().normalize() }
        return (listOfNotNull(projectFolder(config)?.toAbsolutePath()?.normalize().takeIf { scope.working }) + listed)
            .filter { dir -> java.nio.file.Files.isDirectory(dir) && allowedNormal.any { dir.startsWith(it) } }
            .distinct()
    }

    /** The notes of the vault are searched: `vault` is in Settings → Search → Search in. */
    fun notesSearched(config: AppConfig): Boolean = SearchScope.of(config.searchIn).notes

    private val projectIndexes = java.util.concurrent.ConcurrentHashMap<String, EmbeddingIndex>()

    /** The meaning index of a project folder, or null when no embedding model is set up and installed (then only words are used). One per folder, model and size limit; its file is apart from the vault's. */
    fun projectIndexFor(config: AppConfig, source: PassageSource): EmbeddingIndex? {
        val model = config.embedModel.trim().ifEmpty { return null }
        val installed = when (val c = app.connection) { is Connection.Online -> c.models; is Connection.ModelMissing -> c.models; else -> return null }
        val name = installed.firstOrNull { ModelStatus.sameModel(it, model) } ?: return null
        return projectIndexes.getOrPut(source.key + "\n" + name) {
            EmbeddingIndex(EmbeddingIndex.fileFor(source, name), source, name, embedder = Embedder { texts -> app.client.embedBlocking(name, texts) })
        }
    }

    /** The semantic search is on: an embedding model is set and installed on the server (what [semanticIndexFor] needs besides a vault). */
    fun semanticOn(config: AppConfig = app.config): Boolean {
        val model = config.embedModel.trim().ifEmpty { return false }
        val installed = when (val c = app.connection) { is Connection.Online -> c.models; is Connection.ModelMissing -> c.models; else -> return false }
        return installed.any { ModelStatus.sameModel(it, model) }
    }

    /**
     * Makes sure the embedding model for `semantic_search` is installed and chosen, for the first call of the tool: when it is not, **asks the user once** (a dialog "Download a model?"
     * with the model, its size and where it comes from), downloads it through Ollama's `/api/pull` with progress under the conversation, and selects it in the settings. The first search
     * then builds the index by itself. Returns null when it is ready, else the reason, as the tool's error: the user said no, Ollama could not be reached, the download failed.
     */
    internal suspend fun ensureSemanticModel(): String? {
        if (semanticOn()) return null
        val model = app.config.embedModel.trim().ifEmpty { SemanticSetup.RECOMMENDED }
        val have = try { app.client.listModels().any { ModelStatus.sameModel(it, model) } } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            return "semantic search could not be set up: ${e.message}. Use search_notes or grep_files instead."
        }
        if (!have) {
            val choice = askApproval(ChatSession.Approval(
                "pull", "ollama pull $model",
                listOf(
                    "The model wants to search your notes by meaning (semantic_search), which needs an embedding model, and none is installed.",
                    "Download $model (${if (model == SemanticSetup.RECOMMENDED) SemanticSetup.APPROX_SIZE else "size depends on the model"}) into Ollama at ${app.config.ollamaUrl}? Only the model file is downloaded, from the Ollama library; your notes stay on this machine.",
                    "Esc stops the download; Ollama keeps what it fetched. This is asked once.",
                ),
                heading = "Download a model?", icon = "⇩", yes = "Download", always = false,
            ))
            if (choice == ChatSession.ApprovalChoice.Deny) return "the user declined to download the embedding model $model. Use search_notes or grep_files instead."
        }
        return installEmbeddingModel(model) {}?.let { "$it Use search_notes or grep_files instead." }
    }

    /**
     * Downloads [model] through Ollama's `/api/pull` when it is not installed (progress to [onProgress] and under the conversation), selects it as the semantic search model in the
     * settings and reads the server's model list again. Returns null when it is ready, else why not (Ollama cannot be reached, the download failed, the model is not there afterwards).
     * It asks nothing: the callers are a dialog (the model called `semantic_search`) and the Settings row the user chose on purpose (Settings → Search → Embedding model (for meaning)).
     */
    suspend fun installEmbeddingModel(model: String, onProgress: (String) -> Unit): String? {
        try {
            val have = app.client.listModels().any { ModelStatus.sameModel(it, model) }
            if (!have) {
                indexing = "Downloading $model…"
                try {
                    var shown = 0L
                    app.client.pull(model).collect { p ->
                        val now = System.currentTimeMillis()
                        if (now - shown > 150 || p.done) { shown = now; SemanticSetup.progressText(model, p).let { indexing = it; onProgress(it) } }
                    }
                } finally {
                    indexing = null
                }
            }
            app.update(app.config.copy(embedModel = model))
            app.refreshConnection()
            return if (semanticOn()) null else "the embedding model $model is not available on the Ollama server after the download."
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "semantic search could not be set up: ${e.message}."
        }
    }

    /**
     * The semantic index of [vault] for the embedding model of [config], when one is set and installed on the Ollama server; null otherwise (then the
     * `semantic_search` tool is not offered). One index object per vault and model is kept, so it is not read from disk for every message.
     */
    fun semanticIndexFor(config: AppConfig, vault: Vault?): EmbeddingIndex? {
        val model = config.embedModel.trim()
        if (vault == null || model.isEmpty()) return null
        val installed = when (val c = app.connection) { is Connection.Online -> c.models; is Connection.ModelMissing -> c.models; else -> return null }
        val name = installed.firstOrNull { ModelStatus.sameModel(it, model) } ?: return null
        semanticIndex?.let { (root, m, index) -> if (root == vault.root.toString() && m == name) return index }
        val index = EmbeddingIndex(EmbeddingIndex.fileFor(vault, name), vault, name, embedder = Embedder { texts -> app.client.embedBlocking(name, texts) })
        semanticIndex = Triple(vault.root.toString(), name, index)
        return index
    }

    /**
     * `/reindex`: builds or refreshes the semantic index off the UI thread, with progress in [indexing]. [done] gets the closing text (a count and the
     * time) and whether it failed; what was finished before a failure is kept.
     */
    fun reindex(done: (String, Boolean) -> Unit) {
        if (indexing != null) return done("Already indexing.", true)
        val config = app.config
        if (config.embedModel.isBlank()) return done("No embedding model is set: choose one in Settings → Search → Embedding model (for meaning) (for example: ollama pull nomic-embed-text).", true)
        val vault = app.openVault().getOrNull() ?: return done("Open a vault first (Settings → Vault).", true)
        val index = semanticIndexFor(config, vault)
            ?: return done("${config.embedModel} is not installed on the Ollama server, or Ollama cannot be reached. Run: ollama pull ${config.embedModel}", true)
        indexing = "Indexing notes…"
        app.scope.launch(Dispatchers.IO) {
            try {
                val r = index.refresh { d, t -> indexing = if (t == 0) "Indexing: nothing to do…" else "Indexing $t notes… $d/$t" }
                // the project folders too (a folder of code is only embedded by a search when it is small): each has its own index
                val projects = projectRoots(config).mapNotNull { root ->
                    val source = FolderSource(root, config.searchMaxFileKb.coerceIn(10, 5000) * 1000L, vaultRoot = vault.root.toAbsolutePath().normalize())
                    projectIndexFor(config, source)?.let { idx ->
                        val pr = idx.refresh { d, t -> indexing = if (t == 0) "Indexing ${root.fileName}: nothing to do…" else "Indexing ${root.fileName}… $d/$t files" }
                        "${root.fileName}: ${pr.notes} files, ${pr.chunks} passages"
                    }
                }
                done("Semantic index ready: ${r.notes} notes, ${r.chunks} passages (${r.embeddedNotes} embedded, ${r.removedNotes} dropped) in ${"%.1f".format(java.util.Locale.ROOT, r.millis / 1000.0)} s." + projects.joinToString("") { "; $it" }, false)
            } catch (e: Exception) {
                done("Indexing stopped: ${e.message}. What was finished is kept; run /reindex to go on.", true)
            } finally {
                indexing = null
                // the embedding model is in memory now: the bottom bar's ◇ embed says so
                runCatching { app.refreshLoadedContext() }
            }
        }
    }
}
