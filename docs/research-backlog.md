# Research backlog (R6)

Questions occur while you do something else. A thought captured with a `? ` or `research: ` prefix is also put on a checklist in the vault, the Brain screen shows it, one key starts the research, and a finished report ticks the line by itself.

Code: `core/.../ResearchBacklog.kt` (everything pure, tested in `ResearchBacklogTest`), the capture hook in `AppContext.capture`, the Brain section and list in `screens/Home.kt`, the hint line in `navigation/Overlays.kt` (`CaptureOverlay`). Test of the hook: `app/src/test/kotlin/ResearchBacklogCaptureTest.kt`.

## The note

`Research/Research Backlog.md`, created on the first question (the folder is created too). Plain Markdown the user may edit:

```
---
type: backlog
tags: [index, research]
---

# Research Backlog

Questions to research, one per line. …

- [ ] what is the outbox pattern (2026-11-03)
- [x] Navigation 3 on KMP (2026-11-03) -> [[2026-11-04 Nav report]]

Back to [[Home]]
```

Only lines of the form `- [ ] question (YYYY-MM-DD)` and `- [x] question (YYYY-MM-DD)` with an optional ` -> [[note]]` are read; everything else (the intro, your own text) is left alone. The type `backlog` is new: the vault note `Where Things Go` should list it among the allowed `type` values, and `Home` should link `[[Research Backlog]]` (vault side, not done by Arcana).

## Symbols (`ResearchBacklog`)

| Symbol | What it does |
|---|---|
| `capturedQuestion(thought)` | the question of a thought that starts with `? ` or `research: ` (any case, the prefix needs a space after it); null for an ordinary thought or an empty question |
| `normalize(q)` | how two questions are compared: lower case, spaces collapsed, a trailing `?` or `.` dropped |
| `items(vault)` / `open(vault)` | `BacklogItem(line, question, date, done, link)`: all lines / the unticked ones, in the order of the note |
| `add(vault, question, date)` | adds a line above `Back to [[Home]]`; creates the note when missing; false when the question is already there (open or done) or empty |
| `tick(vault, item, link?)` | `[ ]` to `[x]`, adds ` -> [[link]]`; finds the line by its index, else by its question (so a note edited meanwhile still works); false when it is gone or already ticked |
| `reconcile(vault)` | ticks every open item that a note with `type: report`, `status: done` and the same `question:` answers (`Reports.scan`; `normalize` decides "same"); links the newest such report; returns how many |

## Rules

- **Capture:** the thought is saved as always (thought file or folder note, prefix included); the backlog line is extra and best effort (`runCatching`: a failure never loses the thought). The prefix and the text after it become the question.
- **No fuzzy matching.** A report answers a question only when its `question:` property is the same text after `normalize`. The research skills write `question:` from the user's wording; to have a backlog item ticked, start it from the Brain list (the question is prefilled unchanged).
- **Brain:** a RESEARCH BACKLOG section (after VAULT HYGIENE): the open count and the first three questions, or a dim line that teaches the prefix when there is none. `b` (or `Enter` on the count) opens the list; the palette has *Research backlog*. In the list: `↑↓` choose, `Enter` starts the research, `x` marks done by hand (no link), `Esc` back.
- **Start the research:** a new Chat (`chat.startNew()`), the first skill whose name ends with `research-report` attached as a chip (only when skills are enabled and one exists), the question in the message field, **not sent**: `Enter` starts typing, `Enter` again sends.
- **Reconcile** runs off the UI thread whenever the Brain's vault index is rebuilt (`r` re-reads the vault), then the open list is read again. The list is kept while you visit other tabs (`rememberKept`, see `ui-internals.md`).

## How to verify (manual)

1. Press `Ctrl+N`. The dialog has the line "Start with “? ” or “research: ” …". Type `? what is the outbox pattern`, `Enter`. Then capture `buy milk`, then `research: Navigation 3 on KMP`.
2. Open `thought-YYYY-MM-DD.md` in the vault root: all three lines are there as typed.
3. Open `Research/Research Backlog.md`: two unticked lines (`what is the outbox pattern`, `Navigation 3 on KMP`), each with today's date, above `Back to [[Home]]`. `buy milk` is not there. Capture `? What is the outbox pattern?`: no third line.
4. Brain (`1`): a RESEARCH BACKLOG section with `2 open questions` and both questions (scroll down in a 24-row window).
5. Press `b`: a list of two. `↓`, `x`: the second is gone from the list (count 1); in the note its line is `- [x] …` without a link.
6. `b`, then `Enter`: Chat opens with `◆ skill create-dev-research-report` attached (when such a skill exists) and `what is the outbox pattern` in the field, not sent.
7. Make a report note by hand (or run the skill) with `type: report`, `status: done` and `question: "What is the outbox pattern?"`. On the Brain press `r`: the question leaves the backlog and its line in the note becomes `- [x] … -> [[the report's title]]`.
8. With no open question the section says `No open research questions · Ctrl+N, then “? your question”` and `b` shows "No open questions".

## Not verified / limits

- Checked in a real terminal (pty) at 40×120 and 24×80 with a scratch vault: capture, the Brain section, the list, `x`, `Enter` into Chat (chip and field), and the auto-tick. At 24×80 the new section sits below the first screen of the Brain list (it scrolls); the footer there shows only the first key hints.
- Not tried: a vault with hundreds of backlog lines or reports (reconcile scans reports once per vault re-read), Windows, a backlog note edited by hand while Arcana runs (a stale line index falls back to matching the question text).
- The backlog is vault-wide and not per project. Two questions that differ only in case or a final `?` count as one.
- A report skill that does not write `question:` never ticks its item; tick it by hand with `x`.
- Starting from the list does not remember which item started the research; the tick comes from the report's `question:` (or `x`).
