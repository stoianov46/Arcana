# `core` module reference

Path: `core/src/main/kotlin/com/arcana/brain/core/`. No UI, no Compose. Dependencies: kotlinx (serialization, coroutines,
datetime) and Apache PDFBox. Tests: `core/src/test/kotlin/com/arcana/brain/core/CoreTest.kt`.

## File map

| File | Contents |
|---|---|
| `AppConfig.kt` | `AppConfig` (all settings, `@Serializable`), `ConfigStore` (load/save JSON) |
| `OllamaClient.kt` | `OllamaClient`, `OllamaProtocol`, `ChatMessage`, `ChatChunk`, `ReplyStats`, `ModelDetails`, `OllamaException` |
| `Layout.kt` | `Layout`: where Arcana's own files live (`.arcana/skills`, `.arcana/ARCANA.md`, the global `~/.config/arcana/skills` and `ARCANA.md`), `skillsFolder`/`defaultMemory` (automatic choice), `migrate` (`arcana --migrate`) |
| `Vault.kt` | `Vault` (incl. `rename` with link rewriting, `memoryFile`/`memory`/`ensureMemory`/`remember` (no duplicates, no dead note paths), `auditMemory`/`cleanMemory` (`MemoryLines.kt`: `similar`, `noteRefs`, `MemoryAudit`) for the memory file (`ARCANA.md` or `AppConfig.memoryPath`), `MEMORY_TEMPLATE`), `Note`, `SearchHit`, `NoteTask`, `Prompts` (every prompt template), `VaultException` |
| `VaultIndex.kt` | `VaultIndex`, `NoteInfo`, `BrainHealth`, `DailyNotes`, `Vault.capture` |
| `Inbox.kt` | `InboxProcessing` (prompt + parse), `InboxPlan`, `mergeFrontmatterTags`, `Vault.processInboxNote` |
| `ChatStore.kt` | `ChatStore`, `StoredChat` (persistent chats as JSON files) |
| `Session.kt` | `SessionKind`, `SessionEvent`, `StoredSession`, `SessionStore`, `SessionStats`, `SessionReport` |
| `Usage.kt` | `UsageRecord`, `UsageStore` (JSONL), `UsageTotals`, `UsageStats` (totals, `by`, `perDay`, `perHour`, `speedPerDay`, `insights`, `estimatedCost`, `since`, `compact`), `UsageInsights` |
| `ImageFiles.kt` | picture rules + loading (`ImageAttachment`, base64) |
| `TextFiles.kt` | text/PDF attachment rules + loading (`FileAttachment`, PDFBox extraction, `TextFiles.block`) |
| `Tools.kt` | tool-calling types (`ToolCall`, `ToolSpec`, `ToolRisk`), `FileAccess` sandbox, `ToolBox` (all tools, `cheatSheet`, `describe`), prompts (`TOOLS_PROMPT`, `REQUEST_ACCESS_PROMPT`, `NO_TOOLS_PROMPT`), `wait` (`MAX_WAIT_SECONDS`), `ask_user`/`request_access` definitions, `cleanOption`, `looksLikeToolTask`, `describeNow` |
| `Skills.kt` | `Skill`, `Skills.discover` (one folder per skill, entry note `<name>.skill.md`, `output:` folder for results, problems), `catalog`, `load`, `loadEntry`, `isEntry`, `chipLabel`, `find`, `summary`, `parseRequirements`/`requirementsOf`; `Requirement` and `SkillRun` (the completion gate's progress) |
| `VaultIndex.kt` tags | `VaultIndex.frontmatterTags` reads tags however they are written (`tags: [a, b]`, `a, b`, `a b`, `#a #b`, a `- a` list; `tags:`, `tag:` or `Tags:`; several keys all count); `cleanTag` and `isTagKey` are shared with `mergeFrontmatterTags` (Inbox.kt) and `Vault.addTags` |
| `ToolCuts.kt` | `ToolCuts.marker(result)`: the line in which a tool says it left something out, shown in the chat as `✂ …` |
| `Todos.kt` | `TODO`, `TODO_DEFINITION`, `Todos` (`parse`, `describe`, `last`, `render`): the model's own task list, the `todo` pseudo-tool; the list is derived from the latest `todo` call in the chat (`ChatSession.todos`), nothing is stored apart from the messages |
| `ChatStats.kt` | `CHAT_STATS`, `CHAT_STATS_DEFINITION`, `ChatStats.describe(...)`: the text the `chat_stats` pseudo-tool returns |
| `LoopGuard.kt` | `LoopGuard`: `repeating` (the last 80 characters stand N times in the last 12,000), `withoutLoop`, the nudge texts, `Calls` (counts repeated tool calls of a turn: `note` → `Step.None/Warn/Halt`, `forget`, `reset`, `halted`) | the loop guard; pure, no model |
| `Events.kt` | `StatEvent`, `Events` (the kinds: flow, control, redo, check, goal, route, consult, verdict, opinion, cancel, compact, memory, loop, artifact), `EventStore` (`events.jsonl`), `TeamStats` / `TeamStatsBuilder` (the Usage screen's Orchestration page: helper share of tokens and price, models, flow acceptance at the first check, verdict confirmation rate) |
| `ToolUsage.kt` | `ToolUseRecord` (time, tool, millis, `ok`/`error`/`denied`, result chars → `approxTokens` = chars/4), `ToolUseStore` (`tool-usage.jsonl` next to the settings), `ToolStats.by/since` → `ToolTotals` |
| `Compaction.kt` | `Compaction`: `MARK`, `isSummary`, `summaryMessage`, `forModel` (from the latest summary on), `cutPoint` (the latest turns that fit in `keepTokens` stay, at least the last one; without it the last 2; ≥4 older messages; one long turn is cut between its tool rounds at an assistant message), `toSummarize`, `transcript` (tool results cut to 400 characters, ≤30,000 characters) and `INSTRUCTIONS` for the summarizer; the summary is inserted into the chat, nothing is deleted |
| `Sound.kt` | `ChatSoundEvent` (Attention, Done, Failed), `SoundChoices` (ids, labels, `suggested`), `SoundPlayer` / `CommandSoundPlayer` (`afplay` or the terminal bell), `Sounds.notify(config, event, onChatScreen)` |
| `Bootstrap.kt`, `StarterSkills.kt` | `Bootstrap.items(vault, config)` / `create(vault, config, ids)`: what a vault needs for Arcana (memory file, skills folder, skills guide, starter skills, output folder) and creating only what is missing (an existing file is never touched); `StarterSkills.all` (`decompose`) and `StarterSkills.GUIDE` are plain Markdown constants |
| `Storage.kt` | `Storage.areas(configRoot, vaultRoot?)` → `StorageArea`s (backups, trash, vault `.trash`, chats, sessions, usage, tool usage), `use` (files, bytes), `clearable` / `clear(area, olderThanDays)` (empty folders are removed, the area's own folder stays), `size` |
| `Artifacts.kt` | `Artifact`, `Artifacts`: which tool calls leave a file or note behind in a chat (the `✦ N changed` pill, `/artifacts`) |
| `Flow.kt` | `StageKind`, `FlowStage`, `Flow`: a fixed sequence of stages (analyst, planner, worker, reviewer, controller), each with its own model, and the pseudo-tool a model uses to consult a stage |
| `ModelStatus.kt` | `SystemMemory`, `SystemCpu`, `ModelStatus`: one sentence on why a request has not produced a word yet (model not loaded, prompt being read, memory tight) |
| `Router.kt` | `Route`, `Router`: rule-based routing of a message with an image or code to its own model (Settings → Helpers) |
| `Oracul.kt` | `Oracul`: the models (`model`, `judge`, `models` with the extras, `serverConfig` for the second server), the chat block formats and predicates (`MARK`, `MARK_COMPARE`, `MARK_PLAN`, `isOpinion` / `isReview` / `isPlan`, `opinionMessage`, `comparisonMessage`, `planMessage`, `stuckMessage`, `TAKEOVER_MARK`, `body`, `comparisonParts`), the prompts (`reviewRequest`, `answerRequest`, `judgeRequest`, `planRequest`, `stuckRequest`, `attempts`), when to consult by itself (`turnFacts`, `isFailure`, `failedStreak`, `stuckLevel`, `due`), `background` (the recent chat shown to the Oracul, judge and planner; the request builders take it), `lastExchange` / `lastTurn`, `ask` (one request to a client), the status text (`statusText`, `modeWord`, `setupLine`, `GLYPH`); no UI. See [oracul.md](oracul.md) |
| `ModelFit.kt` | `ModelFit`: whether a model fits this machine's memory (`fit`, `budget`, `label`, `physicalMemory`) and the standing advice with a better choice (`advice`, `Role`) and which Oracul and judge to suggest (`suggest`); a rule of thumb, no UI |
| `Web.kt` (HTTP) | `WebPolicy` (which addresses), `fetchUrl` (GET a page as text), and the gate for everything else: `HttpGate` (`refusal`, `parseHeaders`, `maskUrl`, `preview`) and `httpRequest` (method, headers, body; redirects only for GET/HEAD) behind the `http_request` tool |
| `Diff.kt` | `Diff.unified(old, new, context)`: a git-style line diff (`@@ -a,b +c,d @@` hunks, `  ` context, `- ` removed, `+ ` added; common start and end cut off first, an LCS for the middle, big middles shown as one replacement); used by the change previews |
| `Shell.kt` | `ShellPolicy` (blocked / safe commands; `curl` and `wget` are blocked: use `http_request`) and `runShell` (the `run_command` runner) |
| `Web.kt` | `WebPolicy` (public addresses only), `fetchUrl` (manual redirects, size/time limits), `htmlToText` |
| `WebSearch.kt` | `WebSearch.search` with three backends (DuckDuckGo HTML, SearXNG JSON, Brave API), pure parsers, `format`; `SearchResult` |
| `TextToolCalls.kt` | `TextToolCalls.extract`: finds a tool call the model wrote as text at the end of a reply |

## `OllamaClient.kt`

- `OllamaClient(config: () -> AppConfig)`: reads config on every call (settings changes apply immediately).
  - `chat(messages, jsonMode=false, tools: JsonArray?=null): Flow<ChatChunk>`: POST `/api/chat` with `stream=true`; parses NDJSON
    lines; throws `OllamaException` for connection errors / HTTP ≠ 200 / `error` field. Cancelling the collector cancels the request.
  - `show(model): Pair<capabilities, contextLength>` (`/api/show`: capabilities such as `completion`, `vision`, `tools`, `thinking`; the model's
    maximum context from `model_info`); `capabilities(model)` is its first part. Empty/0 when unknown.
  - `listModels(): List<String>` and `modelDetails(): List<ModelDetails>` (`/api/tags` + `show` per model: size, capabilities; `summary()` is
    the `19 GB · dense · tools · thinking`, `MoE` for a mixture of experts, from `model_info`, else from the tensor list (`OllamaProtocol.parseTensorLayout`) text of the model picker).
  - `loadedContext(model)` (`/api/ps`, `OllamaProtocol.parseLoadedContext`): the `context_length` Ollama loaded the model with; 0 when it is not loaded or this Ollama is too old to say.
  - `chatRequest` sends `options.temperature`, and `num_ctx` when `AppConfig.contextTokens > 0`.
- `OllamaProtocol` (pure, unit-tested): `chatRequest(config, messages, jsonMode, tools)`, `parseChunk(line)`, `parseCapabilities`,
  `parseModels`. Messages are serialized with `ChatMessage.withFilesInlined()` (text files become part of `content`).
  Defaults are not encoded, so app-only fields (`imageNames`, `stats`, `noteNames`, `sources`) never reach Ollama when empty.
- `ChatMessage(role, content, images(base64), imageNames, stats, noteNames, sources, files, toolCalls, toolName, thinking)`:
  - `think`: for a message of yours, the reasoning level chosen for it alone (never sent to a model).
  - roles: `system | user | assistant | tool`, and `oracul_step` (`ChatMessage.DISPLAY_ONLY`): a tool call the Oracul made, for the reader only (`OraculSteps`, in `Oracul.kt`). It is kept with the chat and drawn in it, but `Compaction.forModel` and `OllamaProtocol.chatRequest` drop every display-only message, so no model ever receives one; select/copy treats it as one entry.
  - `images` are kept in memory only; stored chats use `withoutImageData()`.
  - `noteNames` are vault paths whose text is read **at send time** (so changed notes are seen as they are).
  - `files: List<FileAttachment>` (text/PDF) are inlined into the content when the request is built.
  - `toolCalls` (assistant) / `toolName` (tool) use Ollama's wire names `tool_calls` / `tool_name`.
  - factories: `system`, `user(content, images, notes, files)`, `assistant(content, stats, sources)`, `assistantCalls`, `toolResult`.
- `ChatChunk(content, thinking, done, promptTokens, completionTokens, totalMillis, evalMillis, toolCalls)`; `toStats()` builds
  `ReplyStats` (null if Ollama sent no counts). `ReplyStats` has `tokensPerSecond` and `seconds`.

## `Vault.kt`

`Vault(root)` throws `VaultException` if the folder is missing. All note paths are **vault-relative with `/`**.

| Method | Behaviour |
|---|---|
| `notes()` | all `*.md` not under a dot-folder (so `.trash`, `.obsidian` excluded), newest first |
| `read(note)` | UTF-8 text; path is checked to stay inside the root (`resolve`) |
| `create(folder, title, body, model)` | writes `Title.md` (title sanitized, `Title 2.md` if taken) with frontmatter `created/generator: arcana/model/tags: [arcana]` |
| `append(note, heading?, body)` | appends, optionally under `## heading` |
| `rename(note, newTitle)` | renames the file in its folder and rewrites `[[links]]`/`![[embeds]]` (title and path style, alias and heading kept, code skipped, ambiguous titles not guessed); returns `RenameResult(path, linksUpdated, notesChanged)` |
| `replace(note, body)` | replaces body keeping frontmatter; old file copied to `.trash` first; returns backup path |
| `trash(note)` | moves to `<root>/.trash` (Obsidian's trash) with unique name |
| `copyAttachment(folder, source)` | copies into `<folder or root>/attachments/`, unique name, returns the file name for `![[name]]` |
| `search(query, limit, excerptChars)` | keyword search: tokens ≥3 letters minus stop words; score = 10 per term in title + min(10, count) per term in text; excerpt around first hit |

Static helpers: `sanitizeTitle`, `splitFrontmatter(text) -> (frontmatter, body)`, `tokenize`.

`Prompts` (all prompt text lives here; `window(limit, history)` is what a chat sends: at least the last N messages and never less than the whole current turn; `cutMarker` / `cutNote` / `hitText` mark a cut note): `summarize`, `ask`, `noteTask(config, NoteTask, title, text, input)`,
`compose(config, request)`, `chatMessages(config, history, hits?, noteTexts, tools, toolsNote, canRequestTools, memory, today)`, `sessionSummary(config, activity, chats)`.
`chatMessages` windows history to `config.chatHistory`, inlines attached notes into the user messages, wraps the latest
question with vault excerpts when `hits != null`, keeps tool output older than the latest user message whole (or, with `oldToolOutput = "shorten"`, cuts it to 1,200 characters with a marker), and builds the system prompt: `systemPrompt` + today's date + (`TOOLS_PROMPT` + tool note | `REQUEST_ACCESS_PROMPT` | `NO_TOOLS_PROMPT`) + the `ARCANA.md` memory.

## `VaultIndex.kt`

`VaultIndex.build(vault)` (companion) scans every note once: word count, frontmatter + inline `#tags`, `[[wikilinks]]` resolved to
paths (unresolved kept separately; a name is tried as a note first, so `[[Qwen3.8 27B vs …]]` with dots is a note, and only a missing name that ends in a dot and 1-5 letters or digits, like `shot.png`, counts as a picture or file and is ignored). **Code blocks and inline code are ignored** for links and tags. Derived: `backlinks`,
`orphans`, `untagged`, `stale(days)`, `inFolder`, `tagCounts`, `related(path)` (shared tags/links), `touchedSince(days)`,
`health(inboxFolder, staleDays) -> BrainHealth` (counts + `connectedPercent`/`taggedPercent`).
`DailyNotes.detect(vault, folder, format)` reads `.obsidian/daily-notes.json` (`folder`, `format` in moment.js tokens) else
falls back to the settings; `pathFor(date)`. `Vault.capture(text, daily, now)` appends `- HH:mm text` (creates the note if needed).

## `Inbox.kt`

`InboxProcessing.prompt(...)` asks for JSON with `title, summary, tags, links, folder`, given tags in use, existing note titles and
folders. `parse(reply, ...)` tolerates messy replies (extracts the JSON object), **drops links to notes that do not exist and
unknown/unsafe folders**, returns `InboxPlan?`. `Vault.processInboxNote(path, plan, keepName)`: backup original to `.trash`,
merge tags into frontmatter (`mergeFrontmatterTags`), add a summary callout and a `## Related` list of `[[links]]`, rename/move.

## Stores (all JSON/JSONL under the config directory)

- `ConfigStore(path = ConfigStore.defaultPath())`: `~/.config/arcana/config.json` or `$ARCANA_CONFIG`; pretty JSON,
  `ignoreUnknownKeys`, `encodeDefaults`. A corrupt file loads as defaults.
- `ChatStore(dir)`: one `<id>.json` per chat (`StoredChat`), `list()` newest first, `latest()`, `save`, `delete`, `prune(keep)`;
  `ChatStore.titleOf(messages)` = first user line.
- `SessionStore(dir)`: one file per session; `SessionReport` turns events into stats, a duration/clock text, activity Markdown and
  the note body for "save summary".
- `UsageStore(path)`: append-only `usage.jsonl` of `UsageRecord(time, feature, model, promptTokens, completionTokens, millis)`;
  `UsageStats` aggregates (totals, `by` key, `perDay`, `since`, `compact` number formatting).

## Attachments

- `ImageFiles`: extensions png/jpg/jpeg/webp; `MAX_COUNT=5`, 10 MB each, 20 MB total; `validate(paths, already)` returns a
  user-facing problem or null; `load` → `ImageAttachment(name, base64, bytes)`.
- `TextFiles`: ~45 text extensions + `pdf`; `MAX_COUNT=5`, text ≤2 MB, PDF ≤20 MB, content cut to 30,000 chars (`truncated`).
  PDF: PDFBox per page with `[page N]` markers; encrypted, corrupt or text-less (scanned) PDFs raise `IllegalArgumentException`
  with a helpful message. `block(file)` renders the fenced "Attached file …" text that goes to the model.

## Tools

See [tools-and-agent.md](tools-and-agent.md).

## Invariants to keep

1. Vault writes go through `Vault` methods (path-escape checks, unique names, frontmatter handling, `.trash` backups).
2. Never write into the vault from the UI layer directly.
3. Anything shown to the model from files is treated as data; prompts live in `Prompts` / `TOOLS_PROMPT`.
4. `core` stays UI-free and unit-testable (use `Files.createTempDirectory` in tests).
