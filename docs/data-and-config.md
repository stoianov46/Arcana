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
| Memory file | `<vault>/ARCANA.md` by default, or `AppConfig.memoryPath` (inside the vault, `~/…` or absolute: `Vault.memoryFile()`) | Markdown, first 8,000 characters go to every chat | `Vault.memory/remember` |
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
| `outputFolder` | `Arcana` | vault folder for notes Arcana creates (empty = vault root) |
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
| `toolAutoSearch` | false | `web_search` runs without asking (Settings → Tools → *Search without asking*; needs `toolWeb`; pages and HTTP requests still ask, the Oracul always asks); the Chat status bar shows an orange `↗ auto-search` |
| `toolFolders` | `""` | Settings → Tools → *Allowed folders* (was *Extra folders*): `;`-separated roots the file tools may read and write besides the vault, `~` expanded (the only place a home or hidden folder can be allowed) |
| `toolAutoRead` | true | read-only tools run without asking |
| `skillsEnabled` / `skillsFolder` | true / `Skills` | the model is told which skills exist and gets `use_skill`; the vault folder with one folder per skill |
| `libraryTree` | true | the Library lists notes as a tree of folders that fold and unfold (false: one flat list with full paths) |
| `toolSteps` | 8 | max tool rounds per question (1–60); at the limit a dialog offers that many more |
| `rolePlanner` / `roleWorker` / `roleReviewer` | `` | the role table for skill runs (Settings → Oracul → Skill planner / worker / reviewer): used when a skill does not name its own (`SkillRoles.withDefaults`); blank = not used |
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
| `memoryPath` | `` | where the memory file is: blank = `ARCANA.md` at the vault root; relative = inside the vault; `~/…` or absolute = anywhere (Settings → Prompts → *Memory file path*); read by `Vault(root, memoryPath)` from `AppContext.openVault()` |
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
- Renaming a note (Library `r`, `Vault.rename`) rewrites the links to it in other notes; `ARCANA.md` at the vault root is the memory file.
- Daily notes: path from `.obsidian/daily-notes.json` (`folder`, `format`) else settings. Capture appends `- HH:mm text`.
- Deletion is never permanent: `Vault.trash` → `.trash/`; the `delete_path` tool moves to `.trash/` (vault) or `<config>/trash`. Replacements/inbox processing back up originals into `.trash/`.
- Session summary notes and saved chats are normal notes created in `outputFolder`.
- Skills: `<skillsFolder>/<name>/<name>.skill.md` (+ `reference/`, `scripts/`); properties Arcana reads: `name`, `description`, `version`, `output`, `requires` (quoted list items); everything else is for people. A skill's results live outside it (`output:`, e.g. `Reports/<name>/`). `create_note` merges a `---` properties block at the top of its content with Arcana's own; `set_properties` changes properties in place. See [skills.md](skills.md).

## Data shapes (persisted JSON)

- `StoredChat { id, title, updated, model, messages: [ChatMessage] }`; `ChatMessage` fields as in
  [core-module.md](core-module.md) (images omitted on disk; `files`, `toolCalls`, `toolName` kept).
- `StoredSession { id, started, ended?, savedAs?, events: [ { time, kind, text, path? } ] }`; `kind` ∈ `SessionKind`
  (`asked, task, created, appended, replaced, renamed, deleted, saved, captured, processed, attached, tool, session`).
- `UsageRecord { time, feature, model, promptTokens, completionTokens, millis }`; `feature` ∈ `Chat`, `Generate` (AI notes), `Inbox`, `Session`
