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
| **Router** | `routeVision` / `routeCode` set in Settings → Helpers | a message with an image goes to the vision model, one with code to the code model, by rules, no model involved | `Router`, `ChatSession.route` |
| **Judge verdict** | after an automatic opinion, when `judgeModel` is set (`oraculVerdict`, default on) | the judge says `AGREE` / `PARTLY` / `DISAGREE` and why, as a block under the opinion; advisory | `ChatSession.verdictOnOpinion`, `Oracul.verdictRequest` |
| **Goal** | `/goal <condition>` | the work starts; after every finished reply a checker (Oracul model, else the chat model) says `VERDICT: MET` or `NOT MET`; while not met the worker is sent on by itself, at most `goalTurns` times | `ChatSession.startGoal` / `checkGoal`, `Oracul.goalRequest`, `parseGoal` |
| **Flow** | Settings → Flow → *Flow* = Careful | every message goes through a fixed sequence: analyst → planner → worker → reviewer, each its own model | `Flow`, `ChatSession.analyse` / `plan` / `applyRoles` |
| **Memory fit** | the model pickers in Settings | each model is marked as fitting this machine or not, with a suggested Oracul and judge | `core/ModelFit.kt` |

## What the Oracul is shown of the conversation

A request to the Oracul, the judge or the planner is built from the last question, and the answer or attempts, plus **`Oracul.background(messages)`**: the last messages of the user and replies of the assistant before that question (all earlier messages by default, newest first: `oraculContextMessages` 0 = all, or a cap up to 200; `oraculContextChars`, default 3,000 each; each is shortened in the middle so the end of a reply, where its options sit, stays; the total is at most `Oracul.MAX_BACKGROUND` = 30,000 characters, because the window of these models is not known; no tool output, no Oracul blocks; a compaction summary counts), under the header `EARLIER IN THE CHAT (context only…)`. Without it a short follow-up such as "do option C" or "yes" meant nothing to the reviewer (found in a real chat: the stuck step-in declared "option B and C are undefined" and the worker believed it). Every request builder takes it (`reviewRequest`, `answerRequest`, `judgeRequest`, `stuckRequest`, `planRequest`). An Oracul block also tells the worker, in `CAVEAT`, that the reviewer saw only part of the conversation and that it should check the block against the chat before agreeing; `Oracul.body` hides that line from the reader and still reads older chats.

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
  takeovers, stepping in), `Judge` (the verdict) `Analyst` (the flow's analysis), `Controller` (its verdict) or `Planner` (the plan): `app.recordUsage(feature, chunk, model)`. The usage file keeps them, so
  *how many times each was called* survives restarts; Usage → By feature shows them, Usage → Breakdown has a panel *Oracul, Analyst, Planner, Controller, Judge* (`UsageStats.advisors`, `AdvisorsPanel`: calls, tokens, share of all tokens, average time, models) and `chat_stats` reports them (`ChatStats.spendLines`: spend per
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

## The two switches

- **Helper models** (`oraculEnabled`, default on; `/helpers`): the master switch for every model that is not the chat model: the Oracul and its opinions, the judge and verdicts, the flow, the specialists (`consult`) and the router. Off: `Oracul.model` and `Oracul.judge` answer blank, `Flow.of` is null, the router and `consult` do nothing and the status bars show no Oracul badge; `/oracul` says the helpers are switched off (`Oracul.missingModelMessage`). **The models stay set**, so switching back loses nothing. The goal check still runs, on the chat model.
- **Skill roles** (`skillRolesEnabled`, default on; `/roles`): the worker, planner and reviewer a skill names or the role table gives. Off: a skill is run by the chat model alone (`ChatSession.applyRoles` returns nothing, unless a flow is selected, whose own stages still apply). It is **independent** of the first switch, so the combinations are: both on (everything), helpers off + roles on (one model, plus skills with their roles), helpers on + roles off, both off (one model only).

## Models and roles

Resolution for a skill run: **the skill's own property → the role table in Settings (`rolePlanner` / `roleWorker` / `roleReviewer`) →
nothing** (`SkillRoles.withDefaults`). Roles apply only to a message that carries a skill (the next message goes back to the settings), unless `rolesScope` is `all`: then the table is a pipeline for every message.

| Role | Does | Server | Validated by |
|---|---|---|---|
| planner | writes a short numbered plan from the question and the skill's instructions, no tools; shown as a plan block that the worker is told to follow where it is right | Oracul server | installed on that server |
| worker | answers the skill run instead of the chat model | main server | installed, and `tools` capability when tools are on |
| reviewer | gives the second opinion when the run is done | Oracul server | installed on that server |

`applyRoles` runs in the first round's `prepare` (so the worker applies from round one) for a skill **chip**, and when the model
loads a skill itself with `use_skill` (worker and reviewer only: **a plan is written once, at the start of a message**). A model that is
not installed or cannot use tools is skipped with a message (`roleNote`, shown as a toast) and the settings' model is used; nothing is
ever written to the settings. The judge is only the judge: it sees all answers (`ANSWER 1` is the chat model's) and picks or merges.

## Flows (orchestration)

A **flow** (`core/Flow.kt`) is a fixed sequence of stages, each with its own model. It is the *sequence* block of a larger design (stage, sequence, router,
loop, check; see `docs/plan-next.md`, N10): this is the first step, and only the fixed sequence exists. The default is still the single chat model; no flow
runs unless *Flow* is set (`AppConfig.flow`).

| Stage | Model (first that is set) | Does | Block in the chat |
|---|---|---|---|
| Analyst | `flowAnalyst`, else the Oracul model | reads the request and writes what is really asked, what is unclear (with an assumption each), constraints and what a good result looks like; no tools | `Ψ Analysis · <model>` |
| Planner | `rolePlanner`, else the Oracul model | the plan, now also shown the analysis | `Ψ Plan · <model>` |
| Worker | `roleWorker`, else the chat model | answers, with tools as usual | the answer |
| Reviewer | `roleReviewer`, else the Oracul model | the second opinion when the run is done, whatever `oraculAuto` says | `Ψ Second opinion · <model>` |
| Controller | `flowController`, else the Oracul model | reads the request, the analysis, the answer and the review and says `VERDICT: DONE` or `VERDICT: REDO` with what must change; no tools | `Ψ Control · <model>` (`Verdict: done` / `Verdict: redo`) |

- **No second way to run a model.** The planner, worker and reviewer are applied through the roles of a skill run (`Flow.roles()` feeds
  `SkillRoles.withDefaults`; a skill's own properties still win). Only the analyst is new: `ChatSession.analyse` runs before `plan` in the first round's
  `prepare`, adds an *analysis block* (a user message, like a plan: `Oracul.MARK_ANALYSIS`, `isAnalysis`; advice, **not** a review, so `/revise` ignores it) and
  passes its text to `Oracul.planRequest(..., analysis)`.
- **Short messages** (under `PLAN_MIN_CHARS` = 60) are neither analysed, planned, reviewed nor checked, so "thanks" costs one model run. A skill chip is always planned.
- **The loop (redo).** After the reviewer, `ChatSession.control` asks the controller (`maybeConsult` runs reviewer then controller as one job, `autoConsult`, so Esc and a new message cancel
  it). `Oracul.parseControl` takes the first `VERDICT: DONE|REDO`; **text without one counts as done**, so a confused model never starts a loop. On *redo* the worker is sent back
  with `Oracul.REDO_PROMPT` (a user message, like `/revise`; the verdict block is in its history) and the answer is reviewed and checked again. At most `flowRedos` (default 2, at most
  `Flow.MAX_REDOS` = 5, 0 = verdict only) redos per message of the user (`flowRedos` in `ChatSession`, reset by a real message); when the limit is reached the last answer stands and a toast says so.
  A redo is the worker's own turn: same models, **no new analysis or plan** (`prepare` checks for `REDO_PROMPT`), and `Oracul.lastExchange` skips the redo and revise prompts so the
  reviewer and controller still see the user's original request.
- **An objective check (`flowCheckCommand`).** The controller's check is a model's opinion, not proof (see the caveats above), so a flow can also run a shell command after each answer
  (`./gradlew test`): `ChatSession.runCheckCommand` runs it in the project folder (else the vault) with `CheckCommand.run`. **Exit code 0** = passed, and the controller is told
  (`CHECK COMMAND` in its request); **anything else** (or a timeout) = a redo with the output tail as the notes, **without asking a model** (the redo limit applies; the block is a `Control` block
  titled with the command). It runs without asking, so it needs `toolShell` **and** `toolAutoShell` on (the same trust as auto-run); commands `ShellPolicy` refuses never run; otherwise it is skipped
  with a note. It also runs when the flow has no controller model.
- **Failures are notes.** An analyst that is not installed, returns nothing or fails leaves the analysis out (`roleNote`, a toast); the rest goes on.
- **Usage:** features `Analyst` and `Controller` (Usage → Breakdown panel *Oracul, Analyst, Planner, Controller, Judge*, `chat_stats`).
- **Cost:** up to four model runs per message, and on a local machine several large models swap in and out of memory (the memory advice in Settings applies to the
  analyst too). Not built yet: more presets.

## Judge verdict and goals

- **Judge verdict (the answer to "why did the judge run once").** The judge used to run only for `/compare` (and *Judge automatically*). Now, after every **automatic**
  opinion (`maybeConsult`), `verdictOnOpinion` asks the judge whether the opinion is right. It needs `judgeModel` set (a judge that is the Oracul model would grade its own
  opinion) and `oraculVerdict` on. Block `<model glyph> Verdict · <model>` (the judge's family glyph, as on a reply, not the Oracul's `Ψ`; `MARK_VERDICT`, advice, not a review: `/revise` ignores it), usage feature `Judge`. Manual `/oracul`, the
  stuck advice and the flow's reviewer get no verdict (the flow has its controller).
- **What happens after an agreeing verdict (`verdictAction`, Settings → Opinions → *When the judge agrees*).** `ask` (default): a dialog (`ChatSession.askWhatNow`, a `Question` like `ask_user`, with Arcana's heading) says the reviewer found problems and the judge agrees, and offers *Revise the answer now* / *Leave it as it is* / *Revise automatically from now on* (sets `verdictAction` to `auto`), or **a note of your own typed on the last row**, with which the chat model revises (`/revise <note>`). `auto`: the chat model revises by itself, once per message (`autoRevises`; a redo of the opinion on the revised answer does not revise again). `advice`: only the blocks and the `/revise` hint. Only an AGREE or PARTLY verdict triggers it; nothing is asked while a reply is being written or another dialog is open. Events `revise` (`auto`, `chosen`, `note`) feed the Team page.
- **Open todo steps (`todoGuard`, Settings → Tools → *Finish open todos*, on).** When the chat model ends its turn (`finishReply`) while its own todo list (`todo` tool) has steps that are not done, it is sent back **once** per message with `Oracul.todoNudge` (the open steps named); it may finish them or close the list. Not for skill runs (their gate does that) and not with tools off. The nudge is an internal prompt like `REDO_PROMPT`: it is not a new question (no new analysis or plan, not the "question" the reviewer sees). Event `todo-nudge`.
- **`/goal`** is the controller loop with a condition you write, as in Claude Code. `/goal <text>` sets it and starts the work (`startGoal` sends the goal as a message);
  `/goal` shows it, `/goal clear` drops it. After each reply `finishReply` waits for the reviewers (`autoConsult.join()`), then `checkGoal` asks the checker (usage feature
  `Controller`). **Met** clears the goal; **not met** (a text without a clear `VERDICT:` counts as not met, unlike the controller) adds a `Ψ Goal` block and sends
  `Oracul.goalContinue`. Limits: `goalTurns` (default 5) automatic turns per goal, reset by a message of yours; then it stops with a toast. Nothing runs while a reply is being
  written (`gen.state == Running`), so a redo or a queued message is never talked over. **The goal is also a standing reminder:** `Prompts.chatMessages(…, goal)` puts `THE GOAL OF THIS CHAT …` into the system prompt of every request (so it survives a long chat and a compaction), and the Chat status bar shows `◎ goal: …`. The goal belongs to the chat in memory: `/new` and opening another chat clear it, and it is
  not saved.

## Specialists as tools (`consult`)

The *subagent* way to use the same stages, as in Claude Code: with **Settings → Flow → Specialists as tools** (`flowTools`, off by default) and tools on, the chat model is offered one
more pseudo-tool, `consult` (`CONSULT_DEFINITION`, handled by `ChatSession.consult` like `ask_user`, not by the `ToolBox`). It names a `stage` (`analyst`, `planner`, `reviewer`; not the controller
or the worker) and writes a `brief`; the stage's model (`Flow.careful(config).model(kind)`: *Flow analyst*, *Skill planner*, *Skill reviewer*, else the Oracul model) is asked with the stage's own
instructions (`Oracul.consultRequest`) and **sees only the brief** (plus the usual earlier-chat header), and its text comes back as the tool's result: `Planner (model) says: …`. So the model
decides when, and most messages call nobody. Limits: `MAX_CONSULTS` = 3 per message of the user (`consults`, reset by a message), one specialist at a time, no recursion. A stage without a
model, a model that is not installed or a wrong stage name is an `Error:` result the model can read and work around. Usage goes to `Analyst`, `Planner` or `Oracul`; nothing is added to the chat
besides the tool call and its result. It is independent of *Flow*: both can be on (the flow then still runs its fixed sequence, and the model may consult inside the worker's turn).

## Router

The *router* block of the orchestration design, **rules first** (`core/Router.kt`): `Router.kindOf(text, hasImages)` says *image* when an image is attached, *code* when the text has a
fenced code block or a source file name (`.kt`, `.py`, `.js`, `.sql`, … not `.md`), else nothing. `routeVision` and `routeCode` (Settings → Helpers → *Route: images to* / *code to*) name the
model for each kind; blank = no routing. `ChatSession.route` runs in the first round's `prepare`, **after** the roles, and only when no worker is named yet (a skill's `worker:`, the
role table and a flow's worker win). The model must be installed, have the `vision` capability for an image and the `tools` capability when tools are on; otherwise the chat model answers and a toast says why (`roleNote`). A routed message shows `Routed to <model>: the message has …` (`routeNote`); the next message routes again from scratch. A redo or a goal turn routes by the message it
continues, so a vision answer is not redone by the chat model. A model-based selector is a possible later step; it is not built, and rules are enough to start.

## Interface

- **Bottom bar badge:** `Ψ <oracul> ⚖ <judge> · auto|always|manual`, the judge only when it differs; orange `Ψ <model>…` while it works;
  nothing when no Oracul model is set. The Chat status bar shows the same setup text when idle and an orange pill while consulting: `Ψ <model>…` for the Oracul, `⚖ <judge>…` while the judge works. Both bars build the text with `Oracul.statusText` so they cannot drift apart. The Chat bar builds the worker and the Oracul as one segment (`modelsSegment`): whoever works now is the first, filled pill, the other follows quietly (`waiting` for the worker); the quiet part is dropped when it would take more than half the bar. While a helper works **after** the main reply is done (reviewer, controller, judge, goal check), the same line appears under `✓ Done` (`ReplyStatus` also draws it when `consulting` is set and the reply is not running). The reply line under the chat (`ReplyStatus`) says `Ψ <model> is answering…` (`⚖ <judge> is judging the answers…` for the judge) with the Oracul's own seconds (`ChatSession.consultingSince`) while it works, instead of the waiting worker's thinking line. Esc: `ChatSession.cancelOracul` cancels `autoConsult` and `manualConsult` (the jobs of `/oracul`, `/compare`, `/takeover`) when the worker is not running; the `finally` blocks clear `consulting` and nothing is added. Over columns the comparison's title is drawn with each model name in its column's colour (`comparisonTitleRuns`, `partColor` shared with the pills). An automatic opinion or comparison (`autoConsult`) is cancelled when the user sends a new message, so it cannot outlive its answer and keep `consulting` set through the next turn. The bottom bar swaps the two badges the same way and greys the worker's; on the Chat screen it collapses the Oracul badge to its glyphs (`Ψ`, plus `⚖` when the judge is another model; orange with `…` while asking), because the Chat bar already names the models. The glyph is `Oracul.GLYPH`.
- **Its tool calls in the chat:** every call the Oracul makes (reviewing, advising a stuck worker, answering for a comparison) is a quiet two-line entry before its block: `Ψ model  tool  what it was about`, then `↳ ✓ how it ended` (`✕` when it failed, `you did not allow it` when you denied it). They are `OraculSteps` messages (role `oracul_step`, display-only): saved with the chat and copied like any entry, **never sent to a model** (a result would cost context on every later turn and a small worker confuses calls it never made with its own; what the Oracul concluded is in its block, which the worker does read). While a call runs the reply line says `Ψ model is using tool …`.
- **Blocks:** a heading `Ψ Second opinion · <model>`, the text, a closing `Checked with: read_note (facts.md), …` when it used tools (each call with its main argument; while a tool runs the reply line says `Ψ model is using read_note facts.md…`), and, while it is open, the
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
| `loopRepeats` / `loopCalls` | `5` / `3` | not the Oracul: the loop guard (functionality.md) catches repeats that fail nothing; it needs no model |
| `oraculContextMessages` / `oraculContextChars` | `0` / `3000` | how many earlier chat messages (0 = all, up to 30,000 characters in total) and how many characters of each the Oracul, judge, planner, analyst and controller are shown besides the last question (Settings → Helpers → Chat context) |
| `oraculJudgeAuto` | `false` | when an automatic opinion is due, run `compare()` (Oracul answer plus judge verdict) instead; mid-turn stuck advice and skill reviewers stay single opinions |
| `oraculTools` | `true` | the Oracul may use the worker's tools |
| `flow` / `flowAnalyst` / `flowController` / `flowRedos` / `flowCheckCommand` | `off` / blank / blank / `2` / blank | the selected flow (`off`, `careful`), the models of its analyst and controller (blank = the Oracul model) how many redos the controller may ask for (0–5) and an objective check command (blank = none; needs shell + auto-run); while a flow is on the role table applies to every message |
| `flowTools` | `false` | the chat model may call the analyst, planner and reviewer as the `consult` tool |
| `routeVision` / `routeCode` | blank / blank | the models the router sends a message with an image / with code to (blank = the chat model) |
| `oraculEnabled` / `skillRolesEnabled` | `true` / `true` | the master switch for the helper models (Oracul, judge, flow, specialists, router) and the switch for skill roles; independent |
| `verdictAction` / `todoGuard` | `ask` / `true` | what an agreeing verdict leads to (`ask` a dialog, `auto` revise by itself, `advice` only the blocks); send the model back once when it ends its turn with open todo steps |
| `oraculVerdict` / `goalTurns` | `true` / `5` | the judge's verdict after an automatic opinion (needs `judgeModel`); how many times `/goal` may continue the work by itself |
| `rolesScope` | `skills` | `skills` or `all`: with `all` the role table also runs for messages without a skill (`ChatSession.applyRoles(null)`; no plan under `PLAN_MIN_CHARS` = 60 characters) |
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
| Roles are per skill with a table as default | the need was "this skill must be right"; a global orchestration layer was not needed **at the time**: it was reopened (see *Flows*), and the role table is now also the base of the flow |
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
