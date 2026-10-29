# Plan: next TODOs (draft for decision)

Status: **proposal**. Nothing here is built except the pipeline option below. The numbers `N3`–`N11` are the numbers of the new TODO list;
`N` keeps them apart from the old numbers in [roadmap.md](roadmap.md) (which has its own #9 and #11). When a task is agreed it moves to the
roadmap table.

Facts below that come from reading the code are marked *(checked)*. Everything else is an estimate or an assumption.

## Already done in this round

| What | Where |
|---|---|
| **Roles apply to → Every message**: the planner, worker and reviewer of the role table run for every message, not only skills. A message under 60 characters gets no plan. Default stays *Skills only*. | Settings → Oracul; `rolesScope`; `ChatSession.applyRoles(null)`; test in `OraculTest` |

Known issue, not caused by this work: `CoreTest.webSearchParsesAllThreeBackendsAndAsksBeforeEverySearch` fails with and without these changes.

## Decisions made

| # | Decision |
|---|---|
| Orchestration | **One flow core**: roles + an optional router (selector) + an optional controller loop, built once. N9 (judge), N10 (selector, controller, presets) and N5 (`goal`) become configurations of it, not separate builds. `oracul.md`'s "a global orchestration layer was not needed" is revisited when it lands. |
| N7 | `Esc` opens the confirm dialog (Enter = cancel the reply, `Esc` = dismiss). The double-`Esc` timer goes. |
| N9 | The judge gives an advisory **verdict on each automatic opinion** (one extra run per opinion), as a stage of the flow. |
| N4 | **Postponed.** Local Ollama stays the priority; the question stays open for later. When it comes back, cloud models are one more backend a role can point to. |
| N6 | An unknown note path is **always refused** (no guessing). A one-off cleanup command lists duplicates and entries with missing paths and **asks before changing** anything. |
| N3 | Artifacts are **files and notes written or edited** by the tools, counted per session. |
| N5 | `/goal <condition>` is a **chat command, as in Claude Code**: it sets a condition, Arcana keeps working after each turn until a check says it is met, then stops with a short note; `/goal` shows it, `/goal clear` removes it. A hard limit on automatic turns and Cancel always stop it. Built on the flow core's controller loop. |

| N8 | All the suggested Library options: default tab (`All`), list/content width split, default sort order, note previews, tag counts. |
| N11 | The **main chat** sends everything until the context limit, then compacts; no fixed message count. See N11 below. |
| N10 | Reworked: five small building blocks (stage, sequence, router, loop, check) as in other agents. The five-stage flow is one preset, not the design. See N10 below. |

One open question: N10, fixed sequence first or delegation from the start.

## Summary and suggested order

| Order | # | Task | Size | Risk | Needs your decision |
|---|---|---|---|---|---|
| 1 | N7 | Confirm dialog for cancelling a job instead of double `Esc` | S | low | which key opens it |
| 2 | N9 | Judge reviews every automatic opinion | S | low | which of the two options |
| 3 | N8 | Library: default tab `All`, a Library settings page | S–M | low | the list of options |
| 4 | N6 | Memory: no duplicates, no missing paths | M | medium | strictness |
| 5 | N3 | Indicator of generated artifacts in Chat | M | low | what counts as "generated" |
| 6 | N11 | Oracul context: look and defaults | S | low | what you want to see |
| 7 | N10 | Predefined oraculs, controller and selector models | L | medium | a concrete example |
| 8 | N5 | `goal` concept like Claude Code | L | medium | semantics |
| 9 | N4 | ChatGPT and Gemini support | XL | high | policy change |

Size: S under half a day, M about a day, L several days, XL a week or more (rough guesses).

---

## N7. Confirm dialog for cancelling a job

**Status: done.** Built as `ChatMode.ConfirmCancel` (Enter or `y` cancels, any other key keeps going; the reply keeps streaming while it is open) and the setting `confirmCancel`. Checked by driving the real app in a pty against a fake Ollama; there is no unit test, because the Chat screen is a composable with no key-level test harness.

**Problem.** Roadmap #9 made `Esc` twice cancel a reply. You use `Esc` a lot in the terminal, so a double press cancels by accident.

**Proposal.**
- `Esc` while a reply runs does nothing destructive. It opens a small dialog: *Cancel this reply? Enter = yes, Esc = no*.
- The same dialog guards cancelling the Oracul, comparison and takeover (`ChatSession.cancelOracul`).
- Add a setting *Confirm before cancelling* (on by default) so the old quick behaviour is available.
- Remove the double-`Esc` timer logic and its hint text.

**Open questions.** Is `Esc` the right opener, or do you want another key (for example `Ctrl+C`) so `Esc` never touches a running reply?

**Where.** `ChatSession` (cancel path), `Chat.kt` (dialog, hints), `Settings.kt`, `docs/functionality.md`, key lists in the Guide, roadmap #9 note.

**Tests.** `Esc` opens the dialog and does not cancel; `Enter` cancels; `Esc` again dismisses; the setting off restores a single press.

## N9. The judge ran once

**What happened** *(checked)*. The judge is only used by `/compare` and by automatic opinions when *Judge automatically* (`oraculJudgeAuto`) is on, and that is **off by default**. Your usage shows 92 Oracul calls (single opinions) and 1 judge call (one `/compare`). So arcana worked as designed; the design does not match what you expect.

**Two options.**
1. **Turn on *Judge automatically*.** Zero code. Each automatic opinion becomes a comparison: the Oracul answers the question too and the judge compares it with the chat model's answer. Three model runs per opinion, slow.
2. **Judge reviews the opinion (recommended).** After an opinion, the judge reads the question, the answer and the opinion and says whether the opinion is right (a verdict line under the opinion block). It runs once per opinion, with the judge model, and is recorded as `Judge` usage. Cheaper than a full comparison and it checks the Oracul, which is what you described.

**Open questions.** Option 2 for every opinion, or only the automatic ones? Does the judge's verdict stay advisory (shown, never applied)? I would keep it advisory, as the Oracul docs require.

**Where.** `Oracul` (new `verdictRequest`), `ChatSession.secondOpinion`, `oracul.md`, Usage panel text.

**Tests.** The fake Ollama gets one more asked model per opinion; usage under `Judge`; a failing judge never breaks the chat.

## N8. Library: default tab and settings

**Status: done.** The "default tab Tags" was a bug, not a default: the Library kept the filter a Brain link had set, so after one such link every sidebar visit opened on it. Now the link names its view once and the sidebar opens on *Settings → Library → Opens on* (All).

**Proposal.**
- The default tab is **All** instead of **Tags**.
- A *Library* group in Settings with options such as: default tab, list/content width split (now fixed 30 % / 70 %, roadmap #33), default sort order, show note previews, show the tag count.

**Decided.** All of them.

**Where.** Library screen, `Settings.kt` (new `Group.Library`), `AppConfig`, `docs/functionality.md`, `docs/data-and-config.md`.

**Tests.** Defaults; the width setting changes the split; invalid values fall back to the default.

## N6. Memory: duplicates and missing paths

**Status: done.** The check is on `remember` (the tool), and `/memory check` for what is already in the file. A line is refused only when it *names* a note that does not exist; there is no way to tell that a plain sentence "should" have carried a path.

**Problem.** The memory file collects entries that repeat, and some lack the file path that is later used as the note path in the vault.

**Proposal.**
- On write, normalise the entry (trim, collapse spaces, case-insensitive compare) and skip or merge it when an equal or near-equal entry exists.
- An entry that refers to a note must carry a path that exists in the vault; otherwise the write is refused with a message the model can act on, or the path is looked up by title when there is exactly one match.
- A one-off cleanup command that lists duplicates and entries with missing paths and asks before changing anything.

**Open questions.** Refuse or repair? I would refuse when the path is unknown and repair only when the title matches exactly one note. Should the cleanup command exist, or only the check on write?

**Where.** Memory code in `core` (I have not read it yet), the memory tool, `docs/data-and-config.md`.

**Tests.** Duplicate added twice gives one entry; missing path refused; one match repaired; two matches refused.

## N3. Indicator of generated artifacts

**Proposal.** A compact strip or a counter in the Chat status bar: *N files written · M notes created · K edits*, built from what the tools actually did in this session. A key (or `/artifacts`) opens a list: path, what happened, in which message. Selecting one opens it in the Library or shows the diff.

**Open questions.** What counts as generated: files and notes only, or also command outputs and saved comparisons? Per session or per chat?

**Where.** Tool-result hooks in `ChatSession` (the tool loop already records a `ToolUseRecord`), a new small model class, `Chat.kt`.

**Tests.** A write and an edit show up once each; a denied change shows nothing; the list survives a chat reload only if you want it to.

## N11. Context: send everything, compact at the limit

**Status: done** (`chatHistory` and `oraculContextMessages` default to 0 = everything; Oracul background capped at 30,000 characters). The quiz mode is not designed and not built.

**What is wrong** *(checked)*. The main chat sends only the latest `chatHistory` messages (default **20**, Settings → Console → *Chat history*, `Prompts.window`). Older messages are silently dropped from the request even when the model's window has plenty of room. The Oracul, judge and planner have their own cap (6 messages × 1500 characters, `oraculContextMessages` / `oraculContextChars`).

**Decision.** Send the whole chat until the context limit is near, then compact the older part. Arcana already has the second half: `autoCompactDue` compacts at `autoCompactPercent` (default 80 %) of the window.

**Proposal.**
- `chatHistory` default becomes **0 = everything** (compaction keeps it within the window); a number stays available for people who want a hard cap. The current turn is always sent whole, as today.
- The Oracul, judge and planner get the same rule: the whole chat (shortened in the middle per message only when it alone would not fit), instead of 6 × 1500. The two settings stay as optional caps, default off.
- The Settings rows say what they limit and show the real size sent.
- Risk: a long chat makes every request slower on a local model, and the first request after a change re-reads the whole prompt. Auto-compact at 80 % limits it; the percent stays adjustable.

**Where.** `Prompts.window`, `AppConfig` defaults, `Oracul.background`, `Settings.kt`, `docs/functionality.md`, `docs/data-and-config.md`, the Guide, `oracul.md`.

**Tests.** A 60-message chat is sent whole with the default; a set cap still cuts; the current turn survives a cap; the Oracul sees the whole chat; auto-compact still triggers at the percent.

## N10. Orchestration: the flow core (reworked to industry practice)

**Status: steps 1, 2, 3 and 4 done** (step 4: N9 judge verdict and N5 `/goal`, see their sections) (step 3: `/flow`, stage names in the reply line, `⇉` in the status bars) (`Flow`, analyst stage, the *Careful* preset, Settings → Oracul → Flow; then the controller stage and the redo loop, model-based check only). Not started: the command-based check (rest of step 2) and steps 4–6.

**Correction.** The first draft of this section turned your example (analyse prompt → plan → execute → analyse result → control) into one fixed five-stage pipeline with gating rules. That was my invention. Your list is **one possible flow**, not the design. The design is a small set of building blocks that can express that flow and others, as other agents do.

**What other agents do** (general knowledge, not a current survey; to be checked before building):
- **A single agent loop is the default.** Claude Code is one main model in a tool loop. Anthropic's own guidance on agents is to start with the simplest thing and add workflow structure only where it measurably helps.
- **Specialists are subagents.** The main loop hands a task to a subagent that has its **own model, own prompt, own tool set and own context**, and gets back only a short result. Claude Code ships ones for exploring and planning, and users define their own; the exploring one is typically a small fast model.
- **Plan mode.** Plan first, read-only, then the user approves, then execute.
- **Verification is grounded.** The strongest check is an objective one (tests pass, the file exists, a command succeeds), run by a hook or a goal condition, not only a second model's opinion.
- **Google's agent framework (ADK) and Gemini CLI** use the same few parts: a *sequential* agent (steps in order), a *parallel* agent, a *loop* agent (repeat until a condition or a limit), and a coordinator that routes to sub-agents. Anthropic names the same patterns: prompt chaining, routing, parallelization, orchestrator–workers, evaluator–optimizer.

**So the core is five small blocks, not one pipeline.**

| Block | Meaning | Already in Arcana |
|---|---|---|
| **Stage** | one role with its own model, server, prompt, tools on/off and context rule | roles: planner, worker, reviewer; the Oracul; the judge |
| **Sequence** | stages in order, each sees the earlier output | skill roles; *Every message* pipeline |
| **Router** | picks the stage or flow for a message (image → vision model, code → coder) | no |
| **Loop** | repeat a stage or sequence until a check passes or a limit is hit | stuck detection and the skill completion gate, not as a general block |
| **Check** | decides *done or redo*: a model, or an objective test (command exit code, file exists) | the completion gate; the Oracul opinion |

A **preset** is a named combination of these. Your five stages are one preset (analyst → planner → worker → reviewer → controller with a redo loop). `/goal` (N5) is a Loop with a user-written Check. The judge verdict (N9) is a Check stage. A selector is a Router.

**What changes compared with the first draft.**
- **Default stays the single agent.** No flow runs unless a preset is selected. I removed the "skip short messages" and "only with `/flow`" rules I invented; they were my guesses. Whether any gate is needed is decided after the first preset is tried.
- **Subagent-style delegation is a candidate addition:** the main model could call a stage as a tool (for example *ask the planner*) instead of a fixed order. That is how Claude Code does it. It is a second way to use the same stages; I would build the fixed sequence first because it is simpler to see and test.
- **Checks can be objective.** A Check may run a command and use its result, not only a model's opinion.
- The memory-swap cost on local machines is a fact of running several large models, so the Settings warning stays as information (`ModelFit` per stage). It is not a restriction on the design.

**Build order.**
1. `Flow` / `Stage` as data and the Sequence block, mapped onto the existing roles; the analyst and controller are two more stage kinds. One built-in preset: your five stages.
2. Loop and Check (model-based and command-based).
3. Settings and `/flow <name>`; stage blocks in the chat, bottom-bar status, usage per role, cancel (N7).
4. N9 (judge verdict) and N5 (`/goal`) as presets on the core.
5. Router, rules first.
6. Stages as callable tools (subagent style), if step 1–5 show it is worth it.

**Open question (one).** Is a fixed sequence enough for the first version, or do you want delegation (the main model calls a stage as a tool, like Claude Code subagents) from the start?

**Where.** `core` (new `Flow.kt`, `AppConfig`), `ChatSession`, `Oracul`, Settings, `oracul.md` (rewrite "a global orchestration layer was not needed"), `functionality.md`, `data-and-config.md`, Guide, Changelog.

**Tests.** The fake Ollama answers per model, as `OraculTest` does: stages run in order with the right models; a redo goes back and stops at the limit; a command-based Check uses the exit code; a failing stage is skipped with a note; usage per role; cancel stops the flow.

## N5. `goal` concept

**What Claude Code does.** You set a goal as a condition ("all tests pass"). After each turn a check decides whether it is met. If not, the assistant continues instead of stopping.

**Proposal for Arcana.**
- `/goal <condition>` sets the goal for the chat; `/goal` shows it; `/goal clear` removes it. The goal shows in the status bar.
- After each finished reply, a check (the Oracul model, or the chat model when no Oracul is set) reads the goal and the last answer plus tool results and returns *met* or *not met, because ...*.
- *Not met* starts another turn automatically, up to a limit (default 5 turns), and always stops when the user presses cancel or when a step is stuck.
- It reuses the skill completion gate and stuck detection.

**Risks.** Endless loops and token cost, so a hard turn limit and a visible counter are required. The check can be wrong, as the Oracul docs warn.

**Open questions.** Is the goal per chat or per session? Does a met goal stop silently or ask?

## N4. ChatGPT and Gemini support

**Situation.** `oracul.md` fixes the rule "Local Ollama only". Every client is `OllamaClient`. Supporting OpenAI and Google models changes that rule.

**What it needs.**
- A provider setting and an API key store. Keys must not be written to logs, usage files or the vault.
- A client abstraction (chat, streaming, tools, images, model list) with an Ollama and a cloud implementation. Arcana's tool calling, thinking, usage and capability checks all assume Ollama's API today.
- Privacy wording: the vault content then leaves the machine.
- Usage records money, not only tokens, which the Usage screen does not do yet.
- "Google Angular" is read here as **Gemini**; please confirm.

**Alternative that costs far less.** Ollama can use cloud-hosted models through its own interface, and many tools expose an OpenAI-compatible API. A client for the **OpenAI-compatible API** would cover OpenAI and, through gateways, many others, without a separate integration for each. I have not checked what Gemini offers in this respect.

**Open questions.** Do you accept that Arcana is no longer local-only? Which provider first? I recommend postponing this until N10 and the client abstraction are settled.

---

## What happens next

Decided, except one N10 question. Proposed order, with N10 first because N9 and N5 sit on it:

1. N7 cancel dialog (small, independent, can go first).
2. N10 steps 1–3 (flow core, built-in preset, Settings, `/flow`).
3. N9 judge verdict and N5 `/goal` on the flow core.
4. N11 context (small, but touches every request, so it is tested on its own).
5. N8 Library, N6 memory, N3 artifact indicator.
6. N10 step 5 (rule-based selector).
7. N4 stays postponed.
