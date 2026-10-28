# Skills

A **skill** is a runbook that lives in the user's vault and that a (small, local) model follows step by step, together with the scripts and
reference notes it needs. Arcana finds skills by their folder layout, tells the model which exist, lets the user attach one to a message
(or lets the model load one itself), and **watches a skill's required steps** so a model that skips one is sent back to it.

This is the one document that explains the whole feature. Code: `core/Skills.kt` (everything pure), `core/Tools.kt` (`use_skill`, the catalog),
`app/ChatSession.kt` (runs and the gate's owner), `app/Generation.kt` (the gate in the loop), `app/screens/Chat.kt` (menu, chip, status badge).
For people writing skills there is also the note `Skills Guide.md` in the skills folder; for the model, `Arcana/Arcana LLM Reference.md`;
for users, the in-app Guide page "Skills".

## Starter skills

`Bootstrap` (first-run setup, palette "Set up the vault", `arcana --init`) puts `Skills Guide.md` and the starter skills (into `.arcana/skills/`, or the skills folder in use) from `core/StarterSkills.kt` into a vault, never over an existing file: today `decompose` (a todo list becomes a plan: analyze → `ask_user` quiz → plan note → one item at a time; it requires the `ask_user` and `create_note` steps). `CoreTest.theStarterSkillsAreRealSkillsWithoutProblems` keeps them valid (found, described, steps parsed, under 18,000 characters, `needs:` are real tools).

## 1. Why it is built this way

| Decision | Reason |
|---|---|
| A skill is **plain Markdown + files in the vault**, nothing registered anywhere | the user owns it, edits it in Obsidian, versions it with the vault; adding a folder is enough |
| The runbook is written for a **weak local model**: numbered steps, exact tool calls, scripts do the math | small models skip steps, narrate instead of acting and cannot copy big outputs |
| Arcana **enforces** required steps instead of only asking nicely (`requires:`, the completion gate) | in practice a small model ignored "ALL STEPS ARE REQUIRED" and even a failing check; only the harness can hold it to the list |
| The entry note is `<name>/<name>.skill.md` | it must stay a Markdown note for Obsidian (links, graph, properties); `.skill` tells it from documentation and scripts; the name is unique because Obsidian links notes by file name (so not `SKILL.md`) |
| **Results live outside the skill folder** (`output:` → e.g. `Reports/<name>/`) | a skill is stable instructions, results grow with every run; keeping them apart keeps the skill small, shareable, and its file listing and per-message scan cheap |
| Attaching a skill **injects its text** into the message (a chip) | a weak model often does not call `use_skill`, or calls it wrongly; the user also sees what was attached |
| `planner:`, `worker:` and `reviewer:` in the properties (or the role table in Settings → Oracul, for skills that name none) give the models for a run. The `planner` writes a short numbered plan from the question and the skill's instructions before the worker starts; it is added as a `Ψ Plan · model` block (`Oracul.planMessage`, not a review: no `/revise` hint) that the worker is told to follow where it is right, and is written only when a skill chip starts the run (not for a skill the model loads itself with `use_skill`); a failing planner is only a message. The same applies to (roles per skill step, level 3 of the Oracul plan) | a small skill model is fast; a skill that must be right (a report, a forecast) wants a bigger one, and only that skill pays for it. Applied in `ChatSession.applyRoles`: the installed `worker` replaces the chat model for that message (it must have the `tools` capability when tools are on), the `reviewer` runs `secondOpinion` when the answer is done, whatever the Oracul's automatic setting says. An uninstalled or tool-less model is skipped with a message and the settings' model is used. The settings are never changed, the next message goes back to them |
| The gate is **generic** (reads `requires:` of any skill) | nothing in Arcana's code knows about one particular skill |

### How this relates to other systems

Skills as a folder of Markdown instructions with reference files and scripts, read when needed, are a common pattern (for example Anthropic's Agent Skills with `SKILL.md`); plans and to-do lists, and guards that stop a model from finishing early (a stop hook, a validator node in a workflow framework), are common too. What is Arcana's own is the combination: the steps a skill lists in `requires:` are **checked in code** by the completion gate and a model that tries to finish early is sent back, with a strike count and a visible badge. Elsewhere steps are usually only advice. The names (`requires:`, the gate, 3 strikes, 40 rounds) are not a convention other tools share. This comparison is from general knowledge of other tools, not from their current documentation.

## 2. Layout and naming

```
<skills folder>/                      automatic: .arcana/skills, or an existing Skills/ (Settings → Tools → Skills folder; AppConfig.skillsFolder; see Layout)
                                      plus ~/.config/arcana/skills/ for every vault (a vault skill of the same name wins)
  <name>/                             lower-case words joined by "-" (nameRule), e.g. coingecko-forecast
    <name>.skill.md                   THE ENTRY NOTE (Skills.ENTRY_SUFFIX). Without it the folder is not a skill.
    reference/…                       notes the skill reads only when needed (loaded on demand with read_note)
    scripts/…                         helper programs, run with run_command and a full path
<output folder>/                      declared by the skill with output:, e.g. Reports/<name>/ (NOT inside the skill)
```

- Folders that start with `.` and files that start with `.` are ignored. A subfolder without `<name>.skill.md` is skipped silently.
- An old-style `<name>.md` entry is **not found** (rename it to `<name>.skill.md`).
- Obsidian `[[links]]` use the file name: `[[coingecko-forecast.skill]]`.

### Properties of the entry note (YAML frontmatter)

| Key | Needed | Meaning |
|---|---|---|
| `name` | no | must equal the folder name if present (else a *problem*) |
| `description` | **yes** | what it does **and when to use it**; the model picks a skill by it. Menu shows its first sentence cut at 80 characters, the model's catalog 240 |
| `version` | no | shown in `use_skill` output and `/skills` |
| `output` | no | vault folder for the skill's results; `use_skill` tells the model (`Results go to (outside the skill): …`) |
| `requires` | no | the required steps (section 5); quoted list items |
| `type`, `tags`, `inputs`, `needs`, … | no | for people and Obsidian; Arcana ignores them |

`Skills.discover` collects **problems** per skill (shown by `/skills`, the skill is still listed): `name` differs from the folder, folder name not
lower-case-with-dashes, no `description`, an unreadable `requires:` line, entry note over `MAX_CHARS` (18,000 characters).

## 3. Life of a skill run (what happens, in order)

```
discover ──► catalog in the system prompt + use_skill tool        (every message: ToolBox is rebuilt, Skills.discover runs)
   │
   ├─ A) user picks "/name" in the menu ─► chip "◆ skill name" on the message (a note attachment)
   │        send: ChatSession.ask() ─► SkillRun started from requires: (if any)
   │              Prompts.chatMessages adds "SKILL “name”: attached … already loaded" + Skills.load(...) to the user message
   │              Skills.stepsFor: this question may take ≥ 40 tool rounds
   │
   └─ B) the model calls use_skill(name) ─► result = Skills.load(...);  runTools starts a SkillRun if that skill has requires:
   │
   ▼
model works in the tool loop ── every call + result ─► SkillRun.observe(...)  (ticks matching steps; status badge updates)
   │
   ▼
model replies WITHOUT a tool call (it wants to finish)
   ├─ a step is missing ─► Generation asks the gate ─► hidden "STOP: … step still missing: …" ─► the loop continues
   │       (same step ignored 3 times ─► it is given up on = "skipped")
   └─ all steps done or skipped ─► the reply is accepted, the turn ends
   │
   ▼
the run ends at the user's NEXT message or /new (the badge disappears); a new chip starts a new run
```

Chip vs `use_skill`: with a chip the text is part of the **user message** (and is re-sent with every request while that message is inside the
chat-history window, about 4–5k tokens for the forecast skill); with `use_skill` it is a **tool result** (cut to 20,000 characters like
`read_note`, and shortened like any old tool output when the history is re-sent). If the user attached the skill, the model is told not to
call `use_skill` for it.

## 4. Discovery, catalog and loading (details)

- `Skills.discover(vault, folder)`: scans the direct subfolders of the skills folder; reads each entry note once (properties + length);
  lists the skill's files (`filesOf`: walk depth 4, hidden skipped, **at most 8 per subfolder and 60 in all**, the rest as "… N more").
- **When it runs:** for every message (`ChatSession.toolboxFor` builds a new `ToolBox`, which discovers); in the Chat screen when it opens,
  after each message and **every time the "/" menu opens**; `/skills` runs it too. A skill added in Obsidian is therefore visible at once.
- **Catalog:** if the vault has ≥ 1 skill and skills are on, `ToolBox.describe()` adds a SKILLS paragraph (`- name: description` per skill, 240
  characters each) telling the model to call `use_skill` first when the request matches or the user names one. No skills or switched off:
  no paragraph and **no `use_skill` tool**.
- **Load text** (`Skills.load`): `SKILL <name> v<version>`, `Folder: <absolute path>`, optional `Results go to …`, the file list
  (vault-relative paths), then the entry note's text **without the properties block** (so `requires:` is not shown to the model; Arcana
  states the missing step itself when needed).
- `use_skill` is a *Read* tool: no approval, allowed in Plan mode. What the skill then does (create notes, run commands, fetch pages) is
  asked as usual.

## 5. Required steps and the progress badge (the completion gate)

A skill lists the steps that must happen in `requires:` (quoted items, because a `:` inside a plain YAML value would break the property):

```yaml
requires:
  - "run_command ~ snapshot | step 4: the pool snapshot"
  - "append_to_note x3 | step 9: append the three parts to the report"
  - "run_command ~ check => REPORT_COMPLETE=yes | step 11: the check must say yes"
```

| Part | Meaning |
|---|---|
| `tool` | the exact Arcana tool name (`[a-z_]+`): a call of that tool counts |
| `~ text` | the call's **arguments** (JSON text) must contain it (case-insensitive); for `run_command` that is the command line |
| `=> text` | the call's **result** must contain it (case-sensitive) |
| `xN` | the step needs N matching calls (N ≥ 1; default 1) |
| `\| label` | what the model is told when the step is missing |

**Semantics (`SkillRun`, pure and unit-tested):**
- Steps are **independent**: any matching call ticks a step, in whatever order the model works. A call can tick several steps.
- A `=>` step ticks only when the result matches: a failing `check` does not count, so the model must fix the report and run it again.
- `nudge()` names the **first** missing step in the skill's order and counts a **strike** against it; after `MAX_STRIKES` (3) that step is
  **skipped** (given up on) so a stuck run still ends; the next missing step is then asked for. `summary()` lists what was left undone.
- It checks that steps **happened**, not that their content is right. Pair it with `=>` on a verification command.

**Where it is enforced:** `ChatSession.skillRun` (a Compose state) is fed by `runTools` (every call and result, including `ask_user`) and read
by `Generation(gate = { skillRun?.nudge() })`. The gate is asked only when the model answers **without a tool call** while tools are offered:
the model's reply (if any) stays in the chat, a hidden user message with the STOP text is appended to the request, and the loop continues.
Hidden messages are **not shown and not saved** to the chat; they stay in the requests of the rest of that run. Gate nudges are not tool
rounds (they do not use up *Steps per question*) and are independent of the "I will now …" announcement nudge (that one allows 2 in a row).

**The badge** (`chatStatus` in `Chat.kt`): `◆ <skill> <done>/<total>` in the Chat status bar. Purple while running; green with `✓` when all steps
are done; red with `✕N skipped` when steps were given up on. The label is the skill's folder name; the total is the number of readable
`requires:` lines.

**Generic by construction:** a run exists only for a skill whose entry note has a non-empty `requires:`. Otherwise `Skills.requirementsOf`
returns an empty list, `startSkillRun` sets `skillRun = null`, and that skill gets **no badge and no gate** (it simply runs from its text).
Nothing needs registering; adding `requires:` to any skill turns the feature on for it.

**Run lifecycle:** started by a chip on a message (`ask`) or by the model's `use_skill`; ended by the **next user message** (even without a chip,
so an unfinished run cannot drag an unrelated question back) or `/new`. Only the **first** skill chip of a message is tracked (others are
loaded as text). Restoring an old chat starts no run.

## 6. Limits and numbers

| Value | Where | Why |
|---|---|---|
| `MAX_CHARS` 18,000 | `Skills` (a *problem* above it) | `read_note` and `use_skill` cut at 20,000; the whole entry note including its properties counts |
| `LOAD_LIMIT` 20,000 | `Skills.load` | same cut as `read_note` |
| `RUN_STEPS` 40 | `Skills.stepsFor` | a skill run takes many tool rounds (the forecast skill ~16); the default of 8 would interrupt it with "N more steps?" |
| `MAX_STRIKES` 3 | `SkillRun` | how often the same missing step may be ignored |
| 8 per folder / 60 files | `filesOf` | keeps `use_skill` output short when a folder grows |
| 80 / 240 characters | menu hint / catalog description | one line in the menu; a sentence or two for the model |
| 80 characters | `create_note` title | `Vault.sanitizeTitle`: skills must build unique, short names in their scripts |
| 8,000 characters | `ARCANA.md` | only the start is sent to the model: keep skill-related rules near the top |

## 7. Tricky moments (read before changing anything)

1. **Entry name = folder name + `.skill.md`.** A mismatch is only a *problem*, but the entry is found by the folder name, so renaming the note makes the skill vanish.
2. **The entry note's size includes its properties.** `requires:` can add 1–2 KB; keep the whole file under 18,000 characters and move detail to `reference/`.
3. **YAML quoting.** Quote every `requires:` item; a `: ` inside an unquoted item makes Obsidian reject the properties. `Skills.frontmatter` (simple `key: value` reader) ignores list lines; lists are read through `Vault.parseProperties`.
4. **`create_note` always writes its own properties** (`created`, `generator`, `model`, `tags: [arcana]`) and **merges** a block you put at the top of the content (your keys win). Change properties later with `set_properties`, never `edit_file`. Titles are cut at 80 characters and an existing name gets ` 2` (nothing is overwritten): let the skill's script build the unique name and check collisions.
5. **A tool name that is not a real tool never ticks and is not reported.** The step is eventually skipped by the three-strikes rule. Write names exactly (`run_command`, `append_to_note`, `set_properties`).
6. **Order is not enforced.** To make "check after append" meaningful use a result condition (`=>`), not position.
7. **A weak model narrates** ("Step 8 – I will run …") instead of calling a tool. That ends its turn; the announcement nudge pushes it (2 in a row, reset after each real tool call) and, when a step is missing, the gate pushes it again. Skill text should say "do not narrate".
8. **A failing verification command shows as a red `exit code 1`** in the chat. That is by design (the script exits 1 to say "not complete"), not a crash.
9. **The status in the report must not lie.** The forecast skill writes `status: done` only after the check passed (`FINAL_STATUS`), else `incomplete`; Arcana's gate and the script's `check` are what make that possible.
10. **Hidden nudges persist inside one run's requests** (they make the model see the exchange) but are never saved to the chat.
11. **Plan mode** keeps `use_skill` (read-only) but removes every writing tool: a skill that needs to create notes cannot finish there; the gate then runs out its strikes.
12. **Tools off:** a skill chip still attaches (a toast warns), the text is delivered, but the model cannot act; skills need *Tools in Chat*.
13. **Rate limits are the skill's problem.** The forecast skill's script waits between API calls and the model uses `wait` on HTTP 429; the CoinGecko free API allows only about 5–7 requests a minute.
14. **Old chats:** a chat reopened from history has no run (the badge is empty) even if it contains a skill chip.
15. **A count step is satisfied by ANY matching call.** `append_to_note x3` is ticked by the three appends of the run, including unrelated later ones (links, an index line), so it cannot tell whether the analysis parts were appended. Bind each step to its content with `~`: `append_to_note ~ ## LP economics` ticks only when the call's content contains that heading (the forecast skill does this for its three parts).
16. **Arcana cannot enforce an order, the skill's script can.** The gate is a finish-guard (it speaks only when the model tries to end) and steps are independent, so a model that keeps calling tools can skip a step unnoticed until the end. For must-be-in-order work let the script read the result (read-only) and refuse: `gt_forecast.py part --n N` withholds part N (red `exit code 1`, `STOP: part N-1 is NOT in the report note yet`) until the previous part is really in the note, and prints that part again.
17. **Do not make an end-of-run question look like an input.** A skill that lists "tokens spent" in its input table and says "get every input" gets that question asked first (it happened with a small model). Name the inputs to ask at the start explicitly, and say which questions belong to the end.
18. **A `=>` result condition is a substring match, so the success text must appear ONLY on success.** The forecast skill's first `check` printed "…until this check prints REPORT_COMPLETE=yes" in its *failure* message; that contained the very text the step waits for, so a failing check ticked the step and the gate stayed silent while the report was incomplete. Scripts used with `=>` must print the success token on success only (and never quote it in help, STOP or FIX text); test the failure output for it.
19. **A path the model passes to a script may be wrong, and a script that silently skips its check hides it.** Gemma passed the note's title instead of its vault path; the script could not find the file and quietly turned its order check off. Scripts must resolve a path leniently (vault path, absolute path or bare title; titles contain dots, so never use `Path.suffix` on them), and **fail loudly and early** on a path that cannot be resolved (before any slow work).
20. **A status written before the last check is stale.** A model fixed the report after two failed checks and wrote `status: incomplete` without checking again. Make the status checkable: the forecast skill's `check --final` compares the note's `status:` with its content (`FINAL_OK=yes`/`no`, with the exact `set_properties` call to fix it) and a `requires:` step demands `FINAL_OK=yes`; the skill says "run the check again after EVERY fix".

## 7a. Diagnosing a failed run (look at the calls, not at the model's explanation)

A small model that is asked "why did it fail?" **makes up a plausible reason** (a run that skipped two `append_to_note` calls was explained as "an API data limit" and
"a loop"). Never rely on it. The truth is in the saved chat: `~/.config/arcana/chats/<id>.json` has every assistant `tool_calls` entry and every `tool` result in order.
Compare the sequence of calls with the skill's steps (a strong model such as Qwen follows it exactly; a small one drops steps): which tool was called after which result,
which result was a `STOP:` / `REPORT_COMPLETE=no`, and what came next. The report's own properties (`status`, `missing`) and the badge (`◆ name 7/11`, `✕ skipped`) say the same in short.
Known small-model patterns: it does only the **last** of two instructions in one tool result; it fixes something and does **not** re-run the verification (so a status written from the earlier result is stale); it asks end-of-run questions at the start; it ignores a failing verification; it
narrates instead of calling a tool; it rationalizes afterwards.

## 8. Code map

| What | File → symbol |
|---|---|
| Layout, discovery, problems, catalog, load, chip helpers, `requires:` parser, step budget | `core/Skills.kt` → `Skills`, `Skill` |
| One required step; the progress and the nudges | `core/Skills.kt` → `Requirement`, `SkillRun` |
| `use_skill` tool and the SKILLS paragraph | `core/Tools.kt` → `ToolBox.skills`, `useSkillTool`, `describe()` |
| Injecting an attached skill into the message | `core/Vault.kt` → `Prompts.chatMessages`; `app/ChatSession.kt` → `noteTexts` (`Skills.loadEntry`) |
| Run state, starting/ending runs, feeding calls | `app/ChatSession.kt` → `skillRun`, `skillTick`, `startSkillRun`, `ask`, `runTools`, `startNew` |
| The gate in the loop | `app/Generation.kt` → constructor parameter `gate` |
| Menu entries, `/skills`, chip, status badge | `app/screens/Chat.kt` → `slashItems`, `useSlash`, `toggleSkills`, `chatStatus`; `design/SlashKind.kt`; `design/Attachments.kt` (`AttachmentLine`) |
| Settings | `AppConfig.skillsEnabled`, `skillsFolder` → `screens/Settings.kt` (Tools group) |
| Tests | `CoreTest`: `skillsAreFoundLoadedAndOfferedToTheModel`, `aSkillCanRequireStepsAndTheRunIsTracked`; `SkillGateTest` (real loop against a fake Ollama server) |

## 9. The reference skill: `coingecko-forecast`

`Skills/coingecko-forecast/` in the vault (`.skill.md`, `reference/`, `scripts/gt_forecast.py`); results in `Reports/coingecko-forecast/`.
It analyses one DeFi pool from the free GeckoTerminal API (candles, statistics, anomaly scan, backtested forecast with an ASCII and a Mermaid
chart, a concentrated-liquidity range study, a verdict) and saves a report with note tools only. It shows every technique above: a script that
does HTTP and math, outputs handed over in small parts (`part --n`), `NEXT:` lines in tool results, a `check` command whose result is a `requires:`
condition (`check`: content, and `check --final`: that the `status:` property tells the truth about it), an order enforced by the script itself (`analyze --note …`, then `part --n` refuses to skip an append; the note may be given by path or just by title), content-bound `append_to_note ~ ## …` steps, `elapsed_time` for the total work time, `set_properties` for the final status, and `output:` for the results folder.

## 9a. A controlled flow: Planner, Worker, Validator (`defi-pool-analysis`)

A second reference skill, written for the user's vault (not a starter skill): `.arcana/skills/defi-pool-analysis/` (`.skill.md`, `reference/` with the research notes and `API Notes (verified).md`, `scripts/dp_pool.py`, standard library only, Python 3.9+); results in `Reports/defi-pool-analysis/`. Version 2 starts from a **token pair, not an address**: the user writes `ETH/USD`, `USDT-BTC`, `WBTC:WETH` or `ETHUSDC`; the script (`compare`) parses it (`parse_pair`; ETH = WETH, BTC = WBTC/cbBTC/BTCB, USD = USDC/USDT/DAI, well-known addresses per chain in `TOKENS`, any other symbol found with DexPaprika's `/search`), finds every pool of the pair on seven chains (`/networks/{chain}/pools/search?token_address=…`, the pair filtered client-side), reads fees from GeckoTerminal (`pools/multi`, one call per chain), scores and ranks the pools (fee yield 30, depth 25, steady volume 20, age 10, venue 15), reads the price history of the pair from the deepest pool (one candles call) and computes the market, a calibrated forecast band and liquidity ranges. `part --n 1..4` prints the four report parts (Summary and pick; Pools compared with ASCII bars of score, TVL and fee yield; Market and forecast with an ASCII price-and-forecast chart; Liquidity ranges with a range ladder and "how to use it"); the closing pick says which pool to use and why. It needs **no code in Arcana**: the roles use what exists, and the skill keeps its progress with the `todo` tool.

| Role | Realised by |
|---|---|
| **Planner** | the `planner:` property or the Settings → Oracul role table: a numbered plan before the worker starts (only with a chip) |
| **Worker** | the main model, or `worker:`; follows ten `requires:` steps (one is `todo`) |
| **Validator** | (1) the script's `validate` command: compares the saved note with the cached data (pick line, pool address, P90, both charts, the four parts and their order, disclaimer, no placeholder, `status:`) and prints `VALID=yes` only on a match, with `FIX:` lines otherwise; `validate --final` also checks that `status` tells the truth (`FINAL_OK=yes`); both are `=>` steps, so the gate holds the worker to them. (2) the `reviewer:` model, a second opinion on the final answer |

Lessons from building it against the live APIs: keyless DexPaprika serves only 24 h of candles (history comes from GeckoTerminal); the DexPaprika `fee` is `null` for Uniswap v3 (the fee comes from GeckoTerminal); the search result lists tokens without symbols; GeckoTerminal's own symbol search is full of noise, so symbols are resolved to addresses first; `token=quote` is only right when the BASE token is the stablecoin, so the script reads both token prices and picks the side (and for a pair with no stablecoin it charts the pair's own ratio, `currency=token`); GeckoTerminal's free tier answers 429 quickly, so the script spaces its calls and waits a 429 out itself (a run takes 15 s to a few minutes: `timeout_seconds` 300). The forecast is a no-drift random walk whose 10-90 band is **calibrated against past windows**; the fee yield of a concentrated range is shown both as a theoretical ceiling and as a rule of thumb (efficiency capped at 5x, times the share of past windows the price stayed inside). Success tokens (`VALID=yes`, `FINAL_OK=yes`) appear only on success (tricky moment 18).

## 10. Changing the skills feature: checklist

Update, in the same piece of work: this file; `tools-and-agent.md` (tool rows); `functionality.md`; `app-module.md` / `core-module.md`
(file tables); `data-and-config.md` (config, vault conventions); `testing.md`; the in-app **Guide** page "Skills" and the **Changelog**;
the vault notes `Skills/Skills Guide.md`, `Arcana/Arcana LLM Reference.md`, `Arcana/Arcana Reference Documentation.md`; the user's `ARCANA.md`
and `Vault.MEMORY_TEMPLATE` when a rule for the model changes. Keep the reference skill's note under 18,000 characters.
