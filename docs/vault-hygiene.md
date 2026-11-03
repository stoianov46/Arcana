# Vault hygiene (Brain screen)

The Brain screen checks the vault for the habits that keep it navigable and shows the result next to the other "needs attention" lines. The rules mirror the vault note
`Research/Where Things Go.md` ("Names and properties"): plain names, a `type` on every note, nothing loose in the root, generated reports finished and fresh, no island.

Code: `core/.../VaultHygiene.kt` (`HygieneKind`, `HygieneIssue`, `HygieneReport`, `VaultHygiene.check` / `checkNotes`), the UI in `app/.../screens/Home.kt`, tests in
`core/src/test/.../VaultHygieneTest.kt`.

## The eight checks

| Kind | Symbol | The rule | Suggested fix (also what `c` sends to the model) |
|---|---|---|---|
| `MissingType` | ◇ | the note has no `type:` property (empty counts as missing); `thought-*` files are logs and are skipped | add `type:` (index, reference, analysis, comparison, checklist, template, article, benchmark-prompt, guide, report, thought) and `tags:` |
| `SymbolName` | ☺ | the file name's first character is not a letter, a digit, `(` or `[` (emoji, symbols) | rename it without the prefix (the message names the clean name) and update the links |
| `GenericName` | ≈ | the whole name is `Index`, `Note(s)`, `Untitled [n]`, `README` or `New note [n]` (case-insensitive); `Dev Index` is fine | rename it after its subject |
| `DuplicateName` | ⧉ | two notes whose names are equal when only letters and digits are kept, lower-cased ("🐍 Phi Reference" = "phi reference"), in any folders; both are listed | merge them (keep the newer, trash the older) or rename |
| `LooseRoot` | ⌂ | a note in the vault root other than `Home` (thought files and `Thoughts Index` belong in `Thoughts/`) | move it into the folder of its subject, a thought into `Thoughts/` |
| `ReportNotDone` | ◔ | `type: report` and `status` is not `done` (a missing status shows as "missing") | finish it with its skill (`REPORT_VALID=yes`, then `status: done`) or delete it |
| `StaleReport` | ◷ | `type: report` older than its limit by `date:` (first 10 characters): **90 days** for `kind: landscape` and `compare`, **365** for every other kind (`VaultHygiene.STALE_DAYS`, `DEFAULT_STALE_DAYS`); a report without a readable date is not judged | open the Library, Reports view, press `f` (refresh: the old report becomes `superseded`, see [report-lifecycle.md](report-lifecycle.md)); superseded reports are skipped by this check and by *not finished* |
| `Island` | ⊘ | no note links to it and it links to no note (`VaultIndex.orphans`, the same figure as "not linked to anything"); `thought-*` files are skipped | link it from the index of its area or from Home, or delete it |

Hidden folders (`.arcana/`, `.trash`, …) are never read: `Vault.notes()` leaves them out, as it does for the rest of the Brain.

## What you see

- **Brain screen**, a **VAULT HYGIENE** section in the *Now* list (the *Today* list in a narrow window), under *Needs attention*: one line per kind with its count (`✓ 0 stale reports` in green when there is none),
  then up to 3 examples for each of the two worst kinds (a note opens in the Library on `Enter`). It is a section of the scrolling list, not a separate panel: a fixed panel would hide rows in a small window (docs/ui-internals.md).
  When nothing is wrong: `✓ Vault hygiene is clean`.
- **`h`** (or `Enter` on a kind line, or the palette command *Vault hygiene…*) opens the full list: every issue, grouped by kind, scrolling. On a kind line the list opens on that kind only.
  `↑↓` / `j k` choose, `Enter` opens the note in the Library, `c` starts a new Chat with the note attached and the field holding
  `Fix this vault issue: <message>. Suggested fix: <fix>` (press `Enter` to type, `Enter` to send; nothing is sent by itself), `Esc` goes back to the Brain.

## How it runs

- The check reads every note once (frontmatter and name only matter) in a background coroutine (`Dispatchers.IO`) whenever the Brain's `VaultIndex` is rebuilt (`r`, or after any change Arcana makes). The last result stays on screen while a new one is computed.
- Islands come from the Brain's index that is already built, so the vault is scanned twice at most (once for the index, once for the texts). A vault of a few thousand notes takes well under a second; there is no cache beyond the screen's state.
- The checks are pure functions of (path, text): `VaultHygiene.checkNotes(notes, islands, today)`. `today` is a parameter so the staleness rule is testable.

## How to verify (manual)

1. Make a scratch vault (anywhere) and put these notes in it, each with a `tags: [x]` property unless said otherwise. Point a throwaway Arcana at it with an isolated home
   (`JAVA_OPTS=-Duser.home=<scratch> app/build/install/arcana/bin/arcana`, config `{"vaultPath": "<vault>", "setupDone": true}` under `<scratch>/.config/arcana/config.json`):
   - `Research/NoType.md` with properties but no `type:`  → ◇ 1
   - `Research/🐉 Emoji Note.md` with `type: reference` → ☺ 1
   - `Research/Index.md` with `type: index` → ≈ 1
   - `Research/Dup.md` and `Projects/dup.md` (`type: reference`) → ⧉ 2
   - `Stray.md` in the root (`type: reference`) → ⌂ 1
   - `Research/Dev/Reports/2026-11-02 Half.md`: `type: report`, `kind: research`, `date: <today>`, `status: incomplete` → ◔ 1
   - `Research/Dev/Reports/2026-01-05 Landscape Old.md`: `type: report`, `kind: landscape`, `date: 2026-01-05`, `status: done` → ◷ 1 (more than 90 days ago)
   - a `Home.md` that links some of them, and `Research/Lonely.md` that nothing links to → ⊘ for the unlinked ones
2. Open Arcana: the Brain shows the **VAULT HYGIENE** section with a count for every kind and examples for the two worst. Expected counts for the notes above: 1, 1, 1, 2, 1, 1, 1 and the islands (every note `Home` does not link and that links nothing).
3. Press `h`: the list shows the kinds with their notes in order; `↓` moves the `▌`; the long messages end with `…` and nothing is cut off silently (scroll).
4. On `Research/Index`, press `Enter`: the Library opens on that note. `Esc`/number key 1 returns to the Brain.
5. Press `h`, move to any issue, press `c`: Chat opens (answer the memory-file question if asked) with the note attached (`Attached ▤ …`) and the field holding the "Fix this vault issue…" text. Nothing is sent.
6. Fix one issue in Obsidian (add `type: index` to `Research/NoType.md` and rename `Index` to `Research Index`), press `r` in Arcana: the counts drop and the `✓ 0 …` line turns green.
7. Resize the terminal to 24×80 and to 40×120: the section scrolls with `↑↓`, nothing is hidden; at 100+ columns it is in the left *Now* column.
8. Make the vault clean (fix everything): the section says `✓ Vault hygiene is clean`.
9. Empty or unreadable vault: no crash; the section is simply absent while the check has not finished.

## Not verified / limits

- Checked: unit tests for every rule and for the clean/dirty vault on disk; the real UI in a pseudo-terminal with the scratch vault above at 40×120 and 24×80 (counts, list, `Enter` into the Library, `c` into the Chat with the prefilled field).
- Not checked: a vault of thousands of notes (the cost is one extra read of every note; no timing was taken), Windows paths, and the Brain on a terminal narrower than 80 columns.
- A section, not a panel: the spec said "a panel under the vitals"; a fixed panel needs up to 15 rows and would hide rows at 24 lines.
- The islands rule duplicates the existing "not linked to anything" line under *Needs attention*; it is kept here so the hygiene list is complete in one place.
- Stale limits are a constant table in code, not a setting. `StaleReport` does not look at `status`.
- Duplicate names compare names only, never content; whether two notes should be merged is for you to decide.
- `thought-*` files are exempt from the type and island rules by name, so a note someone names `thought-…` escapes both.
