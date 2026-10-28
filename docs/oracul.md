# The Oracul: second opinions, escalation and roles

Read this before touching anything that asks **another model** than the chat model. For what each feature looks like to the
user, see [functionality.md](functionality.md) "Oracul"; for the settings, [data-and-config.md](data-and-config.md).

## Principles

1. **The Oracul advises, the user decides.** Nothing it writes is applied by itself: its text is a block in the chat, and the user
   (or the chat model, when the user goes on) acts on it.
2. **Local Ollama only.** Every model it uses is an Ollama model, on the main server or on one second server the user names
   (`oraculUrl`). No remote API, no key handling.
3. **Same tools as the worker, never more power.** The Oracul may check with the worker's tools, but every change, command and web
   access asks the user, even in Auto mode, and it cannot ask for more power (no `ask_user`, `request_mode`, `request_access`).
4. **Rare and cheap by default.** A larger or different-family model is slow and memory hungry: it runs on request, after
   problems (`oraculAuto = problems`), or when a skill asks for it, not on every reply.
5. **Visible.** Every consultation is a block in the chat with the model's name, shown in the bottom bar while it runs, recorded in
   Usage and reported by `chat_stats`.

## Where this sits among agent patterns

Arcana is an agent in the usual sense: a model in a loop that calls tools (the tool rounds in `Generation`, up to 40 per question), with a plan and a completion gate for skills, stuck detection and approvals before changes. It is a human-in-the-loop assistant on local models, lighter than coding agents. The Oracul is a product name for patterns that are well established (from general knowledge of the field, not a current survey):

| In Arcana | Common name |
|---|---|
| `/oracul`, the automatic opinion, a skill's `reviewer:` | critic / reviewer, "reflection" |
| `/compare` and the judge | LLM-as-judge, answer selection |
| `/takeover` and the stuck step-in | escalation, model routing (a cascade) |
| a skill's `planner:` and `worker:` | planner and executor |

What differs: the Oracul is advisory and speaks only after problems or on a command, where many systems always run a verifier (a deliberate choice for speed on local models; *Judge automatically* gives the heavier form). Caveats to keep in mind: a reviewer of the same model family tends to agree with the worker, so a different or stronger model helps; a second opinion is not proof and is shown next to the answer, never applied by itself; more models cost time and memory (see the memory advice). It has been tested with fake servers and short real runs, not measured on real tasks, so how much it improves answers is not known yet.

## What exists

| Feature | How it starts | What happens | Code |
|---|---|---|---|
| **Second opinion** | `/oracul [model]`, or automatically after a turn with problems | the Oracul reads the last question and answer (and may check with tools) and says what is wrong or missing | `ChatSession.secondOpinion`, `Oracul.reviewRequest` |
| **Comparison** | `/compare [model]` | the Oracul model and the extra models (`oraculExtra`) answer the same question; one judge compares every answer and writes the best | `ChatSession.compare`, `Oracul.judgeRequest`, `comparisonMessage` |
| **Revise** | `/revise [note]` | the chat model revises its last answer with the open opinion | `ChatSession.revise`, `Oracul.REVISE_PROMPT` |
| **Takeover** (escalation) | `/takeover`, offered by a toast after a failed turn | the Oracul continues the unanswered turn from the same history, with its own model and tools | `ChatSession.takeOver`, `Oracul.takeoverHeading` |
| **Skill roles** | `worker:`, `reviewer:`, `planner:` in a skill's properties, or the role table in Settings | a skill run uses another worker model, gets a plan first, and a review at the end | `ChatSession.applyRoles` / `plan`, `Skills.roles`, `SkillRoles.withDefaults` |
| **Memory fit** | the model pickers in Settings | each model is marked as fitting this machine or not, with a suggested Oracul and judge | `core/ModelFit.kt` |

## What the Oracul is shown of the conversation

A request to the Oracul, the judge or the planner is built from the last question, and the answer or attempts, plus **`Oracul.background(messages)`**: the last messages of the user and replies of the assistant before that question (`oraculContextMessages`, default 6, 0 = off, up to 200, and `oraculContextChars`, default 1,500 each; each is shortened in the middle so the end of a reply, where its options sit, stays, and the total is messages x characters, nothing else cuts it; no tool output, no Oracul blocks; a compaction summary counts), under the header `EARLIER IN THE CHAT (context only…)`. Without it a short follow-up such as "do option C" or "yes" meant nothing to the reviewer (found in a real chat: the stuck step-in declared "option B and C are undefined" and the worker believed it). Every request builder takes it (`reviewRequest`, `answerRequest`, `judgeRequest`, `stuckRequest`, `planRequest`). An Oracul block also tells the worker, in `CAVEAT`, that the reviewer saw only part of the conversation and that it should check the block against the chat before agreeing; `Oracul.body` hides that line from the reader and still reads older chats.

## How a consultation works

```
trigger ──► model/server resolved ──► request built ──► (tool loop) ──► text ──► block added to the chat
 slash       Oracul.model / judge /       Oracul.*Request   oraculAgent     Oracul.ask   Oracul.*Message
 auto        models, serverConfig                          (only with a toolbox)
 skill
```

- **Which model, which server:** `Oracul.model(config)` (Oracul), `Oracul.judge(config)` (judge, falls back to the Oracul
  model), `Oracul.models(config)` (Oracul plus the extras, no blanks or repeats). `ChatSession.clientFor(model)` builds an
  `OllamaClient` from `Oracul.serverConfig(config, model)`, which swaps `ollamaUrl` for `oraculUrl` when that is set. So the
  Oracul, the judge, the planner, a skill's reviewer and `/takeover` go to the second server; the **worker always stays on the main
  one**.
- **With tools:** `oraculToolbox(model)` returns the worker's toolbox (`toolboxFor(config)`: same allowed folders, project, shell
  and web switches, Plan mode) or null when tools are off in Chat, *Oracul may use tools* is off, or the model has no `tools`
  capability (`/api/show`); then it only talks. `oraculAgent` runs a short loop (at most `toolSteps` rounds, capped at 10; the last
  round offers no tools so it must answer). `oraculCall` runs one call: reads follow *Read without asking*, everything else asks
  through the shared approval queue with a heading `Ψ Oracul · …`, no "always" choice.
- **One dialog at a time:** every dialog goes through `ChatSession.awaitApproval`, a `Mutex` around the single approval slot, so the
  worker's and the Oracul's requests wait for each other in order.
- **Concurrency guard:** `consulting` (the model being asked) is non-null while any consultation runs; a second one is refused
  ("The Oracul is already working"). The bottom bar and the Chat status bar read it.
- **Usage:** every request is recorded under the model that spent the tokens, as feature `Oracul` (opinions, its answers in a comparison,
  takeovers, stepping in), `Judge` (the verdict) or `Planner` (the plan): `app.recordUsage(feature, chunk, model)`. The usage file keeps them, so
  *how many times each was called* survives restarts; Usage → By feature shows them, Usage → Breakdown has a panel *Oracul, Planner, Judge* (`UsageStats.advisors`, `AdvisorsPanel`: calls, tokens, share of all tokens, average time, models) and `chat_stats` reports them (`ChatStats.spendLines`: spend per
  model and in total, and the all-time call counts). Its tool calls go in the tool statistics.

## Message conventions (do not break these)

The Oracul's blocks are stored in the chat like any message, so they survive saving, reopening and compaction:

| Block | Stored as | Starts with | Predicate |
|---|---|---|---|
| second opinion | a **user** message | `[Second opinion · <model>]` | `Oracul.isOpinion`, `isReview` |
| comparison | a **user** message | `[Comparison · <worker> vs <a>, <b>, judged by <judge>]` | `isOpinion`, `isReview`, `comparisonParts` |
| plan | a **user** message | `[Plan · <model>]` | `isOpinion`, `isPlan` (**not** `isReview`) |
| takeover answer | an **assistant** message | `_Ψ <model> took over this turn_` | `Oracul.TAKEOVER_MARK` |

- They are *user* messages so the chat model sees them in its history and can act on them. The block's own introduction
  ("A reviewer (another model) read your last answer and says:") is addressed to the model; `Oracul.body` strips it for display.
- They are **not turns of the user**: `Compaction`, `chat_stats`, `Oracul.turnFacts` and `Oracul.lastExchange` skip them, and a
  compaction summary is not mistaken for a question either.
- `Oracul.hasOpenOpinion` (the `/revise` hint) looks at reviews only, so a plan never offers `/revise`.
- `comparisonParts` splits a comparison by the **names in its title**, in order, not by `## ` lines: a model's own headings inside its
  answer cannot break the split. A block of another shape stays plain text.
- Rendering never changes the stored text: the split view, `/save`, select and copy, and what the chat model sees are the same.

## Triggers

- **Manual:** the slash commands and palette entries. `/oracul`, `/compare` accept a model name for one call, `/revise` a note.
- **Automatic (`oraculAuto`):** after a finished reply `maybeConsult` asks `Oracul.due(config, turnFacts)`: `off` never; `problems` when
  the turn had two or more failed tool calls (`Error:`, timeouts, non-zero exit) or a skill run skipped a required step; `always` on
  every reply. A failure of the Oracul is ignored (it must never break the chat).
- **Stuck worker:** two more cases count as problems. Across turns: the last three turns each had a failed tool call (`failedStreak`, `stuckTurns`). Inside a turn: every 4th failed call (`stuckCalls`) makes `ChatSession.stuckAdvice` ask the Oracul *in the middle of the tool loop*: it reads the task and the attempts (`Oracul.attempts`, `stuckRequest`) and its advice joins the loop as a review block (`stuckMessage`) at the end of that round, so the worker's next request already contains it. At most once per 4 failures, never for `off`, never breaks the turn, and the finished reply of such a turn gets no second automatic review.
- **Skill reviewer:** a skill that names a reviewer (or the role table does) asks for the review itself, whatever `oraculAuto` says.
- **Escalation is never automatic.** After a failed turn (error, or an empty reply that the one retry did not fix) a toast offers
  `/takeover`; the user decides. A tool-error loop or a skipped skill step gets the optional second opinion instead.

## Models and roles

Resolution for a skill run: **the skill's own property → the role table in Settings (`rolePlanner` / `roleWorker` / `roleReviewer`) →
nothing** (`SkillRoles.withDefaults`). Roles apply only to a message that carries a skill; the next message goes back to the settings.

| Role | Does | Server | Validated by |
|---|---|---|---|
| planner | writes a short numbered plan from the question and the skill's instructions, no tools; shown as a plan block that the worker is told to follow where it is right | Oracul server | installed on that server |
| worker | answers the skill run instead of the chat model | main server | installed, and `tools` capability when tools are on |
| reviewer | gives the second opinion when the run is done | Oracul server | installed on that server |

`applyRoles` runs in the first round's `prepare` (so the worker applies from round one) for a skill **chip**, and when the model
loads a skill itself with `use_skill` (worker and reviewer only: **a plan is written once, at the start of a message**). A model that is
not installed or cannot use tools is skipped with a message (`roleNote`, shown as a toast) and the settings' model is used; nothing is
ever written to the settings. The judge is only the judge: it sees all answers (`ANSWER 1` is the chat model's) and picks or merges.

## Interface

- **Bottom bar badge:** `Ψ <oracul> ⚖ <judge> · auto|always|manual`, the judge only when it differs; orange `Ψ <model>…` while it works;
  nothing when no Oracul model is set. The Chat status bar shows the same setup text when idle and an orange pill while consulting: `Ψ <model>…` for the Oracul, `⚖ <judge>…` while the judge works. Both bars build the text with `Oracul.statusText` so they cannot drift apart. The Chat bar builds the worker and the Oracul as one segment (`modelsSegment`): whoever works now is the first, filled pill, the other follows quietly (`waiting` for the worker); the quiet part is dropped when it would take more than half the bar. The reply line under the chat (`ReplyStatus`) says `Ψ <model> is answering…` (`⚖ <judge> is judging the answers…` for the judge) with the Oracul's own seconds (`ChatSession.consultingSince`) while it works, instead of the waiting worker's thinking line. Esc: `ChatSession.cancelOracul` cancels `autoConsult` and `manualConsult` (the jobs of `/oracul`, `/compare`, `/takeover`) when the worker is not running; the `finally` blocks clear `consulting` and nothing is added. Over columns the comparison's title is drawn with each model name in its column's colour (`comparisonTitleRuns`, `partColor` shared with the pills). An automatic opinion or comparison (`autoConsult`) is cancelled when the user sends a new message, so it cannot outlive its answer and keep `consulting` set through the next turn. The bottom bar swaps the two badges the same way and greys the worker's; on the Chat screen it collapses the Oracul badge to its glyphs (`Ψ`, plus `⚖` when the judge is another model; orange with `…` while asking), because the Chat bar already names the models. The glyph is `Oracul.GLYPH`.
- **Blocks:** a heading `Ψ Second opinion · <model>`, the text, a closing `Checked with: …` when it used tools, and, while it is open, the
  hint "› /revise: …".
- **Split view (`comparisonView`, Settings → Console):** a comparison is drawn as columns (`Chat.sideBySide`): a filled name pill per
  answer (main model in the accent colour with its family glyph, Oracul models in blue / purple / green with `Ψ`), a rule, a dim bar
  between columns, the verdict under them in a full-width orange `⚖ Verdict` pill. At most three columns of at least 38 cells
  (`columnCount`); more answers continue in further rows; a narrower window, `stacked`, or an unparsable block draws the answers one
  under another.
- **Slash menu:** the highlighted entry shows a usage line (`SlashItem.usage`, `e.g. /oracul qwen3:30b`).
- **Memory advice (`ModelFit.advice`, `AppContext.memoryAdvice`):** besides the picker labels, a standing note for the model already chosen: `advice(model, role, sizes, worker, ram)` returns null when it is fine, a note when it fits only alone (Ollama swaps it with the main model), and a `⚠` warning when it is too big (swap to disk, CPU or out of memory) with the better installed choice (`Better: …`, or `Install a smaller model`). Roles: main, Oracul (also a skill's planner and reviewer), judge (the judge should stay loaded with the main model). Sizes come from one cheap `/api/tags` call (`OllamaClient.modelSizes`, kept in `AppContext.modelSizes`, refreshed with the connection). It shows in three places: the hint under the selected Settings row, the notice after saving such a row (red for `⚠`), and an orange `⚠` after the Oracul text in the Chat status bar. No advice when the Oracul runs on another server (its memory is unknown).
- **Memory fit (`ModelFit`):** size on disk × 1.2 against 70% of the physical memory (a rule of thumb, not a measurement) gives
  `fits with the main model` / `fits alone (swaps with the main model)` / `too big`. `★ suggested` marks the largest model that fits at all
  for the Oracul (with tools when needed, never the main model or an embedding model) and the largest that stays loaded with the main
  model for the judge. With an Oracul server the memory of that machine is unknown: no verdicts. Nothing is chosen for the user.

## Configuration

| Field | Default | Meaning |
|---|---|---|
| `oraculModel` | blank = off | the Oracul model |
| `judgeModel` | blank = the Oracul model | the judge |
| `oraculExtra` | blank | more models that answer `/compare`, comma-separated (ticked in a picker) |
| `oraculUrl` | blank = main server | a second Ollama server for the Oracul, the judge, planner and reviewer |
| `oraculAuto` | `problems` | `off` / `problems` / `always` |
| `stuckCalls` / `stuckTurns` | `4` / `3` | failed calls in one turn per step-in; failing turns in a row for an automatic review (`0` = never) |
| `oraculContextMessages` / `oraculContextChars` | `6` / `1500` | how many earlier chat messages, and how many characters of each, the Oracul, judge and planner are shown besides the last question (Settings → Oracul → Chat context); 0 messages = none |
| `oraculJudgeAuto` | `false` | when an automatic opinion is due, run `compare()` (Oracul answer plus judge verdict) instead; mid-turn stuck advice and skill reviewers stay single opinions |
| `oraculTools` | `true` | the Oracul may use the worker's tools |
| `rolePlanner` / `roleWorker` / `roleReviewer` | blank | the role table for skill runs |
| `comparisonView` | `side` | `side` or `stacked` |

## Tests

- `OraculTest` (fake Ollama that answers per model, local `HttpServer`): the right model is asked, blocks land in the chat, usage under the
  right model, `/compare` with two, several and failing extra models, `/revise` with a note, tools (read tool, a change asks in Auto
  mode and a denial changes nothing, no capability means text only), `/takeover`, skill worker/reviewer/planner and the role table,
  the second server.
- `CoreTest`: the message predicates, `comparisonParts`, `due`, `turnFacts`, `ModelFit`, `chat_stats` lines, `SkillRoles`.
- `DesignTest`: the slash parsing and usage lines, the column layout of the split view.
- Tests never reach the real network: every model is a local fake.

## Decisions and why

| Decision | Why |
|---|---|
| Blocks are chat messages, not a side panel | they survive save/reopen/compaction and the chat model can use them |
| Plan is not a review | `/revise` and the hint must not fire on a plan |
| Escalation only by the user's choice | a stronger model costs time and memory; the user should know when it is used |
| Same tools as the worker, changes always ask | "same tools" was the choice; the safety comes from asking, not from taking tools away |
| The worker stays on the main server | the Oracul server is for the bigger model; mixing would hide where data goes |
| `oraculExtra` instead of a model list per command | one setting, ticked in a picker, works with `/compare model` for one-off use |
| Roles are per skill with a table as default | the need was "this skill must be right"; a global orchestration layer was not needed |
| Rendering is separate from the message | the split view is a display preference and must not change what is stored or sent |

## Not built (and what it would take)

- **`requires:` steps that name a role** (a step done by the reviewer): `Requirement` would gain a role and the gate would route that
  step; no skill needs it yet.
- **Remote providers** (OpenAI, Anthropic APIs): key handling and privacy of vault text have to be decided first; only Ollama today.
- **Parallel tools and multi-agent planning loops:** out of scope; the Oracul runs one short loop.

## Recommended starting setup

A 32 GB Mac with `qwen3.5:9b` as the worker: Oracul `qwen3:30b` (larger, a related family, a mixture-of-experts model that answers
in about 13 s including the load), judge `gemma4:12b` (another family, light, less biased towards either answer), *Automatic second
opinion* = On problems. Two big models do not stay loaded together on 32 GB: expect a model swap of several seconds each time the
Oracul or the judge runs (the picker marks these as `fits alone`).
