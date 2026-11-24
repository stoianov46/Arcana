# `core` module reference

Path: `core/src/main/kotlin/com/arcana/brain/core/`. No UI, no Compose. Dependencies: kotlinx (serialization, coroutines,
datetime) and Apache PDFBox. Tests: `core/src/test/kotlin/com/arcana/brain/core/CoreTest.kt`.

## File map

| File | Contents |
|---|---|
| `AppConfig.kt` | `AppConfig` (all settings, `@Serializable`), `ConfigStore` (load/save JSON) |
| `OllamaClient.kt` | `OllamaClient`, `OllamaProtocol`, `ChatMessage`, `ChatChunk`, `ReplyStats`, `ModelDetails`, `OllamaException` |
| `Layout.kt` | `Layout`: where Arcana's own files live (`.arcana/skills`, `.arcana/ARCANA.md`, the global `~/.config/arcana/skills` and `ARCANA.md`), `skillsFolder`/`defaultMemory` (automatic choice), `migrate` (`arcana --migrate`) |
| `Vault.kt` | `Vault` (incl. `rename` with link rewriting, `memoryFile`/`memory`/`ensureMemory`/`remember` (no duplicates, no dead note paths), `auditMemory`/`cleanMemory` (`MemoryLines.kt`: `similar`, `noteRefs`, `MemoryAudit`) for the memory file (`ARCANA.md` or `AppConfig.memoryPath`), `MEMORY_TEMPLATE`), `Note`, `SearchHit`, `VaultException` |
| `Prompts.kt` | `Prompts` (every prompt template: the chat's system prompt, `chatMessages`, `userContent`, `SystemParts`/`changesNote`, the note tasks), `NoteTask`; moved out of `Vault.kt` (proposal 0007) |
| `ContextMath.kt` | `effectiveContext` (which window the gauges count against), `contextTokens` (counted or estimated tokens in the context, and whether it is an estimate), `contextUsage` (share and `13.6k/131k` text), `thinkOptions` (the `think` values a model lists, as the settings name them); shared by `ChatSession` and the screens, moved out of `screens/Chat.kt` (proposal 0007) |
| `VaultIndex.kt` | `VaultIndex`, `NoteInfo`, `BrainHealth`, `DailyNotes`, `Vault.capture` |
| `Frontmatter.kt` | `mergeFrontmatterTags` (the tags of a note's frontmatter, merged) |
| `ChatStore.kt` | `ChatStore`, `StoredChat` (persistent chats as JSON files) |
| `Usage.kt` | `UsageRecord`, `UsageStore` (JSONL), `UsageTotals`, `UsageStats` (totals, `by`, `perDay`, `perHour`, `speedPerDay`, `insights`, `estimatedCost`, `since`, `compact`), `UsageInsights` |
| `ImageFiles.kt` | picture rules + loading (`ImageAttachment`, base64) |
| `TextFiles.kt` | text/PDF attachment rules + loading (`FileAttachment`, PDFBox extraction, `TextFiles.block`) |
| `Tools.kt` | (now the core of it: the types, the sandbox and `ToolBox` itself; the tools are in the files below) tool-calling types (`ToolCall`, `ToolSpec`, `ToolRisk`), `FileAccess` sandbox, `ToolBox` (all tools, `switchedOff`, `vaultInfo` and `vaultHealth` (the `vault_info` / `vault_health` tools; `VAULT_INFO`, `VAULT_HEALTH`), `cheatSheet` (not sent), `describe`), `OFF_TOOLS`, `TURN_ON_TOOLS` / `turnOnToolsDefinition`, prompts (`TOOLS_PROMPT`, `REQUEST_ACCESS_PROMPT`, `NO_TOOLS_PROMPT`), `wait` (`MAX_WAIT_SECONDS`), `ask_user`/`request_access` definitions, `cleanOption`, `looksLikeToolTask`, `describeNow` |
| `ToolSettings.kt` | `ToolSettings`: what the settings say about the tools (allowed folders with `~` expanded, plan mode = read-only, shell, web, search backend, skills folder, project folder, search tuning and mode), built once by `ToolSettings.of(config, backups, skillsFolder, notesSearch)`; `ToolBox(vault, settings, semantic, projectSearch, origin, provenance)` takes it; `expandHome`, `projectFolder` (proposal 0007, phase 3) |
| `VaultTools.kt`, `FileTools.kt`, `WebTools.kt`, `ShellTools.kt`, `ClockTools.kt` | the tools themselves, by area, as extension functions of `ToolBox` (which lists them in `specs`): the note tools with `vault_info`, `vault_health` and `notePath`; the file tools with backups, the trash and the folder walks; `web_search` / `fetch_url` / `http_request`; `run_command` and the git tools; `current_time` / `wait` / `elapsed_time` with `describeNow`, `describeDuration`, `parseMoment`, `describeElapsed`. Split out of `Tools.kt` (proposal 0007, phase 3) |
| `ToolDefinitions.kt` | `TOOLS_PROMPT`, `REQUEST_ACCESS_PROMPT`, `NO_TOOLS_PROMPT`, `looksLikeToolTask`, the tool names `VAULT_INFO` / `VAULT_HEALTH`, and the pseudo-tools the chat answers (`ASK_USER*`, `REQUEST_ACCESS*`, `TURN_ON_TOOLS`, `REQUEST_MODE*`, `OFF_TOOLS`, `TOOL_MODES`) |
| `Retrieval.kt` | the search ranking: `RetrievalMode` (keywords / meaning / both), `SearchScope` (parses `searchIn`), `SearchTuning` (weights, noise filter, pool, passages per note), `Terms` (tokenizer: identifiers whole and in parts), `Bm25`, `LexicalIndex` (in-memory keyword index of a source, one per source), `HybridSearch` (Reciprocal Rank Fusion, `noiseFilter`, `answer`, `hits`), `Found` (with `evidence`), `Retrieved`; see [retrieval.md](retrieval.md) |
| `Sources.kt` | `PassageSource` (what is searched: files with size and time, their text, their passages), `VaultSource`, `FolderSource` (code, config, text and documents of a folder; skips hidden, build, big, binary), `CodeChunker` (passages with a line and a declaration), `ProjectSearch` (the `search_files` tool's search over folders, one meaning index per folder, path:line / page) |
| `Documents.kt` | `Documents`: the text of a PDF (page by page), `.docx` and HTML for the file search (best effort, no OCR) |
| `Skills.kt` | `Skill`, `Skills.discover` (one folder per skill, entry note `<name>.skill.md`, `output:` folder for results, problems), `catalog`, `load`, `loadEntry`, `isEntry`, `chipLabel`, `find`, `summary`, `parseRequirements`/`requirementsOf`; `Requirement` and `SkillRun` (the completion gate's progress) |
| `VaultIndex.kt` tags | `VaultIndex.frontmatterTags` reads tags however they are written (`tags: [a, b]`, `a, b`, `a b`, `#a #b`, a `- a` list; `tags:`, `tag:` or `Tags:`; several keys all count); `cleanTag` and `isTagKey` are shared with `mergeFrontmatterTags` (Frontmatter.kt) and `Vault.addTags` |
| `ToolCuts.kt` | `ToolCuts.marker(result)`: the line in which a tool says it left something out, shown in the chat as `✂ …` |
| `Todos.kt` | `TODO`, `TODO_DEFINITION`, `Todos` (`parse`, `describe`, `last`, `render`): the model's own task list, the `todo` pseudo-tool; the list is derived from the latest `todo` call in the chat (`ChatSession.todos`), nothing is stored apart from the messages |
| `PromptTemplates.kt` | `PromptTemplate`, `PromptTemplates` (`FOLDER = "Prompts"`, `list`, `parse`, `placeholders`, `fill`, `noteText`, `create`, `setSkill`, `addNote`, `clearAttachments`, the text-level `withSkill` / `withNote` / `withoutAttachments`, `parseNotes`; a template's optional `skill` and `notes` properties name what is attached when it runs): the notes the Templates tab runs |
| `ChatStats.kt` | `CHAT_STATS`, `CHAT_STATS_DEFINITION`, `CHAT_STATS_JSON`, `CHAT_STATS_JSON_DEFINITION`, `ChatStats.describe(...)`, `ChatStats.describeJson(...)`, `ChatStats.spans` (chat time and active time from `ChatMessage.at`), `CompactionState` / `compactionFacts` / `compactionLine` (how often the chat was compacted, the latest summary's size, the auto-compact threshold and the tokens left before it; the JSON's `compaction` object): the text the `chat_stats` pseudo-tool returns, and the same numbers as JSON for `chat_stats_json` |
| `ContextInspector.kt` | `ContextPart`, `ContextSnapshot`, `ContextInspector` (`inspect`: the request the chat model was sent, split into parts (system prompt with its pieces, tool definitions, summary, earlier conversation, the message being answered with what Arcana added, this turn so far) with estimated tokens (`Compaction.tokens`) and whether each part is the same as in the previous request; `sharedPrefix` (tokens Ollama can reuse from its cache), `transcript`, `bar` (the ▰▱ bar)): what `/context` shows; pure, no UI |
| `Startup.kt` | `CheckState` (Ok, Warn, Off, Pending), `StartupCheck`, `Startup` (`vault`, `memory`, `skills`, `tools`, `search`): the lines of the start screen (`SplashScreen`); pure, no UI |
| `LoopGuard.kt` | `LoopGuard`: `repeating` (the last 80 characters stand N times in the last 12,000), `withoutLoop`, the nudge texts, `Calls` (counts repeated tool calls of a turn: `note` → `Step.None/Warn/Halt`, `forget`, `reset`, `halted`) | the loop guard; pure, no model |
| `Events.kt` | `StatEvent`, `Events` (the kinds: flow, control, redo, check, goal, route, consult, verdict, opinion, cancel, compact, memory, loop, artifact), `EventStore` (`events.jsonl`), `TeamStats` / `TeamStatsBuilder` (the Usage screen's Orchestration page: helper share of tokens and price, models, flow acceptance at the first check, verdict confirmation rate) |
| `ToolUsage.kt` | `ToolUseRecord` (time, tool, millis, `ok`/`error`/`denied`, result chars → `approxTokens` = chars/4), `ToolUseStore` (`tool-usage.jsonl` next to the settings), `ToolStats.by/since` → `ToolTotals` |
| `Embeddings.kt` (since retrieval) | `Embedder`, `Chunk` (with `line`), `Chunker` (notes by headings), `EmbeddingIndex` (the meaning index of ANY `PassageSource`, index file version 2), `SemanticSearch` (the `semantic_search` tool), `SemanticSetup` (the recommended model and its download text) |
| `Compaction.kt` | `Compaction`: `MARK`, `isSummary`, `summaryMessage`, `forModel` (from the latest summary on), `cutPoint` (the latest turns that fit in `keepTokens` stay, at least the last one; without it the last 2; ≥4 older messages; one long turn is cut between its tool rounds at an assistant message), `toSummarize`, `transcript` (tool results cut to 400 characters, ≤30,000 characters) and `INSTRUCTIONS` for the summarizer; the summary is inserted into the chat, nothing is deleted |
| `Sound.kt` | `ChatSoundEvent` (Attention, Done, Failed), `SoundChoices` (ids, labels, `suggested`), `SoundPlayer` / `CommandSoundPlayer` (`afplay` or the terminal bell), `Sounds.notify(config, event, onChatScreen)` |
| `Bootstrap.kt`, `StarterSkills.kt` | `Bootstrap.items(vault, config)` / `create(vault, config, ids)`: what a vault needs for Arcana (memory file, skills folder, skills guide, starter skills, output folder) and creating only what is missing (an existing file is never touched); `StarterSkills.all` (`obsidian-doctor`, `decompose`) and `StarterSkills.GUIDE` are plain Markdown constants |
| `Storage.kt` | `Storage.areas(configRoot, vaultRoot?)` → `StorageArea`s (backups, trash, vault `.trash`, chats, usage, tool usage), `use` (files, bytes), `clearable` / `clear(area, olderThanDays)` (empty folders are removed, the area's own folder stays), `size` |
| `Artifacts.kt` | `Artifact`, `Artifacts`: which tool calls leave a file or note behind in a chat (the `✦ N changed` pill, `/artifacts`; `entries` merges a path's calls, `libraryNote` says which entries the Library can open) |
| `Flow.kt` | `StageKind`, `FlowStage`, `Flow`: a fixed sequence of stages (analyst, planner, worker, reviewer, controller), each with its own model, and the pseudo-tool a model uses to consult a stage |
| `ModelStatus.kt` | `SystemMemory`, `SystemCpu`, `ModelStatus`: one sentence on why a request has not produced a word yet (model not loaded, prompt being read, memory tight); `embedLoaded` (the semantic search embedding model when Ollama holds it next to the chat model: the bottom bar's `◇ embed` segment) |
| `Router.kt` | `Route`, `Router`: rule-based routing of a message with an image or code to its own model (Settings → Helpers) |
| `Oracul.kt` | `Oracul`: the models (`model`, `judge`, `models` with the extras, `serverConfig` for the second server), the chat block formats and predicates (`MARK`, `MARK_COMPARE`, `MARK_PLAN`, `isOpinion` / `isReview` / `isPlan`, `opinionMessage`, `comparisonMessage`, `planMessage`, `stuckMessage`, `TAKEOVER_MARK`, `body`, `comparisonParts`), the prompts (`reviewRequest`, `answerRequest`, `judgeRequest`, `planRequest`, `stuckRequest`, `attempts`), when to consult by itself (`turnFacts`, `isFailure`, `failedStreak`, `stuckLevel`, `due`), `background` (the recent chat shown to the Oracul, judge and planner; the request builders take it), `lastExchange` / `lastTurn`, `ask` (one request to a client), the status text (`statusText`, `modeWord`, `setupLine`, `GLYPH`); no UI. See [oracul.md](oracul.md) |
| `WebTaint.kt` | `WebTaint`: `wrap` (a web result as `<untrusted source=…>`), `host`, `newPlace` (a new host or a query), `NOTE` (the approval line) |
| `ModelFit.kt` | `ModelFit`: whether a model fits this machine's memory (`fit`, `budget`, `label`, `physicalMemory`) and the standing advice with a better choice (`advice`, `Role`) and which Oracul and judge to suggest (`suggest`); a rule of thumb, no UI; `usable` (the GPU limit or 70%), `loadedNeed`, `toUnload` (which loaded models to unload so a model fits). `GpuMemory`: the Mac GPU limit (`iogpu.wired_limit_mb`, its default, `current()`, `suggestedMb`, `raiseCommand`) |
| `Web.kt` (HTTP) | `WebPolicy` (which addresses), `fetchUrl` (GET a page as text), and the gate for everything else: `HttpGate` (`refusal`, `parseHeaders`, `maskUrl`, `preview`) and `httpRequest` (method, headers, body; redirects only for GET/HEAD) behind the `http_request` tool |
| `Diff.kt` | `Diff.unified(old, new, context)`: a git-style line diff (`@@ -a,b +c,d @@` hunks, `  ` context, `- ` removed, `+ ` added; common start and end cut off first, an LCS for the middle, big middles shown as one replacement); used by the change previews |
| `Shell.kt` | `ShellPolicy` (blocked / safe commands; `curl` and `wget` are blocked: use `http_request`) and `runShell` (the `run_command` runner) |
| `Web.kt` | `WebPolicy` (public addresses only), `fetchUrl` (manual redirects, size/time limits), `htmlToText` |
| `WebSearch.kt` | `WebSearch.search` with four backends (DuckDuckGo HTML, SearXNG JSON, Brave API, Ollama hosted search), pure parsers, `format`; `SearchResult` |
| `TextToolCalls.kt` | `TextToolCalls.extract`: finds a tool call the model wrote as text at the end of a reply |

## `OllamaClient.kt`

- `OllamaClient(config: () -> AppConfig)`: reads config on every call (settings changes apply immediately).
  - `chat(messages, jsonMode=false, tools: JsonArray?=null): Flow<ChatChunk>`: POST `/api/chat` with `stream=true`; parses NDJSON
    lines; throws `OllamaException` for connection errors / HTTP ≠ 200 / `error` field. Cancelling the collector cancels the request.
    Streaming: the body is read as an `InputStream` that cancelling the collector closes (so Ollama stops generating at once). The request `timeout` (Settings → Timeout) covers only the wait for the first line (`OllamaProtocol.timeoutMessage`); a watchdog thread stops a stream silent for `max(timeout, minIdleSeconds = 600)` s (`stalledMessage`). `open` retries up to `RETRIES` = 2 times (1.5 s, 4 s) on a connection failure or `OllamaProtocol.isTransient` (503/429/502/504, a 500 saying the runner stopped), never on a time-out or out-of-memory. A stream without a `done` line, an unreadable line or a broken connection becomes an `OllamaException`. `ChatChunk.doneReason` (`done_reason`) and `cutOff` (`length`): `Generation` adds `CUT_NOTE` to such a reply, and a cut reply with no text is the `CUT_EMPTY` error.
    `chat(…, think, format)`: `think` replaces the thinking setting for one request (sent only when the model lists it); `format` is a JSON schema (structured outputs; `chatRequest(…, format)` wins over `jsonMode`).
    Before sending, `makeRoom` (local Ollama only) unloads other loaded models, biggest first, when the model would not fit next to them in `ModelFit.usable` (`ModelFit.toUnload`; `OllamaClient.unloaded` tells the app each name). An out-of-memory error (`OllamaProtocol.isOutOfMemory`, HTTP or mid-stream) is rethrown as `OllamaProtocol.outOfMemoryMessage` (model, context, estimated need, GPU limit, fixes, Ollama's words).
  - `show(model): Pair<capabilities, contextLength>` (`/api/show`: capabilities such as `completion`, `vision`, `tools`, `thinking`; the model's
    maximum context from `model_info`); `capabilities(model)` is its first part. Empty/0 when unknown.
  - `listModels(): List<String>` and `modelDetails(): List<ModelDetails>` (`/api/tags` + `show` per model: size, capabilities; `summary()` is
    the `19 GB · dense · tools · thinking`, `MoE` for a mixture of experts, from `model_info`, else from the tensor list (`OllamaProtocol.parseTensorLayout`) text of the model picker).
  - `loadedContext(model)` (`/api/ps`, `OllamaProtocol.parseLoadedContext`): the `context_length` Ollama loaded the model with; 0 when it is not loaded or this Ollama is too old to say.
  - `chatRequest` sends the sampling options of `Sampling.options(config)` (none in `model` mode; in `custom` mode the temperature and every option that has a value: `top_p`, `top_k`, `min_p`, `presence_penalty`, `repeat_penalty`), `num_ctx` when `AppConfig.contextTokens > 0`, and `repeat_penalty` for the retry after a loop (wins over the setting).
  - `Sampling` (`Sampling.kt`): the one definition of the six options (range, step, help), `get`/`set`/`parse`/`step`, `options`, `effective` (what the model really works with: set values, else its Modelfile's), `toCustom`/`toModel`. `AppConfig.forModel(other)` resets `samplingMode` to `model`: custom values are for the chat model only.
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
`chatMessages` windows history to `config.chatHistory`; a user message that was already sent (`ChatMessage.sent`, set by `ChatSession` the first time: `userContent` = its text with the attached notes, the vault excerpts in front and any change note at the end) goes again exactly as it went; one without it is built with `userContent` (the excerpts only for the latest, when `hits != null`). `system` replaces the built system prompt (`systemPrompt(…)`, `dateLine`); `vaultAnswers` adds the excerpt instruction. `SystemFreeze` (`SystemFreeze.kt`) keeps a chat's system prompt: built on the first `take` or after `reset` (new or opened chat, compaction) and rebuilt only when its key changes (base prompt, model, tools on/off, request-access, effort note, vault answers); a change of `SystemParts` (date, tool notes, memory, goal) becomes `changesNote` (lines added and removed) frozen into the next user message (`commit = true` only when a new message is frozen). The request (`OllamaProtocol.chatRequest`) sends each message as Ollama reads it (`role`, `content`, `images`, `tool_calls`, `tool_name`; Arcana's own fields never go), and the thoughts of the assistant's tool-call rounds after the last real user message (their last `SENT_THINKING` = 4,000 characters). Formerly: it inlined attached notes, keeps tool output older than the latest user message whole (or, with `oldToolOutput = "shorten"`, cuts it to 1,200 characters with a marker), and builds the system prompt: `systemPrompt` + today's date + (`TOOLS_PROMPT` + tool note | `REQUEST_ACCESS_PROMPT` | `NO_TOOLS_PROMPT`) + the `ARCANA.md` memory.

## `VaultIndex.kt`

`VaultIndex.build(vault)` (companion) scans every note once: word count, frontmatter + inline `#tags`, `[[wikilinks]]` resolved to
paths (unresolved kept separately; a name is tried as a note first, so `[[Qwen3.8 27B vs …]]` with dots is a note, and only a missing name that ends in a dot and 1-5 letters or digits, like `shot.png`, counts as a picture or file and is ignored). **Code blocks and inline code are ignored** for links and tags. Derived: `backlinks`,
`orphans`, `untagged`, `stale(days)`, `inFolder`, `tagCounts`, `related(path)` (shared tags/links), `touchedSince(days)`,
`health(staleDays) -> BrainHealth` (counts + `connectedPercent`/`taggedPercent`).
`DailyNotes.detect(vault, folder, format)` reads `.obsidian/daily-notes.json` (`folder`, `format` in moment.js tokens) else
falls back to the settings; `pathFor(date)`. `Vault.capture(text, daily, now)` appends `- HH:mm text` (creates the note if needed).

## Stores (all JSON/JSONL under the config directory)

- `ConfigStore(path = ConfigStore.defaultPath())`: `~/.config/arcana/config.json` or `$ARCANA_CONFIG`; pretty JSON,
  `ignoreUnknownKeys`, `encodeDefaults`. A corrupt file loads as defaults.
- `ChatStore(dir)`: one `<id>.json` per chat (`StoredChat`), `list()` newest first, `latest()`, `save`, `delete`, `prune(keep)`;
  `ChatStore.titleOf(messages)` = first user line.
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
