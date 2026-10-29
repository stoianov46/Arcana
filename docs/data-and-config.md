# Data, configuration and vault conventions

## Where things live

| What | Location | Format | Owner |
|---|---|---|---|
| Settings | `~/.config/arcana/config.json` (or `$ARCANA_CONFIG`) | JSON (`AppConfig`) | `ConfigStore` |
| Chats | `~/.config/arcana/chats/<id>.json` | `StoredChat` JSON (images not stored) | `ChatStore` |
| Sessions | `~/.config/arcana/sessions/<id>.json` | `StoredSession` JSON, written as events happen | `SessionStore` |
| Token usage | `~/.config/arcana/usage.jsonl` | one `UsageRecord` per line | `UsageStore` |
| Tool backups | `~/.config/arcana/backups/` | copies of replaced/edited files | `ToolBox` |
| Tool trash | `~/.config/arcana/trash/` | files deleted by `delete_path` outside the vault (inside the vault: `<vault>/.trash`) | `ToolBox` |
| Memory file | `<vault>/.arcana/ARCANA.md` by default (an existing `<vault>/ARCANA.md` is still used), or `AppConfig.memoryPath` (inside the vault, `~/…` or absolute: `Vault.memoryFile()`); the user's own `~/.config/arcana/ARCANA.md` comes before it in every chat (see "Where Arcana's own files live") | Markdown, first 8,000 characters go to every chat | `Vault.memory/remember` |
| Your notes | the vault folder (`vaultPath`) | Markdown | `Vault` |
| Vault trash | `<vault>/.trash/` | Obsidian-compatible trash | `Vault.trash/replace`, inbox backups |
| Attachments | `<vault>/<outputFolder>/attachments/` | copied pictures/files | `Vault.copyAttachment` |

The config directory is derived from `ConfigStore.defaultPath().parent`; tests and diagnostics redirect everything by setting
`ARCANA_CONFIG` to another path.

## `AppConfig` fields

| Field | Default | Meaning |
|---|---|---|
| `ollamaUrl` | `http://localhost:11434` | Ollama base URL |
| `model` | `gemma4:latest` | model for every feature |
| `temperature` | 0.7 | sampling temperature |
| `timeoutSeconds` | 300 | HTTP timeout per request |
| `vaultPath` | `$ARCANA_VAULT` or empty | Obsidian vault folder (`~` allowed) |
| `outputFolder` | `Research` | vault folder for notes Arcana creates for you (AI notes, saved chats; visible content, never skills or memory; empty = vault root). It used to be `Arcana`, which was confusing next to the internal `.arcana/`; an existing config keeps its saved value |
| `contextNotes` / `contextCharsPerNote` | 5 / 4000 | how many notes / how many characters of each `/vault` mode sends; a longer note is cut with a marker for the model (`Prompts.cutMarker`) and the answer lists the cut sources (`ChatMessage.sourcesCut`) |
| `systemPrompt` / `summaryPrompt` | see source | prompts editable in Settings |
| `theme` | `dark` | theme id (`Themes.all`) |
| `uiScale` | `normal` | `compact`/`normal`/`spacious` layout density |
| `navPosition` | `left` | `left` / `right` / `top` |
| `sidebarCollapsed` | false | icon rail |
| `tabOrder` | `[]` | screen names in the user's order (first = start screen) |
| `animateLogo` | true | colour sweep while generating |
| `plainGlyphs` | false | ASCII-only output (screen readers) |
| `renderMarkdown` | true | rich Markdown vs raw text |
| `showThinking` | true | "<model> is thinking…" status |
| `thinkingView` | summary | how a reasoning model's thoughts show in Chat: `summary` (one dim line), `full`, `hidden`; the text is stored in `ChatMessage.thinking` (never sent back to the model) |
| `comparisonView` | side | how a `/compare` result is drawn in Chat: `side` (answers in columns, verdict below) or `stacked`; narrow windows stack either way |
| `autoCompactPercent` | 80 | compact the chat before a message when the context window is this percent full (0 = off); `/compact` always works (see `Compaction`) |
| `chatHistory` | 20 | latest messages sent as context |
| `keepChats` / `chatsKept` | true / 50 | persist chats / how many to keep |
| `chatUseVault` | false | answer from vault (also `/vault`) |
| `copyAs` | `markdown` | select-mode copy format: `markdown` / `plain` |
| `inboxFolder` | `Inbox` | where unprocessed notes are |
| `dailyFolder` / `dailyFormat` | `""` / `YYYY-MM-DD` | daily note fallback when Obsidian has no setting |
| `staleDays` | 90 | stale threshold |
| `lastPickerDir` | `""` | last folder of the file browser |
| `toolsEnabled` | false | tools in Chat |
| `toolMode` | `ask` | `ask` / `auto` / `plan` |
| `toolAutoShell` | false | shell commands run without asking (`/autorun`, Settings → Tools); `ShellPolicy.blockedReason` still refuses; Chat status bar shows red `▶ auto-run` |
| `toolShell` / `toolWeb` | false / false | `run_command` / `fetch_url` and `web_search` may be used (each call still asks) |
| `searchBackend` | `duckduckgo` | `duckduckgo` / `searxng` / `brave`: where `web_search` looks |
| `searchUrl` | `""` | address of the user's SearXNG server (used only by that backend) |
| `searchKey` | `""` | Brave Search API key, stored in plain text in `config.json` (keep the file private); the Settings row shows only its last 4 characters |
| `textCalls` | `auto` | a tool call written as text: `auto` runs it, `ask` shows it first, `off` leaves it |
| `toolAutoSearch` | false | `web_search` and `fetch_url` run without asking (Settings → Tools → *Search and fetch without asking*; needs `toolWeb`; HTTP requests still ask, the Oracul always asks); the Chat status bar shows an orange `↗ auto-search` |
| `toolFolders` | `""` | Settings → Tools → *Allowed folders* (was *Extra folders*): `;`-separated roots the file tools may read and write besides the vault, `~` expanded (the only place a home or hidden folder can be allowed) |
| `toolAutoRead` | true | read-only tools run without asking |
| `skillsEnabled` / `skillsFolder` | true / `` (automatic) | the model is told which skills exist and gets `use_skill`; the vault folder with one folder per skill. Blank = `Layout.skillsFolder`: `.arcana/skills`, or an existing `Skills/`; a name set here wins |
| `libraryTree` | true | the Library lists notes as a tree of folders that fold and unfold (false: one flat list with full paths) |
| `repeatMemorySeconds` | 45 | how long an identical read, search or web fetch of the current question is answered from memory (0 = off); Settings → Tools → *Remember repeated reads* |
| `thinkingPane` / `thinkingPaneRows` | false / 6 | the split window that follows the model's thinking (Settings → Console → *Thinking pane*, `/thinking`) and how many rows (3-15) it shows |
| `oldToolOutput` | `whole` | tool results from earlier questions are sent as they were (`whole`) or cut to 1,200 characters with a marker (`shorten`); Settings → Tools → *Old tool output* |
| `toolSteps` | 8 | max tool rounds per question (1–60); at the limit `stepLimit` decides |
| `stepLimit` | `ask` | at the step limit: `ask` shows a dialog offering that many more steps, `auto` gives them at once (up to `STEP_LIMIT_AUTO_ROUNDS` = 3 times per question, logged in the session, then it asks), `stop` makes the model answer with what it has. Each change, command and web address inside the extra steps keeps its own approval |
| `rolePlanner` / `roleWorker` / `roleReviewer` | `` | the role table for skill runs (Settings → Oracul → Skill planner / worker / reviewer): used when a skill does not name its own (`SkillRoles.withDefaults`); blank = not used |
| `rolesScope` | `skills` | `skills` or `all`: where the role table below applies (only a run with a skill, or every message) |
| `oraculExtra` | `` | more models that answer a `/compare` besides the Oracul model, comma-separated (`Oracul.models`; the Settings picker ticks them with Space); the judge compares every answer |
| `stuckCalls` / `stuckTurns` | `4` / `3` | the stuck thresholds (Settings → Oracul → *Stuck: failed calls / failing turns*): the Oracul steps into a tool loop after every `stuckCalls` failed calls of one turn, and an automatic review is due after `stuckTurns` failing turns in a row; `0` = never |
| `oraculUrl` | `` | another Ollama server for the Oracul and the judge (blank = the main one); `Oracul.serverConfig` swaps `ollamaUrl` for them, the model pickers of Settings → Oracul list what is installed there |
| `oraculModel` / `judgeModel` / `oraculAuto` | `` / `` / `problems` | the Oracul (second opinion) model, the judge model (blank = the Oracul model), and when a second opinion is asked for by itself (`off`, `problems`, `always`); blank `oraculModel` turns the Oracul off |
| `oraculContextMessages` / `oraculContextChars` | 6 / 1500 | how many earlier chat messages (0 = none, up to 200) and how many characters of each the Oracul, judge and planner are shown besides the last question; Settings → Oracul → *Chat context* |
| `oraculJudgeAuto` | false | when an automatic second opinion is due, run a full comparison (Oracul answers, judge compares) instead; slow, three model runs |
| `oraculTools` | true | the Oracul may use the worker's tools to check an answer (reads follow *Read without asking*; every change, command and web access asks, even in Auto mode); needs `toolsEnabled` and a tools-capable Oracul model |
| `setupDone` | false | the first-run setup was finished or skipped (it opens by itself only while there is no vault and this is false); set by the wizard and by `arcana --init` |
| `soundAttention` / `soundDone` / `soundFailed` | `off` | a `SoundChoices` id (`off`, `sys:Hero`, `bell1`..`bell5`, `file`) for: a dialog waits for you / a reply finished / a reply failed |
| `soundVolume` / `soundFile` / `soundWhere` | 80 / `` / `away` | volume in percent for system sounds and the file; the file for the choice `file`; `away` = only while Chat is not on screen, `always` |
| `projectFolder` | `` | Settings → Tools → *Working folder* (was *Project folder*; optional, grants nothing beyond itself): allowed like the vault, where relative paths and commands start, its ARCANA.md / CLAUDE.md / AGENTS.md goes to the model (see tools-and-agent.md "Project context") |
| `memoryPath` | `` | where the memory file is: blank = `.arcana/ARCANA.md` (an existing root `ARCANA.md` is still used: `Layout.defaultMemory`); relative = inside the vault; `~/…` or absolute = anywhere (Settings → Prompts → *Memory file path*); read by `Vault(root, memoryPath)` from `AppContext.openVault()` |
| `memoryEnabled` / `memoryOffered` | true / false | add `ARCANA.md` to every chat / already created or checked once (so a deleted file is not recreated) |
| `contextTokens` | 0 | `num_ctx` to ask Ollama for (0 = Ollama's default); the Chat gauge counts against it, capped at the model's maximum; with 0 it counts against `AppContext.loadedContext` (what Ollama really loaded, usually far below the model's maximum) and only then the model's maximum (`effectiveContext`); sizes are written `256k` / `32k` (`UsageStats.contextSize`: multiples of 1024), a window under 32k (`COMFORTABLE_CONTEXT`) adds an orange `⚠ ctx 4k` pill; Settings → Context window shows "Right now: Ollama runs … with a window of …" |
| `estPriceIn` / `estPriceOut` | 3 / 15 | US dollars per million prompt / reply tokens, only for the estimated price in the status bars |

Adding a field: give it a default (old config files must still load), add a Settings row (see
[conventions.md](conventions.md)), document it here.

## Vault conventions Arcana relies on

- Notes are `*.md`; paths are vault-relative with `/`. Folders starting with `.` are ignored by listings and the index.
- Links: `[[Note title]]`, `[[folder/Note]]`, `[[Note|alias]]`, `[[Note#heading]]` (resolved by path or title; unresolved ones count as
  broken links). Embeds/links to non-note files (`![[pic.png]]`, `[[x.pdf]]`) are neither links nor broken links.
  Links and tags inside code fences/inline code are ignored.
- Tags: `#tag` inline and `tags:` in frontmatter (list `[a, b]` or YAML `- a` lines).
- Frontmatter written by Arcana: `created`, `generator: arcana`, `model`, `tags: [arcana]`. `Vault.replace` and
  `mergeFrontmatterTags` preserve existing frontmatter.
- Renaming a note (Library `r`, `Vault.rename`) rewrites the links to it in other notes; the memory file is `.arcana/ARCANA.md` (or an older `ARCANA.md` at the vault root).
- Daily notes: path from `.obsidian/daily-notes.json` (`folder`, `format`) else settings. Capture appends `- HH:mm text`.
- Deletion is never permanent: `Vault.trash` → `.trash/`; the `delete_path` tool moves to `.trash/` (vault) or `<config>/trash`. Replacements/inbox processing back up originals into `.trash/`.
- Session summary notes and saved chats are normal notes created in `outputFolder`.
- **Where Arcana's own files live (`core/Layout.kt`; Settings → Vault has read-only rows that show the places, and *Skills folder* / *Memory file path* show the file in use: `skillsWhere`, `memoryWhere`).** Its working files are kept apart from the notes it produces (reports, research, AI notes, which stay visible in the vault in `outputFolder`, `Reports/…`, `Research/…`). *In the vault* the hidden folder `.arcana/`: `.arcana/skills/` and `.arcana/ARCANA.md` (hidden folders are skipped by Obsidian, by the note search and by `Vault.listNotes`, but every tool that opens a path reads them). *For the user*, in the settings folder `~/.config/arcana/`: `skills/` (every vault; a vault skill of the same name wins, `Skills.discoverAll`; a global skill is addressed by its absolute path and the toolbox may read that folder) and `ARCANA.md` (read first, then the vault's). Nothing moves by itself: a vault with `Skills/` or a root `ARCANA.md` keeps them until `arcana --migrate` (`Layout.migrate`) moves them (a target that exists is never overwritten; `[[links]]` to moved notes are not rewritten); a new vault gets `.arcana/`. Reference notes for the model (for example the LLM reference) live in `.arcana/reference/`, but Arcana does not create or read them itself (the memory file tells the model their path). **Links:** Obsidian does not resolve a `[[link]]` to a note inside the hidden folder, so a visible note (a report, research) must not wikilink to a skill or a reference note in `.arcana/`: write its name as plain text or in backticks; a skill that writes reports must not put such a link into them (`gt_forecast.py` writes `coingecko-forecast` as text). Links between notes inside `.arcana/`, and to visible notes, are fine.
- Skills: `<skillsFolder>/<name>/<name>.skill.md` (+ `reference/`, `scripts/`); properties Arcana reads: `name`, `description`, `version`, `output`, `requires` (quoted list items); everything else is for people. A skill's results live outside it (`output:`, e.g. `Reports/<name>/`). `create_note` merges a `---` properties block at the top of its content with Arcana's own; `set_properties` changes properties in place. See [skills.md](skills.md).

## Data shapes (persisted JSON)

- `StoredChat { id, title, updated, model, messages: [ChatMessage] }`; `ChatMessage` fields as in
  [core-module.md](core-module.md) (images omitted on disk; `files`, `toolCalls`, `toolName` kept).
- `StoredSession { id, started, ended?, savedAs?, events: [ { time, kind, text, path? } ] }`; `kind` ∈ `SessionKind`
  (`asked, task, created, appended, replaced, renamed, deleted, saved, captured, processed, attached, tool, session`).
- `UsageRecord { time, feature, model, promptTokens, completionTokens, millis }`; `feature` ∈ `Chat`, `Generate` (AI notes), `Inbox`, `Session`
