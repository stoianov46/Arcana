# 0002 Work items and provenance

Status: **phase 1 shipped** · written 2026-11-06 · decisions by the project owner on the same day.

## Problem

A research question lives in `Research/Research Backlog.md` as a Markdown line, and the link between that line and the report that answers it is fragile. Facts checked in the code and in the real vault:

- **The line is the database.** `ResearchBacklog` reads and writes `- [ ] question (date) ^marker` with a regex (`LINE`). A hand edit that breaks the shape, a renamed question or an Obsidian plugin that rewrites `->` drops or duplicates an item. The marker (`^q-0611-1650`) exists only for lines that were once opened from the Brain screen (`ensureId`); older lines have none.
- **The link is made on the input side, by the chat.** A chat knows its line only when it was *started* from Brain (`Home.kt:105` sets `backlogQuestion` / `backlogId`; `ChatSession.stampBacklog` stamps the notes it creates). A chat typed freely never knows. Real case, 2026-11-06: "investigate airportgap.com/docs and create a prompt…" answered the open line "create prompt for llm: JFK airport tracking dashboard", wrote a note, and nothing linked or ticked anything. The reverse case works only because of one UI path.
- **The fallback is text equality** on the report's `question:` property, which reports written outside a skill do not have.
- **One concept, three copies of the machinery.** The backlog of questions, the Todo list and the Thoughts (`Thoughts/…`) are the same thing (a captured item that later gets worked on and finished), yet each is its own parser. The backlog already asks for this ("do like TodoBacklog for thoughts").
- **Provenance is not recorded.** Nothing says which chat, run or model produced a note except the free-text `generator` / `model` properties. So nothing can be linked afterwards.

## Goals

1. **Linking never depends on the model and never on how the chat was started.** A model that forgets, misspells or ignores an id must not break anything.
2. **Linking can happen after the fact.** If a free chat turns out to have answered a question, one action links the question to the work already done.
3. **One engine for every kind of item** (research question, task, thought), so a new kind costs a definition, not a new parser.
4. **Durable:** a crash, a double run, a hand edit in Obsidian or a sync conflict between two machines loses nothing and cannot create a wrong tick.
5. **Scalable:** thousands of items and years of history stay fast; the vault stays readable Markdown.

Non-goals: a database server, a hosted service, giving the model write access to ids, an automatic tick that cannot be undone.

## Design

Three separate things that today are tangled in one Markdown line.

### 1. Work items: identity and state are separate from the text

A **work item** has an immutable **id** made when it is captured (time-sortable, 26 characters, like a ULID: no collisions, no meaning to parse), a **kind** (`question`, `task`, `thought`), its text and its history.

State is never stored; it is **derived from an append-only event log**:

| Event | Meaning |
|---|---|
| `Captured(text, kind, at)` | the item exists |
| `Edited(text)` | the wording changed |
| `RunLinked(runId)` | a run (see 2) worked on it |
| `ArtifactLinked(noteId, role)` | a note answers it (`report`, `source`, `draft`) |
| `Closed(reason)` | `answered`, `abandoned`, `merged` |
| `Reopened` | undo of `Closed` |

State = the fold of the log: **open** → **in progress** (has a linked run, no closing event) → **answered** (a linked report whose status is `done`) or **abandoned**. Every event has its own id, so replaying a log or merging two logs from two machines is a union by event id: **idempotent, order-tolerant, nothing overwritten**.

The log is one JSON Lines file per kind inside the vault (`.arcana/work/<kind>.jsonl`), so it travels with the vault and syncs like any file. A `WorkStore` interface (port) hides the storage: the JSONL log today, SQLite later if a vault ever needs it, without touching the callers.

### 2. Runs and provenance: stamp the output, not the input

Instead of a chat remembering its item, **every note Arcana writes carries where it came from**, stamped at the one place notes are written (the vault write path, not each tool):

- A **run** is one assistant turn sequence for one request: `runId`, `chatId`, start time, the first user message.
- Every note a run creates or appends to gets `origin: run-<id>` (and a `chat:` id) in its properties, set by code.

So "which questions did this work answer" becomes a query, **not a binding made in advance**:

- A run linked to an item (`RunLinked`) links all of that run's artifacts to it, including notes written *before* the link was made.
- A chat started from Brain still links at once (the same event, sent early).
- A free-typed chat links later, by one action (3).

The model sees no ids and has no tool for this. The Oracul and other helpers get the same stamp for free because it is applied where notes are written.

### 3. Linking and ticking are decisions by the app, shown to the user

- **Suggestions are computed, not guessed by the model.** Arcana already has an embedding index. For a run that produced a report but is linked to no item, it compares the run's request with the open items by meaning and by words, and offers the best few: `This looks like: create prompt for llm: JFK airport… [Enter link · n not this one]`. One key confirms; the confirmation is the `RunLinked` event.
- **Ticking is a derived state, not a write.** An item is *answered* when it has a linked `report` artifact that is `done`. Nothing edits a checkbox by hand in code; the Markdown file is regenerated from the state.
- **Everything is reversible and visible.** Each link or close is a line in the chat (`✓ answered: <item> → [[note]]`) and an `Undo` is a `Reopened` event, not an edit of history.

### 4. The Markdown note becomes a view

`Research/Research Backlog.md` is **generated** from the log (grouped open / done, with the `[[report]]` links and a hidden marker per line, `^<id>`), so Obsidian users keep their familiar checklist and backlinks. A hand edit is read back by a small **ingester**: it matches a line by its marker, turns a changed text into `Edited`, a ticked box into `Closed(answered by user)`, an unknown new line into `Captured`. A line it cannot understand is left exactly as it is (never deleted), with a warning in the Brain screen. Because the log is the truth, a broken file can always be regenerated.

### 5. One engine, many kinds

`question`, `task` and `thought` share the log, the states, the provenance and the ingester; a kind only supplies: its Markdown file, its capture prefix (`?`, `todo:`, none), and what counts as *answered*. The Todo list and the Thoughts backlog become two more kind definitions instead of two more parsers.

## Options considered

| Option | Verdict |
|---|---|
| Keep the Markdown line as the database and add more regex, ids and chat binding | rejected: the failures above are structural (identity and state live in text a human edits) |
| Give the model a `complete_backlog(id)` tool | rejected: depends on a small model noticing, remembering and spelling an id; it fails silently, and a wrong id is a wrong tick |
| A binding made by the user in the chat (`/backlog`) | rejected as the only path: it must be remembered *before* the work, and cannot fix work already done |
| **Event log + provenance on output + app-computed suggestions** | **recommended**: nothing depends on the model, linking works after the fact, and history cannot be corrupted |

## Phases

Each one is releasable on its own.

1. **Identity and log.** *(shipped 2026-11-06: `Work.kt`, `WorkStore.kt`, `WorkMigration.kt`; facts in `docs/research-backlog.md`; the fold sorts captures first so a skewed clock cannot drop an event; a backlog marker is kept as the item id when a line has one, otherwise an id is made from the text and date and the marker is recorded as an alias.)** `WorkItem`, the event model, the fold, `WorkStore` over JSONL, and a **migration** that reads the existing backlog note (every line, with or without a marker) into events. The note keeps being written exactly as today (still parsed by the old code) so nothing changes for the user. *Test:* the fold is the same after replaying the log twice or in another order; a golden test migrates a copy of the real note; a crash in the middle of an append loses at most the last event.
2. **Provenance.** `Run` records and the `origin` stamp applied at the vault write path for every created or appended note. *Test:* every writing tool, the Oracul and skills produce a stamped note; an unstamped legacy note is simply "unknown origin".
3. **Link and tick by the app.** `RunLinked` / `ArtifactLinked` from Brain-started chats (replaces `backlogQuestion` / `stampBacklog`), and *answered* derived from a linked done report. *Test:* the AirportGap case end to end (free chat → note → link → answered), and a chat that is cancelled or fails leaves the item open.
4. **Suggestions and Undo.** The meaning + words match of an unlinked run to open items, the confirm key, `Reopened`. *Test:* a labelled set of real run/item pairs, with a threshold that prefers "no suggestion" to a wrong one.
5. **The note as a view.** Generate the Markdown from the log and add the ingester; remove the regex parser as the source of truth. *Test:* round trip (log → note → ingest → same log), hand edits, a line it cannot read stays.
6. **More kinds.** Todo and Thoughts as kind definitions; the Brain screen lists any kind.

## Risks

- **Two representations while migrating** (phases 1 to 4 write the note and the log). Mitigation: phase 1 changes nothing visible, and the log is checked against the note on every start until phase 5.
- **Hand edits in Obsidian** are the hardest part of phase 5. Mitigation: the ingester never deletes what it cannot read, and the log can regenerate the file.
- **A wrong suggestion** that the user accepts by habit. Mitigation: it is only ever a suggestion with its two best candidates, never a tick, and `Undo` is one key.

## Decisions (owner, 2026-11-06)

1. **The log lives in the vault** (`.arcana/work/<kind>.jsonl`): it travels and syncs with the vault.
2. **Answered = a linked report with `status: done`.** A linked note that is not done (a draft, a failed or cancelled run) leaves the item *in progress*.
3. **An item may have several answered reports.** Each run with a different skill links its own report; the item is answered when any linked report is done, and a later run never reopens the first.
4. **A skill opts in to the kinds it works on** with a `handles:` property (`handles: [question]`); Arcana never guesses from `inputs`. A skill without it works as before and does not appear in the item picker.
5. **Capture and organisation of the other kinds follow the vault and the TUI, not a typing rule alone** (details under Open questions). The `todo:` prefix is accepted for Ctrl+N capture; a thought with no prefix stays a thought.

## Kinds and skills

Kinds stay separate: every item stores its kind (`question`, `task`, `thought`), each kind has its own file and folder in the vault, the Brain list shows one kind at a time, and each kind has its own default skill.

**Skills stay where they are** (the vault, `.arcana/skills/<name>/<name>.skill.md`). The one addition is `handles: [question]` in the skill's properties.

**Running an item** (`Enter` on it in Brain): a small picker lists the skills that `handles` this kind, plus `(no skill: plain chat)`, with the default preselected; Enter accepts, another choice overrides it, and a second key sets the worker, planner and reviewer models for this run only. The run starts in Chat and is linked to the item when it starts (`RunLinked`).

**Where the default comes from**, strongest first:
1. the item: a `skill:` property pins one skill to one item;
2. the kind: a setting names the default skill per kind (questions: dev research; tasks: plain chat);
3. the last skill used for this kind.

**Several runs per item:** an item can be run again with another skill (a quick dev research first, a deeper comparison later). Each run links its own report; the item is *answered* when any linked report is `status: done`.

## Open questions

1. ~~When should Arcana suggest a question for a finished run?~~ **Decided (owner, 2026-11-06): only when the note is a research report** (written by a research skill, or with `type: report`); a chat that only edits a note or answers a quick question never asks.
2. **Vault structure and TUI controls for the kinds.** Recommended: one folder per kind (`Research/Research Backlog.md` stays; `Tasks/Tasks.md` and `Thoughts/…` follow the same shape), the Brain screen gets a kind switch (`b` cycles question, task, thought) with the same keys for every kind (`Enter` start work, `Space` tick or untick by hand, `x` abandon, `u` undo, `l` link a finished run). To be decided with the Todo and Thoughts phase.
