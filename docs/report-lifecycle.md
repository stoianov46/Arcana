# Report lifecycle: metadata, rating and refresh (R7, R8)

What happens to a research report after it is written: Arcana records what it cost (R7), you can rate it, and a stale report is replaced by a new one instead of being overwritten (R8). The report format is the vault note `Report Standard` (`.arcana/reference/report-kit/`); the Library view that shows all of this is [research-reports.md](research-reports.md).

## R7: metadata

| Property | Written by | Meaning |
|---|---|---|
| `model` | `create_note` (Arcana fills it if missing) | the chat model of the run |
| `tokens` | **Arcana** when the skill run ends | everything the run spent, every model (worker, planner, Oracul, judge, compaction…), from the usage records since the run started |
| `work_time` | **Arcana** (the DeFi forecast skill writes its own from `elapsed_time`; Arcana never overwrites a value) | wall-clock time from the start of the run to its final reply, as `5 min 12 s`; includes the time you took to answer questions |
| `rating` | the skill's last, optional step (`ask_user` "Rate this report (1-5), or skip", then `set_properties`) | 1 to 5; anything else is ignored by the reader |

### Why the app writes the numbers

Two designs were possible: (a) a vault-side `rpt.py meta` fed by the model from `chat_stats` / `elapsed_time`, or (b) Arcana writes them. (b) was chosen: a small model copying numbers between tool calls is exactly what it does badly (see the traps in [skills.md](skills.md)), while the app already knows the run (`SkillRun`, `artifacts`, `app.usage`). It works for every skill with `requires:` without changing the skill; only the optional rating needs the model.

How: `ChatSession.startSkillRun` remembers the start time and clears the stamped list. At the end of every final reply (`finishReply` → `stampReports`), if the run is finished (`SkillRun.finished`), each note this run created with `create_note` that is a `type: report` note gets the missing keys among `tokens`, `work_time`, `model` via `ReportMeta.stamp` (`Vault.setProperties`). Existing values are never changed, doing it twice changes nothing, non-reports are untouched, a failure leaves the report as it is, and a token count of 0 is not written.

## R8: refresh a stale report

1. In the Reports view a stale report shows `◷ … stale, N d` (90 days for landscape and compare, 365 otherwise); the Brain hygiene list says the same and names the way: Reports view, `f`.
2. `f` starts a fresh Chat with the report's skill attached and `Refresh [[<old>]]: <its question>` in the field (`s` still just runs the skill again). It works on any report not already superseded.
3. The skill (a hard rule in research, landscape, compare, pool and forecast; not the digest) redoes the run, reading the old report for what the message lacks. The new report has `supersedes: "[[X]]"` and a first TL;DR bullet `Replaces [[X]].`.
4. When the new report's checks pass, the skill sets `status: superseded` and `superseded_by: "[[New]]"` on X.
5. The new report's `rpt.py check --final` fails until X is marked (its FIX line gives the exact `set_properties` call).

### Supersede rules

- The ordinary honesty check is unchanged: `done` needs a valid report, `incomplete` is accepted only for an invalid one.
- A `superseded` report is not re-judged, but it is accepted only if `superseded_by` names a report that exists, is `type: report` with `status: done`, and has `supersedes` pointing back.
- A report with `supersedes: [[X]]` (status done) requires X to exist and be marked superseded, pointing back.
- Superseded reports are skipped by `Reports.isIncomplete` / `isStale` and by the hygiene checks `ReportNotDone` and `StaleReport`. The Library shows `↻` (plain `>`) and `replaced by <new>`; the topic index shows `superseded by [[New]]` and `(rating N/5)`.

## R9: reviewer pass (vault only, no app change)

Structure is checked by `rpt.py check` and the existence of sources by `verify-sources`; the reviewer checks that a claim is supported by what its source says. Flow, in the four web skills as an optional step after `verify-sources` (not in `requires:`, because it needs the helper models):
1. `rpt.py review-pack NOTE` builds a brief of at most about 6,000 characters: the review task (SUPPORTED / UNSUPPORTED / NOT CHECKABLE with a quoted sentence, claims that cite nothing, overconfident words, `VERDICT:`), the report's claims (every section except Sources, Method and gaps, Diagram, Chart, Review; cut at 3,200 characters) and an excerpt of each cited page (up to 3 sentences matching the words of the link title, else the opening; a page that cannot be read says `NO EXCERPT (HTTP 404)`).
2. The model passes the brief to the existing `consult` pseudo-tool with `stage` = `reviewer` (Settings → Flow → *Specialists as tools* and helper models on; the reviewer model is the *Skill reviewer* setting, else the Oracul model). The reviewer sees only the brief.
3. The answer is appended to the report as `## Review` (`Reviewer: <model>` first). A claim marked UNSUPPORTED is removed or softened and the checks run again; one pass per report.
4. No `consult` in the tool list, or an `Error:` result: the step is skipped and no section is written.
Limits: the model must copy the brief into `consult` (about 1.5k tokens; a file-based hand-over would be more reliable and is not built); excerpts are chosen by keywords, so a supporting sentence the keywords miss shows as NOT CHECKABLE; the reviewer is a model and can be wrong, it only reads the excerpts.
Verify (manual): enable *Specialists as tools*, run a research skill, and check that a `## Review` section appears with quoted sentences; then edit a claim in a copy of a report to say something its source does not, run the step by hand (`rpt.py review-pack`, then `consult`) and see it flagged.

## Files and symbols

`ReportMeta.kt` (`stamp`, `workTime`); `Reports.kt` (`ReportInfo.model/tokens/workTime/rating/supersededBy`, `SUPERSEDED`, `isSuperseded`); `VaultHygiene.kt`; `ChatSession.kt` (`startSkillRun`, `stampReports`); `Library.kt` (`f`, `runAgain(note, refresh)`, `refreshMessage`, `reportMeta`); vault `rpt.py` (`superseded_problems`, `supersedes_problems`, `report_rows`); tests `ReportLifecycleTest.kt`, `ReportRowTest.kt`.

## How to verify (manual)

1. Run a research skill to the end. Expected: `tokens`, `work_time` and `model` in the report's properties.
2. Answer the rating question with 4 (and Skip in another run). Expected: `rating: 4`; the Reports row ends `★4 · 20.5k tok · …`; Skip leaves no star.
3. Compare `tokens` with the Usage screen's session tokens before and after the run.
4. Backdate a landscape or compare report by 5 months. Expected: `◷ stale`, counted in the list title and in the Brain hygiene list with a fix text that mentions `f`.
5. Press `f` on it. Expected: Chat with the skill attached and `Refresh [[<title>]]: <question>` in the field; send it.
6. After the run: the new report has `supersedes` and `Replaces [[old]]`; the old one is `superseded` with `superseded_by`; the old row shows `↻ replaced by <new>` and is no longer stale or unfinished; `f` on it says it was already replaced.
7. In a scratch copy remove `status: superseded` from the old report and run `rpt.py check "<new>" --kind <kind> --final`. Expected: `FINAL_OK=no` with an exact FIX line, never `FINAL_OK=yes`.
8. `s` on a report still only re-runs the skill (no `Refresh`, nothing replaced).

## Not verified / limits

Not run end to end with a model (unit tests, `rpt.py` in a throwaway vault, and the view in a pty only). `work_time` is wall-clock including your thinking time; `tokens` includes every helper and the rating step. Stamping happens at the final reply of a finished run: a cancelled run, closing Arcana first, or a chat reopened from history stamps nothing; only notes created with `create_note` in that run are stamped. A skill without `requires:` has no run. A superseded report is not re-checked. `f` does not check that the refreshed question still makes sense. Usage → Orchestration does not show per-report cost or rating yet. The reading-mode header does not show rating or tokens, only the list rows. The digest skill has no refresh rule (it depends on freshly pasted news).
