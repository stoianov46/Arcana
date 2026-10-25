package com.arcana.brain.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.design.Attachments
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.app.navigation.ScreenOrder
import com.arcana.brain.core.ChatChunk
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.DailyNotes
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.SessionStore
import com.arcana.brain.core.capture
import com.arcana.brain.core.OllamaClient
import com.arcana.brain.core.UsageRecord
import com.arcana.brain.core.UsageStore
import com.arcana.brain.core.Vault
import com.arcana.brain.core.VaultException
import java.nio.file.Path

/** What is known about the Ollama server and the configured model. */
sealed interface Connection {
    data object Checking : Connection
    data class Online(val models: List<String>) : Connection
    data class ModelMissing(val models: List<String>) : Connection
    data class Offline(val message: String) : Connection
}

class AppContext(
    private val store: ConfigStore = ConfigStore(),
    private val usageStore: UsageStore = UsageStore(),
    chatStore: ChatStore = ChatStore(),
    sessionStore: SessionStore = SessionStore(),
) {
    var config by mutableStateOf(store.load())
        private set
    var connection by mutableStateOf<Connection>(Connection.Checking)
        private set

    /** Whether the chosen model can look at pictures: null while unknown (Ollama did not say, or is not reachable). */
    var vision by mutableStateOf<Boolean?>(null)

    /** Whether the model can call tools (null = unknown). */
    var tools by mutableStateOf<Boolean?>(null)
        private set

    /** The most tokens of context the model supports, as Ollama reports it; 0 when unknown. */
    var modelContext by mutableStateOf(0)
        private set
    val client = OllamaClient { config }

    /** The scope replies are generated in: the app's own, so a reply keeps streaming when you switch to another screen. */
    lateinit var scope: kotlinx.coroutines.CoroutineScope

    /** The Chat screen's conversation: lives here so it survives switching screens, and is saved between runs. */
    val chat = ChatSession(this, chatStore)

    /** Every request ever made (loaded from `usage.jsonl`), newest last; the dashboard and the footer read it. */
    val usage = mutableStateListOf<UsageRecord>().also { it.addAll(usageStore.load()) }
    /** The working session: everything done since Arcana opened (or since "new session"), logged and kept. */
    val session = AppSession(sessionStore)
    val sessionStart: Long get() = session.started

    /** The map of the vault: links, tags and what needs attention. */
    val brain = BrainState(this)

    /** Tokens used since Arcana started. */
    val sessionTokens: Long get() = usage.filter { it.time >= sessionStart }.sumOf { it.totalTokens.toLong() }

    /** Called when a reply finishes: keeps the tokens Ollama reported for it. */
    fun recordUsage(feature: String, chunk: ChatChunk) {
        val record = UsageRecord(System.currentTimeMillis(), feature, config.model, chunk.promptTokens, chunk.completionTokens, chunk.totalMillis)
        usage += record
        runCatching { usageStore.append(record) }
    }

    fun clearUsage() {
        usage.clear()
        runCatching { usageStore.clear() }
    }

    /** How many replies are being generated right now (drives the animated logo). */
    var busy by mutableIntStateOf(0)
    val configPath: Path get() = store.path

    init {
        ScreenOrder.use(config.tabOrder)
    }

    fun update(new: AppConfig) {
        config = new
        store.save(new)
        ScreenOrder.use(new.tabOrder)
    }

    suspend fun refreshConnection() {
        connection = try {
            val installed = client.listModels()
            if (config.model in installed) Connection.Online(installed) else Connection.ModelMissing(installed)
        } catch (e: Exception) {
            Connection.Offline(e.message ?: e.toString())
        }
        val shown = if (connection is Connection.Online) runCatching { client.show(config.model) }.getOrNull() else null
        modelContext = shown?.second ?: 0
        val capabilities = shown?.first?.takeIf { it.isNotEmpty() }
        vision = capabilities?.let { "vision" in it }
        tools = capabilities?.let { "tools" in it }
    }

    /** The daily-note settings of the open vault: Obsidian's own when it has them, else the ones from Settings. */
    fun dailyNotes(vault: Vault) = DailyNotes.detect(vault, config.dailyFolder, config.dailyFormat)

    /**
     * Ends the working session and starts a fresh one, without restarting Arcana: the activity log starts over, the chat
     * starts empty (the old one stays in the history) and the token count for the session goes back to zero.
     */
    fun startNewSession() {
        session.startNew()
        chat.startNew()
        revision++
    }

    /** Bumped when a new session starts, so every screen is rebuilt from scratch. */
    var revision by mutableIntStateOf(0)
        private set

    /** The files attached to the thought being captured: only copied into the vault, never read. */
    val captureAttachments: Attachments by lazy { attachments(forModel = false) }

    /**
     * Writes a thought to the end of today's daily note, stamped with the time. Each of [files] is copied into the vault's
     * attachments folder and embedded in the thought. Returns the note's vault path.
     */
    fun capture(text: String, files: List<Path> = emptyList()): Result<String> = openVault().mapCatching { vault ->
        val embeds = files.map { "![[${vault.copyAttachment(config.outputFolder, it)}]]" }
        val full = (listOf(text.trim()) + embeds).filter { it.isNotEmpty() }.joinToString(" ")
        val path = vault.capture(full, dailyNotes(vault))
        session.log(SessionKind.Captured, full, path)
        brain.changed()
        path
    }

    /** A new set of attachments for a message field; [forModel] false is for fields whose files are only copied into the vault. */
    fun attachments(forModel: Boolean = true) = Attachments(
        modelCanSee = { vision != false },
        startDir = { config.lastPickerDir.takeIf { it.isNotBlank() }?.let { Path.of(it) } },
        onFolder = { dir -> update(config.copy(lastPickerDir = dir.toString())) },
        forModel = forModel,
    )

    fun openVault(): Result<Vault> {
        val raw = config.vaultPath.trim()
        if (raw.isEmpty()) return Result.failure(VaultException("Vault path is not set. Open Settings and choose your vault folder."))
        return runCatching { Vault(expandHome(raw)) }
    }

    companion object {
        fun expandHome(path: String): Path =
            if (path == "~" || path.startsWith("~/")) {
                Path.of(System.getProperty("user.home"), path.removePrefix("~").trimStart('/'))
            } else {
                Path.of(path)
            }
    }
}
