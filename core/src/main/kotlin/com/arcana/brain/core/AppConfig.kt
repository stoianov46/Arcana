package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** With `stepLimit = "auto"`: how many times one question may be given more steps without asking. */
const val STEP_LIMIT_AUTO_ROUNDS = 3

@Serializable
data class AppConfig(
    val ollamaUrl: String = "http://localhost:11434",
    val model: String = "gemma4:latest",
    val theme: String = "dark",
    val vaultPath: String = System.getenv("ARCANA_VAULT").orEmpty(),
    val outputFolder: String = "Research",
    /**
     * Sampling (see [Sampling]): `model` (default) sends none of the options below and the model's own defaults apply (its Modelfile); `custom` sends them. In custom mode [temperature] is
     * always sent and every other option only when it is set (null = the model's own). They affect only the chat model: helper models always use their own defaults.
     */
    val samplingMode: String = Sampling.MODEL,
    val temperature: Double = 0.7,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val presencePenalty: Double? = null,
    val repeatPenalty: Double? = null,
    val timeoutSeconds: Int = 300,
    val contextNotes: Int = 5,
    /**
     * HOW everything is searched (notes, code, documents; `/vault` answers and the model's search tools alike): "both" (words and meaning together; words only while no embedding model is
     * set up), "keywords" or "meaning". See [RetrievalMode].
     */
    val searchBy: String = "both",
    /**
     * WHERE things are searched, one list separated by `;` (Settings → Search → Search in): `vault` = the notes of the vault, `working` = the Working folder (Settings → Tools), anything else a
     * folder of code or documents (`~` is the home folder; it must lie inside the allowed folders, Settings → Tools). Remove an entry to stop searching it. See [SearchScope].
     */
    val searchIn: String = "vault; working",
    /** Show the advanced search settings (balance, noise filter, candidates, passages, limits) in Settings → Search. */
    val searchAdvanced: Boolean = false,
    /** How much weight meaning has against words when the two are fused, 0 to 10 (0 = words only decide, 5 = equal, 10 = meaning only decides): see [SearchTuning]. */
    val searchBalance: Int = 5,
    /** Noise filter, meaning side: a passage with too few of the query's words is left out when its vector similarity is more than this many hundredths below the best hit's (8 = 0.08; 0 = never left out for this). */
    val searchGap: Int = 8,
    /** Noise filter, words side: the least share of the query's words (percent) a passage must contain to stay when its similarity is not near the best (0 = no filter). */
    val searchMinWords: Int = 35,
    /** How many candidate passages each method (words, meaning) hands to the fusion. */
    val searchPool: Int = 40,
    /** How many passages of one note are sent to the model in a `/vault` answer. */
    val searchPassages: Int = 4,
    /** The biggest file (in KB) `search_files` reads; a bigger one is left out. */
    val searchMaxFileKb: Int = 300,
    val contextCharsPerNote: Int = 4000,
    val systemPrompt: String = "You are Arcana, a concise assistant that helps the user work with their Obsidian notes. " +
        "Answer in the language of the user's request. Use Markdown.",
    /** Interface density: "compact", "normal" or "spacious" (a terminal sets its own font size, so this is layout density). */
    val uiScale: String = "normal",
    /** Sidebar screens by name, in the user's order; the first one is where Arcana opens. Empty = the built-in order. */
    val tabOrder: List<String> = emptyList(),
    /** The sidebar folded to icons (Ctrl+B toggles it from anywhere). */
    val sidebarCollapsed: Boolean = false,
    /** Where the navigation sits: "left" sidebar, "right" sidebar, or a "top" bar. */
    val navPosition: String = "left",
    /** The sidebar logo's colours sweep while the model is generating. */
    val animateLogo: Boolean = true,
    val showModelTraits: Boolean = true,
    /** The `think` of a request: "default" (the model decides), "off", "on", or a level ("low", "medium", "high") for a model that lists it. */
    val thinking: String = "default",
    /** Plain words and ASCII instead of decorative glyphs and frames, for screen readers. */
    val plainGlyphs: Boolean = false,
    /** Render Markdown in output (headings, bullets, code); off shows the model's text as written. */
    val renderMarkdown: Boolean = true,
    /** Show "<model> is thinking…" while a reasoning model works before its first words. */
    val showThinking: Boolean = true,
    /** How a reasoning model's thoughts show in Chat: "hidden", "summary" (one line with the length) or "full" (the whole text, dimmed, before the answer). */
    val thinkingView: String = "summary",
    /** How Arcana's own prompts to the model (todo reminder, redo, unmet goal, revise) show in Chat: `marked` (a System block) or `hidden` (they are still sent). */
    val systemMessages: String = "marked",
    /** How a `/compare` result is drawn in Chat: `side` (the answers next to each other, when the window is wide enough) or `stacked` (one under another). */
    val comparisonView: String = "side",
    /** Compact the chat automatically (summarize the older part, see [Compaction]) before a message when the context window is this percent full; 0 turns it off. */
    val autoCompactPercent: Int = 80,
    /** When the chat is compacted, the latest turns that fit in this share of the context window stay word for word (the rest becomes the summary); 0 = the last [Compaction.KEEP_USER_TURNS] turns. */
    val compactKeepPercent: Int = 30,
    /** How many of the latest chat messages are sent to the model as context. */
    val chatHistory: Int = 0,
    /** Esc on a running reply (or an Oracul run) asks "Cancel this reply?" first; off = Esc cancels at once. */
    val confirmCancel: Boolean = true,
    /** Chats are saved to disk and the last one is reopened when Arcana starts. */
    /** The embedding model of the semantic search (`semantic_search` tool, `/reindex`): an installed Ollama model such as nomic-embed-text. Blank = the semantic search is off. */
    val embedModel: String = "",
    /** Daily notes: used only when the vault has no Obsidian Daily notes settings of its own. */
    val dailyFolder: String = "Thoughts",
    /** `thought-2026-11-02.md`: the prefix keeps the thought files apart from other date-named notes; `[text]` is literal (Obsidian's syntax). */
    val dailyFormat: String = "[thought-]YYYY-MM-DD",
    /** Where capture (Ctrl+N) saves a thought: empty = a line in today's daily note, else a folder at the top of the vault (each thought becomes a note there). Remembered from the last capture. */
    val captureFolder: String = "",
    /** A note counts as stale after this many days without a change. */
    val staleDays: Int = 90,
    val keepChats: Boolean = true,
    /** Chat searches the vault for each question and answers from the notes it finds, citing them. */
    val chatUseVault: Boolean = false,
    /** The folder the image picker was last left in (empty = start at the home folder). */
    val lastPickerDir: String = "",
    /** How many chats are kept; the least recently used are deleted. */
    val chatsKept: Int = 50,
    /** Chat may use tools: read and write notes and files. Off by default. */
    val toolsEnabled: Boolean = false,
    /** Folders besides the vault that tools may read and write, separated by ";". */
    val toolFolders: String = "",
    /** Tools that only read run without asking; off asks about every call. Writes are always asked. */
    val toolAutoRead: Boolean = true,
    /** What the same tokens would cost on a paid service, US dollars per million tokens: only for the "estimated price" in the status bars (local models cost nothing). */
    val estPriceIn: Int = 3,
    val estPriceOut: Int = 15,
    /** Set once the memory file has been created or found, so a file the user deletes is not created again behind their back. */
    val memoryOffered: Boolean = false,
    /** The vault note ARCANA.md (standing instructions and facts) is added to every chat's system prompt. */
    val memoryEnabled: Boolean = true,
    /** Where the memory file (ARCANA.md) lives: blank = `ARCANA.md` at the vault root; a relative path is inside the vault; `~/…` or an absolute path is anywhere on this computer. */
    val memoryPath: String = "",
    /** Context window to ask Ollama for (num_ctx); 0 leaves Ollama's own default. Also the size the Chat context meter counts against when set. */
    val contextTokens: Int = 0,
    /**
     * How long Ollama keeps the chat model in memory after a request (`keep_alive`): blank = not sent, so Ollama's own default applies (5 minutes: after a longer pause the model
     * is loaded again and the whole conversation is read again, which for a long chat takes minutes); `30m`, `1h`; `-1` = until Ollama stops. Sent only with requests to the chat
     * model (see [forModel]), because every request sets the timer again.
     */
    val keepAlive: String = "",
    /** A tool call the model wrote as text instead of making it: "auto" runs it as a real call, "ask" shows it first, "off" leaves it as text. */
    val textCalls: String = "auto",
    /** Where web_search looks: "duckduckgo" (no setup), "searxng" (a server the user runs, at [searchUrl]) or "brave" (needs [searchKey]). */
    val searchBackend: String = "duckduckgo",
    val searchUrl: String = "",
    val searchKey: String = "",
    /** The Oracul (see [Oracul]): the model that gives second opinions; blank = off. */
    val oraculModel: String = "",
    /** The master switch for every helper model: the Oracul and its second opinions, the judge, the flow, the specialists (`consult`) and the router. Off: only the chat model works (skill roles have their own switch, [skillRolesEnabled]). The models stay set, so switching back loses nothing. */
    val oraculEnabled: Boolean = true,
    /** Skill roles: the worker, planner and reviewer a skill names (or the role table gives) for a skill run. Off: a skill is run by the chat model alone. Separate from [oraculEnabled]. */
    val skillRolesEnabled: Boolean = true,
    /** Default models for skill runs (a skill's own `planner:` / `worker:` / `reviewer:` wins); blank = not used. See [SkillRoles]. */
    val rolePlanner: String = "",
    val roleWorker: String = "",
    val roleReviewer: String = "",
    /** Where the role table applies: "skills" (only a run that carries a skill) or "all" (every message: plan, worker, review). */
    val rolesScope: String = "skills",
    /** The flow that runs for every message: "off" or "careful" (see [Flow]). A flow brings its own scope: the role table applies to every message while it is on. */
    val flow: String = "off",
    /** The model of the flow's analyst stage; blank = the Oracul model. */
    val flowAnalyst: String = "",
    /** The model of the flow's controller stage; blank = the Oracul model. */
    val flowController: String = "",
    /** How many times the controller may send the worker back to redo an answer (0 = it only gives its verdict). */
    val flowRedos: Int = 2,
    /** The main model may call the analyst, planner and reviewer itself as a tool (`consult`, subagent style), when the stage has a model; needs tools on. */
    val flowTools: Boolean = false,
    /** An objective check for the flow: a shell command that runs in the project folder after each answer (`./gradlew test`). Exit code 0 = passed (the controller is told); anything else = redo, with the output as the notes. Needs the shell tools and auto-run on. Blank = no command. */
    val flowCheckCommand: String = "",
    /** The router (see [Router]): the model that answers a message with an image attached, and the one for a message with code in it; blank = the chat model. */
    val routeVision: String = "",
    val routeCode: String = "",
    /** More models that answer a comparison (`/compare`) besides the Oracul model, comma-separated; the judge then compares all the answers. */
    val oraculExtra: String = "",
    /** Another Ollama server for the Oracul and the judge (a bigger machine on your network); blank = the same server as the main model. */
    val oraculUrl: String = "",
    /** The model that judges a comparison; blank = the Oracul model. */
    val judgeModel: String = "",
    /** When a second opinion is asked for without being asked: `off`, `problems` (failed tool calls, skipped skill steps) or `always`. */
    val oraculAuto: String = "problems",
    /** When an automatic second opinion is due, run a full comparison instead (the Oracul answers too, the judge compares). Slower: three model runs. Off by default. */
    val oraculJudgeAuto: Boolean = false,
    /** After an automatic opinion, the judge model (when [judgeModel] is set) says whether the opinion is right; advisory. */
    val oraculVerdict: Boolean = true,
    /** What happens when the judge says AGREE or PARTLY on an automatic opinion: "ask" (a dialog with choices: revise, revise with a note of yours, leave it, revise automatically from now on), "auto" (the chat model revises by itself, once per message) or "advice" (only the blocks, you type /revise). */
    val verdictAction: String = "ask",
    /** When the model ends its turn with open steps on its own todo list, it is sent back once to finish them or close the list. */
    val todoGuard: Boolean = true,
    /** How many times `/goal` may continue the work by itself for one goal before it stops. */
    val goalTurns: Int = 5,
    /** How many earlier messages of the chat the Oracul, the judge and the planner are shown besides the last question (0 = none), and how many characters of each. */
    val oraculContextMessages: Int = 0,
    val oraculContextChars: Int = 3000,
    /** The worker counts as stuck, and the Oracul steps into its tool loop, after this many failed tool calls in one turn (and again after every further such number); 0 = never. */
    val stuckCalls: Int = 4,
    /** An automatic review is due after this many turns in a row that each had a failed tool call; 0 = never. */
    val stuckTurns: Int = 3,
    /** The loop guard stops a round whose text (thinking or answer) has repeated the same stretch this many times, and retries it once with a nudge; 0 = off. */
    val loopRepeats: Int = 5,
    /** The loop guard warns a model that repeats an identical tool call (nothing changed in between) at this many repeats in one turn, and takes the tools away at twice as many; 0 = off. */
    val loopCalls: Int = 3,
    /** The Oracul may use the worker's tools to check an answer (reads follow *Read without asking*; every change, command and web access asks the user, even in Auto mode). Needs tools on and an Oracul model that supports them. */
    val oraculTools: Boolean = true,
    /** The first-run setup (vault, memory file, skills) was finished or skipped; it opens by itself only until then. */
    val setupDone: Boolean = false,
    /** Sounds for what happens in Chat (see [SoundChoices] for the ids): when the model waits for you, when a reply finishes, when it fails. All off by default. */
    val soundAttention: String = "off",
    val soundDone: String = "off",
    val soundFailed: String = "off",
    /** Volume of system and file sounds (the terminal bell has none), in percent. */
    val soundVolume: Int = 80,
    /** The sound file used by the choice "Your own file" (.wav, .aiff, .mp3). */
    val soundFile: String = "",
    /** "away": a sound plays only while another screen than Chat is showing; "always": also while you look at Chat. */
    val soundWhere: String = "away",
    /** The project the user works on: a folder that is allowed like the vault, where relative paths and commands start, and whose own instructions (ARCANA.md, CLAUDE.md or AGENTS.md) go to the model. Empty = none. */
    val projectFolder: String = "",
    /** The model may ask to read web pages (fetch_url; every address asks first). Off by default. */
    val toolWeb: Boolean = false,
    /** web_search and fetch_url run without asking (the search words or the page address leave the computer). HTTP requests still ask; the Oracul always asks. Off by default. */
    val toolAutoSearch: Boolean = false,
    /** The model may ask to run shell commands (every one needs approval). Off by default. */
    val toolShell: Boolean = false,
    /** Shell commands run without asking (those [ShellPolicy] refuses never run). Off by default; the Chat status bar shows `▶ auto-run` while it is on. */
    val toolAutoShell: Boolean = false,
    /** What tools may do: "ask" (changes wait for approval), "auto" (changes run at once, backed up) or "plan" (read-only, the model writes a plan). */
    val toolMode: String = "ask",
    /** How many rounds of tool use one question may take before the model must answer (0 = unlimited). */
    val toolSteps: Int = 8,
    /** The skill a research item runs with unless the item pins another: a skill name, `none` (a plain chat) or blank (automatic: the first skill that handles research). */
    val workSkillResearch: String = "",
    /** The same for questions (blank = a plain chat). */
    val workSkillQuestion: String = "",
    /** The same for todos (blank = a plain chat). */
    val workSkillTodo: String = "",
    /** A pane under the Chat conversation that shows what a reasoning model is thinking while it streams (and the last thoughts when it is idle). Off by default. */
    val thinkingPane: Boolean = false,
    /** Where the thinking pane sits around the conversation: "bottom", "top", "left" or "right". */
    val thinkingPanePlacement: String = "bottom",
    /** How much of the main axis the thinking pane takes, in percent: of the height for "top" and "bottom", of the width for "left" and "right". */
    val thinkingPanePercent: Int = 30,
    /** What happens to the results of tools from earlier turns when the chat is sent again: "whole" (kept as they were; the default, nothing is cut silently) or "shorten" (each cut to 1,200 characters, for small context windows). */
    val oldToolOutput: String = "whole",
    /** How many seconds an identical read, search or page fetch of the current question is answered from memory instead of being run (and asked about) again; 0 = every call runs. */
    val repeatMemorySeconds: Int = 45,
    /** What happens when a question uses up its steps while the model still wants tools: "ask" (a dialog), "auto" (more steps at once, at most [STEP_LIMIT_AUTO_ROUNDS] times, then ask) or "stop" (it answers with what it has). */
    val stepLimit: String = "ask",
    /** Skills (runbooks in the vault, see Skills.kt): the model gets a list of them and can load one with use_skill; Chat's "/" menu offers them. */
    val skillsEnabled: Boolean = true,
    /** The vault folder that holds one folder per skill. Blank = automatic ([Layout.skillsFolder]: `.arcana/skills`, or an existing `Skills/`). */
    val skillsFolder: String = "",
    /** The Library lists its notes as a tree of folders that fold and unfold (off: one flat list with the full paths). */
    val libraryTree: Boolean = true,
    /** The view the Library opens on from the sidebar: a [LibraryFilter] name in lower case (`all`, `recent`, `orphans`, `untagged`, `broken`, `stale`, `tags`). */
    val libraryTab: String = "all",
    /** How much of a wide Library the list takes, in percent (the preview gets the rest). */
    val libraryListPercent: Int = 30,
    /** How the lists that are ordered by date are ordered: "modified" (newest first) or "title" (A to Z). */
    val librarySort: String = "modified",
    /** Show the note preview beside the list in a wide window (off: the list takes the whole width and Enter opens the note). */
    val libraryPreview: Boolean = true,
    /** Show the number of notes in each Library tab (`All 120`). */
    val libraryCounts: Boolean = true,
    /** What select mode copies: "markdown" (as written) or "plain" (Markdown markers removed). */
    val copyAs: String = "markdown",
    val summaryPrompt: String = "Summarize the following note. Start with a one-sentence TL;DR, " +
        "then list the key points as bullets, then any action items.",
) {
    /**
     * This configuration for a request to [model]: [keepAlive] stays only when [model] is the chat model (every request starts the keep-alive timer again, so a helper that is
     * the same model must send it too, and another model must not be pinned by it). Another model also gets no custom sampling: the values are chosen for the chat model.
     */
    /** [toolSteps] as a limit: 0 means unlimited (the model is never stopped for using too many rounds; the loop guards still work). */
    val toolStepLimit: Int get() = if (toolSteps <= 0) Int.MAX_VALUE else toolSteps

    fun forModel(model: String): AppConfig =
        if (model == this.model) copy(model = model) else copy(model = model, keepAlive = "", samplingMode = Sampling.MODEL)

    /** The `keep_alive` to send, as Ollama takes it (`-1` and `0` are numbers, a time like `30m` is text); null when blank or not a time. */
    fun keepAliveValue(): kotlinx.serialization.json.JsonPrimitive? = keepAlive.trim().let { k ->
        when {
            k == "-1" || k == "0" -> kotlinx.serialization.json.JsonPrimitive(k.toInt())
            Regex("""\d+[smh]""").matches(k) -> kotlinx.serialization.json.JsonPrimitive(k)
            else -> null
        }
    }
}

class ConfigStore(val path: Path = defaultPath()) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppConfig {
        if (!Files.exists(path)) return AppConfig()
        return runCatching { json.decodeFromString<AppConfig>(Files.readString(path)) }
            .map(::migrated)
            .getOrElse { AppConfig() }
    }

    /** A config saved before thought files got their prefix still holds the old default format: it is moved to the new one, so every next file follows the scheme. */
    private fun migrated(config: AppConfig): AppConfig {
        var c = config
        if (c.dailyFormat == "YYYY-MM-DD") c = c.copy(dailyFormat = AppConfig().dailyFormat)
        // and one saved when thought files lay in the vault root: they now get a folder of their own
        if (c.dailyFolder.isEmpty()) c = c.copy(dailyFolder = AppConfig().dailyFolder)
        return c
    }

    fun save(config: AppConfig) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(AppConfig.serializer(), config))
    }

    companion object {
        fun defaultPath(): Path {
            System.getenv("ARCANA_CONFIG")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            return Path.of(System.getProperty("user.home"), ".config", "arcana", "config.json")
        }
    }
}
