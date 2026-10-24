# Architecture

## Modules

```
Arcana (Gradle multi-project)
├── core/   pure Kotlin/JVM library. No Compose. Unit-tested (core/src/test). Package com.arcana.brain.core
├── app/    Mosaic (Compose-for-terminal) application. Depends on :core. Package com.arcana.brain.app
├── utils/  unused template module (kept; do not build features here)
└── buildSrc/  convention plugin `buildsrc.convention.kotlin-jvm` (JVM toolchain 21, JUnit platform, test logging)
```

`app` main class: `com.arcana.brain.app.AppKt` (file `app/src/main/kotlin/App.kt`), application name `arcana`.
`core` exposes everything with plain classes; `app` never reimplements vault, Ollama or tool logic.

## Layer diagram

```
 terminal (stdin/stdout, pty)
        │ raw bytes
        ▼
 input/        KeyMapping → Key  →  KeyDispatcher (paste repair, overlay → screen → global)
        │
        ▼
 navigation/   Shell (layout, sidebar/top bar, footer, global keys)  Navigator (screens, overlays, guards, toasts)
        │                                │
        ▼                                ▼
 screens/      Home Chat Library Inbox Compose Session Usage Settings Guide     overlays: help, palette, capture
        │  use                           │ publish Hints / Commands / FocusMode to Navigator
        ▼
 design/       Theme(Palette) Panel Label Stack Picker FilePicker ScrollView Markdown Attachments TextInput …
        │
 state holders (app/): AppContext ─ ChatSession ─ Generation ─ AppSession ─ BrainState
        │
        ▼
 core/         OllamaClient  Vault  VaultIndex  ChatStore  SessionStore  UsageStore  ConfigStore  Inbox  ToolBox
        │
        ▼
 Ollama HTTP (localhost:11434)      vault folder (Markdown files)      ~/.config/arcana/*
```

## Central state: `AppContext` (`app/.../AppContext.kt`)

One instance, created in `ArcanaApp` with `remember { AppContext() }`. It owns:

| Member | Purpose |
|---|---|
| `config` (+ `update(newConfig)`) | current `AppConfig`; `update` saves to disk, bumps `revision` when needed |
| `client: OllamaClient` | reads `config` lazily (URL/model/temperature/timeout changes apply at once) |
| `connection: Connection` | `Checking / Online(models) / ModelMissing(models) / Offline(error)`; refreshed by `refreshConnection()` |
| `vision`, `tools: Boolean?` | model capabilities from `/api/show` (`null` = unknown) |
| `usage`, `recordUsage(feature, chunk)` | token accounting (`UsageStore`, `usage.jsonl`) |
| `session: AppSession` | the run's activity log and "needs saving" logic |
| `brain: BrainState` | background-built `VaultIndex` + `BrainHealth`; `refresh()` / `changed()` |
| `chat: ChatSession` | the one persistent chat (survives screen switches) |
| `captureAttachments` | attachments of the capture overlay (files copied into the vault) |
| `scope` | the Compose coroutine scope (set by `ArcanaApp`) used for app-level jobs |
| `busy`, `revision` | `busy` counts running generations (drives logo animation + sidebar badge); `revision` forces screens to rebuild (`key(app.revision)`) |
| `openVault()` | `Result<Vault>` from `config.vaultPath` (`~` expanded) |
| `attachments(forModel)` | factory for an `Attachments` set wired to model capabilities and last picker dir |
| `capture(text, files)` | writes to today's daily note via `Vault.capture`, copies attached files |
| `newSession()` | ends the session, starts fresh (empty chat, zero usage) without restarting |

## Data flow of a chat question (the main path)

1. `Chat.kt` `send()` → `ChatSession.send(text)`.
2. `attachments.drain()` → user `ChatMessage` (images, text files, `noteNames`) appended to `messages` and persisted.
3. `Generation.start(tools, maxSteps) { prepare }` launches a coroutine on `app.scope`:
   - `prepare()` (IO dispatcher): optional vault search (`/vault` mode), reads attached notes, builds
     `Prompts.chatMessages(config, history, hits, noteTexts, tools)`.
   - `OllamaClient.chat(messages, json, tools)` streams NDJSON `ChatChunk`s; `text` state updates per chunk.
   - If chunks carry `tool_calls` and tools were offered: `ChatSession.runTools(...)` executes them (asking the user for
     writes), appends results, and the loop asks the model again (see [tools-and-agent.md](tools-and-agent.md)).
   - On the final text: `onComplete` → `ChatSession.finishReply` appends the assistant message (with `ReplyStats` and `sources`).
4. UI recomposes from `gen.text`, `gen.state`, `chat.messages`, `chat.approval`.
5. Usage is recorded per finished round (`onUsage`), the session log gets `Asked` / `Tool` events.

The generation lives in `ChatSession`, **not** in the Chat screen, so leaving the screen does not cancel it.
Other screens (`Compose`, `Inbox`, `Session`) use `rememberGeneration(app, feature)` which cancels on leaving.

## Concurrency model

- UI and Compose state run on the Mosaic/Compose dispatcher. `app.scope` is a `rememberCoroutineScope()`.
- Anything blocking (HTTP, file IO, vault scan, PDF parse, tool execution) runs in `withContext(Dispatchers.IO)`.
- `OllamaClient.chat` returns a cold `Flow` (`flowOn(IO)`); cancelling the collector cancels the HTTP future.
- Snapshot state (`mutableStateOf`, `mutableStateListOf`) is mutated only from the scope's dispatcher; do not mutate it
  inside `withContext(IO)` blocks.
- `BrainState.refresh()` rebuilds the index on IO and publishes the result; triggered by `LaunchedEffect(vaultPath, brain.revision)`.

## Startup and shutdown (`App.kt`, `Terminal.kt`)

Startup: `main()` clears the screen, installs `ResizeSignalGuard`, starts `TerminalSize` polling (stty), runs Mosaic in
`runBlocking`, composes `ArcanaApp`. `ArcanaApp` wires theme (`Palette.use(...)`), connection check, brain refresh, and renders
`key(app.revision) { <current screen> }` inside `Shell`.

Quit: Ctrl+C is **always handled by Arcana** (never left to Mosaic): first press shows a confirmation (or the "Before you go"
session-save flow when `AppSession.needsSaving`), the second quits. Quit = `session.finish()` → complete a `CompletableDeferred`
→ cancel the Mosaic job → `Runtime.halt(0)` (background loops must not keep the JVM alive).

## Key design decisions (and why)

| Decision | Why |
|---|---|
| Chat state in `ChatSession` at app level | replies must keep streaming while the user browses other screens |
| Overlays drawn over the screen, not navigation | the screen keeps its state (scroll, input, mode) |
| Screens publish `Hints`, `Commands`, `FocusMode` | the shell shows footer hints, the palette lists screen actions, and global keys stay off while typing |
| Clip-safe primitives (`Label`, `Stack`, `Panel`) | Mosaic cannot clip; text that is too long would wreck borders |
| `sanitizeForTerminal` on all model text | emoji/CJK are 2 terminal cells but 1 Mosaic cell → broken frames |
| Exact row accounting (`outputRows`) | output areas must fill the height exactly, no jumps or lost rows |
| Vault index rebuilt in background, not on each frame | scanning hundreds of notes on UI thread would freeze typing |
| Tools write only inside allowed folders + approval | an LLM with file access must be sandboxed and supervised |
| Mirror CosmoCLI (sibling project) for input/scroll/select/settings | its bugs were already fixed there ([conventions.md](conventions.md)) |
