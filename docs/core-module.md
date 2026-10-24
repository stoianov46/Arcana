# `core` module reference

Path: `core/src/main/kotlin/com/arcana/brain/core/`. No UI, no Compose. Dependencies: kotlinx (serialization, coroutines,
datetime) and Apache PDFBox. Tests: `core/src/test/kotlin/com/arcana/brain/core/CoreTest.kt`.

## File map

| File | Contents |
|---|---|
| `AppConfig.kt` | `AppConfig` (all settings, `@Serializable`), `ConfigStore` (load/save JSON) |
| `OllamaClient.kt` | `OllamaClient`, `OllamaProtocol`, `ChatMessage`, `ChatChunk`, `ReplyStats`, `OllamaException` |
| `Vault.kt` | `Vault`, `Note`, `SearchHit`, `NoteTask`, `Prompts` (every prompt template), `VaultException` |
| `VaultIndex.kt` | `VaultIndex`, `NoteInfo`, `BrainHealth`, `DailyNotes`, `Vault.capture` |
| `Inbox.kt` | `InboxProcessing` (prompt + parse), `InboxPlan`, `mergeFrontmatterTags`, `Vault.processInboxNote` |
| `ChatStore.kt` | `ChatStore`, `StoredChat` (persistent chats as JSON files) |
| `Session.kt` | `SessionKind`, `SessionEvent`, `StoredSession`, `SessionStore`, `SessionStats`, `SessionReport` |
| `Usage.kt` | `UsageRecord`, `UsageStore` (JSONL), `UsageTotals`, `UsageStats` |
| `ImageFiles.kt` | picture rules + loading (`ImageAttachment`, base64) |
| `TextFiles.kt` | text/PDF attachment rules + loading (`FileAttachment`, PDFBox extraction, `TextFiles.block`) |
| `Tools.kt` | tool-calling types (`ToolCall`, `ToolSpec`, `ToolRisk`), `FileAccess` sandbox, `ToolBox` (all tools), `TOOLS_PROMPT` |

## `OllamaClient.kt`

- `OllamaClient(config: () -> AppConfig)`: reads config on every call (settings changes apply immediately).
  - `chat(messages, jsonMode=false, tools: JsonArray?=null): Flow<ChatChunk>`: POST `/api/chat` with `stream=true`; parses NDJSON
    lines; throws `OllamaException` for connection errors / HTTP ≠ 200 / `error` field. Cancelling the collector cancels the request.
  - `capabilities(model): List<String>` (`/api/show`: `completion`, `vision`, `tools`, `thinking`, …; empty when unknown).
  - `listModels(): List<String>` (`/api/tags`).
- `OllamaProtocol` (pure, unit-tested): `chatRequest(config, messages, jsonMode, tools)`, `parseChunk(line)`, `parseCapabilities`,
  `parseModels`. Messages are serialized with `ChatMessage.withFilesInlined()` (text files become part of `content`).
  Defaults are not encoded, so app-only fields (`imageNames`, `stats`, `noteNames`, `sources`) never reach Ollama when empty.
- `ChatMessage(role, content, images(base64), imageNames, stats, noteNames, sources, files, toolCalls, toolName)`:
  - roles: `system | user | assistant | tool`.
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
| `replace(note, body)` | replaces body keeping frontmatter; old file copied to `.trash` first; returns backup path |
| `trash(note)` | moves to `<root>/.trash` (Obsidian's trash) with unique name |
| `copyAttachment(folder, source)` | copies into `<folder or root>/attachments/`, unique name, returns the file name for `![[name]]` |
| `search(query, limit, excerptChars)` | keyword search: tokens ≥3 letters minus stop words; score = 10 per term in title + min(10, count) per term in text; excerpt around first hit |

Static helpers: `sanitizeTitle`, `splitFrontmatter(text) -> (frontmatter, body)`, `tokenize`.

`Prompts` (all prompt text lives here): `summarize`, `ask`, `noteTask(config, NoteTask, title, text, input)`,
`compose(config, request)`, `chatMessages(config, history, hits?, noteTexts, tools)`, `sessionSummary(config, activity, chats)`.
`chatMessages` windows history to `config.chatHistory`, inlines attached notes into the user messages, wraps the latest
question with vault excerpts when `hits != null`, and appends `TOOLS_PROMPT` to the system prompt when `tools` is true.

## `VaultIndex.kt`

`VaultIndex.build(vault)` (companion) scans every note once: word count, frontmatter + inline `#tags`, `[[wikilinks]]` resolved to
paths (unresolved kept separately). **Code blocks and inline code are ignored** for links and tags. Derived: `backlinks`,
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
