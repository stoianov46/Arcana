# Functionality (what the user sees and can do)

The product loop: **capture → see → think → shape → keep**.
Screens are numbered; the number key opens the screen from anywhere when no text field has focus
(`Screen` enum order, user-reorderable in Settings → Tabs). Shortcuts are **Ctrl** chords (⌘ chords were tried and dropped: terminals and
macOS claim them).

| Key | Screen | Role |
|---|---|---|
| 1 | **Brain** (`Home`) | dashboard: vault stats, what needs attention, recent notes, top tags |
| 2 | **Chat** | hub for talking to the model; attachments, vault answers, tools, questions |
| 3 | **Library** | browse every note, rename, chat about a note |
| 4 | **Inbox** | AI-assisted filing of unprocessed notes |
| 5 | **AI notes** (`Compose`) | write or transform notes with AI, refine, apply |
| 6 | **Session** | what happened this run; save a summary note or log; new session |
| 7 | **Usage** | dashboard of tokens, speed, estimated price, habits |
| 8 | **Settings** | tabbed settings |
| 9 | **Guide** | in-app tutorial pages (12) |
| 0 | **Changelog** | what has been built, by theme |

Global keys: `Ctrl+P` command palette · `Ctrl+N` quick capture · `?` key map for the screen · `Ctrl+B` fold the sidebar ·
`Ctrl+C` ×2 quit · `0`-`9` screens (when not typing). Navigation can be a left sidebar, right sidebar or top bar (Settings → Appearance →
Navigation). The sidebar shows badges (inbox count, unsaved session, `…` on Chat for a running reply, `!` when the model waits for an
answer) and folds to icons under 90 columns.

## Brain (Home)

Two columns when ≥100 wide. Left: *Capture* hint, *Needs attention* (inbox waiting, notes with no links, no tags, broken
links), *Top tags*. Right: *Jump back in* (Ask your brain, AI notes, this session), *Recent notes*. Data comes from
`BrainState.index` / `VaultIndex.health(inbox, staleDays)`. Enter opens the highlighted item (Library filter, Chat, a note).

## Chat (hub)

- The message field **always has the keyboard** (letters never trigger shortcuts). `Esc` leaves the field into "browse" mode (scroll,
  `s` select & copy, `Enter` back to typing); while a reply runs `Esc` cancels it instead.
- **Multi-line field**: grows to a quarter of the screen (3–8 rows) with a scrollbar (`WrappedText`); `Alt+Enter`, or `\` then Enter,
  starts a new line; Up/Down move between lines. `Enter` sends. Long pastes are repaired from the clipboard (`KeyDispatcher`).
- **The "/" menu**: a message that starts with `/` (no space yet) opens `slashMatches(SlashCommands + tool specs, typed)` above the
  field (≤8 rows, counted in `below`): ↑↓ choose, Tab/Enter use, Esc clears. A command runs through `runCommand` (`/new /history /save
  /note /image /vault /tools /mode /memory /append /replace /clear`); settings show an ON/OFF/mode badge (`slashState`); a tool inserts
  "Use the <tool> tool to " (tools are called by the model, not typed).
- `@` attaches a vault **note**; `Ctrl+O` attaches **files** (pictures ≤5/10 MB for a vision model, **PDFs** ≤20 MB, text files ≤5/2 MB).
  Backspace on an empty field removes the last attachment.
- `/vault` toggles **answer from vault** (each question searches the vault; the reply lists `Sources` and cites `[[Note]]`).
  `/tools` toggles tools, `/mode` cycles ask → auto → plan, `/memory` opens `ARCANA.md`.
- Replies stream; the footer shows `✓ Done · time · ↑ prompt ↓ output tokens · tok/s` (a round that ended in a tool call shows a quiet
  `· step` line). The "thinking" status names the model. A reply keeps running when you leave the screen: with an empty field the
  number keys (and `Ctrl+P`) switch screens.
- **Status bar under the field** (`StatusBar(chatStatus(…))`): model pill, memory size (`✎ memory 2.3k`), context gauge (`ctx █░░ 3%
  3.9k/131k`: last prompt + reply against `contextTokens` or the model's `context_length`, never more than the model supports), tokens in
  and out, estimated price (`est.`, from Settings → Ollama → Price per million tokens), session time, tool-mode pill. Least important
  items drop first on a narrow terminal.
- Chats are **kept automatically** (`ChatStore`, one JSON per chat; the latest reopens at start; `/history`). Leaving never asks (only
  with *Keep chats* off does Arcana ask). The header says `kept in history · not in vault (Ctrl+S)`; **Ctrl+S "Save to vault"** turns
  the chat (or the last answer) into a note. `/append` and `/replace` use the last answer.
- Output is rendered Markdown (see [ui-internals.md](ui-internals.md)): boxed tables, closed code boxes with line numbers and
  highlighting, coloured figures. Select mode: ↑↓ move, Space ticks, `c` copies (Markdown or plain per Settings), `b` code/tables only.
- Dialogs inside Chat (buttons **inside** the dialog): approvals, folder grants, questions from the model; see
  [tools-and-agent.md](tools-and-agent.md).

### Tools in Chat (opt-in)

With *Settings → Tools → Tools in Chat* on (or when a dialog offers it), the model may call the tools listed in
[tools-and-agent.md](tools-and-agent.md): read-only ones run at once; changes, deletes, moves, commands and web fetches always ask.
Modes: ask / auto / plan. Shell commands and web pages are separate switches, off by default.

## Memory file (ARCANA.md)

The vault note `ARCANA.md` (first 4,000 characters, no frontmatter) is added to every chat's system prompt (`Vault.memory()` →
`Prompts.chatMessages(memory = …)`; Settings → Prompts → *Memory file*; the status bar shows `✎ memory`). `ChatSession.offerMemory()` runs
when Chat opens: if the vault has no `ARCANA.md` and `memoryOffered` is false it creates one from `Vault.MEMORY_TEMPLATE` (eleven rules
for using tools, plus empty sections) and shows "Memory file created" with an offer to open it; the flag then stays true, so a file the user
deletes comes back only through `/memory`. The `remember` tool appends to its "Remembered" section (always asks). `ToolBox.cheatSheet()`
adds the real tool list to the same prompt.

## Library

Views (←/→): All, Recent, Inbox, No links, No tags, Broken links, Stale, Tags. Left list + right preview (frontmatter, links, backlinks,
related notes). Keys: `Enter` read, `a` AI on this note (opens AI notes with the note preset), `c` **chat about it** (a new chat with the
note attached; a saved chat note carries its whole transcript), `r` **rename** (a field with the current name; the note stays in its
folder and the `[[links]]`/`![[embeds]]` to it in other notes are rewritten like Obsidian does, outside code; `Vault.rename`), `o` open in
Obsidian, `d` trash (moves to `.trash`, asks first), `/` filter, `p` process the inbox (Inbox view); the Tags view browses notes per tag.

## Inbox

Lists notes in `inboxFolder`. "Process with AI" asks the model for a JSON plan per note (`InboxProcessing`): title, tags, a one-line
summary, links to existing notes, destination folder. `Enter` reviews a note, `p` processes all; in the review `Tab` moves between editable
fields, `Enter` applies, `Ctrl+X` skips. The plan is validated against the vault (links must exist, folder must exist or be a sensible new
one), previewed, then applied with `Vault.processInboxNote`, which first backs the original up to `.trash`, merges tags into frontmatter,
adds a summary callout and a `## Related` section, then renames/moves.

## AI notes (Compose)

Tasks: Write a new note · Turn pictures into a note · Summarize · Improve · Continue · Translate · Action items · Connect (suggests tags
and links from what the vault already contains) · Custom. Input accepts attachments (pictures, PDFs, files). The result is a **draft**:
refine (`f`), add more (`m`), undo (`u`), redo (`r`), then save as new (`n`), add to a note (`a`), replace the source note (`w`, with
backup), or copy. Leaving with an unapplied draft asks first (the only leave guard besides *Keep chats* off).

## Session

Every run is a session logged to `~/.config/arcana/sessions/` as it happens (asked, task, created, appended, replaced, renamed, deleted,
saved, captured, processed, attached, tool). Actions: save a **summary note** (model-written from the log and chat transcripts) or the
**plain log** into the vault; **start a new session** without restarting. Quitting with unsaved activity opens the "Before you go" flow
(`s` save summary, `l` save log, `Esc` keep working).

## Capture (`Ctrl+N`)

One line becomes `- HH:mm text` at the end of today's daily note. The daily note path/format comes from Obsidian's
`.obsidian/daily-notes.json` when present, else Settings (`dailyFolder`, `dailyFormat`). `Ctrl+O` attaches any file(s): copied into
`<outputFolder>/attachments/` and embedded as `![[name]]`.

## Usage

Dashboard over `usage.jsonl` (`screens/Usage.kt`; analytics in `core/Usage.kt`: `UsageStats.perHour/perDay/speedPerDay/insights/
estimatedCost`). Periods (←→): session, today, 7 days, 30 days, all time. Pages (`Tab`): **Overview** (totals tiles, column chart by hour or
day, insights: busiest day, peak hour, streak, week on week, typical/slowest reply, context load), **Breakdown** (by feature/model with share,
hour heat strip, speed sparkline), **Recent replies** (table; longest green, slowest orange). Its header is a `StatusHeader` (page, period,
replies, tokens, estimated price, speed, session time). Every panel states its row need and is dropped when the height cannot hold it
(80×24 safe).

## Changelog (key 0)

Six pages (`←→`) written by hand in `screens/Changelog.kt` (`changePages()`): Latest, Tools, Chat, Brain, Interface, Start. When something
ships, add a bullet to the newest page.

## Settings

Tabs (groups) and rows:
- **Ollama**: URL, Model (picker with size and tools/vision/thinking per installed model), Temperature, Context window (model default … 1M),
  Timeout, Price for prompt/reply tokens (only for the estimated price).
- **Vault**: Vault path (folder browser), Output folder, Context notes, Inbox folder, Daily notes folder/format, Stale after, Chars per note.
- **Appearance**: Colour theme, Navigation (left/right/top), Sidebar (full/icons), Logo animation.
- **Console**: Answer from vault, Render Markdown, Show thinking, Chat history, Keep chats, Chats kept, Copy as.
- **Tools**: Tools in Chat, Extra folders, Shell commands, Calls written as text (auto/ask/off), Search backend (DuckDuckGo/SearXNG/Brave),
  Search address, Search API key, Web pages (search and fetch), Mode (ask/auto/plan), Read without asking, Steps per question (1–60).
- **Prompts**: Memory file, System prompt, Summary prompt.
- **Accessibility**: Interface scale, Screen-reader output. **Tabs**: order of the screens.

Values apply immediately and persist to `config.json`. Themes: Dark, Light, Soft Dark, Soft Light, Darcula, Solarized Dark/Light, Nord.

## Guide

`screens/Guide.kt` holds 12 pages (Markdown strings rendered by the same renderer). **When you change behaviour, update the matching Guide
page, the Changelog page and this file.**
