# `app` module reference

Path: `app/src/main/kotlin/` (package `com.arcana.brain.app`; sub-packages `design`, `input`, `navigation`, `screens`).
Tests: `app/src/test/kotlin/` (`DesignTest`, `InputTest`, `KeyMappingTest`, `FilePickerTest`, `AppSessionTest`, `PasteRepairTest`).

## Top-level state holders (package `com.arcana.brain.app`)

| File | Class / function | Responsibility |
|---|---|---|
| `App.kt` | `main()`, `ArcanaApp` | entry point; creates `AppContext`/`Navigator`; effects for theme, connection, brain refresh; renders the screen inside `Shell` wrapped in `key(app.revision)` |
| `AppContext.kt` | `AppContext` | the app-wide state; see [architecture.md](architecture.md) |
| `AppSession.kt` | `AppSession` | the run's logged session: `events`, `log(kind, text, path?)` (persisted as it happens), `markSaved(path)`, `needsSaving`/`unsavedCount` (activity since the last save), `finish()`. Writing the summary note/log into the vault is done by `screens/Session.kt` |
| `BrainState.kt` | `BrainState` | background `VaultIndex` (`index`, `reading`, `error`, `revision`); `changed()` (after vault writes) and `refresh()` |
| `ChatSession.kt` | `ChatSession` | the persistent chat: `messages`, `input`, `attachments`, `send()`, `gen`, the tool loop (`runTools`), `Approval` dialogs (folder, shell, web, access, step limit, text calls, memory), `skillRun`/`skillTick` (the active skill run: started by a skill chip or `use_skill`, fed every tool call and result by `runTools`, asked by the completion gate), `Question` (`ask_user`), `offerTools`, `offerMemory`, history, `startNew/open/deleteCurrent`, `persist()` |
| `Generation.kt` | `Generation`, `GenState`, `rememberGeneration`, `announcesAction` | one streamed model request and the tool-use loop (text-written calls via `TextToolCalls`, the "keep going" nudge, `onStepLimit`, `useTools`, the loop guard: `loopRepeats` stops a round whose thoughts or answer repeat and retries once, `toolsHalted` takes the tools away); observable `state/text/thinking/error/stats`; `gate` (a skill run with a required step open sends the model back instead of letting it finish); `onLive` reports the reply tokens streaming right now (batches of `LIVE_BATCH` pieces) and takes them back when the request's exact counts arrive or it ends |
| `Terminal.kt` | `TerminalSize`, `ResizeSignalGuard` | size from stty polling + SIGWINCH guard; `ARCANA_COLUMNS/ROWS` overrides |
| `SystemOpen.kt` | `SystemOpen` | open files / `obsidian://` links with the OS opener |
| `com/jakewharton/mosaic/compat.kt` | shadow of Mosaic's key mapping | fixes a crash on non-ASCII keys (Mosaic 0.18); copied from CosmoCLI. Do not delete |

`GenState`: `Idle → Running → Done | Failed | Cancelled`. `Generation.start(json, tools, maxSteps, prepare)` runs
`prepare()` on IO, streams, and may loop for tool rounds. `consume()` returns to Idle after the reply is stored.

## `navigation/`

- `Navigator.kt`
  - `Screen` enum (Home, Chat, Library, Inbox, Compose, Session, Usage, Settings, Guide, Changelog) with `title`, `icon`, `group`
    (`ScreenGroup.Workspace`="BRAIN", `Account`="SYSTEM"); `ScreenOrder` applies the user's `tabOrder`; `SHORTCUT_KEYS="1234567890"`.
  - `Navigator` holds: `current`, `navigate(screen, force)`, `leaveGuard` (a screen may veto leaving), overlays
    (`helpOpen`, `paletteOpen`, `captureOpen`, `palette`, `captureField`), `toast`/`showToast`, `quitPending`,
    cross-screen requests (`libraryFilter`, `libraryNote`, `libraryTag`, `noteRequest` to open AI notes with a note,
    `sessionIntent`), `focusMode`, `actionBars`.
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
| `Chat.kt` | Chat | `ChatMode` (Chat, History, PickNote, PickTarget, ConfirmReplace, Save, Leave (only with Keep chats off), ConfirmClear); `messageLines` renders messages incl. tool steps; approval and question panels (buttons inside); the `/` menu (`SlashItem` with its `usage` line, `slashMatches`, `slashState`, `splitSlash`, `unknownCommand`, `toolUsage`); typed commands take text (`runCommand(command, args)`); the Oracul blocks (`opinionLines`) and their split view (`sideBySide`, `columnCount`); `chatStatus` (the worker and the Oracul as one `modelsSegment`, the working model first), the comparison title (`comparisonTitleRuns`, `partColor`) + `contextUsage`; select mode via `OutputState` |
| `LibraryTree.kt` | the Library's tree: `LibraryRow` (`Folder`/`Note` with depth), `libraryRows(notes, expanded)`, `ancestorsOf`, `allFolders` (pure, unit-tested) |
| `Library.kt` | Library | tree or flat list (`AppConfig.libraryTree`, `AppContext.libraryExpanded`), `LibraryMode` (List, Reading, ConfirmDelete, Rename, Edit, ConfirmDiscard); `e` edits the whole file by hand (`Vault.write`); filters from `LibraryFilter`; preview pane; trash; rename; chat about a note; open in Obsidian; `.` shows notes of hidden folders in All (`Vault.hiddenNotes()`, not in the index, so no count/search/Brain sees them; rename/trash refused) |
| `Inbox.kt` | Inbox | `InboxMode`; `Analysis` (Ready/Failed); uses `InboxProcessing`; editable plan fields |
| `Compose.kt` | AI notes (`NoteComposerScreen`) | `Task` enum, `Mode` (Start, PickSource, Input, Follow, Title, AppendPick, ConfirmReplace, ConfirmDiscard, Result); conversation + `drafts` stack for undo; attachments |
| `Session.kt` | Session | `SessionMode` (Overview, Summary, ConfirmNew); exit flow |
| `Chat.kt` thinking pane | `ThinkingPane` (the panel at the `thinkingPanePlacement` side of the conversation; `thinkingPaneLayout` decides place and size, a strip's rows are counted in the conversation's `outputRows`, a side column splits the width in a `Row`), `wrapWords`, `toggleThinkingPane` (`/thinking`); data from `Generation.liveThinking` |
| `ChatSession` repeat memory | `turnCalls`, `repeatable`, `NEVER_REMEMBERED`, `CLEARS_MEMORY`, `FAILURE_LOOK`; checked in `runTools` before approval, filled after a run, cleared in `ask` and by changes, commands, `wait` |
| `Chat.kt` tool results | `toolResultLines`: the first line and a size, the whole task list for `todo`, and an orange `✂` line for a cut (`ToolCuts.marker`) |
| `ChatSession` queue | `queued`, `enqueue`, `dismissQueued` (drops the newest; Backspace on an empty field in `Chat.kt`, after the attachments, with a toast), `takeQueued`; drained in `runTools` (joins the turn) and `finishReply` (next turn); `Chat.send` queues while `GenState.Running`; a `LaunchedEffect` on failure or cancel returns it to the field |
| `ChatSession` artifacts | `artifacts` (a state list of `Artifact`, filled in the tool loop by `Artifacts.of`), cleared by `startNew` / `open`; the Chat status bar pill and `ChatMode.Artifacts` read it |
| `ChatSession` Oracul | `secondOpinion(model?)`, `compare(model?)`, `revise(note)`, `takeOver()`, `maybeConsult()` (called by `finishReply`), `stuckAdvice()` (called at the end of every tool round), `applyRoles` / `plan` (a skill's `worker:` / `reviewer:` / `planner:`, `roleNote`), `clientFor(model)` (the Oracul's server), `AppContext.modelSizes` / `memoryAdvice`, `toolAutoSearch` in the tool-approval rule of `ChatSession` (the memory advice for a chosen model), `consulting` / `consultingSince` (the model being asked, for the status bars and the reply line), `oraculBackground()` (Settings → Helpers → Chat context, passed to every Oracul, judge and planner request), `longNotes()` / `wholeNotes` / `cutAccepted` (the dialog before a too-long attached note), `pendingCut` (`ChatMessage.sourcesCut`), `autoConsult` / `manualConsult` / `cancelOracul()` (Esc and a new message stop an Oracul run), `oraculJudgeAuto` in `maybeConsult`, `oraculToolbox` / `oraculAgent` / `oraculCall` (the Oracul's tool loop; changes, commands and web always ask) and `awaitApproval` (the mutex that queues every dialog); `Settings.kt`: `Editor.Model(label, get, set, allowNone, oracul, multi)` is the model picker (`oracul`: lists the Oracul server's models, `multi`: Space ticks several) with the memory-fit notes. Design: [oracul.md](oracul.md) |
| `Setup.kt`, `Cli.kt` (package root) | `SetupScreen`: the first-run wizard (choose the vault with `DirectoryPicker`, tick what `Bootstrap` creates, report); `App.kt` shows it instead of the screens while `AppContext.setupOpen` (true on the first start: no vault and `setupDone` false; the palette command "Set up the vault" sets it). `Cli.run(args)`: `arcana --init <vault> [--skip memory,skills,output]` and `--help` without a screen |
| `Usage.kt` | Usage | dashboard: `Period` × `Page` (Overview, Breakdown, Team = the Orchestration tab, Tools, Recent; the pages are a grid inside a `ScrollColumn`, Recent and Tools scroll their rows; Tools = `ToolsPanel` over `AppContext.toolUsage`, recorded by `ChatSession.runTools`); tiles, `ColumnChart`, insights, feature/model bars, `AdvisorsPanel` (Oracul / Analyst / Planner / Controller / Judge), hour strip, speed sparkline, recent table; `axisLabels`; `StatusHeader`; clear history |
| `UsageTeam.kt` | the Usage → Orchestration page: `teamLines` (what the orchestration cost and did, as wrapped lines for a `ScrollView`), `TeamPage`, `shareBar` |
| `Changelog.kt` | Changelog | `changePages()`: hand-written pages (Latest, Tools, Chat, Brain, Interface, Start); same scroll/select view as Guide |
| `Settings.kt` | Settings | declarative `settings` list (`Setting`, `choice`, `toggle`, `stepper`, `text`, `Editor.{Text,Model,Folder,Theme}`), tabs by `Group`; the model picker shows `ModelDetails` |
| `Guide.kt` | Guide | 13 pages (Markdown strings; incl. "Skills") |
| `Common.kt` | helpers | `bodyRows`, `outputRows(fixed, notice, actions)`, `panelWidth`, `outputWidth`, `WithVault`, `GenerationView`, `ReplyStatus`, `relativeTime`, `saveFailure` |
| `AttachBrowser.kt` | shared | the full-screen file browser used by Chat, AI notes and Capture |

Each screen composable: publishes `Hints`/`Commands`/`FocusMode`, installs one `KeyHandler { key -> Boolean }`, and draws.
`KeyHandler` returns `true` when it used the key (stops propagation to the global handler).

## `design/` (the design system; reuse before adding)

| File | Provides |
|---|---|
| `Theme.kt` | `ThemeSpec`, `Themes.all/byId`, `Colors`, `Palette` (live theme accessors: `Palette.Text/Accent/Cta/Green/Red/Console*/UserBg…`), `Dimens` |
| `ScrollColumn.kt` | `ScrollColumn` + `ScrollState`: a window of whole children with a scrollbar column and a `follow` cursor; ↑↓ PgUp/PgDn Home/End |
| `Layout.kt` | `Stack` (column that shortens or hides what does not fit; `Modifier.flexible()` keeps room for later siblings), `VGap`, `HGap`, `LocalContentWidth/Height/UiScale`, `rememberDensity()` (`inline` mode at small sizes) |
| `Rainbow.kt` | `RainbowText(text, animate, color)`: the logo's flowing rainbow, shared by the sidebar logo and the reasoning-effort control inside the message field |
| `Label.kt` | `Label` (single-style, clipped to its width; use instead of `Text`), `HBar`, `TailText`, `WrappedText` (wraps, scrolls with the caret, draws a scrollbar in its last column), `wrap`, `wrapRows` |
| `Panel.kt` | `Panel(title, accent)`, `Card`, `ScreenHeader(title, subtitle)`, frame glyphs |
| `Fields.kt` | `TextInput(label, field, focused, placeholder, maxRows, attachments)`, `LabeledField`, `InlineField`, `handleAttachmentKey` |
| `Actions.kt` | `ActionItem`, `ActionStyle` (`Quiet` default, `Button`), `ActionBar(items, width, style)`, `actionBarRows`, `breakIntoLines(items, width, style)`, `Badge` |
| `ModelIcon.kt` | `ModelIcon` (`of`, `label`, `user`): the glyph before a model name by family (`◆` gemma, `◈` qwen, `▲` llama…; default `◆`, ASCII `*` in plain mode); the name itself is always Ollama's own. Used by the Chat labels and status bar; add a family to `FAMILIES` |
| `SlashKind.kt` | `SlashKind` (+ `SlashCategory`: the COMMAND / SKILL / TOOL group and badge of every row): what a "/" menu entry is (Command, Skill, Read, Write, Shell, Danger, Web, Ask) with its `Palette` colour, glyph and badge word; `forTool(name, risk)` classifies a tool |
| `StatusBar.kt` | **reusable status line**: `StatusSegment`/`StatusPart`, `statusSegment`, `pillSegment`, `gaugeSegment`, `wrapSegments` / `statusRowCount` (wrap onto more rows, never drop), `StatusBar`, `StatusHeader`, `spanText`, `priceText` |
| `Shortcuts.kt` | `Shortcuts.label(text)`: the one place shortcut labels pass through (Ctrl chords) |
| `Messages.kt` | `Notice`, `NoticeLine`, `Hint`, `Warning`, `Cta`, `Loading`, `Spinner` |
| `Picker.kt` | `PickerState` + `Picker<T>`: `/` filters (any order of words), ↑↓, Enter; optional `icon`/`iconColor`/`meta`, and for trees `indent` (two spaces per level), `labelColor` and `total` (the count shown in the search strip) |
| `ScreenMemory.kt` | `ScreenMemory` (in `AppContext.screenMemory`) and `rememberKept(app, key) { … }`: a screen's state holders that outlive its tab (the shell draws only the current screen, so plain `remember` state is lost on every tab switch). Used by the Library (view, tag, mode, `PickerState`s, preview scroll, show-hidden) and the Brain (selected line, hygiene list). Key = name + `@` + vault path |
| `FilePicker.kt` | `FilePickerState`/`FilePicker`: folder browser returning files (tick with Space, Tab to attach) |
| `DirectoryPicker.kt` | folder-only picker (vault path) |
| `Attachments.kt` | `Attachments` state (pictures, files, notes, plain), `AttachmentLine`, `SentAttachments`, `attachBrowserHints` |
| `ScrollView.kt` | `Line`, `OutputState` (scroll + select mode + copy), `ScrollView`, `ScrollBar`, `RunsRow` |
| `Markdown.kt` | `parseMarkdown` (blocks), `renderMarkdown` (styled `Line`s), closed code boxes + highlighter, boxed tables, `unmath`, `plainText`, `codeAndTables` |
| `ReplyFooter.kt` | `replyFooter(stats, plain, step)`: `✓ Done · 6.4s · ↑ 293 ↓ 820 tokens · 101 tok/s`, or a quiet `· step` for a round that ended in a tool call |
| `Sanitize.kt` | `sanitizeForTerminal` (wide chars/emoji → one-cell lookalikes) |
| `TabBar.kt` | `TabBar(tabs, current)` used by Settings |

## `input/`

| File | Provides |
|---|---|
| `Key.kt` | `sealed interface Key` (`Up/Down/Enter/Escape/Tab/Backspace…`, `Text(char)`, `PastedNewline` (Alt+Enter and pasted line breaks), plus app keys `Save` Ctrl+S, `Browse` Ctrl+O, `Palette` Ctrl+P, `Capture` Ctrl+N, `Skip` Ctrl+X, `Thinking` Ctrl+T (the thinking pane), `Effort` Ctrl+G (the reasoning-effort picker, same as `/effort`)); helpers `char`, `isUp/isDown` |
| `KeyMapping.kt` | Mosaic `KeyEvent` → `Key` (handles lock bits, Kitty-style modifiers) |
| `KeyDispatcher.kt` | dispatch order overlay → screen → global; **paste repair** (keys <20 ms apart are text; a paste that lost its beginning or its middle is typed back whole from the clipboard); `Meta` sequences |
| `MetaSequence.kt` | ESC-prefixed sequences (Alt+arrows, word jumps) |
| `KeyboardLayout.kt` | Cyrillic letters mapped back to the QWERTY key in the same position so shortcuts work on any layout |
| `TextEdit.kt` / `TextFieldState.kt` | caret, selection, word moves/deletes, line start/end, Ctrl+U; `TextFieldState` + `KeyHandler` composable |
| `Clipboard.kt` | OS clipboard via `pbcopy` (macOS), `wl-copy` / `xclip` / `xsel` (Linux) writers + readers |

Rules: never bind `z`/`Z` as a control key (CosmoCLI rule). Printable keys must reach text fields as typed (including Cyrillic).
