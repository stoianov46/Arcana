package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppConfig(
    val ollamaUrl: String = "http://localhost:11434",
    val model: String = "gemma4:latest",
    val theme: String = "dark",
    val vaultPath: String = System.getenv("ARCANA_VAULT").orEmpty(),
    val outputFolder: String = "Arcana",
    val temperature: Double = 0.7,
    val timeoutSeconds: Int = 300,
    val contextNotes: Int = 5,
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
    /** Plain words and ASCII instead of decorative glyphs and frames, for screen readers. */
    val plainGlyphs: Boolean = false,
    /** Render Markdown in output (headings, bullets, code); off shows the model's text as written. */
    val renderMarkdown: Boolean = true,
    /** Show "<model> is thinking…" while a reasoning model works before its first words. */
    val showThinking: Boolean = true,
    /** How a reasoning model's thoughts show in Chat: "hidden", "summary" (one line with the length) or "full" (the whole text, dimmed, before the answer). */
    val thinkingView: String = "summary",
    /** How a `/compare` result is drawn in Chat: `side` (the answers next to each other, when the window is wide enough) or `stacked` (one under another). */
    val comparisonView: String = "side",
    /** Compact the chat automatically (summarize the older part, see [Compaction]) before a message when the context window is this percent full; 0 turns it off. */
    val autoCompactPercent: Int = 80,
    /** How many of the latest chat messages are sent to the model as context. */
    val chatHistory: Int = 20,
    /** Chats are saved to disk and the last one is reopened when Arcana starts. */
    /** The folder where new, unprocessed notes land; Arcana counts what is waiting there. */
    val inboxFolder: String = "Inbox",
    /** Daily notes: used only when the vault has no Obsidian Daily notes settings of its own. */
    val dailyFolder: String = "",
    val dailyFormat: String = "YYYY-MM-DD",
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
    /** A tool call the model wrote as text instead of making it: "auto" runs it as a real call, "ask" shows it first, "off" leaves it as text. */
    val textCalls: String = "auto",
    /** Where web_search looks: "duckduckgo" (no setup), "searxng" (a server the user runs, at [searchUrl]) or "brave" (needs [searchKey]). */
    val searchBackend: String = "duckduckgo",
    val searchUrl: String = "",
    val searchKey: String = "",
    /** The Oracul (see [Oracul]): the model that gives second opinions; blank = off. */
    val oraculModel: String = "",
    /** Default models for skill runs (a skill's own `planner:` / `worker:` / `reviewer:` wins); blank = not used. See [SkillRoles]. */
    val rolePlanner: String = "",
    val roleWorker: String = "",
    val roleReviewer: String = "",
    /** More models that answer a comparison (`/compare`) besides the Oracul model, comma-separated; the judge then compares all the answers. */
    val oraculExtra: String = "",
    /** Another Ollama server for the Oracul and the judge (a bigger machine on your network); blank = the same server as the main model. */
    val oraculUrl: String = "",
    /** The model that judges a comparison; blank = the Oracul model. */
    val judgeModel: String = "",
    /** When a second opinion is asked for without being asked: `off`, `problems` (failed tool calls, skipped skill steps) or `always`. */
    val oraculAuto: String = "problems",
    /** The worker counts as stuck, and the Oracul steps into its tool loop, after this many failed tool calls in one turn (and again after every further such number); 0 = never. */
    val stuckCalls: Int = 4,
    /** An automatic review is due after this many turns in a row that each had a failed tool call; 0 = never. */
    val stuckTurns: Int = 3,
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
    /** The model may ask to run shell commands (every one needs approval). Off by default. */
    val toolShell: Boolean = false,
    /** Shell commands run without asking (those [ShellPolicy] refuses never run). Off by default; the Chat status bar shows `▶ auto-run` while it is on. */
    val toolAutoShell: Boolean = false,
    /** What tools may do: "ask" (changes wait for approval), "auto" (changes run at once, backed up) or "plan" (read-only, the model writes a plan). */
    val toolMode: String = "ask",
    /** How many rounds of tool use one question may take before the model must answer. */
    val toolSteps: Int = 8,
    /** Skills (runbooks in the vault, see Skills.kt): the model gets a list of them and can load one with use_skill; Chat's "/" menu offers them. */
    val skillsEnabled: Boolean = true,
    /** The vault folder that holds one folder per skill. */
    val skillsFolder: String = "Skills",
    /** The Library lists its notes as a tree of folders that fold and unfold (off: one flat list with the full paths). */
    val libraryTree: Boolean = true,
    /** What select mode copies: "markdown" (as written) or "plain" (Markdown markers removed). */
    val copyAs: String = "markdown",
    val summaryPrompt: String = "Summarize the following note. Start with a one-sentence TL;DR, " +
        "then list the key points as bullets, then any action items.",
)

class ConfigStore(val path: Path = defaultPath()) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppConfig {
        if (!Files.exists(path)) return AppConfig()
        return runCatching { json.decodeFromString<AppConfig>(Files.readString(path)) }
            .getOrElse { AppConfig() }
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
