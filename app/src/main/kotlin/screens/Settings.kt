package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.core.ModelFit.Role as FitRole
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.Flow
import com.arcana.brain.core.STEP_LIMIT_AUTO_ROUNDS
import com.arcana.brain.core.Skills
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.launch

/** Backups and trash are pruned by age: what is older than this many days. */
private const val PRUNE_DAYS = 30

private enum class Group(val title: String, val about: String = "") {
    Ollama("Ollama"), Vault("Vault"), Library("Library"), Appearance("Appearance"), Console("Console"), Tools("Tools"), Prompts("Prompts"), Helpers("Helpers", "Models other than the chat model, and the master switch that turns them all off. The Oracul gives second opinions; the router sends images and code to their own models."), Opinions("Opinions", "When the Oracul speaks up by itself, the judge's verdict on it, and when it steps in because the chat model keeps failing; the loop guard against a model that repeats itself needs no helper model."), Flow("Flow", "Every message through analyst, planner, worker, reviewer and controller, each its own model; specialists as tools; /goal."), Skills("Skills", "The worker, planner and reviewer a skill run uses (its own properties win), with their own on/off switch."), Sounds("Sounds"), Storage("Storage"), Accessibility("Accessibility"), Tabs("Tabs"),
}

/** How Enter edits a setting that is not simply stepped with ←/→. */
private sealed interface Editor {
    /** Types a value; [apply] throws IllegalArgumentException with a user-facing message on invalid input. */
    class Text(val apply: (AppConfig, String) -> AppConfig) : Editor
    /** Enter lists the installed models; [set] stores the choice in the setting this editor belongs to ([allowNone]: blank is a valid choice and means off). */
    class Model(val label: String, val get: (AppConfig) -> String, val set: (AppConfig, String) -> AppConfig, val allowNone: Boolean = false, val oracul: Boolean = false, val multi: Boolean = false) : Editor
    data object Folder : Editor
    data object Theme : Editor
    /** Enter asks to clear the files of one [com.arcana.brain.core.Storage] area. */
    class Clear(val area: String) : Editor
}

private class Setting(
    val group: Group,
    val label: String,
    val help: String,
    val value: (AppConfig) -> String,
    /** ←/→ (and Enter, when there is no [editor]) step the value; null = not steppable. */
    val step: ((AppConfig, Int) -> AppConfig)? = null,
    val editor: Editor? = null,
)

private fun choice(group: Group, label: String, help: String, options: List<Pair<String, String>>, get: (AppConfig) -> String, set: (AppConfig, String) -> AppConfig) =
    Setting(
        group, label, help,
        value = { c -> options.firstOrNull { it.first == get(c) }?.second ?: get(c) },
        step = { c, dir ->
            val i = options.indexOfFirst { it.first == get(c) }.coerceAtLeast(0)
            set(c, options[(i + dir).mod(options.size)].first)
        },
    )

private fun toggle(group: Group, label: String, help: String, get: (AppConfig) -> Boolean, set: (AppConfig, Boolean) -> AppConfig) =
    choice(group, label, help, listOf("on" to "On", "off" to "Off"), { if (get(it)) "on" else "off" }, { c, v -> set(c, v == "on") })

private fun number(text: String, name: String): Int =
    text.trim().toIntOrNull() ?: throw IllegalArgumentException("$name must be a whole number")

private fun stepper(group: Group, label: String, help: String, range: IntRange, step: Int, get: (AppConfig) -> Int, set: (AppConfig, Int) -> AppConfig, unit: String = "", zero: String = "") =
    Setting(
        group, label, help,
        value = { if (zero.isNotEmpty() && get(it) == 0) zero else get(it).toString() + unit },
        step = { c, dir -> set(c, (get(c) + dir * step).coerceIn(range)) },
        editor = Editor.Text { c, v ->
            val n = number(v, label)
            require(n in range) { "$label must be ${range.first}–${range.last}" }
            set(c, n)
        },
    )

private fun text(group: Group, label: String, help: String, get: (AppConfig) -> String, set: (AppConfig, String) -> AppConfig, allowEmpty: Boolean = false, emptyLabel: String = "(none)", emptyShown: ((AppConfig) -> String)? = null) =
    Setting(
        group, label, help,
        value = { c -> get(c).ifEmpty { emptyShown?.invoke(c) ?: emptyLabel } },
        editor = Editor.Text { c, v ->
            require(allowEmpty || v.isNotBlank()) { "$label cannot be empty" }
            set(c, v.trim())
        },
    )

/** Plays [choice] at once, so a sound is heard as it is chosen. */
private fun hear(config: AppConfig, choice: String) = com.arcana.brain.core.Sounds.player.play(choice, config.soundVolume, config.soundFile, null)

private val soundOptions = com.arcana.brain.core.SoundChoices.ids.map { it to com.arcana.brain.core.SoundChoices.label(it) }

private fun soundSetting(label: String, help: String, event: com.arcana.brain.core.ChatSoundEvent, get: (AppConfig) -> String, set: (AppConfig, String) -> AppConfig) =
    choice(Group.Sounds, label, "$help ←/→ chooses and plays it. Off by default; the terminal bell is the fallback where system sounds do not exist (not macOS).", soundOptions, get) { c, v ->
        // leaving "off" forwards lands on this event's own suggested sound, so one key press already gives something sensible
        val next = if (get(c) == com.arcana.brain.core.SoundChoices.OFF && v == com.arcana.brain.core.SoundChoices.system.first().let { "sys:$it" }) com.arcana.brain.core.SoundChoices.suggested(event) else v
        hear(c, next)
        set(c, next)
    }

private fun tilde(path: String) = path.replace(System.getProperty("user.home"), "~")

private fun vaultRoot(c: AppConfig): Path? = c.vaultPath.trim().takeIf { it.isNotEmpty() }?.let { AppContext.expandHome(it) }?.takeIf { Files.isDirectory(it) }

/** Where the skills really are for these settings: the automatic choice resolved to a path, so the user sees it. */
private fun skillsWhere(c: AppConfig): String = vaultRoot(c)?.let { tilde(it.resolve(com.arcana.brain.core.Layout.skillsFolder(it, c.skillsFolder)).toString()) } ?: "(no vault yet)"

/** Where the memory file really is for these settings. */
private fun memoryWhere(c: AppConfig): String = vaultRoot(c)?.let { runCatching { tilde(com.arcana.brain.core.Vault(it, c.memoryPath).memoryFile().toString()) }.getOrNull() } ?: "(no vault yet)"

/** The folder that holds Arcana's own files (`config.json` and the rest). */
private fun storageRoot(): Path = com.arcana.brain.core.ConfigStore.defaultPath().toAbsolutePath().parent

/** The storage areas as they are now: the vault's own `.trash` is among them when a vault is set. */
private fun storageAreas(config: AppConfig): List<com.arcana.brain.core.StorageArea> =
    com.arcana.brain.core.Storage.areas(storageRoot(), config.vaultPath.trim().takeIf { it.isNotEmpty() }?.let { AppContext.expandHome(it) }?.takeIf { Files.isDirectory(it) })

private fun storageSetting(id: String): Setting {
    val template = com.arcana.brain.core.Storage.areas(Path.of("."), Path.of(".")).first { it.id == id }
    return Setting(
        Group.Storage, template.title, "${template.detail.replaceFirstChar { it.uppercase() }}. Enter clears it after you confirm. Nothing here is your notes or your settings.",
        value = { c ->
            val area = storageAreas(c).firstOrNull { it.id == id }
            if (area == null) "(no vault)" else com.arcana.brain.core.Storage.use(area).let { if (it.files == 0) "empty" else "${it.files} file${if (it.files == 1) "" else "s"} · ${com.arcana.brain.core.Storage.size(it.bytes)}" }
        },
        editor = Editor.Clear(id),
    )
}

/** The editor of the main model setting (declared before [settings], which uses it). */
private val MainModel = Editor.Model("Model", { it.model }, { c, v -> c.copy(model = v) })

private val OraculModel = Editor.Model("Oracul model", { it.oraculModel }, { c, v -> c.copy(oraculModel = v) }, allowNone = true, oracul = true)
private val ExtraModels = Editor.Model("More Oracul models", { it.oraculExtra }, { c, v -> c.copy(oraculExtra = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")) }, allowNone = true, oracul = true, multi = true)
private val AnalystRole = Editor.Model("Flow analyst", { it.flowAnalyst }, { c, v -> c.copy(flowAnalyst = v) }, allowNone = true, oracul = true)
private val ControllerRole = Editor.Model("Flow controller", { it.flowController }, { c, v -> c.copy(flowController = v) }, allowNone = true, oracul = true)
private val RouteVisionRole = Editor.Model("Route: images to", { it.routeVision }, { c, v -> c.copy(routeVision = v) }, allowNone = true)
private val RouteCodeRole = Editor.Model("Route: code to", { it.routeCode }, { c, v -> c.copy(routeCode = v) }, allowNone = true)
private val PlannerRole = Editor.Model("Skill planner", { it.rolePlanner }, { c, v -> c.copy(rolePlanner = v) }, allowNone = true, oracul = true)
private val WorkerRole = Editor.Model("Skill worker", { it.roleWorker }, { c, v -> c.copy(roleWorker = v) }, allowNone = true)
private val ReviewerRole = Editor.Model("Skill reviewer", { it.roleReviewer }, { c, v -> c.copy(roleReviewer = v) }, allowNone = true, oracul = true)
private val JudgeModel = Editor.Model("Judge model", { it.judgeModel }, { c, v -> c.copy(judgeModel = v) }, allowNone = true, oracul = true)

private val declared: List<Setting> = listOf(
    text(Group.Ollama, "Ollama URL", "Where the Ollama server listens.", { it.ollamaUrl }, { c, v ->
        require(v.startsWith("http://") || v.startsWith("https://")) { "URL must start with http:// or https://" }
        c.copy(ollamaUrl = v.trimEnd('/'))
    }),
    Setting(Group.Ollama, "Model", "The model that answers. Enter lists what is installed.", { it.model }, editor = MainModel),
    Setting(
        Group.Ollama, "Temperature", "0 is focused and repeatable, 2 is adventurous. ←/→ step by 0.1, Enter types a value.",
        value = { it.temperature.toString() },
        step = { c, dir -> c.copy(temperature = ((c.temperature * 10).toInt() + dir).coerceIn(0, 20) / 10.0) },
        editor = Editor.Text { c, v ->
            val t = v.trim().toDoubleOrNull()
            require(t != null && t in 0.0..2.0) { "Temperature must be between 0 and 2" }
            c.copy(temperature = t)
        },
    ),
    choice(Group.Ollama, "Context window", "How many tokens of conversation the model may hold (num_ctx). Model default leaves Ollama's own setting. A larger window remembers more of a long chat or a big file, but its memory (the KV cache) grows with it and replies get slower: a 30B model at 128k needs roughly 12 GB more than at 8k. It can never exceed what the model was built for (the status bar shows that maximum; most models stop at 128k–256k, a million needs a special model and a lot of memory). The Chat status bar counts against this.", listOf("0" to "Model default", "8192" to "8k", "16384" to "16k", "32768" to "32k", "65536" to "64k", "131072" to "128k", "262144" to "256k", "524288" to "512k", "1048576" to "1M"), { it.contextTokens.toString() }, { c, v -> c.copy(contextTokens = v.toInt()) }),
    stepper(Group.Ollama, "Timeout", "How long to wait for a reply before giving up. ←/→ step by 15 s.", 5..3600, 15, { it.timeoutSeconds }, { c, n -> c.copy(timeoutSeconds = n) }, " s"),

    Setting(
        Group.Vault, "Vault path", "Your Obsidian vault folder. Enter opens a folder browser.",
        value = { it.vaultPath.ifEmpty { "(not set)" } }, editor = Editor.Folder,
    ),
    toggle(Group.Library, "Library as a tree", "The Library shows your notes as folders that fold and unfold (Enter or h / l). Off: one flat list with the full path of every note. You can also press t in the Library.", { it.libraryTree }, { c, v -> c.copy(libraryTree = v) }),
    text(Group.Vault, "Output folder", "What Arcana writes FOR you (AI notes, saved chats, session summaries) goes to this visible folder inside the vault. Reports and research from skills go to the folders their skills name (for example Reports/…). Arcana's own working files (skills, the memory file) are not content and live apart, hidden: see the next three rows. Empty = the vault root.", { it.outputFolder }, { c, v -> c.copy(outputFolder = v.trim('/')) }, allowEmpty = true),
    Setting(
        Group.Vault, "Where Arcana's own files are (this vault)", "Arcana's working files for THIS vault live in the hidden folder .arcana/ inside it (Obsidian, the note search and the graph do not show it): skills in .arcana/skills/ and the memory file .arcana/ARCANA.md. An older vault that still has Skills/ or a root ARCANA.md keeps using them until you run arcana --migrate in a terminal (it moves them, never overwrites). Show hidden files in Finder with Cmd+Shift+. This row only shows the place; change it with the Skills folder and Memory file path settings.",
        value = { c -> vaultRoot(c)?.let { tilde(it.resolve(com.arcana.brain.core.Layout.INTERNAL).toString()) + "/" } ?: "(no vault yet)" },
    ),
    Setting(
        Group.Vault, "Where YOUR files are (every vault)", "Next to the settings, in ~/.config/arcana/ (or where ARCANA_CONFIG points): config.json, chats, usage logs, backups and the trash, and, for every vault: skills/ (skills that apply everywhere; a skill of the same name in the vault wins) and ARCANA.md (your own memory file, read before the vault's). Settings → Storage lists and clears the logs and caches. This row only shows the place.",
        value = { tilde(storageRoot().toString()) + "/" },
    ),
    stepper(Group.Vault, "Context notes", "How many notes Ask your vault sends to the model.", 1..20, 1, { it.contextNotes }, { c, n -> c.copy(contextNotes = n) }),
    text(Group.Vault, "Inbox folder", "Where new, unprocessed notes land. The Brain screen counts what is waiting there.", { it.inboxFolder }, { c, v -> c.copy(inboxFolder = v.trim('/')) }, allowEmpty = true),
    text(Group.Vault, "Daily notes folder", "Where capture (Ctrl+N) writes today's note. Used only when Obsidian's Daily notes plugin has no setting of its own for the vault. Empty = the vault root.", { it.dailyFolder }, { c, v -> c.copy(dailyFolder = v.trim('/')) }, allowEmpty = true),
    text(Group.Vault, "Daily note format", "The file name of a daily note, like Obsidian's: YYYY-MM-DD, DD.MM.YYYY… Used only without an Obsidian setting.", { it.dailyFormat }, { c, v -> c.copy(dailyFormat = v.trim()) }),
    stepper(Group.Vault, "Stale after", "A note counts as stale after this many days without a change. ←/→ step by 30.", 7..1000, 30, { it.staleDays }, { c, n -> c.copy(staleDays = n) }, " days"),
    stepper(Group.Vault, "Chars per note", "How much of each note found by Answer from notes (/vault) is sent to the model. A longer note is cut: the model is told, and the answer lists which notes were only partly read. ←/→ step by 500.", 200..20_000, 500, { it.contextCharsPerNote }, { c, n -> c.copy(contextCharsPerNote = n) }),

    Setting(
        Group.Appearance, "Colour theme", "Applies at once. ←/→ step through the themes, Enter opens the list with a preview.",
        value = { Themes.byId(it.theme).name },
        step = { c, dir ->
            val i = Themes.all.indexOfFirst { it.id == c.theme }.coerceAtLeast(0)
            c.copy(theme = Themes.all[(i + dir).mod(Themes.all.size)].id)
        },
        editor = Editor.Theme,
    ),
    choice(Group.Appearance, "Navigation", "Where the screens are listed: a sidebar on the left or the right, or a bar across the top. Switches at once, so you can compare.", listOf("left" to "Left sidebar", "right" to "Right sidebar", "top" to "Top bar"), { it.navPosition }, { c, v -> c.copy(navPosition = v) }),
    choice(Group.Appearance, "Sidebar", "Full: screen names. Icons: only the number key and an icon, which leaves the content more width. Ctrl+B switches it from any screen.", listOf("full" to "Full", "icons" to "Icons"), { if (it.sidebarCollapsed) "icons" else "full" }, { c, v -> c.copy(sidebarCollapsed = v == "icons") }),
    choice(Group.Ollama, "Thinking", "Whether the model reasons before it answers (Ollama's think). Model default leaves it to the model (Qwen and Gemma think every time). Off answers at once: a simple question took 0.3 s instead of 10 s in a test, but hard questions come out weaker. On makes it think even if it would not. A level (Low, Medium, High) is sent only to a model that lists it (gpt-oss does; Qwen and Gemma only have on and off, so for them it falls back to the model default). The Chat status bar shows `think auto / off / on`, and `/think` cycles it.", listOf("default" to "Model default", "off" to "Off (fast)", "on" to "On", "low" to "Low", "medium" to "Medium", "high" to "High"), { it.thinking }, { c, v -> c.copy(thinking = v) }),
    toggle(Group.Ollama, "Model abilities in the bottom bar", "Next to the model name the bottom bar names what Ollama says the model can do: `▣ image` (it can look at pictures: attach one with Ctrl+O), `♪ audio` (it can hear sound) and `MoE 128` (a mixture of experts with 128 experts: only a few of them work on each token, so it answers faster than a dense model of the same file size, though all of it must still fit in memory). Nothing is shown for a model that has none of these, or when Ollama does not say.", { it.showModelTraits }, { c, v -> c.copy(showModelTraits = v) }),
    toggle(Group.Appearance, "Logo animation", "While a reply is being generated, the colours of the sidebar logo sweep along it. Off keeps the logo still.", { it.animateLogo }, { c, v -> c.copy(animateLogo = v) }),

    toggle(Group.Console, "Answer from vault", "Chat searches your notes for each question and answers from what it finds, citing the notes. You can also switch this per chat with /vault.", { it.chatUseVault }, { c, v -> c.copy(chatUseVault = v) }),
    toggle(Group.Console, "Render Markdown", "On: headings, bullets and code are drawn as such. Off: the model's text exactly as written.", { it.renderMarkdown }, { c, v -> c.copy(renderMarkdown = v) }),
    toggle(Group.Console, "Confirm before cancelling", "On: Esc during a reply asks \"Cancel this reply?\" (Enter = yes, any other key = keep going), so a stray Esc never throws a reply away. Off: Esc cancels at once.", { it.confirmCancel }, { c, v -> c.copy(confirmCancel = v) }),
    choice(Group.Library, "Opens on", "The view the Library shows when you open it from the sidebar. A link from the Brain screen (for example the inbox count) still opens its own view once.", listOf("all" to "All", "recent" to "Recent", "inbox" to "Inbox", "orphans" to "No links", "untagged" to "No tags", "broken" to "Broken links", "stale" to "Stale", "tags" to "Tags"), { it.libraryTab }, { c, v -> c.copy(libraryTab = v) }),
    stepper(Group.Library, "List width", "How much of a wide Library the notes list takes, in percent; the preview gets the rest (a window under 96 columns shows one pane at a time). ←/→ step by 5.", 20..60, 5, { it.libraryListPercent }, { c, n -> c.copy(libraryListPercent = n) }, unit = " %"),
    choice(Group.Library, "Sort by", "How the lists ordered by date (All, Inbox, No tags, a tag) are ordered: newest first, or by title A to Z. No links, Broken links and Stale keep their own order.", listOf("modified" to "Newest first", "title" to "Title A–Z"), { it.librarySort }, { c, v -> c.copy(librarySort = v) }),
    toggle(Group.Library, "Preview pane", "On: a wide window shows the note beside the list. Off: the list takes the whole width and Enter opens the note.", { it.libraryPreview }, { c, v -> c.copy(libraryPreview = v) }),
    toggle(Group.Library, "Tab counts", "Show how many notes each tab holds (All 120, Inbox 3).", { it.libraryCounts }, { c, v -> c.copy(libraryCounts = v) }),
    toggle(Group.Tools, "Finish open todos", "On: when the model ends its turn with open steps on its own todo list, it is sent back once to finish them or to close the list, so it does not stop at 5 of 7. Off: it may stop with steps open.", { it.todoGuard }, { c, v -> c.copy(todoGuard = v) }),
    toggle(Group.Console, "Show thinking", "Show \"<model> is thinking…\" while a reasoning model works before its first words.", { it.showThinking }, { c, v -> c.copy(showThinking = v) }),
    choice(Group.Console, "Comparison view", "How /compare shows the answers in Chat. Side by side: the main model's answer and the Oracul models' answers in columns, each under a coloured name, the verdict below across the whole width (needs a window of about 80 columns for two answers, 120 for three, else they stack). Stacked: one under another.", listOf("side" to "Side by side", "stacked" to "Stacked"), { it.comparisonView }, { c, v -> c.copy(comparisonView = v) }),
    toggle(Group.Console, "Thinking pane", "A split window under the Chat conversation that follows what a reasoning model is thinking while it works (and shows its last thoughts when idle), so you can watch its reasoning without it filling the conversation. Needs a model that can think (the model list in Settings → Ollama says so) and a terminal at least 26 rows tall; /thinking in Chat toggles it. Off by default.", { it.thinkingPane }, { c, v -> c.copy(thinkingPane = v) }),
    choice(Group.Console, "Thinking pane placement", "Where the thinking pane sits around the conversation: bottom or top (a strip across the full width), left or right (a column beside the conversation; needs a window about 80 columns wide, else it goes to the bottom). Ctrl+T in Chat changes its size at any moment.", listOf("bottom" to "Bottom", "top" to "Top", "left" to "Left", "right" to "Right"), { it.thinkingPanePlacement }, { c, v -> c.copy(thinkingPanePlacement = v) }),
    stepper(Group.Console, "Thinking pane size", "How much of the main axis the pane takes, in percent: of the conversation's height when it is at the bottom or top, of the width when it is left or right (10 to 90, ←/→ step by 5). It always shows the end of the stream. The conversation keeps a few rows of its own whatever you set.", 10..90, 5, { it.thinkingPanePercent }, { c, n -> c.copy(thinkingPanePercent = n) }, unit = " %"),
    choice(Group.Console, "Thinking in Chat", "What a reasoning model thought before it answered, kept apart from the answer. Summary: one dim line with its length. Full: all of it, dimmed, above the answer. Hidden: not shown (it is still kept with the chat).", listOf("summary" to "Summary", "full" to "Full", "hidden" to "Hidden"), { it.thinkingView }, { c, v -> c.copy(thinkingView = v) }),
    choice(Group.Console, "Auto-compact", "When the context window is this full, Arcana first asks the model to summarize the older part of the chat (like /compact), then sends your message. Nothing is deleted: the summary appears in the chat as a divider and the model just stops being sent what is above it. Off: only /compact does it.", listOf("0" to "Off", "70" to "At 70%", "80" to "At 80%", "90" to "At 90%"), { it.autoCompactPercent.toString() }, { c, v -> c.copy(autoCompactPercent = v.toInt()) }),
    stepper(Group.Console, "Keep after compacting", "When the chat is compacted (Auto-compact or /compact), the latest turns that fit in this share of the context window stay word for word and only what is older becomes the summary. 0 = just your last 2 messages. More keeps more detail but frees less room each time. ←/→ step by 5.", 0..60, 5, { it.compactKeepPercent }, { c, n -> c.copy(compactKeepPercent = n) }, unit = " %", zero = "last 2 turns"),
    stepper(Group.Console, "Chat history", "How many of the latest messages Chat sends to the model as context (a tool call and its result each count as a message). 0 = everything: the whole chat is sent and, when the model's window fills up, the older part is summarized (see Auto-compact) instead of dropped. A number cuts the history to that many messages. The current turn, from your last message on, is always sent whole, however long the tool run. ←/→ step by 2.", 0..100, 2, { it.chatHistory }, { c, n -> c.copy(chatHistory = n) }, zero = "everything"),
    toggle(Group.Console, "Keep chats", "Save every chat to disk and reopen the last one when Arcana starts. Off: the chat lives only until you quit.", { it.keepChats }, { c, v -> c.copy(keepChats = v) }),
    stepper(Group.Console, "Chats kept", "How many saved chats to keep; the least recently used are deleted. ←/→ step by 5.", 5..200, 5, { it.chatsKept }, { c, n -> c.copy(chatsKept = n) }),
    choice(Group.Console, "Copy as", "What select mode (s) puts on the clipboard. Markdown: as written. Plain text: markers removed.", listOf("markdown" to "Markdown", "plain" to "Plain text"), { it.copyAs }, { c, v -> c.copy(copyAs = v) }),

    toggle(Group.Tools, "Tools in Chat", "Lets the model search, read and write your notes, and read and write files in the folders below. Every change asks you first. Needs a model with tool support (Gemma 4 has it). Also /tools in Chat.", { it.toolsEnabled }, { c, v -> c.copy(toolsEnabled = v) }),
    text(Group.Tools, "Working folder", "Optional: the one folder you are working in right now (a web project, a repository). Where it matters: relative paths and commands (run_command, git) start there instead of in your vault; the model is told about it; its own ARCANA.md / CLAUDE.md / AGENTS.md is passed on as instructions; the status bar shows ▣ and its name. It is allowed automatically, so you do not need to list it under Allowed folders. It grants nothing outside itself. Needs tools on. Empty = no working folder.", { it.projectFolder }, { c, v ->
        val path = v.trim()
        require(path.isEmpty() || java.nio.file.Files.isDirectory(AppContext.expandHome(path))) { "That folder does not exist" }
        c.copy(projectFolder = path)
    }, allowEmpty = true, emptyLabel = "(none)"),
    text(Group.Tools, "Allowed folders", "Where the model may read and write besides your vault, separated by ;  For example ~/Projects;~/Documents, or ~ for your whole home folder (a home or hidden folder can only be allowed here, Chat never offers it on the spot). Every change, command and web access still asks you first; nothing outside the vault, these folders and the Working folder is ever touched.", { it.toolFolders }, { c, v -> c.copy(toolFolders = v.trim().trim(';')) }, allowEmpty = true),
    toggle(Group.Tools, "Shell commands", "Lets the model ask to run commands on this computer (build, test, git…). Every command still waits for your y / n, except harmless ones like git status; Plan mode never offers it. Commands run with your own rights and are NOT limited to the folders above, so read each one before you press y. Off by default.", { it.toolShell }, { c, v -> c.copy(toolShell = v) }),
    toggle(Group.Tools, "Run commands without asking", "On: shell commands run at once, without your y / n (the Chat status bar shows a red ▶ auto-run). Commands Arcana never runs (sudo, rm -rf /, formatting a disk…) are still refused. Needs Shell commands on. Use it for a trusted project and turn it off again.", { it.toolAutoShell }, { c, v -> c.copy(toolAutoShell = v) }),
    choice(Group.Tools, "Calls written as text", "Small models sometimes write a tool call as text (fetch_url(url=\"…\")) instead of making it, and then nothing happens. Auto runs such a call as a real one (it still asks first when it is a change, a command or a web address). Ask shows the call and waits for your y. Off leaves it as text.", listOf("auto" to "Auto", "ask" to "Ask", "off" to "Off"), { it.textCalls }, { c, v -> c.copy(textCalls = v) }),
    choice(Group.Tools, "Search backend", "Where the web_search tool looks. DuckDuckGo needs no setup or key (it may refuse after many searches); SearXNG is a server you run (set its address below); Brave needs an API key. Only the search words are sent, and every search asks you first. DuckDuckGo has no key and no quota and may answer with a human check (HTTP 202) from some connections: then the other backends you have set up (a Brave key, a SearXNG address) are tried automatically, and DuckDuckGo is left alone for 10 minutes. For dependable searching set up Brave (a free key from api-dashboard.search.brave.com) or SearXNG.", listOf("duckduckgo" to "DuckDuckGo", "searxng" to "SearXNG", "brave" to "Brave"), { it.searchBackend }, { c, v -> c.copy(searchBackend = v) }),
    text(Group.Tools, "Search address", "The address of your SearXNG server, for example http://localhost:8080 (its settings must allow format json). Used only with the SearXNG backend.", { it.searchUrl }, { c, v -> c.copy(searchUrl = v.trim().trimEnd('/')) }, allowEmpty = true),
    Setting(Group.Tools, "Search API key", "Your Brave Search API key, used only with the Brave backend. It is stored in config.json on this computer, so keep that file private. Enter types a new key; an empty value removes it.", value = { if (it.searchKey.isEmpty()) "(not set)" else "(set, ends …${it.searchKey.takeLast(4)})" }, editor = Editor.Text { c, v -> c.copy(searchKey = v.trim()) }),
    toggle(Group.Tools, "Web pages", "Lets the model ask to search the web (web_search) and to read a public web page (documentation, articles, JSON). Every address waits for your y / n and is shown in full; pages on your own computer or network are never fetched, and what a page says is treated as text, not as instructions. Plan mode never offers it. Off by default.", { it.toolWeb }, { c, v -> c.copy(toolWeb = v) }),
    toggle(Group.Tools, "Search and fetch without asking", "On: web_search and fetch_url run at once, without your y / n, even in Ask mode (the Chat status bar shows an orange ↗ auto-search). Warning: the search words and the address of a page can carry what the model has read to the outside, so use it for research chats, not with private notes in play. Private addresses are still refused, HTTP requests (http_request) still ask for every one, and the Oracul always asks. Needs Web pages on; Plan mode never offers it. Off by default.", { it.toolAutoSearch }, { c, v -> c.copy(toolAutoSearch = v) }),
    toggle(Group.Tools, "Skills", "Skills are runbooks in your vault, one folder each (Skills/<name>/<name>.skill.md with its scripts and reference notes). On: the model is told which skills exist and can load one with use_skill, and Chat's / menu lists them. Needs Tools in Chat. Also /skills.", { it.skillsEnabled }, { c, v -> c.copy(skillsEnabled = v) }),
    text(Group.Tools, "Skills folder", "The vault folder that holds one folder per skill. Empty = automatic: .arcana/skills (a hidden folder, kept apart from your notes), or an existing Skills/; the row shows the folder in use. Skills in ~/.config/arcana/skills apply to every vault. Move an old Skills/ with: arcana --migrate.", { it.skillsFolder }, { c, v -> c.copy(skillsFolder = v.trim('/')) }, allowEmpty = true, emptyLabel = "(automatic)", emptyShown = { c -> "(automatic) " + skillsWhere(c) }),
    choice(Group.Tools, "Mode", "Ask: every change waits for your y / n. Auto: changes run at once (the old file is always backed up). Plan: read-only, the model investigates and writes a plan without changing anything. Also /mode in Chat.", listOf("ask" to "Ask", "auto" to "Auto", "plan" to "Plan"), { it.toolMode }, { c, v -> c.copy(toolMode = v) }),
    toggle(Group.Tools, "Read without asking", "On: searching and reading run at once. Off: every tool call waits for your y / n. Changes always ask.", { it.toolAutoRead }, { c, v -> c.copy(toolAutoRead = v) }),
    choice(Group.Tools, "Old tool output", "What the model is sent of tool results from EARLIER questions in a chat. Whole (default): nothing is cut, so it never reads again what it could no longer see and never answers from half a file; the context gauge shows the cost, and /compact or the automatic compaction makes room. Shorten: each old result is cut to 1,200 characters (the model is told it was cut and can call the tool again), for small context windows. The results of the current question are never cut by this.", listOf("whole" to "Whole", "shorten" to "Shorten"), { it.oldToolOutput }, { c, v -> c.copy(oldToolOutput = v) }),
    stepper(Group.Tools, "Remember repeated reads", "When the model asks for the exact same read, search or web page again in one question, and nothing has changed since, Arcana answers \"you already made this call, the result is above\" instead of running it (and asking you) again. This is how many SECONDS that memory lasts; after that the call runs again, so a note you edited meanwhile is read fresh. 0 = off, every call runs. A change, a command, http_request, a wait, your next message and any failed result (an error, HTTP 429) clear or skip it; wait and the clock tools always run. Nothing is kept between questions.", 0..300, 15, { it.repeatMemorySeconds }, { c, n -> c.copy(repeatMemorySeconds = n) }, " s"),
    choice(Group.Tools, "At the step limit", "What happens when a question has used its steps and the model still wants tools. Ask: a dialog offers more steps. Auto: more steps are given at once, up to ${STEP_LIMIT_AUTO_ROUNDS} times (then it asks); warning: a long run can take a lot of time and tokens, and in Mode Auto its changes run without asking. Changes, commands and web addresses keep their own approval (see Mode and the web settings). Stop: the model answers with what it has.", listOf("ask" to "Ask", "auto" to "Auto", "stop" to "Stop"), { it.stepLimit }, { c, v -> c.copy(stepLimit = v) }),
    stepper(Group.Tools, "Steps per question", "How many rounds of tool use one question may take before the model has to answer. A round may read several files; raise this for big reviews.", 1..60, 1, { it.toolSteps }, { c, n -> c.copy(toolSteps = n) }),

    stepper(Group.Ollama, "Price: prompt tokens", "Only for the estimated price in the status bars: what a million prompt tokens would cost on a paid service, in US dollars. Local models cost nothing; this shows what the same work would cost elsewhere.", 0..200, 1, { it.estPriceIn }, { c, n -> c.copy(estPriceIn = n) }, " $/M"),
    stepper(Group.Ollama, "Price: reply tokens", "The same for a million reply tokens (they usually cost more).", 0..400, 1, { it.estPriceOut }, { c, n -> c.copy(estPriceOut = n) }, " $/M"),
    text(Group.Prompts, "Memory file path", "Where the memory file is. Empty = automatic: .arcana/ARCANA.md in the vault (a hidden folder; an older ARCANA.md at the vault root is still used); the row shows the file in use. Your own ~/.config/arcana/ARCANA.md is read first, for every vault. A path inside the vault (Notes/Memory.md) or anywhere on this computer (~/Documents/arcana-memory.md); a missing file is created from the template the first time you open it with /memory. The Memory file switch below turns its use on or off.", { it.memoryPath }, { c, v ->
        val path = v.trim()
        require(path.isEmpty() || path.startsWith("/") || path.startsWith("~/") || ".." !in path.split('/')) { "A path inside the vault cannot contain .." }
        require(path.isEmpty() || !path.endsWith("/")) { "Give the file, not a folder (…/ARCANA.md)" }
        c.copy(memoryPath = path)
    }, allowEmpty = true, emptyLabel = "(automatic)", emptyShown = { c -> "(automatic) " + memoryWhere(c) }),
    toggle(Group.Prompts, "Memory file", "Adds your memory file (see Memory file path for where it is: by default .arcana/ARCANA.md in the vault; standing instructions and facts, like a CLAUDE.md) to every chat, after your own ~/.config/arcana/ARCANA.md, up to 8,000 characters. Its text is not shown here: it is sent with each message, and the Chat status bar shows ✎ memory while it is in use. /memory in Chat opens it, and creates it from a template the first time. It counts against the context window.", { it.memoryEnabled }, { c, v -> c.copy(memoryEnabled = v) }),
    text(Group.Prompts, "System prompt", "Tells the model who it is. Sent with every request.", { it.systemPrompt }, { c, v -> c.copy(systemPrompt = v) }),
    text(Group.Prompts, "Summary prompt", "The instruction used by Summarize.", { it.summaryPrompt }, { c, v -> c.copy(summaryPrompt = v) }),

    toggle(Group.Helpers, "Helper models", "The master switch for every helper model: the Oracul and its second opinions, the judge, the flow, the specialists (consult tool) and the router. Off: only the chat model works; the models below stay set, so switching back loses nothing. Skill roles have their own switch below. /helpers in Chat does the same.", { it.oraculEnabled }, { c, v -> c.copy(oraculEnabled = v) }),
    Setting(Group.Helpers, "Oracul model", "A second model that reads the worker's answer and says what is wrong or missing (a second opinion), and answers the same question for a comparison. It advises only; its text appears in the chat as a Ψ block. None = the Oracul is off. Enter lists the installed models; a bigger or different one from the worker is the point (loading it replaces the worker in memory for a while on a small machine).", { it.oraculModel.ifEmpty { "(none: off)" } }, editor = OraculModel),
    Setting(Group.Helpers, "More Oracul models", "Optional. Enter lists the installed models: Space ticks several, Enter saves. /compare then asks all of them and the judge compares every answer. More models, longer wait: each one is loaded in turn.", { it.oraculExtra.ifEmpty { "(none)" } }, editor = ExtraModels),
    Setting(Group.Helpers, "Judge model", "The model that compares the answers (/compare) and writes the best final answer. None = the Oracul model judges.", { it.judgeModel.ifEmpty { "(same as the Oracul)" } }, editor = JudgeModel),
    text(Group.Helpers, "Oracul server", "Another Ollama server for the Oracul and the judge, for example a bigger machine on your network (http://host:11434). Blank: the same server as the main model. Only Ollama; what the Oracul reads from your chat goes to that server.", { it.oraculUrl }, { c, v ->
        require(v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")) { "URL must start with http:// or https://" }
        c.copy(oraculUrl = v.trim().trimEnd('/'))
    }),
    toggle(Group.Helpers, "Oracul may use tools", "On: the Oracul can use the same tools as the main model to CHECK an answer (read files, search notes, run a harmless command). Reading follows Read without asking; every change, command and web access asks you first, even in Auto mode, in a dialog marked Ψ Oracul. Needs Tools in Chat on and an Oracul model that supports tools; Plan mode keeps it read-only. Off: it only reads the question and the answer.", { it.oraculTools }, { c, v -> c.copy(oraculTools = v) }),
    Setting(Group.Helpers, "Route: images to", "The model that answers a message with an image attached (it must read images; the model list says which can). Blank: the chat model. A skill's or a flow's own worker wins.", { it.routeVision.ifEmpty { "(the chat model)" } }, editor = RouteVisionRole),
    Setting(Group.Helpers, "Route: code to", "The model that answers a message with a code block or a source file name in it (with tools on it must be able to use them). Blank: the chat model. A skill's or a flow's own worker wins.", { it.routeCode.ifEmpty { "(the chat model)" } }, editor = RouteCodeRole),
    stepper(Group.Helpers, "Chat context: messages", "How many earlier messages of the chat (yours and the model's replies, without tool output) the Oracul, the judge, the planner, the analyst and the controller are shown besides the last question, so a short follow-up like \"do option C\" makes sense to them. 0 = all of them, newest first, up to about 30 000 characters in total (these models' windows are not known). A number caps it. ←/→ step by 2.", 0..200, 2, { it.oraculContextMessages }, { c, n -> c.copy(oraculContextMessages = n) }, zero = "all"),
    stepper(Group.Helpers, "Chat context: characters each", "How much of each of those messages is sent: a longer one is cut in the middle, so its start and its end stay (about 4 characters per token). ←/→ step by 500.", 500..20_000, 500, { it.oraculContextChars }, { c, n -> c.copy(oraculContextChars = n) }),
    choice(Group.Opinions, "Automatic second opinion", "Off: only when you type /oracul. On problems: after a turn where two or more tool calls failed, a skill had to skip a required step, or three turns in a row had a failed call; and in the middle of a turn, every 4th failed call (the worker keeps failing: the Oracul reads the attempts and its advice goes into the loop). Always: after every finished reply (slow: the Oracul model runs each time). Needs an Oracul model.", listOf("off" to "Off", "problems" to "On problems", "always" to "Always"), { it.oraculAuto }, { c, v -> c.copy(oraculAuto = v) }),
    soundSetting("Needs you", "Plays when the model waits for you: an approval or a question.", com.arcana.brain.core.ChatSoundEvent.Attention, { it.soundAttention }, { c, v -> c.copy(soundAttention = v) }),
    soundSetting("Reply finished", "Plays when a reply, with all its tool steps, is finished.", com.arcana.brain.core.ChatSoundEvent.Done, { it.soundDone }, { c, v -> c.copy(soundDone = v) }),
    soundSetting("Reply failed", "Plays when a reply ends with an error.", com.arcana.brain.core.ChatSoundEvent.Failed, { it.soundFailed }, { c, v -> c.copy(soundFailed = v) }),
    toggle(Group.Opinions, "Judge verdict on opinions", "On: after an automatic second opinion the judge model (Settings → Helpers → Judge model; it must be set) says whether the opinion is right: AGREE, PARTLY or DISAGREE and why. One more model run per opinion. Advisory: shown under the opinion, never applied by itself.", { it.oraculVerdict }, { c, v -> c.copy(oraculVerdict = v) }),
    choice(Group.Opinions, "When the judge agrees", "What happens when the judge says AGREE or PARTLY on an automatic opinion. Ask: a dialog offers to revise the answer now, to revise with a note of yours (type it in the dialog), to leave it, or to revise automatically from now on. Automatic: the chat model revises by itself, once per message. Advice only: you just see the blocks and can type /revise.", listOf("ask" to "Ask me", "auto" to "Revise automatically", "advice" to "Advice only"), { it.verdictAction }, { c, v -> c.copy(verdictAction = v) }),
    toggle(Group.Opinions, "Judge automatically", "On: when an automatic second opinion is due (see above), run a full comparison instead of one opinion: the Oracul answers the question too and the judge model compares both answers (as /compare does; the judge is shown as ⚖). Slow: three model runs instead of one, and more tokens. Off: the judge works only on /compare. The Oracul stepping in mid-turn and skill reviewers stay single opinions.", { it.oraculJudgeAuto }, { c, v -> c.copy(oraculJudgeAuto = v) }),
    choice(Group.Opinions, "Stuck: failed calls", "The main model counts as stuck after this many failed tool calls in one turn (errors, timeouts, commands that exit with an error). The Oracul then reads the attempts and its advice goes into the main model's loop, and again after every further such number. Needs an Oracul model and an automatic setting other than Off.", listOf("0" to "Never", "3" to "After 3", "4" to "After 4", "6" to "After 6", "8" to "After 8"), { it.stuckCalls.toString() }, { c, v -> c.copy(stuckCalls = v.toInt()) }),
    choice(Group.Opinions, "Stuck: failing turns", "An automatic review is due when this many turns in a row each had a failed tool call, even if each had only one (you keep coming back, the main model keeps failing). Used by On problems.", listOf("0" to "Never", "2" to "After 2", "3" to "After 3", "4" to "After 4", "5" to "After 5"), { it.stuckTurns.toString() }, { c, v -> c.copy(stuckTurns = v.toInt()) }),
    choice(Group.Opinions, "Loop guard: repeated text", "A model sometimes writes the same passage again and again (in its thinking or in its answer) without any error. When the same stretch has come this many times in one round, Arcana stops the round, tells the model out of sight to decide and answer, and asks once more with a higher repeat penalty; if it loops again you get what it had written, or an error that says so. Never = the round runs until the model stops by itself.", listOf("0" to "Never", "3" to "After 3", "5" to "After 5", "8" to "After 8"), { it.loopRepeats.toString() }, { c, v -> c.copy(loopRepeats = v.toInt()) }),
    choice(Group.Opinions, "Loop guard: repeated calls", "When the model makes the same tool call again (same tool, same arguments, nothing changed in between; a failed call counts too), this many times in one question, it is told out of sight to step back and try something different. At twice as many, the tools are taken away for the rest of the question and it has to answer with what it has. A change or a command that worked starts the count again. Never = no check.", listOf("0" to "Never", "2" to "After 2", "3" to "After 3", "4" to "After 4", "6" to "After 6"), { it.loopCalls.toString() }, { c, v -> c.copy(loopCalls = v.toInt()) }),
    choice(Group.Flow, "Flow", "A fixed sequence of models that handles every message: the analyst says what is really asked, the planner plans, the worker does it, the reviewer checks. Careful uses the models below (an unset stage takes the Oracul model; the worker is the chat model unless Skill worker is set) and a message under 60 characters goes straight to the worker. Off: the chat model answers, and skills use the role table. Slow and heavy: several models run for each message.", listOf("off" to "Off", "careful" to "Careful"), { it.flow }, { c, v -> c.copy(flow = v) }),
    Setting(Group.Flow, "Flow analyst", "The model that reads your request first and writes what is really asked, what is unclear and what a good result looks like (needs Flow: Careful). Blank: the Oracul model.", { it.flowAnalyst.ifEmpty { "(the Oracul model)" } }, editor = AnalystRole),
    Setting(Group.Flow, "Flow controller", "The model that checks the finished answer against your request and the reviewer's notes and says done or redo (needs Flow: Careful). On redo the worker is sent back with what to fix. Blank: the Oracul model.", { it.flowController.ifEmpty { "(the Oracul model)" } }, editor = ControllerRole),
    Setting(Group.Flow, "Flow check command", "A shell command the flow runs in the project folder after each answer, as an objective check (for example ./gradlew test). Exit code 0: passed, and the controller is told. Anything else: the output goes back to the worker as a redo, without asking a model. It runs WITHOUT asking, so it needs Settings → Tools → shell commands and auto-run to be on; commands Arcana never runs (sudo, rm -r /, …) are refused. Blank: no command.", { it.flowCheckCommand.ifEmpty { "(none)" } }, editor = Editor.Text { c, v -> c.copy(flowCheckCommand = v.trim()) }),
    stepper(Group.Flow, "Flow redos", "How many times the controller may send the worker back for one message (0 = it only gives its verdict, never a redo). Each redo runs the worker, the reviewer and the controller again, so it is slow. ←/→ step by 1.", 0..Flow.MAX_REDOS, 1, { it.flowRedos }, { c, n -> c.copy(flowRedos = n) }),
    toggle(Group.Flow, "Specialists as tools", "On: the chat model itself may call the analyst, the planner or the reviewer (the models of Flow analyst, Skill planner, Skill reviewer, else the Oracul model) as a tool called consult, when it finds a task hard or unclear: it writes a brief, the specialist answers, and it carries on. At most 3 times per message, only with tools on. Unlike Flow, the chat model decides when; most messages call nobody.", { it.flowTools }, { c, v -> c.copy(flowTools = v) }),
    stepper(Group.Flow, "Goal turns", "How many times /goal may continue the work by itself for one goal before it stops (a message of yours resets the count). Each turn runs the worker and the goal check. ←/→ step by 1.", 1..20, 1, { it.goalTurns }, { c, n -> c.copy(goalTurns = n) }),
    toggle(Group.Skills, "Skill roles", "On: a skill run uses the worker, planner and reviewer the skill names (its worker:, planner:, reviewer: properties) or the role table below. Off: a skill is run by the chat model alone. Independent of Helper models, so you can test one model plus skill roles. /roles in Chat does the same.", { it.skillRolesEnabled }, { c, v -> c.copy(skillRolesEnabled = v) }),
    choice(Group.Skills, "Roles apply to", "Skills only: the planner, worker and reviewer below run for a message that carries a skill. Every message: they run as a pipeline for all chat (a plan first, for messages of 60 characters or more; the worker answers; the reviewer checks). Slower, but each model does what it is best at. A skill's own properties still win.", listOf("skills" to "Skills only", "all" to "Every message"), { it.rolesScope }, { c, v -> c.copy(rolesScope = v) }),
    Setting(Group.Skills, "Skill planner", "The model that writes a short plan before a skill starts (the worker then sees it). A skill's own `planner:` property wins. Blank: no plan.", { it.rolePlanner.ifEmpty { "(no plan)" } }, editor = PlannerRole),
    Setting(Group.Skills, "Skill worker", "The model that runs skills instead of the chat model. A skill's own `worker:` property wins. Blank: the chat model.", { it.roleWorker.ifEmpty { "(the chat model)" } }, editor = WorkerRole),
    Setting(Group.Skills, "Skill reviewer", "The model that gives a second opinion when a skill's run is done, whatever the automatic setting says. A skill's own `reviewer:` property wins. Blank: none.", { it.roleReviewer.ifEmpty { "(no review)" } }, editor = ReviewerRole),
    choice(Group.Sounds, "Play when", "Away: only while another screen than Chat is showing (you are not looking at the reply). Always: also while you are in Chat.", listOf("away" to "Away from Chat", "always" to "Always"), { it.soundWhere }, { c, v -> c.copy(soundWhere = v) }),
    stepper(Group.Sounds, "Volume", "Volume of system sounds and your own file, in percent (the terminal bell has none). ←/→ step by 10 and play a sample.", 0..100, 10, { it.soundVolume }, { c, n ->
        val next = c.copy(soundVolume = n)
        hear(next, listOf(c.soundDone, c.soundAttention, c.soundFailed).firstOrNull { it != com.arcana.brain.core.SoundChoices.OFF } ?: "sys:Pop")
        next
    }, " %"),
    text(Group.Sounds, "Your own sound", "A sound file (.wav, .aiff, .mp3) for the choice “Your own file”; ~ is your home folder. macOS plays it with afplay.", { it.soundFile }, { c, v ->
        require(v.isBlank() || java.io.File(v.trim().let { p -> if (p.startsWith("~/")) System.getProperty("user.home") + p.removePrefix("~") else p }).isFile) { "That file does not exist" }
        c.copy(soundFile = v.trim())
    }, allowEmpty = true),
    storageSetting(com.arcana.brain.core.Storage.BACKUPS),
    storageSetting(com.arcana.brain.core.Storage.TRASH),
    storageSetting(com.arcana.brain.core.Storage.VAULT_TRASH),
    storageSetting(com.arcana.brain.core.Storage.CHATS),
    storageSetting(com.arcana.brain.core.Storage.SESSIONS),
    storageSetting(com.arcana.brain.core.Storage.USAGE),
    storageSetting(com.arcana.brain.core.Storage.TOOL_USAGE),
    choice(Group.Accessibility, "Interface scale", "A terminal sets its own font size, so this is the layout density: Spacious keeps the roomy screens for longer, Compact packs more in. Change your terminal's font size for bigger text.", listOf("compact" to "Compact", "normal" to "Normal", "spacious" to "Spacious"), { it.uiScale }, { c, v -> c.copy(uiScale = v) }),
    toggle(Group.Accessibility, "Screen-reader output", "Drops decorative symbols (▌ ✓ ✕ → • and the rounded frames) in favour of plain words and ASCII that read cleanly in a screen reader.", { it.plainGlyphs }, { c, v -> c.copy(plainGlyphs = v) }),
)

/** A long tab is split into titled sections, in this order; a short tab has none. Rows are named by label. */
private val sections: Map<Group, List<Pair<String, List<String>>>> = mapOf(
    Group.Ollama to listOf(
        "Connection" to listOf("Ollama URL", "Model", "Timeout"),
        "Generation" to listOf("Temperature", "Context window", "Thinking"),
        "Display and cost" to listOf("Model abilities in the bottom bar", "Price: prompt tokens", "Price: reply tokens"),
    ),
    Group.Vault to listOf(
        "Location" to listOf("Vault path", "Output folder", "Where Arcana's own files are (this vault)", "Where YOUR files are (every vault)"),
        "Notes" to listOf("Context notes", "Chars per note", "Inbox folder", "Daily notes folder", "Daily note format", "Stale after"),
    ),
    Group.Console to listOf(
        "Chat" to listOf("Answer from vault", "Render Markdown", "Confirm before cancelling", "Comparison view", "Copy as"),
        "Thinking" to listOf("Show thinking", "Thinking pane", "Thinking pane placement", "Thinking pane size", "Thinking in Chat"),
        "History and compacting" to listOf("Auto-compact", "Keep after compacting", "Chat history", "Keep chats", "Chats kept"),
    ),
    Group.Tools to listOf(
        "Steps" to listOf("Tools in Chat", "Mode", "Finish open todos", "At the step limit", "Steps per question"),
        "Files and shell" to listOf("Working folder", "Allowed folders", "Read without asking", "Shell commands", "Run commands without asking"),
        "Web" to listOf("Search backend", "Search address", "Search API key", "Web pages", "Search and fetch without asking"),
        "Skills" to listOf("Skills", "Skills folder"),
        "Tool output" to listOf("Calls written as text", "Old tool output", "Remember repeated reads"),
    ),
    Group.Helpers to listOf(
        "Oracul" to listOf("Helper models", "Oracul model", "More Oracul models", "Judge model", "Oracul server", "Oracul may use tools"),
        "Router" to listOf("Route: images to", "Route: code to"),
        "Chat context" to listOf("Chat context: messages", "Chat context: characters each"),
    ),
)

/** The section a row belongs to, or null in a tab without sections. A row no section names lands in a last one, so none is lost. */
private fun sectionOf(s: Setting): String? = sections[s.group]?.let { list -> list.firstOrNull { s.label in it.second }?.first ?: "More" }

/** The rows, each tab's own rows gathered by section (the order inside a section is the order given above). */
private val settings: List<Setting> = declared.sortedBy { s ->
    val list = sections[s.group]
    list?.indexOfFirst { s.label in it.second }?.takeIf { it >= 0 }?.let { i -> i * 100 + list[i].second.indexOf(s.label) } ?: (if (list != null) Int.MAX_VALUE else 0)
}

private const val CustomModel = "✎ Type a custom name…"
private const val NoModel = "∅ None (off)"

/** What the screen is doing with the highlighted setting. */
private sealed interface Editing {
    data class Typing(val setting: Setting, val editor: Editor.Text) : Editing
    data class PickModel(val target: Editor.Model, val models: List<String>, val details: Map<String, String> = emptyMap(), val ticked: androidx.compose.runtime.snapshots.SnapshotStateList<String> = androidx.compose.runtime.mutableStateListOf(), val machine: String = "") : Editing
    data object PickTheme : Editing
    data class PickFolder(val browser: DirectoryPickerState) : Editing
    data object ConfirmReset : Editing
    data class ConfirmClear(val areaId: String) : Editing
}

/** What Ollama really runs the model with, next to the setting that asks for it: the number that counts, and a warning when it is small. */
/** The memory advice for the model a Settings row names (the main model, the Oracul, the judge, a skill role), or null when the row is no model or the choice is fine. */
private fun adviceFor(app: AppContext, label: String): String? {
    val config = app.config
    return when (label) {
        "Model" -> app.memoryAdvice(config.model, FitRole.Main)
        "Oracul model" -> app.memoryAdvice(config.oraculModel, FitRole.Oracul)
        "Judge model" -> app.memoryAdvice(com.arcana.brain.core.Oracul.judge(config), FitRole.Judge)
        "Route: images to" -> app.memoryAdvice(config.routeVision, FitRole.Main)
        "Route: code to" -> app.memoryAdvice(config.routeCode, FitRole.Main)
        "Flow analyst" -> app.memoryAdvice(config.flowAnalyst, FitRole.Oracul)
        "Flow controller" -> app.memoryAdvice(config.flowController, FitRole.Oracul)
        "Skill planner" -> app.memoryAdvice(config.rolePlanner, FitRole.Oracul)
        "Skill reviewer" -> app.memoryAdvice(config.roleReviewer, FitRole.Oracul)
        "Skill worker" -> app.memoryAdvice(config.roleWorker, FitRole.Main)
        else -> null
    }
}

private fun contextNote(app: AppContext): String {
    val config = app.config
    val loaded = app.loadedContext
    val real = com.arcana.brain.core.UsageStats::contextSize
    val tooBig = config.contextTokens > 0 && app.modelContext > 0 && config.contextTokens > app.modelContext
    val slow = app.offloads.joinToString("") { "\n⚠ ${it.model} runs ${it.cpuPercent}% on the CPU because it does not fit in memory with this window (${com.arcana.brain.core.UsageStats.compact(it.sizeBytes / 1_000_000)} MB): replies are several times slower and a long prompt can time out. Choose a smaller window or a smaller model." }
    return slow + (if (tooBig) "\n⚠ You ask for ${real(config.contextTokens.toLong())} but ${config.model} is built for ${real(app.modelContext.toLong())}: the rest is wasted memory (the cache for the window is allocated up front) and can make your Mac swap. Choose ${real(app.modelContext.toLong())} or less." else "") + "\nRight now: " + when {
        loaded > 0 -> "Ollama runs ${config.model} with a window of ${real(loaded.toLong())}" +
            (if (config.contextTokens > 0 && loaded != config.contextTokens) " (you asked for ${real(config.contextTokens.toLong())}; Ollama's figure is the one that counts)" else "") +
            (if (loaded < COMFORTABLE_CONTEXT) ". ⚠ That is small: long chats, notes and tool results get cut. Choose 32k or more." else ".")
        config.contextTokens > 0 -> "${real(config.contextTokens.toLong())} is sent with every request; Ollama has not loaded the model yet, so send a message and this shows what it really got."
        else -> "Ollama's own default is used and has not been read yet (send a message). It is often only 4k, which is too small for tools and long chats."
    }
}

@Composable
fun SettingsScreen(app: AppContext) {
    val scope = rememberCoroutineScope()
    val picker = remember { PickerState() }
    val listScroll = rememberScrollState()
    val field = remember { TextFieldState() }
    var group by remember { mutableStateOf(Group.Ollama) }
    var index by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }

    val inGroup = settings.filter { it.group == group }
    val selected = inGroup.getOrNull(index.coerceIn(0, (inGroup.size - 1).coerceAtLeast(0)))

    fun changed(newConfig: AppConfig, what: String) {
        app.update(newConfig)
        if (what == "Ollama URL" || what == "Model") scope.launch { app.refreshConnection() }
        // a model that does not suit this machine's memory is said right away, with a better choice
        val advice = adviceFor(app, what)
        notice = if (advice != null) Notice("$what saved. $advice", isError = advice.startsWith("⚠")) else Notice("$what saved")
    }

    fun commitText(setting: Setting, editor: Editor.Text) {
        try {
            changed(editor.apply(app.config, field.text), setting.label)
            editing = null
        } catch (e: IllegalArgumentException) {
            notice = Notice(e.message ?: "Invalid value", isError = true)
        } catch (e: Exception) {
            notice = Notice("Could not save: ${e.message}", isError = true)
        }
    }

    fun commitFolder(path: Path) {
        if (!Files.isDirectory(path)) {
            notice = Notice("Not a directory: $path", isError = true)
            return
        }
        changed(app.config.copy(vaultPath = path.toString()), "Vault path")
        editing = null
    }

    fun startEdit(setting: Setting) {
        notice = null
        when (val editor = setting.editor) {
            is Editor.Text -> {
                field.set(setting.value(app.config).takeUnless { it.startsWith("(") } ?: "")
                editing = Editing.Typing(setting, editor)
            }
            Editor.Folder -> {
                val start = app.config.vaultPath.takeIf { it.isNotBlank() }?.let { AppContext.expandHome(it) }?.takeIf { Files.isDirectory(it) }
                    ?: Path.of(System.getProperty("user.home"))
                editing = Editing.PickFolder(DirectoryPickerState(start, onChoose = ::commitFolder, onCancel = { editing = null }))
            }
            Editor.Theme -> {
                picker.reset()
                picker.index = Themes.all.indexOfFirst { it.id == app.config.theme }.coerceAtLeast(0)
                editing = Editing.PickTheme
            }
            is Editor.Model -> scope.launch {
                try {
                    val details = (if (editor.oracul) com.arcana.brain.core.OllamaClient { com.arcana.brain.core.Oracul.serverConfig(app.config, app.config.model) } else app.client).modelDetails()
                    val models = (if (editor.allowNone) listOf(NoModel) else emptyList()) + details.map { it.name } + CustomModel
                    picker.reset()
                    picker.index = models.indexOf(editor.get(app.config)).coerceAtLeast(0)
                    // does it fit this machine's memory (the Oracul's own server: unknown, so no verdict), and which one would we pick
                    val ram = if (editor.oracul && app.config.oraculUrl.isNotBlank()) 0L else com.arcana.brain.core.ModelFit.physicalMemory()
                    val workerBytes = if (editor.oracul) details.firstOrNull { it.name == app.config.model }?.sizeBytes ?: 0L else 0L
                    val picks = com.arcana.brain.core.ModelFit.suggest(details, app.config.model, ram, app.config.oraculTools)
                    val suggested = when { !editor.oracul -> null; editor === JudgeModel -> picks.judge; else -> picks.oracul }
                    val notes = details.associate { d ->
                        d.name to buildString {
                            append(d.summary())
                            if (ram > 0 && d.sizeBytes > 0) {
                                val fit = com.arcana.brain.core.ModelFit.fit(d.sizeBytes, workerBytes, ram)
                                append(" · ").append(if (editor.oracul || fit == com.arcana.brain.core.ModelFit.Fit.TooBig) com.arcana.brain.core.ModelFit.label(fit) else "fits")
                            }
                            if (d.name == suggested) append(" · ★ suggested")
                        }
                    }
                    val machine = if (ram > 0) "this machine ${com.arcana.brain.core.ModelFit.gb(ram)}, ~${com.arcana.brain.core.ModelFit.gb(com.arcana.brain.core.ModelFit.budget(ram))} for models" else if (editor.oracul && app.config.oraculUrl.isNotBlank()) "the Oracul server's memory is unknown here" else ""
                    editing = Editing.PickModel(editor, models, notes, androidx.compose.runtime.mutableStateListOf<String>().also { t -> if (editor.multi) t += editor.get(app.config).split(',').map { it.trim() }.filter { it.isNotEmpty() } }, machine)
                } catch (e: Exception) {
                    notice = Notice("${e.message} Type the model name instead.", isError = true)
                    field.set(editor.get(app.config))
                    editing = Editing.Typing(setting, Editor.Text { c, v -> require(editor.allowNone || v.isNotBlank()) { "Model name cannot be empty" }; editor.set(c, v.trim()) })
                }
            }
            is Editor.Clear -> {
                val area = storageAreas(app.config).firstOrNull { it.id == editor.area }
                if (area == null) notice = Notice("Choose a vault first (Settings → Vault).", isError = true)
                else if (com.arcana.brain.core.Storage.use(area).files == 0) notice = Notice("${area.title} is already empty.")
                else editing = Editing.ConfirmClear(area.id)
            }
            null -> setting.step?.let { changed(it(app.config, 1), setting.label) }
        }
    }

    fun testConnection() {
        notice = Notice("Testing…")
        scope.launch {
            app.refreshConnection()
            notice = try {
                val installed = app.client.listModels()
                if (app.config.model in installed) {
                    Notice("Connected · ${installed.size} models installed · ${app.config.model} is available")
                } else {
                    Notice("Connected, but ${app.config.model} is not installed. Installed: ${installed.joinToString()}", isError = true)
                }
            } catch (e: Exception) {
                Notice(e.message ?: "Cannot reach Ollama", isError = true)
            }
        }
    }

    /** Moves the chosen tab one place earlier (-1) or later (+1), within its own sidebar section. */
    fun moveTab(delta: Int) {
        val tabs = Screen.sidebar
        val from = index.coerceIn(0, tabs.lastIndex)
        val to = from + delta
        if (to !in tabs.indices) return
        if (tabs[from].group != tabs[to].group) {
            notice = Notice("Tabs stay inside their section (${tabs[from].group.heading.lowercase()}).", isError = true)
            return
        }
        val order = tabs.toMutableList()
        order[from] = tabs[to]
        order[to] = tabs[from]
        index = to
        changed(app.config.copy(tabOrder = order.map { it.name }), "Tab order")
    }

    fun switchGroup(delta: Int) {
        group = Group.entries[(group.ordinal + delta).mod(Group.entries.size)]
        index = 0
        notice = null
    }

    val mode = editing
    FocusMode(
        when (mode) {
            is Editing.Typing -> true
            is Editing.PickModel -> picker.filtering
            Editing.PickTheme -> picker.filtering
            is Editing.PickFolder -> mode.browser.filtering
            else -> false
        },
    )
    when (mode) {
        null -> if (group == Group.Tabs) {
            Hints("↑↓  choose a tab", "←→  move it earlier / later", "Tab  next group", "R  reset")
        } else {
            Hints("↑↓  choose", "←→  change", "Enter  edit", "Tab  next group", "R  reset", "t  test")
        }
        is Editing.Typing -> Hints("Enter  save", "Ctrl+U  clear line", "Esc  cancel")
        is Editing.PickModel -> if (mode.target.multi) Hints("↑↓  move", "Space  tick", "Enter  save", "/  filter", "Esc  cancel") else Hints("↑↓  move", "Enter  choose", "/  filter", "Esc  cancel")
        Editing.PickTheme -> Hints("↑↓  preview", "Enter  choose", "/  filter", "Esc  cancel")
        is Editing.PickFolder ->
            if (mode.browser.filtering) Hints("↑↓  move", "Enter  open", "Esc  clear filter") else Hints("Space  use folder", "Enter  open", "←  up", "/  filter", "Esc  cancel")
        Editing.ConfirmReset -> Hints("y  reset all", "any other key  keep")
        is Editing.ConfirmClear -> storageAreas(app.config).firstOrNull { it.id == mode.areaId }?.let { if (it.prunable) Hints("y  clear what is older than $PRUNE_DAYS days", "a  clear everything", "any other key  keep") else Hints("y  clear it", "any other key  keep") } ?: Hints("any other key  back")
    }

    KeyHandler { key ->
        when (mode) {
            is Editing.Typing -> when {
                key == Key.Escape -> { editing = null; true }
                key == Key.Enter -> { commitText(mode.setting, mode.editor); true }
                else -> field.onKey(key)
            }
            is Editing.PickModel -> when {
                // several models: Space ticks the highlighted one (while filtering, a space belongs to the filter)
                mode.target.multi && !picker.filtering && key.char == ' ' -> {
                    picker.selected(mode.models) { it }?.takeIf { it != CustomModel && it != NoModel }?.let { if (it in mode.ticked) mode.ticked.remove(it) else mode.ticked.add(it) }
                    true
                }
                picker.handle(key, mode.models) { it } -> true
                key == Key.Enter -> {
                    when (val choice = picker.selected(mode.models) { it }) {
                        null -> Unit
                        CustomModel -> {
                            field.set(mode.target.get(app.config))
                            editing = Editing.Typing(settings.first { it.editor === mode.target }, Editor.Text { c, v -> require(mode.target.allowNone || v.isNotBlank()) { "Model name cannot be empty" }; mode.target.set(c, v.trim()) })
                        }
                        NoModel -> { changed(mode.target.set(app.config, ""), mode.target.label); editing = null }
                        // nothing ticked: Enter on a model chooses just that one
                        else -> { changed(mode.target.set(app.config, if (mode.target.multi) mode.ticked.ifEmpty { listOf(choice) }.joinToString(", ") else choice), mode.target.label); editing = null }
                    }
                    true
                }
                key == Key.Escape -> { editing = null; true }
                else -> false
            }
            Editing.PickTheme -> {
                val names = Themes.all.map { it.name }
                when {
                    picker.handle(key, names) { it } -> true
                    key == Key.Enter -> {
                        picker.selected(names) { it }?.let { name -> changed(app.config.copy(theme = Themes.all.first { it.name == name }.id), "Colour theme") }
                        editing = null
                        true
                    }
                    key == Key.Escape -> { editing = null; true }
                    else -> false
                }
            }
            is Editing.PickFolder -> mode.browser.handle(key)
            Editing.ConfirmReset -> {
                if (key.char == 'y' || key.char == 'Y') {
                    // Everything goes back to the built-in defaults except where the vault is.
                    app.update(AppConfig(vaultPath = app.config.vaultPath))
                    notice = Notice("All options reset to the built-in defaults")
                }
                editing = null
                true
            }
            is Editing.ConfirmClear -> {
                val area = storageAreas(app.config).firstOrNull { it.id == mode.areaId }
                val days = if (key.char == 'y' || key.char == 'Y') (if (area?.prunable == true) PRUNE_DAYS else 0) else if (area?.prunable == true && (key.char == 'a' || key.char == 'A')) 0 else -1
                if (area != null && days >= 0) {
                    val removed = app.clearStorage(area, days)
                    notice = Notice("${area.title}: $removed file${if (removed == 1) "" else "s"} removed")
                }
                editing = null
                true
            }
            null -> if (group == Group.Tabs) when {
                key == Key.Up || key.char == 'k' -> { index = (index - 1).coerceAtLeast(0); true }
                key == Key.Down || key.char == 'j' -> { index = (index + 1).coerceAtMost(Screen.sidebar.lastIndex); true }
                key == Key.Left || key.char == 'K' -> { moveTab(-1); true }
                key == Key.Right || key.char == 'J' -> { moveTab(1); true }
                key == Key.Tab -> { switchGroup(1); true }
                key == Key.BackTab -> { switchGroup(-1); true }
                key.char == 'R' -> { editing = Editing.ConfirmReset; true }
                else -> false
            } else when {
                key == Key.Up || key.char == 'k' -> { index = (index - 1).coerceAtLeast(0); true }
                key == Key.Down || key.char == 'j' -> { index = (index + 1).coerceAtMost(inGroup.lastIndex); true }
                key == Key.Tab -> { switchGroup(1); true }
                key == Key.BackTab -> { switchGroup(-1); true }
                key == Key.Left -> { selected?.step?.let { changed(it(app.config, -1), selected.label) }; true }
                key == Key.Right -> { selected?.step?.let { changed(it(app.config, 1), selected.label) }; true }
                key == Key.Enter -> { selected?.let(::startEdit); true }
                key.char == 't' -> { testConnection(); true }
                key.char == 'R' -> { editing = Editing.ConfirmReset; true }
                else -> false
            }
        }
    }

    Stack {
        when (mode) {
            null -> {
                ScreenHeader("Settings", "Changes are saved at once to ${app.configPath}")
                VGap()
                GroupBar(group)
                VGap()
                // the list scrolls with the cursor inside the panel: no option is ever cut off, whatever the window height
                Panel(group.title, Modifier.flexible()) {
                    if (group == Group.Tabs) {
                        ScrollColumn(listScroll, Modifier.flexible(), follow = index.coerceIn(0, Screen.sidebar.lastIndex)) {
                            TabList(index.coerceIn(0, Screen.sidebar.lastIndex))
                        }
                    } else {
                        if (group.about.isNotEmpty()) { Label(group.about, color = Palette.Muted, wrap = true); VGap() }
                        val at = index.coerceIn(0, inGroup.lastIndex)
                        ScrollColumn(listScroll, Modifier.flexible(), follow = optionChild(inGroup, at)) {
                            OptionList(app.config, inGroup, at)
                        }
                    }
                    if (group == Group.Appearance) {
                        VGap()
                        ThemeSwatches(Themes.byId(app.config.theme))
                    }
                    NoticeLine(notice)
                }
                if (group == Group.Tabs) {
                    if (!rememberDensity().compact) Hint("The first tab is where Arcana opens, and a tab's position decides its number key. Tabs move within their own section.")
                } else {
                    selected?.let { if (!rememberDensity().compact) Hint(shortened("${it.label}: ${it.help}" + (if (it.label == "Context window") contextNote(app) else "") + (adviceFor(app, it.label)?.let { advice -> "\n$advice" } ?: ""))) }
                }
            }
            is Editing.Typing -> {
                ScreenHeader("Settings › ${mode.setting.label}", "Current: ${mode.setting.value(app.config)}")
                VGap()
                Panel(mode.setting.label) {
                    TextInput(mode.setting.label, field, true, maxRows = 4)
                    VGap()
                    Hint(mode.setting.help + (if (mode.setting.label == "Context window") contextNote(app) else ""))
                    NoticeLine(notice)
                }
            }
            is Editing.PickModel -> {
                ScreenHeader("Settings › ${mode.target.label}", "Installed on ${if (mode.target.oracul) com.arcana.brain.core.Oracul.serverConfig(app.config, "").ollamaUrl else app.config.ollamaUrl}" + if (mode.machine.isEmpty()) "" else "  ·  ${mode.machine}")
                VGap()
                Panel("Models") {
                    Picker(mode.models, picker, label = { if (mode.target.multi && it != CustomModel && it != NoModel) (if (it in mode.ticked) "[x] " else "[ ] ") + it else it }, rows = bodyRows(extra = 5), icon = { if (it == CustomModel || it == NoModel) "" else "◆" }, iconColor = { Palette.Accent }, meta = { (if (it == mode.target.get(app.config)) "current · " else "") + mode.details[it].orEmpty() }, metaColor = { val note = mode.details[it].orEmpty(); when { "too big" in note -> Palette.Red; "★" in note || it == app.config.model -> Palette.Green; else -> Palette.Muted } })
                }
            }
            Editing.PickTheme -> {
                val names = Themes.all.map { it.name }
                val highlighted = picker.selected(names) { it }?.let { n -> Themes.all.first { it.name == n } } ?: Themes.byId(app.config.theme)
                ScreenHeader("Settings › Colour theme", "Chosen: ${Themes.byId(app.config.theme).name}")
                VGap()
                Panel("Themes") {
                    Picker(names, picker, label = { it }, rows = bodyRows(extra = 8), icon = { "◐" }, iconColor = { Palette.Accent }, meta = { if (it == Themes.byId(app.config.theme).name) "current" else "" }, metaColor = { Palette.Green })
                    VGap()
                    Label("PREVIEW  ${highlighted.name}", color = Palette.Muted, textStyle = TextStyle.Bold)
                    ThemeSwatches(highlighted)
                }
            }
            is Editing.PickFolder -> {
                ScreenHeader("Settings › Vault path", "Your Obsidian vault folder")
                VGap()
                Panel("Choose a folder") {
                    DirectoryPicker(mode.browser, rows = bodyRows(extra = 8))
                    NoticeLine(notice)
                }
                ActionBar(DirectoryPickerState.actions(mode.browser))
            }
            is Editing.ConfirmClear -> {
                val area = storageAreas(app.config).firstOrNull { it.id == mode.areaId }
                ScreenHeader("Settings › Storage", area?.title ?: "Storage")
                VGap()
                Panel("Clear ${area?.title ?: "this"}?", accent = Palette.Red) {
                    if (area != null) {
                        val all = com.arcana.brain.core.Storage.use(area)
                        Warning("${area.detail.replaceFirstChar { it.uppercase() }}: ${all.files} file${if (all.files == 1) "" else "s"}, ${com.arcana.brain.core.Storage.size(all.bytes)} in ${area.path.toString().replace(System.getProperty("user.home"), "~")}. This cannot be undone.")
                        if (area.prunable) {
                            val old = com.arcana.brain.core.Storage.clearable(area, PRUNE_DAYS)
                            Hint("y removes only what is older than $PRUNE_DAYS days (${old.files} file${if (old.files == 1) "" else "s"}, ${com.arcana.brain.core.Storage.size(old.bytes)}); a removes everything.")
                        }
                    }
                }
                ActionBar(if (area?.prunable == true) listOf(ActionItem("y", "Older than $PRUNE_DAYS days", accent = Palette.Red), ActionItem("a", "Everything", accent = Palette.Red), ActionItem("any other key", "Keep", accent = Palette.Cta))
                    else listOf(ActionItem("y", "Clear it", accent = Palette.Red), ActionItem("any other key", "Keep", accent = Palette.Cta)), style = ActionStyle.Button)
            }
            Editing.ConfirmReset -> {
                ScreenHeader("Settings", "Reset every option")
                VGap()
                Panel("Reset all options?", accent = Palette.Red) {
                    Warning("Every option goes back to its built-in default, including prompts, model and Ollama URL. The vault path is kept.")
                }
                ActionBar(listOf(ActionItem("y", "Reset all", accent = Palette.Red), ActionItem("any other key", "Keep my settings", accent = Palette.Cta)), style = ActionStyle.Button)
            }
        }
    }
}

/** The groups as a row of tabs; the one on show is lit. */
@Composable
private fun GroupBar(current: Group) {
    // the tabs wrap onto more rows instead of running off the edge: none is ever hidden
    val width = LocalContentWidth.current
    val rows = mutableListOf(mutableListOf<Group>())
    var used = 0
    Group.entries.forEach { g ->
        val w = g.title.length + 3
        if (rows.last().isNotEmpty() && used + w > width) { rows.add(mutableListOf()); used = 0 }
        rows.last().add(g)
        used += w
    }
    Column {
        rows.forEach { row ->
            Row {
                row.forEach { g ->
                    val on = g == current
                    Label(" ${g.title} ", color = if (on) Palette.Background else Palette.Muted, background = if (on) Palette.Cta else Color.Unspecified, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
                    Label(" ")
                }
            }
        }
    }
}

/** A row that does nothing while its switch is off: the helper-model rows (but not the master switch itself or the goal) and the skill-role rows. */
private fun inactive(config: AppConfig, s: Setting): Boolean = when (s.group) {
    Group.Helpers, Group.Opinions, Group.Flow -> !config.oraculEnabled && s.label != "Helper models" && s.label != "Goal turns" && !s.label.startsWith("Loop guard")
    Group.Skills -> !config.skillRolesEnabled && s.label != "Skill roles"
    else -> false
}

@Composable
private fun OptionList(config: AppConfig, items: List<Setting>, selected: Int) {
    val labelWidth = items.maxOf { it.label.length } + 2
    val valueWidth = (panelWidth() - labelWidth - 8).coerceAtLeast(10)
    items.forEachIndexed { i, s ->
        val on = i == selected
        val bg = if (on) Palette.Selected else if (i % 2 == 0) Palette.EntryBg else Palette.Surface
        val off = inactive(config, s)
        val value = s.value(config) + if (off) "  · off" else ""
        val section = sectionOf(s)
        if (section != null && section != sectionOf(items.getOrNull(i - 1) ?: s).takeIf { i > 0 }) {
            Label("  ── $section ".padEnd((panelWidth() - 2).coerceAtLeast(10), '─'), color = Palette.Muted, textStyle = TextStyle.Bold)
        }
        Row(Modifier.fillMaxWidth().background(bg)) {
            Label(if (on) "▌" else " ", color = Palette.Accent)
            Label(" " + fit(s.label, labelWidth), color = if (on) Palette.Cta else if (off) Palette.Dim else Palette.FieldLabel, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(if (on && s.step != null) "‹ " else "  ", color = Palette.Muted)
            Label(if (value.length > valueWidth) value.take(valueWidth - 1) + "…" else value, color = if (off) Palette.Dim else Palette.Text, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label(if (on && s.step != null) " ›" else "", color = Palette.Muted)
        }
    }
}

/**
 * [text] cut to a fifth of the window's rows, ending in "…" when there was more: the help under the list must not take the rows
 * the list needs (the full text is one Enter away, where it has the whole panel).
 */
@Composable
private fun shortened(text: String): String {
    val cap = (LocalContentHeight.current / 5).coerceIn(2, 8)
    val lines = text.split('\n').flatMap { wrap(it, (LocalContentWidth.current - 4).coerceAtLeast(20)) }
    return if (lines.size <= cap) text else lines.take(cap).joinToString("\n").trimEnd() + "…"
}

/** The index, among the children [OptionList] draws (section headings included), of option [selected]. */
private fun optionChild(items: List<Setting>, selected: Int): Int {
    var child = 0
    for (i in 0..selected.coerceAtMost(items.lastIndex)) {
        val section = sectionOf(items[i])
        if (section != null && section != sectionOf(items.getOrNull(i - 1) ?: items[i]).takeIf { i > 0 }) child++
        if (i < selected) child++
    }
    return child
}

/** The sidebar tabs in their current order, with the number key each one has; the chosen one can be moved. */
@Composable
private fun TabList(selected: Int) {
    val tabs = Screen.sidebar
    tabs.forEachIndexed { i, screen ->
        val on = i == selected
        val bg = if (on) Palette.Selected else if (i % 2 == 0) Palette.EntryBg else Palette.Surface
        Row(Modifier.fillMaxWidth().background(bg)) {
            Label(if (on) "▌" else " ", color = Palette.Accent)
            Label(" ${screen.shortcut}  ", color = Palette.Cta, textStyle = TextStyle.Bold)
            Label("${screen.icon}  ${fit(screen.title, 16)}", color = if (on) Palette.Text else Palette.Muted, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
            Label("  ${screen.group.heading.lowercase()}", Modifier.weight(1f), color = Palette.Dim)
            if (on) Label(" ‹ move ›", color = Palette.Muted)
            if (i == 0) Label("  opens first", color = Palette.Green)
        }
    }
}
