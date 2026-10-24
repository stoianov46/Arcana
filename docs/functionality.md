# Functionality (what the user sees and can do)

The product loop: **capture → see → think → shape → keep**.
Screens are numbered; the number key opens the screen from anywhere when no text field has focus
(`Screen` enum order, user-reorderable in Settings → Tabs).

| Key | Screen | Role |
|---|---|---|
| 1 | **Brain** (`Home`) | dashboard: vault stats, what needs attention, recent notes, top tags |
| 2 | **Chat** | hub for talking to the model; attachments, vault answers, tools |
| 3 | **Library** | browse every note and the ones that need attention |
| 4 | **Inbox** | AI-assisted filing of unprocessed notes |
| 5 | **AI notes** (`Compose`) | write or transform notes with AI, refine, apply |
| 6 | **Session** | what happened this run; save a summary note or log; new session |
| 7 | **Usage** | token dashboard |
| 8 | **Settings** | tabbed settings |
| 9 | **Guide** | in-app tutorial pages (11) |

Global keys: `Ctrl+P` command palette · `Ctrl+N` quick capture · `?` key map for the screen · `Ctrl+B` fold the sidebar ·
`Ctrl+C` ×2 quit · `1`-`9` screens (when not typing). Navigation can be a left sidebar, right sidebar or top bar
(Settings → Appearance → Navigation). The sidebar shows badges (inbox count, unsaved session, running reply) and folds to
icons under 90 columns.

## Brain (Home)

Two columns when ≥100 wide. Left: *Capture* hint, *Needs attention* (inbox waiting, notes with no links, no tags, broken
links), *Top tags*. Right: *Jump back in* (Ask your brain, AI notes, this session), *Recent notes*. Data comes from
`BrainState.index` / `VaultIndex.health(inbox, staleDays)`. Enter opens the highlighted item (Library filter, Chat, a note).

## Chat (hub)

- The message field **always has the keyboard** (letters never trigger shortcuts). `Esc` leaves the field into "browse"
  mode (scroll, `s` select & copy, `Enter` back to typing).
- `Enter` sends; `/commands`: `/new /history /clear /save /image /note /vault /tools /append /replace`.
- `@` attaches a vault **note** (its text is read when the message is sent).
- `Ctrl+O` attaches **files**: pictures (PNG/JPEG/WebP, ≤5, ≤10 MB, vision model), **PDFs** (text layer, ≤20 MB),
  text files (JSON, CSV, code, logs, Markdown…; ≤5, ≤2 MB). Backspace on an empty field removes the last attachment.
- `/vault` toggles **answer from vault**: each question searches the vault; the reply lists `Sources` and cites `[[Note]]`.
- `/tools` toggles **tools** (see below). `Ctrl+S` saves the chat (or the last answer) as a vault note.
- Replies stream; a footer line shows `✓ Done · time · ↑ prompt ↓ output tokens · tok/s`. `Esc` cancels a running reply.
- Persistent chats: the last one reopens at start; `/new` starts a fresh one; `/history` lists saved chats.
- Leaving a chat that was not saved to the vault asks first (save, or `Esc` again to leave).
- Output is rendered Markdown (see [ui-internals.md](ui-internals.md)): boxed tables, highlighted code, coloured figures.
- Select mode: ↑↓ move, Space ticks, `c` copies (Markdown or plain per Settings), `b` copies code/tables only.

### Tools in Chat (opt-in)

With *Settings → Tools → Tools in Chat* on, the model may call: `search_notes`, `list_notes`, `read_note`, `list_dir`,
`read_file` (run at once) and `create_note`, `append_to_note`, `write_file`, `edit_file` (always ask: panel with diff,
`y` allow / `n` deny / `a` always this session). Confined to the vault + configured folders. Full design in
[tools-and-agent.md](tools-and-agent.md).

## Library

Views (←/→): All, Recent, Inbox, No links, No tags, Broken links, Stale, Tags. Left list + right preview (frontmatter, links,
backlinks, related notes). Keys: `Enter` read, `a` AI on this note (opens AI notes with the note preset), `o` open in Obsidian
(`obsidian://` link), `d` trash (moves to `.trash`, asks first), `/` filter, `p` process the inbox (Inbox view); the Tags view browses
notes per tag.

## Inbox

Lists notes in `inboxFolder`. "Process with AI" asks the model for a JSON plan per note (`InboxProcessing`): title, tags,
a one-line summary, links to existing notes, destination folder. `Enter` reviews a note, `p` processes all; in the review `Tab`
moves between editable fields, `Enter` applies, `Ctrl+X` skips. The plan is validated against the vault (links must exist,
folder must exist or be a sensible new one), previewed, then applied with `Vault.processInboxNote` which first backs the
original up to `.trash`, merges tags into frontmatter, adds a summary callout and a `## Related` section, then renames/moves.

## AI notes (Compose)

Tasks: Write a new note · Turn pictures into a note · Summarize · Improve · Continue · Translate · Action items · Connect
(suggests tags and links from what the vault already contains) · Custom. Input accepts attachments (pictures, PDFs, files).
Result is a **draft**: refine (`f`), add more (`m`), undo (`u`), redo (`r`), then save as new (`n`), add to a note (`a`),
replace the source note (`w`, with backup), or copy. Saving a note made from pictures can copy and embed them (`![[x.png]]`).
Leaving with an unapplied draft asks first.

## Session

Every run is a session logged to `~/.config/arcana/sessions/` as it happens (asked, task, created, appended, replaced, deleted,
saved, captured, processed, attached, tool). Actions: save a **summary note** (model-written from the log and chat
transcripts) or the **plain log** into the vault; **start a new session** without restarting. Quitting with unsaved activity
opens the "Before you go" flow (`s` save summary, `l` save log, `Esc` keep working).

## Capture (`Ctrl+N`)

One line becomes `- HH:mm text` at the end of today's daily note. The daily note path/format comes from Obsidian's
`.obsidian/daily-notes.json` when present, else Settings (`dailyFolder`, `dailyFormat`). `Ctrl+O` attaches any file(s): copied into
`<outputFolder>/attachments/` and embedded as `![[name]]`.

## Usage

Tokens in/out per feature and per day from `usage.jsonl`, session total, speed.

## Settings

Tabs (groups): Ollama (URL, model picker, temperature, timeout), Vault (path via folder browser, output folder, context
notes, inbox folder, daily note settings, stale days, chars per note), Appearance (theme, navigation, sidebar, logo animation),
Console (answer from vault, Markdown rendering, thinking, chat history, keep chats, leave guard, chats kept, copy format),
Tools (enable, extra folders, auto-read, steps), Prompts (system prompt, summary prompt), Accessibility (interface scale,
screen-reader mode), Tabs (order of the screens). Values apply immediately and persist to `config.json`.

Themes: Dark, Light, Soft Dark, Soft Light, Darcula, Solarized Dark/Light, Nord.

## Guide

`screens/Guide.kt` holds 11 pages (Markdown strings rendered by the same renderer): Start, Setup, Brain, Library, Inbox, Chat,
Session, LLM, Obsidian, Keys, More. **When you change behaviour, update the matching Guide page and
[functionality.md](functionality.md).**
