# Research reports in the Library (the Reports view)

Arcana is a research system: its skills (`create-dev-research-report`, `create-llm-compare-report`, …) write **reports**, notes whose properties say
`type: report` (the vault note `Report Standard` fixes the properties and sections; `Research/Where Things Go.md` fixes the folders,
`Research/<Domain>/Reports/`). The **Reports view** of the Library makes them visible inside Arcana without opening Obsidian.

## Where it is and what it shows

Library → `←` `→` to the **Reports** tab (third: All, Recent, **Reports**, No links, …). The tab shows the count. The list panel is titled
`Reports · N · M incomplete · K stale` (the last two only when above 0). One row per report, newest date first:

```
✓ 2026-11-01  compare    Qwen vs Gemma on 32GB  ·  Use qwen3.5:9b for everyday chat …   just now
⚠ 2026-10-30  research   Navigation 3 in Compose Multiplatform  ·  Unfinished: …        incomplete
◷ 2026-06-01  landscape  New LLM families  ·  Five new open families since spring …     stale, 155 d
```

| Mark | Meaning | Plain glyphs (Settings → Appearance) |
|---|---|---|
| `✓` green | `status: done`, not stale | `+` |
| `⚠` orange | not finished: `status` is not `done` (normally `incomplete`) | `!` |
| `◷` grey | finished but older than its kind's limit | `~` |

Order of priority when several apply: incomplete, then stale, then done. The text is date, kind, the file name without its date (the date is last in the file name, `<Title> 2026-11-03`; older reports have it first, both are handled), and the
`summary` property; the list cuts the end to the window width. The filter (`/`) matches that whole text, so `landscape`, `incomplete` or a word of
a summary narrow the list. The tree and the hidden-folders switch do not apply to this view (it is always a flat list).

Keys (as in the rest of the Library): `Enter` reads the report (preview with links and backlinks), `a` AI on it, `c` chat about it, `e` edit, `r` rename,
`o` Obsidian, `d` trash. New: **`s` runs the report's skill again**: it opens a fresh Chat with the skill from the `skill:` property attached as a
chip and the report's `question` (else `pair`) property in the message field; you edit it if you like and send it. Without a `skill:` property,
or when that skill is not in the skills folder any more, a toast says so.
**`f` refreshes a report**: the same, but the field starts `Refresh [[<this report>]]: ` so the new report replaces the old one (the old one becomes `superseded`); a row also shows `★4` (your rating) and the tokens the run spent, and a superseded report shows `↻ replaced by …`. Details: [report-lifecycle.md](report-lifecycle.md).

## Files and symbols

| What | Where |
|---|---|
| `ReportInfo`, `Reports.scan(vault, today)`, `Reports.isStale`, `Reports.isIncomplete`, `Reports.domainOf`, `Reports.groupByDomain`, `Reports.STALE_AFTER_DAYS` | `core/.../core/Reports.kt` |
| the view: `LibraryFilter.Reports`, `reportLabel`, `reportMark`, `reportMeta`, `reportsTitle`, `runAgain` | `app/.../navigation/Navigator.kt`, `app/.../screens/Library.kt` |
| tests | `core/src/test/.../ReportsTest.kt`, `app/src/test/kotlin/ReportsViewTest.kt` |

## How reports are found

`Reports.scan` walks the vault's notes (`Vault.notes()`: every `.md` outside hidden folders, so `.arcana/` and `.trash/` are not included), reads only the
first 6,000 characters of each, takes the properties block (`Vault.splitFrontmatter` + `Vault.parseProperties`) and keeps the notes where
`type` is `report`. Missing keys are empty, a date that is not `YYYY-MM-DD` gives no age, a block that is not closed within those characters means
"not a report". Nothing is written. The Library scans once per vault index (after a note changes, the index refreshes and so does the list).

## Stale rules

A report is **stale** when its age (today minus its `date`) is more than its kind's limit: **90 days for `landscape` and `compare`** (new models appear every
month, what was true then ages fast), **365 days for every other kind** (`digest`, `research`, `pool`, `forecast`). The table is `Reports.STALE_AFTER_DAYS`
plus `DEFAULT_STALE_DAYS`. A report without a readable date is never stale. Staleness is only a mark here; nothing refreshes a report by itself.

## File names

`rpt.py new` names a report `<Title> <YYYY-MM-DD>` (title first, date last; ` 2` after the date when the name is taken). Reason: Obsidian's and Arcana's file trees are narrow and cut the end of a long name, so a date at the start hid the subject. `Reports.nameWithoutDate` removes the date from the row (both orders). The `date` property is the source for sorting and staleness, never the file name.

## Links

The index lists every report, and `rpt.py index` (the last step of each report skill) ends every report with `Back to [[<Domain> Reports Index]]` (idempotent), so the link goes both ways in the graph. Vault notes a report used are `[[linked]]` in its Sources. `Reports Overview` links the newest report of each domain.

## Vault shape per domain

Every `Research/<Domain>/` has the same shape: a hub `<Domain> Index` (Dev, LLM, DeFi, Arcana), `<Domain> Reports Index` (generated; links the hub back when it exists), area folders each with its own index (Dev: Architecture, Design, Engineering; LLM: Model-Families, Benchmark, Comparison; DeFi: Reference; Arcana: Architecture, Testing) and a flat `Reports/` folder. Home links every hub. The rules are in the vault note `Where Things Go`.

## How to verify (manual)

1. Make sure the vault has a few reports (run `/create-dev-research-report <question>` or `/create-llm-compare-report …`, or copy the properties of
   the vault note `Report Standard` into three notes with different `kind`, `status` and `date`, one `landscape` dated over 90 days ago).
2. Open Arcana, press the Library's number key, then `→` twice. **Expected:** the third tab is `Reports N`; the list title reads `Reports · N …`;
   each report is one row with a mark, date, kind, name and summary; the newest is first.
3. The unfinished one has `⚠` and `incomplete` on the right; the old `landscape` has `◷` and `stale, N d`; a finished recent one has `✓`.
4. Press `/`, type `landscape`. **Expected:** only that row stays, and the count in the filter strip shows `1/N`.
5. `Esc`, then `Enter` on a report. **Expected:** the note opens in reading mode with its path, words, links; `Esc` returns to the list.
6. Press `s` on a report whose skill exists. **Expected:** Chat opens, the skill chip `◆ skill …` is above the message field and the question is in it.
7. Press `s` on a report whose `skill:` points at a skill you do not have. **Expected:** a toast says the skill is not in the skills folder; nothing else changes.
8. Resize the terminal to 24 rows × 80 columns. **Expected:** the list and the hints shorten with `…` or wrap; nothing is cut or overlaps.
9. A note with `type: reference` or no properties must **not** appear in this view.

## Not verified / limits

- Seen only in a scratch vault with three fake reports at 30×120 and 24×80 and with Ollama offline; not in your real vault, and not with a model running a skill.
- `s` needs the skill's entry note to be found by `Skills.discoverAll`; a skill that has problems (name differs from the folder) is still found, one in `~/.config/arcana/skills`
  is found too, but an older report whose `skill:` link carries a different name than the folder will report "not in the skills folder".
- The scan reads the head of every note once per index refresh; for a very large vault (tens of thousands of notes) that is noticeable on the first open. No caching beyond the index.
- `Settings → Library → Opens on` does not list Reports in its choices (that file was out of scope); the config value `libraryTab: "reports"` works.
- `Reports.groupByDomain` is built and tested but not used by the view yet (the Brain screen and the roadmap's "research view" ideas can use it).
- The Reports view does not offer "re-run" for the report kinds whose properties lack a `question`/`pair` (the field is then left empty).
