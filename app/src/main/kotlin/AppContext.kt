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
import com.arcana.brain.core.Layout
import com.arcana.brain.core.ResearchBacklog
import com.arcana.brain.core.Skills
import com.arcana.brain.core.DailyNotes
import com.arcana.brain.core.listThought
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.SessionStore
import com.arcana.brain.core.capture
import com.arcana.brain.core.OllamaClient
import com.arcana.brain.core.UsageRecord
import com.arcana.brain.core.ToolUseRecord
import com.arcana.brain.core.EventStore
import com.arcana.brain.core.StatEvent
import com.arcana.brain.core.ToolUseStore
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
    private val toolUseStore: ToolUseStore = ToolUseStore(),
    chatStore: ChatStore = ChatStore(),
    sessionStore: SessionStore = SessionStore(),
    private val eventStore: EventStore = EventStore(),
) {
    var config by mutableStateOf(store.load())
        private set
    var connection by mutableStateOf<Connection>(Connection.Checking)
        private set

    /** Whether the chosen model can look at pictures: null while unknown (Ollama did not say, or is not reachable). */
    var vision by mutableStateOf<Boolean?>(null)

    /** Whether the model can think before it answers (null = unknown). */
    var thinking by mutableStateOf<Boolean?>(null)
        private set

    /** Whether the model hears audio (null = unknown). */
    var audio by mutableStateOf<Boolean?>(null)
        private set

    /** The values the model's `think` takes (`true`, `false`, `low`…): empty when it cannot think or Ollama did not say. */
    var thinkingValues by mutableStateOf<List<String>>(emptyList())
        private set

    /** What the model does when `think` is not sent (`true` or `false`; empty when unknown). */
    var thinkingDefault by mutableStateOf("")

    /** The model's own sampling defaults from its Modelfile (`temperature`, `top_k`, `top_p`…), as `/api/show` lists them; empty when it lists none. */
    var modelParameters by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** What a token of context costs in memory for the chat model (0 = unknown), from `/api/show`: with the weights and this machine's memory it gives [tokenRoom]. */
    var kvBytesPerToken by mutableStateOf(0L)
        private set

    /** About how many tokens of context fit in this machine's memory next to the chat model's weights; null when it cannot be worked out. An estimate (see [com.arcana.brain.core.ModelFit.tokenRoom]). */
    val tokenRoom: Long? get() = com.arcana.brain.core.ModelFit.tokenRoom(com.arcana.brain.core.ModelFit.physicalMemory(), modelSizes[config.model] ?: 0L, kvBytesPerToken)

    /** How many experts the model has when it is a mixture of experts (0 = dense or unknown). */
    var experts by mutableStateOf(0)
        private set

    /** Whether the model can call tools (null = unknown). */
    var tools by mutableStateOf<Boolean?>(null)
        private set

    /** The most tokens of context the model supports, as Ollama reports it; 0 when unknown. */
    /** The context window Ollama has really loaded the model with (from `/api/ps`); 0 until the model has run, or when Ollama does not say. */
    var loadedContext by mutableStateOf(0)
        private set

    suspend fun refreshLoadedContext() {
        loadedContext = runCatching { client.loadedContext(config.model) }.getOrDefault(0)
        val all = runCatching { client.loadedModels() }.getOrNull()
        loaded = all
        offloads = all.orEmpty().filter { it.cpuPercent >= 10 }.sortedByDescending { it.cpuPercent }.map { com.arcana.brain.core.Offload(it.name, it.cpuPercent, it.sizeBytes) }
    }

    /** Memory in use on the whole machine in bytes (null = not read yet): the second tone of the ram meter. Read with [cpuPercent]. */
    var usedMemory by mutableStateOf<Long?>(null)

    /** How busy the whole processor is (like htop), null until read: the `cpu` figure of the Chat status bar. Read every two seconds while the Chat is shown. */
    var cpuPercent by mutableStateOf<Int?>(null)

    /** Every model Ollama has loaded (null = not known yet or Ollama cannot be asked): the model-state indicator in the Chat status bar. */
    var loaded by mutableStateOf<List<com.arcana.brain.core.LoadedModel>?>(null)

    /** Why the model being waited for has not answered yet (loading it, reading the prompt, memory tight); empty when nothing is being waited for. Set by the waiting line, shown in the status bar. */
    var modelWhy by mutableStateOf("")

    /** The loaded models that run partly on the CPU because they do not fit in memory (slow; the Chat status bar warns). Read with the loaded context, after replies and helper work. */
    var offloads by mutableStateOf<List<com.arcana.brain.core.Offload>>(emptyList())

    /** The installed models' sizes in bytes (on the main server), for the memory advice; empty until read. */
    var modelSizes by mutableStateOf<Map<String, Long>>(emptyMap())

    /**
     * A note when [model] in [role] does not suit this machine's memory, with a better installed choice (see [com.arcana.brain.core.ModelFit.advice]); null when it is fine,
     * unknown, or when the Oracul runs on another server whose memory we cannot know.
     */
    fun memoryAdvice(model: String, role: com.arcana.brain.core.ModelFit.Role): String? {
        if (model.isBlank() || (role != com.arcana.brain.core.ModelFit.Role.Main && config.oraculUrl.isNotBlank())) return null
        return com.arcana.brain.core.ModelFit.advice(model, role, modelSizes, config.model, com.arcana.brain.core.ModelFit.physicalMemory(), config.oraculTools)
    }

    var modelContext by mutableStateOf(0)
        private set
    val client = OllamaClient { config }

    /** The scope replies are generated in: the app's own, so a reply keeps streaming when you switch to another screen. */
    lateinit var scope: kotlinx.coroutines.CoroutineScope

    /** The Chat screen's conversation: lives here so it survives switching screens, and is saved between runs. */
    val chat = ChatSession(this, chatStore)

    /** Every request ever made (loaded from `usage.jsonl`), newest last; the dashboard and the footer read it. */
    val usage = mutableStateListOf<UsageRecord>().also { it.addAll(usageStore.load()) }
    /** Every tool call the model made (loaded from `tool-usage.jsonl`), newest last; the Usage screen's Tools page reads it. */
    val toolUsage = mutableStateListOf<ToolUseRecord>().also { it.addAll(toolUseStore.load()) }

    /** What happened in the orchestration (redos, verdicts, routes, goals), loaded from `events.jsonl`; the Usage screen's Team page reads it. */
    val events = mutableStateListOf<StatEvent>().also { it.addAll(eventStore.load()) }

    fun recordEvent(kind: String, detail: String = "", model: String = "") {
        val event = StatEvent(System.currentTimeMillis(), kind, detail, model)
        events += event
        runCatching { eventStore.append(event) }
    }

    fun recordToolUse(record: ToolUseRecord) {
        toolUsage += record
        runCatching { toolUseStore.append(record) }
    }

    /** The working session: everything done since Arcana opened (or since "new session"), logged and kept. */
    val session = AppSession(sessionStore)
    val sessionStart: Long get() = session.started

    /** The map of the vault: links, tags and what needs attention. */
    val brain = BrainState(this)

    /**
     * Reply tokens streaming right now, counted by pieces (about one token each). Ollama reports exact counts only when a request
     * ends, so the counters add this meanwhile and it is taken back when the exact figures are recorded (or the request ends).
     */
    var liveTokens by mutableIntStateOf(0)
        private set

    fun addLiveTokens(delta: Int) {
        liveTokens = (liveTokens + delta).coerceAtLeast(0)
    }

    /** True while the setup wizard is showing: on the first start (no vault chosen and setup not finished), or from the palette. */
    var setupOpen by mutableStateOf(config.vaultPath.isBlank() && !config.setupDone)

    /** The Library folders that are unfolded (vault-relative paths); kept while Arcana runs, so leaving the Library and coming back keeps the tree as it was. */
    var libraryExpanded by mutableStateOf<Set<String>>(emptySet())

    /** State the screens keep while another tab is open: the Library's selection and scroll, the Brain's selected line (see [com.arcana.brain.app.design.ScreenMemory]). */
    val screenMemory = com.arcana.brain.app.design.ScreenMemory()

    /** Tokens used since Arcana started (including the reply streaming right now, as an estimate until it ends). */
    val sessionTokens: Long get() = usage.filter { it.time >= sessionStart }.sumOf { it.totalTokens.toLong() } + liveTokens

    /** Called when a reply finishes: keeps the tokens Ollama reported for it. */
    fun recordUsage(feature: String, chunk: ChatChunk, model: String = config.model) {
        val record = UsageRecord(System.currentTimeMillis(), feature, model, chunk.promptTokens, chunk.completionTokens, chunk.totalMillis)
        usage += record
        runCatching { usageStore.append(record) }
    }

    /** Clears the files of a [com.arcana.brain.core.StorageArea] (only those older than [olderThanDays] when it is above 0) and what is kept in memory for it; returns how many files went. */
    fun clearStorage(area: com.arcana.brain.core.StorageArea, olderThanDays: Int = 0): Int {
        val removed = runCatching { com.arcana.brain.core.Storage.clear(area, olderThanDays) }.getOrDefault(0)
        when (area.id) {
            com.arcana.brain.core.Storage.USAGE -> usage.clear()
            com.arcana.brain.core.Storage.TOOL_USAGE -> toolUsage.clear()
            com.arcana.brain.core.Storage.EVENTS -> events.clear()
        }
        return removed
    }

    fun clearUsage() {
        usage.clear()
        runCatching { usageStore.clear() }
        toolUsage.clear()
        runCatching { toolUseStore.clear() }
        events.clear()
        runCatching { eventStore.clear() }
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
        modelContext = shown?.contextLength ?: 0
        modelSizes = runCatching { client.modelSizes() }.getOrDefault(emptyMap())
        refreshLoadedContext()
        val capabilities = shown?.capabilities?.takeIf { it.isNotEmpty() }
        vision = capabilities?.let { "vision" in it }
        audio = capabilities?.let { "audio" in it }
        thinking = capabilities?.let { "thinking" in it }
        experts = shown?.experts ?: 0
        kvBytesPerToken = shown?.kvBytesPerToken ?: 0L
        thinkingValues = shown?.thinkingValues.orEmpty()
        modelParameters = shown?.parameters.orEmpty()
        com.arcana.brain.app.screens.ThinkLevels.options = com.arcana.brain.app.screens.thinkOptions(thinkingValues)
        thinkingDefault = shown?.thinkingDefault.orEmpty()
        com.arcana.brain.app.screens.ThinkLevels.defaultWord = com.arcana.brain.app.screens.thinkDefaultWord(thinkingDefault)
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
     * Writes a thought to the end of today's daily note, stamped with the time, or, with a [folder] (a folder at the top of the vault), as a note of
     * its own there. Each of [files] is copied into the vault's attachments folder and embedded in the thought. Returns the note's vault path.
     */
    fun capture(text: String, files: List<Path> = emptyList(), folder: String = ""): Result<String> = openVault().mapCatching { vault ->
        val embeds = files.map { "![[${vault.copyAttachment(config.outputFolder, it)}]]" }
        val full = (listOf(text.trim()) + embeds).filter { it.isNotEmpty() }.joinToString(if (folder.isBlank()) " " else "\n\n")
        val path = if (folder.isBlank()) vault.capture(full, dailyNotes(vault)) else vault.captureNote(folder, full).also { if (folder.trim('/') == dailyNotes(vault).folder.trim('/')) vault.listThought(it) }
        // a thought that starts with `? ` or `research: ` is also a question for the research backlog (best effort: the thought is saved either way)
        ResearchBacklog.capturedQuestion(text)?.let { q -> runCatching { ResearchBacklog.add(vault, q) } }
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

    /** The vault folder with the skills for [config]'s settings (automatic when blank: see [Layout.skillsFolder]); `Skills` when no vault is open. */
    fun skillsFolder(config: AppConfig = this.config): String =
        openVault().getOrNull()?.let { Layout.skillsFolder(it.root, config.skillsFolder) } ?: config.skillsFolder.trim('/').ifEmpty { Skills.DEFAULT_FOLDER }

    fun openVault(): Result<Vault> {
        val raw = config.vaultPath.trim()
        if (raw.isEmpty()) return Result.failure(VaultException("Vault path is not set. Open Settings and choose your vault folder."))
        return runCatching { Vault(expandHome(raw), config.memoryPath) }
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
