# `app` module reference

Path: `app/src/main/kotlin/` (package `com.arcana.brain.app`; sub-packages `design`, `input`, `navigation`, `screens`).
Tests: `app/src/test/kotlin/` (`DesignTest`, `InputTest`, `KeyMappingTest`, `FilePickerTest`, `PasteRepairTest`).

## Top-level state holders (package `com.arcana.brain.app`)

| File | Class / function | Responsibility |
|---|---|---|
| `App.kt` | `main()`, `ArcanaApp` | entry point; creates `AppContext`/`Navigator`; effects for theme, connection, brain refresh; renders the screen inside `Shell` wrapped in `key(app.revision)` |
| `AppContext.kt` | `AppContext` | the app-wide state; see [architecture.md](architecture.md) |
| `BrainState.kt` | `BrainState` | background `VaultIndex` (`index`, `reading`, `error`, `revision`); `changed()` (after vault writes) and `refresh()` |
| `ChatSession.kt` | `ChatSession` | the persistent chat: `messages`, `input`, `attachments`, `send()`, `gen`, the tool loop (`runTools`), `Approval` dialogs (folder, shell, web, access, step limit, text calls, memory), `skillRun`/`skillTick` (the active skill run: started by a skill chip or `use_skill`, fed every tool call and result by `runTools`, asked by the completion gate), `Question` (`ask_user`), `offerTools`, `offerMemory`, `lastContext` (a `ContextSnapshot` of what the chat model was sent with the latest request, set every time that request is composed, reset by a new or cleared chat: `/context`), `compactionState()` (for `chat_stats`), history, `startNew/open/deleteCurrent`, `persist()` |
| `Generation.kt` | `Generation`, `GenState`, `rememberGeneration`, `announcesAction` | one streamed model request and the tool-use loop (text-written calls via `TextToolCalls`, the "keep going" nudge, `onStepLimit`, `useTools`, the loop guard: `loopRepeats` stops a round whose thoughts or answer repeat and retries once, `toolsHalted` takes the tools away); observable `state/text/thinking/error/stats`; `gate` (a skill run with a required step open sends the model back instead of letting it finish); `onLive` reports the reply tokens streaming right now (batches of `LIVE_BATCH` pieces) and takes them back when the request's exact counts arrive or it ends; `roundFirstAt` (when the current round's first piece came) and `liveSpeed(pieces, firstAt, now)` give the running line's `· 18 tok/s` (shown after `LIVE_SPEED_MIN_PIECES` = 8 pieces and `LIVE_SPEED_MIN_MILLIS` = 1 s; prompt reading excluded) |
| `Terminal.kt` | `TerminalSize`, `ResizeSignalGuard` | size from stty polling + SIGWINCH guard; `ARCANA_COLUMNS/ROWS` overrides |
| `SystemOpen.kt` | `SystemOpen` | open files / `obsidian://` links with the OS opener |
| `com/jakewharton/mosaic/compat.kt` | shadow of Mosaic's key mapping | fixes a crash on non-ASCII keys (Mosaic 0.18); copied from CosmoCLI. Do not delete |

`GenState`: `Idle → Running → Done | Failed | Cancelled`. `Generation.start(json, tools, maxSteps, prepare)` runs
`prepare()` on IO, streams, and may loop for tool rounds. `consume()` returns to Idle after the reply is stored.

## `navigation/`

- `Navigator.kt`
  - `Screen` enum (Home, Chat, Library, Compose, Prompts (title "Templates"), Session, Usage, Settings, Guide, Changelog) with `title`, `icon`, `group` and `inSidebar` (Inbox and Session are `false`: no tab and no number key, reached from Brain's Overview, the Library's Inbox view and the command palette)
    (`ScreenGroup.Workspace`="BRAIN", `Account`="SYSTEM"); `ScreenOrder` applies the user's `tabOrder`; `SHORTCUT_KEYS="1234567890"`.
  - `Navigator` holds: `current`, `navigate(screen, force)`, `leaveGuard` (a screen may veto leaving), overlays
    (`helpOpen`, `paletteOpen`, `captureOpen`, `palette`, `captureField`), `toast`/`showToast`, `quitPending`,
    cross-screen requests (`libraryFilter`, `libraryNote`, `libraryTag`, `noteRequest` to open AI notes with a note), `focusMode`, `actionBars`.
  - Screen-side API (composable effects): `Hints(vararg)` footer hints, `FocusMode(active)` (true while typing: global letter keys
    off), `Commands(list)` extra palette entries. `Command(label, group, keys, enabled, run)`.
- `Shell.kt`: layout (left/right sidebar or `TopBar`), `Sidebar` with badges and an icon rail under 90 columns, the logo
  animation (colour sweep while `app.busy > 0`), `Footer` with toast and the session token badge (`⚡ N tok`, solid `Palette.Blue`, like the model pill), global key map (`Ctrl+C` flow, `Ctrl+B`, number keys,
  `?`), `LocalContentWidth/Height` providers. Dispatch order: overlay → screen `KeyHandler` → global.
- `Overlays.kt`: `paletteCommands` (every screen jump, navigation position switches, session actions, quit, plus the screen's own
  `Commands`), `overlayKey` (keys while an overlay is open, incl. capture + capture attachments), `PaletteOverlay`, `CaptureOverlay`.

## `screens/` (one file per screen)

| File | Screen | Notable internals |
|---|---|---|
| `Home.kt` | Brain | two-column dashboard from `BrainHealth`; Enter jumps (Library filter / Chat / note) |
| `Chat.kt` | Chat | `ChatMode` (Chat, History, PickNote, PickTarget, ConfirmReplace, Save, Leave (only with Keep chats off), ConfirmClear, …, Context and ContextPart: the context inspector, `ContextInspectorView`, `/context`); `embedSegment` (the bottom bar's `◇ embed 0.6 GB`); `messageLines` renders messages incl. tool steps; approval and question panels (buttons inside); the `/` menu (`SlashItem` with its `usage` line, `slashMatches`, `slashState`, `splitSlash`, `unknownCommand`, `toolUsage`); typed commands take text (`runCommand(command, args)`); the Oracul blocks (`opinionLines`) and their split view (`sideBySide`, `columnCount`); `chatStatus` (the worker and the Oracul as one `modelsSegment`, the working model first), the comparison title (`comparisonTitleRuns`, `partColor`) + `contextUsage`; select mode via `OutputState` |
| `LibraryTree.kt` | the Library's tree: `LibraryRow` (`Folder`/`Note` with depth), `libraryRows(notes, expanded)`, `ancestorsOf`, `allFolders` (pure, unit-tested) |
| `Library.kt` | Library | tree or flat list (`AppConfig.libraryTree`, `AppContext.libraryExpanded`), `LibraryMode` (List, Reading, ConfirmDelete, Rename, Edit, ConfirmDiscard); `e` edits the whole file by hand (`Vault.write`); filters from `LibraryFilter`; preview pane; trash; rename; chat about a note; open in Obsidian; `.` shows notes of hidden folders in All (`Vault.hiddenNotes()`, not in the index, so no count/search/Brain sees them; rename/trash refused) |
| `Prompts.kt` | Templates (`PromptsScreen`) | `PromptMode` (List, Fill, NewName, Edit, ConfirmDiscard, ConfirmDelete, PickSkill); the prompt notes of `Prompts/`, `{{blank}}` questions, run = new chat + `ChatSession.autoSend` |
| `Compose.kt` | AI notes (`NoteComposerScreen`) | `Task` enum, `Mode` (Start, PickSource, Input, Follow, Title, AppendPick, ConfirmReplace, ConfirmDiscard, Result); conversation + `drafts` stack for undo; attachments |
| `Chat.kt` thinking pane | `ThinkingPane` (the panel at the `thinkingPanePlacement` side of the conversation; `thinkingPaneLayout` decides place and size, a strip's rows are counted in the conversation's `outputRows`, a side column splits the width in a `Row`), `wrapWords`, `toggleThinkingPane` (`/thinking`); data from `Generation.liveThinking` |
| `Docks.kt` | `PaneId`, `Docks` (placement, `occupant`, `refusal`, `sideOf`): the IDE-like dock rules, one panel per left/right side (tested in `DocksTest`); Chat draws the columns as `DockColumn`s |
| `StatusPane.kt` | `StatusPane` (Model, Sampling and Context sections; three densities by height), the pure parts `statusPaneStep` (`{` wider / `}` narrower), `sliderCells`, `slimBar`, `contextCells`, `compactionView`, `contextColor` (tested in `StatusPaneTest`); edited with `ChatMode.Status`, which routes the keys to `PaneEditor` (rows `PaneRows`: model, its side (`Docks`), sampling mode and options, thinking effort / in chat / pane / its side, window, auto-compact, keep; `nextModel`, `nextChoice`, `windowLabel`, `pickRows`) |
| `ChatSession` repeat memory | `turnCalls`, `repeatable`, `NEVER_REMEMBERED`, `CLEARS_MEMORY`, `FAILURE_LOOK`; checked in `runTools` before approval, filled after a run, cleared in `ask` and by changes, commands, `wait` |
| `Chat.kt` tool results | `toolResultLines`: the first line and a size, the whole task list for `todo`, and an orange `✂` line for a cut (`ToolCuts.marker`) |
| `ChatSession` queue | `queued`, `enqueue`, `dismissQueued` (drops the newest; Backspace on an empty field in `Chat.kt`, after the attachments, with a toast), `takeQueued`; drained in `runTools` (joins the turn) and `finishReply` (next turn); `Chat.send` queues while `GenState.Running`; a `LaunchedEffect` on failure or cancel returns it to the field |
| `ChatSession` artifacts | `artifacts` (a state list of `Artifact`, filled in the tool loop by `Artifacts.of`), cleared by `startNew` / `open`; the Chat status bar pill and `ChatMode.Artifacts` read it |
| `ChatSession` Oracul | `secondOpinion(model?)`, `compare(model?)`, `revise(note)`, `takeOver()`, `maybeConsult()` (called by `finishReply`), `stuckAdvice()` (called at the end of every tool round), `applyRoles` / `plan` (a skill's `worker:` / `reviewer:` / `planner:`, `roleNote`), `clientFor(model)` (the Oracul's server), `AppContext.modelSizes` / `memoryAdvice`, `toolAutoSearch` in the tool-approval rule of `ChatSession` (the memory advice for a chosen model), `consulting` / `consultingSince` (the model being asked, for the status bars and the reply line), `oraculBackground()` (Settings → Helpers → Chat context, passed to every Oracul, judge and planner request), `longNotes()` / `wholeNotes` / `cutAccepted` (the dialog before a too-long attached note), `pendingCut` (`ChatMessage.sourcesCut`), `autoConsult` / `manualConsult` / `cancelOracul()` (Esc and a new message stop an Oracul run), `oraculJudgeAuto` in `maybeConsult`, `oraculToolbox` / `oraculAgent` / `oraculCall` (the Oracul's tool loop; changes, commands and web always ask) and `awaitApproval` (the mutex that queues every dialog); `Settings.kt`: `Editor.Model(label, get, set, allowNone, oracul, multi)` is the model picker (`oracul`: lists the Oracul server's models, `multi`: Space ticks several) with the memory-fit notes. Design: [oracul.md](oracul.md) |
| `ChatSession` search | `vaultHits` (the notes sent with a `/vault` question: `HybridSearch` by `searchBy`, with `VaultContext`), `projectRoots(config)` (the folders of *Search in* that exist and lie inside the allowed ones) and `projectIndexFor` (one meaning index per folder), `notesSearched`, `ensureSemanticModel` / `installEmbeddingModel` (the download when the model uses a meaning search without an embedding model), `noteToolUse` + `turnTools` / `turnReads` (what the answer is built from: the notes read and the tools used, shown by `Chat.kt` `sourceLines` and the `tools:` line), `reindex` (also the folders); see [retrieval.md](retrieval.md) |
| `screens/Splash.kt` | `SplashScreen`: the start screen (model, vault, memory, skills, tools, search, from `core/Startup.kt`); `navigation/Shell.kt` draws it alone (no tabs, sidebar or footer) while `AppContext.splashOpen`; the `ARCANA` block wordmark (`Wordmark`, shaded with `Palette.logoShade`, the plain title when it does not fit) (true on start when `showSplash` is on and the first-run setup is not open); it closes 1.5 s after the checks, at most after 6 s, or on any key (swallowed) |
| `Setup.kt`, `Cli.kt` (package root) | `SetupScreen`: the first-run wizard (choose the vault with `DirectoryPicker`, tick what `Bootstrap` creates, report); `App.kt` shows it instead of the screens while `AppContext.setupOpen` (true on the first start: no vault and `setupDone` false; the palette command "Set up the vault" sets it). `Cli.run(args)`: `arcana --init <vault> [--skip memory,skills,output]` and `--help` without a screen |
| `Usage.kt` | Usage | dashboard: `Period` × `Page` (Overview, Breakdown, Team = the Orchestration tab, Tools, Recent; the pages are a grid inside a `ScrollColumn`, Recent and Tools scroll their rows; Tools = `ToolsPanel` over `AppContext.toolUsage`, recorded by `ChatSession.runTools`); tiles, `ColumnChart`, insights, feature/model bars, `AdvisorsPanel` (Oracul / Analyst / Planner / Controller / Judge), hour strip, speed sparkline, recent table; `axisLabels`; `StatusHeader`; clear history |
| `UsageTeam.kt` | the Usage → Orchestration page: `teamLines` (what the orchestration cost and did, as wrapped lines for a `ScrollView`), `TeamPage`, `shareBar` |
| `Changelog.kt` | Changelog | `changePages()`: hand-written pages (Latest, Tools, Chat, Brain, Interface, Start); same scroll/select view as Guide |
| `Settings.kt` | Settings | declarative `settings` list (`Setting`, `choice`, `toggle`, `stepper`, `text`, `Editor.{Text,Model,Folder,Folders,Theme}`; `Setting.advanced()` hides a row until *Show advanced search settings* is on), tabs by `Group` (the **Search** tab has three essential rows: *Search in*, *Search by*, *Embedding model (for meaning)*); `Editor.Folders` is the folder-list editor (`placeRows`, `toggledPlaces`: switch `vault`/`working`, add with the folder browser, remove) used by *Search in* and *Allowed folders*; the model picker shows `ModelDetails` |
| `Guide.kt` | Guide | 12 pages (Markdown strings; incl. "Skills" and "Templates") |
| `Common.kt` | helpers | `bodyRows`, `outputRows(fixed, notice, actions)`, `panelWidth`, `outputWidth`, `WithVault`, `GenerationView`, `ReplyStatus`, `relativeTime`, `saveFailure` |
| `AttachBrowser.kt` | shared | the full-screen file browser used by Chat, AI notes and Capture |

Each screen composable: publishes `Hints`/`Commands`/`FocusMode`, installs one `KeyHandler { key -> Boolean }`, and draws.
`KeyHandler` returns `true` when it used the key (stops propagation to the global handler).

## The design system is Ink (`com.idea:ink`)

Arcana does not own its colours, components or text editing any more: they come from the **Ink** library (the sibling project `../Ink`, shared with CosmoCLI; see [proposal 0005](../proposals/0005-adopt-ink.md)). Reuse before adding; a fix to a generic piece is made **in Ink**, then `make publish-local` there and rebuild here (`mavenLocal()` is declared in `settings.gradle.kts`, the version is `ink` in `gradle/libs.versions.toml`).

| Ink package | Provides (what Arcana's old `design/` and `input/` held) |
|---|---|
| `com.idea.ink.theme` | `ThemeSpec`, `Themes.all/byId` (19 themes), `InkColors` (every role and its derivation) |
| `com.idea.ink.colors`, `.text`, `.chart`, `.scroll`, `.layout`, `.terminal` | colour maths and `ColorSupport`, `sanitizeForTerminal`, `wrapRows`, `Chart` (bars, sparklines, `heatCalendar`), `scrollThumb`, `planDocks`, terminal size |
| `com.idea.ink.input` | `Key`, `KeyDispatcher` (paste repair), `TextEdit`, `MetaSequence`, `KeyboardLayout`, `Clipboard` |
| `com.idea.ink.ui` | `Palette`, `Label`, `Stack`, `Panel`, `ScrollColumn`, `ScrollView` + `OutputState` (select mode), `Line`, `renderMarkdown` / `parseMarkdown`, `StatusBar` and its segments, `Picker`, `FilePicker`, `DirectoryPicker`, `TextInput`, `TextFieldState`, `KeyHandler`, `ActionBar`, `ScreenMemory`, charts, notices |

What stays in `app/.../design/` is only what knows Arcana:

| File | Provides |
|---|---|
| `Attachments.kt` | `Attachments` state (pictures, files, notes, plain), `AttachmentLine`, `attachmentLine(…)` (the slot for `TextInput(above = …)`), `SentAttachments`, `attachBrowserHints` |
| `AttachmentKeys.kt` | `handleAttachmentKey` |
| `ModelIcon.kt` | `ModelIcon` (`of`, `label`, `user`): the glyph before a model name by family |
| `SlashKind.kt` | `SlashKind` (+ `SlashCategory`): what a "/" menu entry is, with its `Palette` colour and badge |
| `ReplyFooter.kt` | `replyFooter(stats, plain, step)` |
| `Shortcuts.kt` | `Shortcuts.label(text)` |
| `ScreenMemory.kt` | `rememberKept(app, key) { … }` over `AppContext.screenMemory` |
| `Charts.kt` | `Chart.heatLevels(days: List<DayPoint>, weeks)` over Ink's `heatCalendar` |

`input/` is gone: Mosaic key events are mapped by Ink (`KeyEvent.toKey()`), and `com/jakewharton/mosaic/compat.kt` stays in the app (Mosaic calls it by class name).

Rules: never bind `z`/`Z` as a control key (CosmoCLI rule). Printable keys must reach text fields as typed (including Cyrillic).
