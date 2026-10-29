package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.navigation.Hints

/** One page of the changelog: a theme of the work, newest page first. */
private class ChangePage(val tab: String, val title: String, val markdown: String)

/**
 * What has been built, so the work can be looked back on. Written by hand when something ships: add a bullet to the
 * newest page (or start a page for a new theme), keep it to what a user could notice, and say how to reach it.
 */
private fun changePages() = listOf(
    ChangePage(
        "Latest", "The latest work",
        """
# The latest work
Built on 24–25 October 2026. Last updated 28 October 2026.

## Cancel asks first
- `Esc` on a running reply (or an Oracul run) opens *Cancel this reply?*: `Enter` cancels, any other key keeps going, and the reply keeps streaming meanwhile. The double `Esc` is gone; **Settings → Console → Confirm before cancelling** turns the question off.

## Models as a pipeline
- **Flow: Careful** (Settings → Flow → Flow): every message goes through an **analyst** (what is really asked, what is unclear), a **planner**, the **worker** and a **reviewer**, each its own model. Short messages skip the analysis and the plan. New *Analysis* block in the chat and an *Analyst* line in Usage. First step of the orchestration work.
- **Router**: messages with an image go to a vision model and messages with code to a code model (Settings → Helpers → Route), by plain rules; a toast says `Routed to …`. A skill's or flow's own worker still wins.
- **Generated files at a glance**: a `✦ 3 changed` pill in the Chat status bar counts the files and notes the model wrote or changed in this chat, and `/artifacts` lists them (1-9 opens a note in the Library).
- **Memory without duplicates or dead paths**: `remember` skips a fact that is already there and refuses a `[[link]]` or `.md` path that does not exist. `/memory check` lists repeats and dead paths and asks before removing the repeats.
- **Library settings** (new Settings → Library page): which view it opens on (**All** by default; it used to keep the last view a link had set, which is why it opened on Tags), the list/preview width, sort order, preview pane on/off and tab counts. *Library as a tree* moved there from Vault.
- **The whole chat is sent**: *Chat history* is now 0 = everything (it was the last 20 messages): the chat is compacted when the window fills (Auto-compact, 80 %), not cut. The Oracul and the other models see all earlier messages too (up to 30,000 characters). **A config saved before keeps the old 20 until you set Settings → Console → Chat history to 0.**
- **Model state**: the *Waiting for the model…* line now says why (loading the model into memory, reading about N tokens, N% on the CPU, memory tight) and the Chat status bar has a live `● loaded 7.7 GB 100% GPU` / `○ not loaded` indicator.
- **Hardware warnings in the footer**: a red `⚠ 35% on CPU: slow, lower the context` badge (and orange `⚠ ctx 256k > model 128k`, `⚠ ctx 4k is small`) next to the model badge on every screen, short so the chat status bar stays free (the explanation is under Settings → Ollama → Context window) when a loaded model does not fit in memory and runs partly on the CPU; this is what made a long prompt end in `Cannot reach Ollama: request timed out`.
- **A verdict wears its model's icon** (`◆ Verdict · qwen3.5:9b`), like a reply, instead of the Oracul's Ψ.
- **`Esc` then `f`** (or Ctrl+P → *Files and notes changed in this chat*) opens the list of files and notes the model changed, the same as `/artifacts`.
- **Select & copy works per item**: every tool call and every tool result is its own entry now (before, a reply that only called tools put its header on the next message's entry, so selecting one item lit up all of them and the tool lines could not be selected).
- **`?` opens the key map in Chat even while you are typing**, as the footer promises, when the message field is empty (with text in it, `?` is still a question mark).
- **Quieter status bar**: normal facts (model, tool mode, memory, tokens, price, time, skill progress) are plain coloured text now; a filled background is kept for what needs attention (warnings, a helper model working, auto-run / auto-search, the goal, changed files).
- **Settings made readable**: the 30-row Oracul tab is split into four: **Helpers** (the master switch, the models, the router, chat context), **Opinions** (when the Oracul speaks, verdicts, stuck rules), **Flow** (the flow, specialists, `/goal`) and **Skills** (skill roles). Each tab says in a line what it is for, rows whose switch is off are greyed with `· off`, and the tab bar wraps so no tab is cut off in a narrow window.
- **Two switches**: **Helper models** (`/helpers`) turns every model but the chat model off at once (Oracul, judge, flow, specialists, router; their settings are kept), and **Skill roles** (`/roles`) turns the skills' worker, planner and reviewer off. Independent, so one model alone, or one model plus skill roles, can be tried.
- **Usage → Team**: a new page about the orchestration: how much of the tokens and the price the helper models took, per-model speed, how often the flow was accepted at the first check, redos, the judge's agree / partly / disagree, goals met, routed messages, consults, cancels, compactions, memory duplicates. New `events.jsonl` records what token counts cannot show.
- **The logo keeps animating while a helper model works** (reviewer, controller, judge, goal check, compaction), not only while the main model writes.
- **Nothing hidden in a narrow window**: the Chat status bar now wraps onto more rows instead of dropping facts (and is no longer left out on very short windows). New `Σ … all models` figure: the tokens of every model this session (the arrows are the chat model only; the price is worked out from Σ). While a helper model works after the reply is done (reviewer, controller, judge, goal check) a progress line says which and for how long.
- **Compacting keeps more**: the latest turns that fit in 30% of the context window stay word for word (it was just the last two); Settings → Console → Keep after compacting. Settings and the status bar now warn when the context window you ask for is larger than the model is built for (it wastes memory and can make a Mac swap).
- **Specialists as tools**: with the setting on, the chat model can call the analyst, planner or reviewer as a tool (`consult`) when a task is hard, the way Claude Code uses subagents (up to 3 times per message).
- **Flow check command** (Settings → Flow): a shell command, for example `./gradlew test`, runs after each answer of the flow; a failing exit code sends the worker back with the output (no model asked), a passing one is told to the controller. Needs shell commands and auto-run on.
- **`/goal <condition>`** (like Claude Code): sets a goal that stays the main idea of the chat (in every request, `◎ goal` in the status bar) and starts the work; a checker runs after every reply and the work goes on by itself until the goal is met or *Goal turns* (5) are used. `/goal` shows, `/goal clear` drops it.
- **Judge verdict**: why the judge ran only once: it only ran for `/compare`. Now it also says AGREE / PARTLY / DISAGREE on every automatic opinion (needs a Judge model; Settings → Opinions → Judge verdict on opinions).
- **`/flow`** switches the flow on and off from Chat, the reply line names the working stage (`Ψ <model> is planning…`) and the status bars show `⇉ careful`.
- **Flow controller and redo loop**: after the reviewer, a **controller** model says *done* or *redo*. On redo the worker is sent back with what to fix (the reviewer and controller check again), up to **Flow redos** times (default 2, 0 = verdict only). New *Control* block, *Controller* line in Usage, Settings → Flow → Flow controller / Flow redos. A confused controller never starts a loop: no clear verdict counts as done.
- **Settings → Skills → Roles apply to → Every message** runs the planner, worker and reviewer for all chat, not only skills: a plan first (messages of 60+ characters), the worker answers, the reviewer checks.

## Usage became a dashboard
- Five periods (session, today, 7 days, 30 days, all time) and three pages: **Overview**, **Breakdown**, **Recent replies**.
- Overview: totals tiles, a column chart of tokens by hour or day, and insights (busiest day, peak hour, streak, week on week, typical and slowest reply, context load).
- Breakdown: by feature and by model with their share, when in the day you use it, and the speed trend. Recent replies: a table with tokens, time and tok/s.

## Chat got better to type in
- **Multi-line message field**: grows to a quarter of the screen with a **scrollbar**; `Alt+Enter`, or a `\` before Enter, starts a new line; Up/Down move between lines.
- **Pasting long text works**: a paste that lost its beginning *or its middle* is typed back whole from the clipboard.
- **Context gauge** in the Chat status bar (`ctx █░░░░░░░░░ 3% 3.9k/131k`, orange and red as it fills). Set the window in Settings → Ollama → *Context window*.
- A reply that ended in a tool call shows a quiet `step` line, so only the final answer says **Done**.

## More tools
- **A live thinking pane**: `/thinking` (or Settings → Console → *Thinking pane*) adds a split window under the Chat conversation that follows what a reasoning model is thinking while it streams, word-wrapped, with the character count, and keeps showing the last thoughts when the model is idle. *Thinking pane height* sets how many rows it takes (3-15). It is separate from the `/compare` split view (columns of answers).
- **Web search no longer dies on a DuckDuckGo block**: DuckDuckGo answers some connections with a human check (HTTP 202). Arcana now tries the other backends you have set up (Brave key, SearXNG address) automatically, leaves DuckDuckGo alone for 10 minutes after it blocks (every new try makes a block longer), and when nothing answers tells the model not to retry and tells you how to fix it: Settings → Tools → Search backend → Brave (free key) or SearXNG. Without either, `web_search` only works while DuckDuckGo allows your connection; `fetch_url` and the skills' own APIs do not need it.
- **The `Arcana/` folder is gone from the default layout**: the default *Output folder* (where notes Arcana writes for you go) is now **Research** instead of `Arcana`, which was confusing next to the hidden `.arcana/` and had collected unrelated notes. Arcana's own reference notes (LLM Reference, Reference Documentation, Distribution and Setup) live in `.arcana/reference/`; an existing config keeps the folder it saved. Links from visible notes into the hidden folder cannot resolve in Obsidian: the reports were changed to plain names and the coingecko skill now writes its name as text (documented in `docs/skills.md`).
- **Nothing is cut silently any more**: results of tools from earlier questions used to be trimmed to 1,200 characters when you wrote again, so the model could answer from half a file or read it again without you knowing. Now they are kept whole (Settings → Tools → *Old tool output*: Whole, the default, or Shorten for small context windows; a shortened result says how much was kept and that the tool can be called again). And when a tool itself leaves something out (a long file, a long list, shell output, a web page) the chat shows an orange `✂` line under the result saying what, instead of only a character count.
- **A model that asks for the same thing twice is answered, not obeyed**: in a saved-chat check 23% of the model's reads were exact repeats. Now an identical read, search or web fetch in the same turn, with nothing changed in between, is not run again and does not ask you again for the same address: the model is told the result is already above in the conversation. The memory is short-lived on purpose: it expires after 45 seconds (Settings → Tools → *Remember repeated reads*, 0 = off; a note you edit in Obsidian meanwhile is read again), a change, a command, a `wait` or your next message clears it, a failed result (an HTTP error, a rate limit) is never remembered so a retry really runs, and the clock tools and `wait` always run. Nothing is cached between turns, because files change.
- **A task list for the model (`todo`)**: for a job of three or more steps the model writes its plan as a checklist (`☐ to do`, `▸ doing`, `☑ done`) and ticks it off as it goes. You see the list in the chat and a `☑ 2/5` pill in the status bar; the tool changes nothing and asks nothing. It is a visibility aid, not a gate: a skill's required steps still decide when a run may end.
- **Settings shows where everything lives**: Settings → Vault has two rows, *Where Arcana's own files are (this vault)* (`.arcana/`) and *Where YOUR files are (every vault)* (`~/.config/arcana/`), and *Skills folder* and *Memory file path* show the folder or file really in use instead of "(automatic)". *Output folder* now says what goes there (visible content) and what does not (skills and memory).
- **Arcana's own files are kept apart from your content**: skills and the memory file now live in a hidden `.arcana/` folder in the vault (`.arcana/skills/`, `.arcana/ARCANA.md`), so Reports, Research and your notes are all that Obsidian shows. New vaults get it from the setup; an older vault keeps `Skills/` and `ARCANA.md` and works as before until you run `arcana --migrate`, which moves them (never overwriting, and saying what it moved). Skills and a memory file in `~/.config/arcana/` apply to every vault: a vault skill of the same name wins, and the global memory comes first. *Settings → Tools → Skills folder* is now empty = automatic, or names any folder.
- **A DeFi pair report with a Planner and a Validator**: the vault skill `defi-pool-analysis` takes a token pair in any format (`ETH/USD`, `USDT-BTC`, `WBTC:WETH`), finds every pool of it on seven chains (DexPaprika), ranks them, reads the price history (GeckoTerminal) and writes ONE report with ASCII charts (scores, TVL, fee yield, a price-and-forecast chart, a range ladder) that ends with **which pool to use and why**. The flow is controlled in the skill, not in code: a **Planner** writes the plan, the **Worker** follows ten required steps and keeps a `todo` list, and a **Validator** (the script's `validate`, which must print `VALID=yes`, then `FINAL_OK=yes`, plus the `reviewer:` model) checks the saved note against the data. Per pool it reads the APR history, forecasts the fee return for your horizon with a band calibrated against the past, subtracts expected impermanent loss (bad / typical / good, per $100), and gives a **Confidence score with the reasons** (history length, how often the forecast held, how erratic the volume is, pool age). Details in `docs/skills.md` section 9a.
- **Queued messages**: type while the model works and press Enter: your message waits above the field (`⏳ queued`). In a tool turn it joins at the next step, so a correction or a clarification changes what the model does next; otherwise it is sent right after the reply. **Backspace on an empty field removes the newest queued message**; Esc Esc (cancel) or a failed reply puts the queue back in the field. Commands and attachments cannot be queued.
- **At the step limit (setting)**: Settings → Tools → *At the step limit*: **Ask** (the dialog, as before), **Auto** (more steps at once, up to 3 times per question, then it asks; logged) or **Stop** (the model answers with what it has). Extra steps never lift an approval: each change, command and web address still asks (Mode Auto aside).
- **`fetch_url` follows the search rule**: Settings → Tools → *Search without asking* is now *Search and fetch without asking*. When it is on, `fetch_url` runs without a dialog just like `web_search` (private addresses are still refused). `http_request` and the Oracul's calls still ask every time. The setting warns that an address can carry data out.
- **Usage shows the advisors**: Usage → Breakdown has a panel *Oracul, Planner, Judge* with calls, tokens, share of all tokens, average time per call and the models, so you can see what the second opinions cost.
- **`/compare` with more than two models**: Settings → Helpers → *More Oracul models* (comma-separated). `/compare` asks the Oracul model and each extra model, and the judge compares every answer. A model that cannot answer is left out with a note.
- **Oracul server**: Settings → Helpers → *Oracul server* sends the Oracul, the judge, `/takeover` and a skill's reviewer to another Ollama server, for example a bigger machine on your network. The model pickers list what is installed there. Blank = the same server as the main model. What the Oracul reads from the chat goes to that server.
- **The Oracul steps in when the main model keeps failing**: with *Automatic second opinion* on (default), after every 4th failed tool call in one turn (a bug fixed over and over, a command that keeps erroring) the Oracul reads the attempts so far and says what is really going wrong and what to try instead; its advice goes into the main model's loop so the next try starts from it. Three turns in a row with a failed call also trigger a review after the reply. It works with the same tools and the same asking as before, and does nothing when the Oracul is off.
- **Tab completes a command**: in the `/` menu, Tab now puts the highlighted name into the message field (`/revise `, `/read_note `, `/forecast `) so you can add your text and press Enter, like completion in a shell. Enter still runs the command, attaches the skill, or writes the tool request, as before.
- **Better `chat_stats`: token spend per model and in total**: ask "how much did this cost?" and the model now gets the tokens per model since Arcana started (read + generated, requests, what each model was used for, estimated cost), the total, and how many times the Oracul, the judge and the planner were called in all the history. The judge and planner are now stored as their own entries in Usage (Usage → By feature), so these counts survive restarts.
- **Stuck thresholds are settings**: Settings → Opinions → *Stuck: failed calls* (never, 3, 4, 6, 8; default 4) and *Stuck: failing turns* (never, 2–5; default 3) set when the Oracul steps in on a main model that keeps failing.
- **The reply line explains a frozen token count**: in a tool loop every round starts silent while the model reads the whole conversation again (minutes on a big context, with the count standing still). The line now says `<model> is reading the conversation again…` with that round's own seconds, until its first token arrives.
- **The memory pill warns when the file is cut**: when `ARCANA.md` is longer than the 8,000 characters that are sent, the status bar shows an orange `⚠ memory 8k of 11k: end cut` instead of the green `✎ memory`, so lost rules do not go unnoticed.
- **The Oracul now sees the conversation, and a long tool run keeps your request**: found in a real chat. A short follow-up ("do option C") reached the Oracul without the earlier message that defined the options, so it declared them undefined and the worker believed it. The Oracul, the judge and the planner now also get the last messages before the question (Settings → Helpers → *Chat context*: how many messages, default 6, up to 200, 0 = none, and how many characters of each, default 1,500; the total is their product, nothing else cuts it), and an Oracul block tells the worker that the reviewer saw only part of the chat. Separately, *Chat history* counted tool calls and results as messages, so a long tool run (over about 30 messages) dropped your own request from what the model was sent; the current turn is now always sent whole.
- **Docs: where the Oracul sits among agent patterns**: `docs/oracul.md` maps the Oracul, the judge, `/takeover` and the skill roles to the usual names (critic, LLM-as-judge, escalation, planner / executor) and lists the caveats.
- **Search without asking (optional)**: web access asked every time, even in Auto mode, because a query can carry data out. Settings → Tools → *Search without asking* now lets `web_search` (only) run at once; pages and HTTP requests still ask for every address, and the Oracul always asks. Off by default; the status bar shows an orange `↗ auto-search` while it is on.
- **Memory advice for the models you chose**: when the main model, the Oracul, the judge or a skill's planner / worker / reviewer does not suit this machine's memory, Settings says so under the row and right after you save it, with a better installed model (`⚠ beta:2b is too big for this memory (72 GB needed of about 24 GB usable)… Better: alpha:1b`). A model that fits only when the main model is unloaded gets a quieter note. The Chat status bar shows an orange `⚠` after the Oracul when it is too big. Nothing is shown when the Oracul runs on another server.
- **Docs: how skills compare with other systems**: `docs/skills.md` and the Skills Guide now say what is common practice (skills as instruction folders, plans, stop guards) and what is Arcana's own (steps enforced in code by the completion gate).
- **A cut note is never silent**: when a note found by *Answer from notes* is longer than *Chars per note*, the model is told its text is cut (so it can read the rest with `read_note`) and the answer says `⚠ Only the start was read of: …`. A note you attach with `@` that is longer than 60,000 characters now asks before it is sent: the first 60k, the whole note, or cancel (your text comes back).
- **A little air above the footer in Chat**: one empty row now sits between the Chat's last row (its key hints) and the bottom bar, so they no longer touch. The conversation is one row shorter to make room; very small windows keep the old layout.
- **Clearer folder settings**: *Extra folders* is now *Allowed folders* (where the model may read and write besides the vault: `~/Projects;~/Documents`, or `~` for the whole home folder) and *Project folder* is now *Working folder* (optional: the one folder you work in right now, where relative paths and commands start, with its own instruction file and a status-bar pill). The working folder is allowed automatically; it grants nothing outside itself. The settings and their saved values are unchanged.
- **A bigger memory file**: the first 8,000 characters of `ARCANA.md` are sent to the model in every chat (it was 4,000). The text is part of every request, so a long file uses part of the context window each time; the `✎ memory` pill shows how much is sent.
- **Esc cancels the Oracul**: while the Oracul or the judge works on its own (an automatic opinion, `/oracul`, `/compare`, `/takeover`), Esc (after the *Cancel?* question) stops it and nothing is added. While it works inside the worker's turn Esc asks to cancel the turn.
- **Coloured model names in a comparison's title**: over the columns the names in `Ψ Comparison · A vs B, judged by J` wear the colour of their column pill, and the judge's is the verdict's orange.
- **No stale Oracul pill**: an automatic opinion on the previous answer kept running (and the Oracul pill stayed lit) through your next question, and for minutes if the Oracul model is slow. A new message now cancels it, since you have moved on.
- **Judge automatically (optional)**: Settings → Opinions → *Judge automatically*. When an automatic second opinion is due, the Oracul answers too and the judge compares both answers, as `/compare` does, instead of one opinion. Off by default: it is three model runs instead of one.
- **The judge is visible**: while the judge compares the answers of a `/compare`, the reply line says `⚖ <judge> is judging the answers…` (the status bars already showed `⚖`).
- **The reply line names the model that works**: while the Oracul or the judge answers inside a turn, the line under the chat said `<worker> is thinking…` with the worker's token count. It now says `Ψ <model> is answering…` with the Oracul's own seconds.
- **One place for who is working**: the main model and the Oracul are now one block in the Chat status bar, with the model that works right now first. While the main model works it is the filled pill with the Oracul's setup quietly after it; while the Oracul or the judge answers, its orange `Ψ model…` pill comes first and the main model follows as `waiting`. The bottom bar swaps the same way (the main model's pill turns grey); in Chat it shrinks to just the glyphs (`Ψ ⚖`, orange with `…` while it answers) since the status bar already names the models. On a narrow bar only the pill stays.
- **The Oracul in the Chat status bar**: when an Oracul model is set, the bar under the message field now shows it all the time (`Ψ qwen3:30b ⚖ gemma4:12b · auto`), not only the global bottom bar. While it works the pill turns orange (`Ψ model…`, and `⚖ judge…` while the judge is the one working).
- **Comparisons side by side**: a `/compare` result is now drawn in columns, the way ChatGPT shows two answers: each answer under a coloured name pill (your main model, then the Oracul models with `Ψ`), a dim bar between the columns and the judge's verdict across the full width behind an orange `⚖ Verdict` pill. Needs a window of about 80 columns (two answers) or 120 (three); narrower ones stack them. Settings → Console → *Comparison view* switches back to stacked.
- **Usage examples in the `/` menu**: the highlighted command, skill or tool gets a second line showing how to type it with its text: `e.g. /revise focus on the edge case`, `e.g. /read_note Trip Planner`, `e.g. /<skill> <what you want>`. Tools without a prepared example show their required arguments.
- **`search_notes` no longer looks broken**: a query made only of short or very common words ("the", "AI") used to answer "No notes matched" and a model concluded the tool was broken. Now such a query is searched as written, and a real miss says which words were searched, that the vault has N notes, and to try other words or `grep_files`.
- **Slash commands take text**: `/revise now`, `/oracul qwen3:30b` (ask that model once), `/compare model`, a skill or tool typed out with its request (`/forecast ETH`) all work; a command that takes no text says so, and a near miss names the commands you may have meant. Before, anything after the command gave "Unknown command".
- **Quieter action keys inside screens**: the keys listed under a screen's input or list (`Enter Send · @ Note · Ctrl+S Save to vault`) are now plain coloured text with a dim dot between them instead of filled buttons, so they stop competing with the status bar, and every screen gains a row. Dialogs and questions (approvals, `y` / any other key confirmations, the setup wizard) keep the filled buttons.
- **A better Oracul icon, memory hints and Oracul in `chat_stats`**: the Oracul is now `Ψ` (psi: the seer; it shared `◇` with the phi model family). The model pickers in Settings say whether each model fits this machine's memory (`fits with the main model`, `fits alone`, red `too big for this memory`) and mark a `★ suggested` Oracul and judge, so a choice does not run out of memory or fall back to the slow CPU. `chat_stats` now tells the model which other models were consulted in the chat (second opinions, comparisons, plans, takeovers) and what each spent. The bottom bar and Chat status bar show `Ψ model…` while the Oracul works.
- **Planner role, role table, tick-box model picker**: a skill may also name `planner:`: that model writes a short plan before the worker starts (shown as a `Ψ Plan` block the worker follows). Settings → Helpers has a role table (*Skill planner / worker / reviewer*) for skills that name none, and *More Oracul models* is now a list where Space ticks several models.
- **Skills can name their models**: add `worker: qwen3:30b` and/or `reviewer: gemma4:12b` to a skill's properties. The worker runs that skill instead of the chat model, the reviewer gives a second opinion when the run is done. Only for that message; the settings never change. A model that is not installed or cannot use tools is skipped with a message.
- **`/takeover`: the Oracul finishes a failed turn**: when the main model fails a turn (an error, or an empty reply it could not fix) and an Oracul model is set, a message offers `/takeover`. The Oracul continues from the same chat with its own model and tools (every change still asks) and its answer appears as a normal reply headed `Ψ model took over this turn`. Never automatic.
- **`http_request`: a gate for HTTP**: models used to reach for `curl` in a shell to POST or send an API key, where nothing checked it. Now `curl` and `wget` are refused in `run_command`, and the model uses **`http_request`** (GET, POST, PUT, PATCH, DELETE, HEAD, with headers and a body). Every request asks first and shows the whole thing: the address (a `?api_key=` value hidden), the headers (`Authorization`, cookies and tokens hidden but named), the body, who gets the data, and a warning when credentials go along. Only public addresses are reachable (never your own computer or network), the body is limited to 200,000 characters, redirects of a POST are reported and never followed. Same switch as `fetch_url` (Settings → Tools → Web pages).
- **The Oracul can use tools**: Settings → Helpers → *Oracul may use tools* (on by default). The Oracul model gets the same tools as the main model to CHECK an answer (read the files, search the notes, run a harmless check), and its block says what it used (`Checked with: read_note`). Reading follows *Read without asking*; **every change, command and web access asks you, even in Auto mode**, in a dialog marked `Ψ Oracul`. It cannot ask for more power (no `ask_user`, no mode changes). A model without the tools capability, or Tools in Chat off, keeps it text only. All dialogs now queue, so the Oracul's and the main model's never collide.
- **Oracul in the bottom bar, and `/revise`**: next to the model pill the bottom bar now shows the Oracul (`Ψ qwen3:30b ⚖ gemma4:12b · auto`, the judge shown when it is another model; orange while it works; nothing when no Oracul model is set). `/revise` closes the loop: after a second opinion the main model revises its last answer with it, using its tools if it needs to check. An opinion that nobody has answered yet ends with a hint, "/revise: the main model corrects its answer with this · or just carry on" (nothing is ever applied by itself). How it is built: `docs/oracul.md`.
- **Oracul: a second opinion from another model**: Settings → **Oracul** takes an *Oracul model* and a *Judge model* (picked from your installed models). `/oracul` asks the Oracul model to read the last question and answer and say what is wrong or missing; `/compare` makes it answer the same question and the judge compares both answers and writes the best one. The result is a `Ψ` block in the chat that the worker model also sees when you go on. *Automatic second opinion* (default *On problems*: two failed tool calls, or a skill that skipped a step; or *Always*) asks for it by itself once an Oracul model is set. Local Ollama only (it can use tools too: see the entry above). Its tokens show in Usage as `Oracul`.
- **Settings → Storage** shows what Arcana keeps on your disk and clears it on request: backups of files the model changed, the trash of what it deleted (outside the vault, and the vault's own `.trash`), chat history, saved sessions and the usage logs, each with its file count and size. Enter asks first; backups and trash can be pruned to what is older than 30 days instead of everything. Notes and settings are never in the list.
- **First-run setup and `arcana --init`**: with no vault chosen, Arcana opens a short setup instead of an error: pick your Obsidian vault, then tick what to create in it (the memory file `ARCANA.md`, the `Skills/` folder, a Skills Guide, the starter skills, the output folder). It only creates what is missing and never touches a file that is there. Run it again any time from the palette ("Set up the vault"), or without a screen: `arcana --init ~/Obsidian/Vault --skip skills`. `make dist` builds `arcana.zip` to hand to someone else.
- **Starter skills**: `decompose` turns a pasted todo list into a plan: it looks at what exists, asks you the open decisions with `ask_user`, saves the plan as a note and then works through it one item at a time (adapted from CosmoCLI's skill). Pick it with `/` in Chat.
- **Sounds and a louder Chat tab**: Settings → **Sounds** can play a sound when the model waits for you (an approval or a question), when a reply finishes and when it fails: macOS system sounds, terminal bells or a file of your own, with a volume; ←/→ plays the sound as you choose it. They are off by default and by default play only while another screen than Chat is showing (*Play when*). The Chat tab shows a pulsing `!` for an approval and `?` for a question from every screen.
- **Project context**: Settings → Tools → *Working folder* (called Project folder at first) points Arcana at a folder you are working on (a web project, a repository). The model is told what is in it, may read and (with your approval) change its files, relative paths and commands start there, and the project's own `ARCANA.md` / `CLAUDE.md` / `AGENTS.md` is passed on as instructions. The status bar shows `▣ name` while one is set. Vault tools still work on the vault.
- **Choose where the memory file lives**: Settings → Prompts → *Memory file path*. Empty keeps `ARCANA.md` at the vault root; `Notes/Memory.md` puts it in a vault folder; `~/Documents/arcana-memory.md` puts it anywhere on your computer. `/memory`, the `remember` tool and the status bar all follow it, and a missing file (and folder) is created from the template.
- **`/compact` and auto-compact**: when a chat gets long, `/compact` asks the model to summarize the older part; the summary appears in the chat under a dim divider and the model is sent the summary plus your last two messages instead of everything. Nothing is deleted: the old messages stay on screen and in saved notes. *Auto-compact* (Settings → Console, default at 80% of the window) does it for you right before a message when the context is nearly full; `◌ compacting…` shows in the status bar.
- **The context window is the real one**: Settings said `256k` while Chat said `262k` (the same 262,144 tokens counted two ways); both now write `256k`. With *Model default* Chat used to measure against the model's maximum, but Ollama runs with its own, often tiny, window; Arcana now asks Ollama (`/api/ps`) what the model was really loaded with and counts against that. A window under 32k shows an orange `⚠ ctx 4k` in the status bar, and Settings → Context window says "Right now: Ollama runs … with a window of …".
- **What the tools cost**: Usage has a new **Tools** page (`Tab` switches pages). Per tool it shows how often the model called it, about how many tokens its results put into the conversation (the part of a prompt that comes from tools, biggest first), the average time, and how many calls failed (`✕`) or were denied (`⊘`). It follows the period you pick; the history is kept in `tool-usage.jsonl` and cleared with `c`.
- **`chat_stats`**: the model can read how much this chat has used: model, tokens in and out, estimated cost, time spent generating, tool calls by name and how full the context window is. Ask "how much did this chat cost?" and it answers from real numbers.
- **Tags**: new tools `add_tags` (adds tags to a note and keeps its own; asks first) and `list_tags` (the vault's tags with counts, so the model reuses them). The Library's **Tags** tab now also finds tags an agent wrote as `tags: a b`, `tags: #a #b`, `tag: a` or `Tags: [a]`, which used to be missed or counted as one odd tag.
- **Wrong tool names get a hint**: when a model calls a tool Arcana does not have (`http_request`, `curl`, `ls`, `grep`, `head`…) the error now says which tool does that job (`fetch_url` reads pages; anything else goes through `http_request`; `curl` is refused), so it stops retrying the same name.
- **`cat`**: prints a file with line numbers (like `cat -n`), the way small models expect from a shell; it shares `read_file`'s limits. `git_diff` on a folder that is not allowed yet now asks "Allow this folder?" like `run_command` does.
- **Save to vault can overwrite**: in the `Ctrl+S` dialog, `Ctrl+O` lets you pick a note that already exists and replace it with the whole chat (or the last answer). You confirm first, and the old text is kept in the vault's `.trash`.
- **`request_mode`**: the model can ask to switch the tool mode (Ask, Auto or Plan) with a reason, for example Auto before a long series of edits or Plan to investigate first. You are always asked ("Switch to Auto mode?"); a refusal keeps the current mode.
- **Auto-run for shell commands**: Settings → Tools → *Run commands without asking* (or `/autorun` in Chat) lets commands run without your `y`. A red `▶ auto-run` pill in the Chat status bar says it is on; `sudo`, `rm -rf /` and the like are still refused.
- **Changes show as a diff before you allow them**: when the model wants to `edit_file` or overwrite a file with `write_file`, the approval panel shows what would really change, like `git diff` (`@@ -3,5 +3,5 @@`, two lines of context, red `-` and green `+` lines), not just the new text. A long diff is cut to fit the screen with a "… N more lines" note; a file that is not text or is over 500 KB falls back to the plain preview.
- **Thinking is kept apart from the answer**: what a reasoning model thinks is stored with its reply and shown as a dim `◌ thinking · 412 words` line above the answer (Settings → Console → *Thinking in Chat*: Summary, Full or Hidden). It is never copied with the answer and never sent back to the model.
- **Library gives the note more room**: the notes list takes 30 % of the width (it was 42 %) and the note preview the rest, with the divider line under its header running the whole width.
- **Who is speaking**: in Chat, replies are labelled `◆ qwen3:30b` and your messages `● You`; the glyph follows the model family (`◆` gemma, `◈` qwen, `▲` llama, `◐` mistral, `◇` phi, `◉` deepseek, …) and the status bar uses it too. The name is always exactly what Ollama calls the model.
- **Empty replies get a second chance**: a model that answers with nothing (after a tool result or a finished skill) is asked once more, out of sight, for a short answer; only a second empty reply shows "The model returned an empty response."
- **Cancelling a reply asks first** (replaced the double `Esc`): `Esc` in Chat opens *Cancel this reply?*, `Enter` cancels, any other key keeps going. Settings → Console → *Confirm before cancelling* turns the question off.
- **Real model names**: the thinking line in Chat shows the model exactly as Ollama names it (`gemma4:12b is thinking…`), the same as the status bar, not a prettified "Gemma4".
- **Git tools**: `git_diff` (the patch, `stat`, or just the file names; staged or not; one path), `git_status` and `git_log`. All read-only and without approval, so they also work in Plan mode; a commit or push still goes through `run_command`, which asks.
- **`run_command`** (shell), off by default, every command asks first; harmless ones such as `git status` run at once; `sudo`, `rm -rf /` and similar are never run.
- **`ask_user`**: the model can ask you a question with options (a quiz or a decision); **`delete_path`** and **`move_path`** always ask first, even in Auto mode, and a deleted item goes to a trash folder you can restore from.
- **`fetch_url`** reads a public web page (HTML as readable text, JSON, PDF), off by default; every address asks first and is shown in full; localhost and your own network are refused, redirects are checked hop by hop.
- **`web_search`**: the model can search the web (DuckDuckGo by default, no key; SearXNG or Brave in Settings → Tools). It asks before every search, shows what it sends, and reads a result with `fetch_url`.
- **`grep_files`** (search inside files), **globs** in `find_files`, and **line ranges** in `read_file`.
- **`current_time`**: the date and time in any time zone, so the model does not guess; chats also start with today's date.
- **Chat opens unfocused**: opening the Chat tab no longer drops you into editing; press `Enter` to start typing (`Esc` leaves the field again). `/new` still puts you straight into the field.
- **The Library is a tree**: your notes appear in their folders, which fold and unfold (`Enter`, `Space`, `h`/`l`; `+` unfolds all, `-` folds all). Folders are cyan with the number of notes inside; `t` switches to the old flat list, and the filter lists matches flat with their paths. The open folders are remembered while Arcana runs, and jumping to a note from the Brain screen unfolds the way to it. Settings → Vault → *Library as a tree*.
- **A Skills page in the Guide and a full architecture document**: the Guide now has a page that explains skills for users (how to use one, how to read the progress badge, what Arcana does behind the scenes, what to do when something looks wrong), and the repository has `docs/skills.md` (layout, properties, the life of a run, the required-steps gate, limits, tricky moments, code map). Opening a chat from the history no longer keeps the previous chat's skill badge.
- **Skills can require steps**: a skill lists its required steps (`requires:` in its properties) and Arcana watches them (any skill that lists steps gets this automatically; one that does not is unaffected). If the model tries to finish with one still missing, Arcana sends it back with a hidden message naming the step (after three ignored tries on the same step it gives up on that one). Chat's status bar shows the progress, `◆ coingecko-forecast 7/11`, green when done and red when steps had to be skipped. The forecast skill uses it, including a check whose result must say `REPORT_COMPLETE=yes`.
- **The `/` menu names what each entry is**: it is grouped commands, then skills, then tools, and every row has a `COMMAND`, `SKILL` or `TOOL` badge in its own column (tools add a second badge for what they can do). Skills are scanned from your skills folder each time the menu opens. Entries are also coloured by meaning: commands are cyan, skills purple (`◆ SKILL`), read-only tools green (`◎ READ`), tools that change notes or files orange (`✎ WRITE`), shell red (`▶ SHELL`), delete and move red (`✕ DELETE/MOVE`), web blue (`↗ WEB`), questions muted (`? ASK`). A glyph and a badge word go with every colour, so it still reads in a plain terminal; the same colours mean the same things (green safe, orange changes, red high risk, blue leaves the computer) across Arcana.
- **Token badge**: the session total in the bottom bar (`⚡ N tok`) is now a solid blue badge like the model pill, instead of plain text on the bar's background; blue is the token colour everywhere (`↑` in the Chat status bar too).
- **Live token counters**: while a reply streams, the Chat bar's `↓` and the bottom `⚡ N tok` (and the estimated price) count the reply tokens as they arrive instead of jumping when the request ends. The figures are exact again as soon as Ollama reports them (it sends exact counts only at the end of a request, so `↑` prompt tokens still step once per model round).
- **`elapsed_time`**: how much time passed since a moment (or between two), as `5 min 12 s` plus the exact end time, so a model measures a task without doing clock arithmetic (pairs with `current_time`).
- **`set_properties`** and merged properties: `create_note` merges a properties block at the top of its content with Arcana's own instead of writing a second block, and `set_properties(path, properties)` changes properties of an existing note in place (a key is replaced or added, the text stays). Reports and similar notes no longer need `edit_file`.
- **`wait`**: the model can pause 1–120 seconds (no approval, `Esc` stops it) to sit out a rate limit such as CoinGecko's 5–7 requests a minute, then retry. The system prompt tells the model to wait and retry on HTTP 429 instead of hammering a service.
- **Skills**: a runbook in your vault that a model follows step by step, with its scripts and reference notes. One folder per skill (`Skills/<name>/<name>.skill.md`, plus `reference/` and `scripts/`); a skill's results are kept outside it, in a folder it names with `output:` (the forecast skill: `Reports/coingecko-forecast/`), and the model is told that folder when it loads the skill. The model is told which skills exist and loads one with `use_skill`; type `/` in Chat to pick one (it is attached as a `◆ skill` chip and its text goes in with your message), `/skills` turns them on or off and lists what it found, and Settings → Tools has *Skills* and *Skills folder*. Arcana warns about a skill with no description or an entry note too long to be read in full (over 18,000 characters). The first skill, `coingecko-forecast` (pool analysis, forecast chart, liquidity-range study, verdict), and a `Skills Guide` note on writing skills are in the vault.
- Limits explain themselves: a dialog offers to turn tools on, leave plan mode, allow a folder, turn shell on or add more steps, right where the limit is hit.

## Chat details
- **Status bar** under the field, built from one reusable component: model, `✎ memory`, a coloured context gauge, tokens, estimated price, session time, tool mode.
- **Memory file `ARCANA.md`** in the vault (created on first use, with rules for using tools) goes into every chat; `/memory` opens it; the model can `remember` lines. The system prompt also carries today's date and an exact list of the tools.
- **Type `/`** for a menu of commands and tools, with ON/OFF badges for settings.
- **`ask_user` dialogs**: ↑↓ + Enter, number keys, or a text field inside the dialog for your own answer; options are cleaned of `A)` and backticks. A missing question is recovered or re-asked.
- Chats are **kept automatically**: leaving never asks (only with *Keep chats* off); the button says **Save to vault**.
- Model picker shows **size and capabilities** for each installed model; the context window goes up to 1M and the gauge never counts beyond what the model supports.
- A tool call written as text is run (or asked about); the "keep going" nudge no longer fires on "Let me know…"; while a reply runs, number keys switch screens and the sidebar shows `…` or `!`.
- Dialog buttons sit inside the dialog; code blocks are closed boxes.

## Smaller things
- Shortcuts stay **Ctrl** chords (Ctrl+P commands, Ctrl+N capture, Ctrl+O attach, Ctrl+S save…): ⌘ chords were tried and dropped because terminals and macOS claim them (Print, New window).
- Chat: type `/` for a menu of commands and tools (filter as you type, ↑↓ Tab/Enter).
- Library: `r` **Rename** a note and the links to it are updated everywhere; `c` **Chat about it** continues with a note attached (a saved chat note carries its whole conversation).
- This **Changelog** tab.
- **Docs refreshed**: the `docs/` folder of the project, the in-app Guide, and two Arcana notes in the vault (a short LLM reference and a reference documentation note) now match what the app does.
""".trimIndent(),
    ),
    ChangePage(
        "Tools", "Tools and the agent loop",
        """
# Tools and the agent loop
The model can act, not only talk: search your notes, read and edit files, ask you questions, run commands and read web pages, with you in control.

## The loop
- The model asks for a tool, Arcana runs it and returns the result, and the model carries on, up to *Steps per question* rounds; at the limit a dialog offers more.
- If a model announces a step ("Next I will read…") instead of doing it, Arcana nudges it on (twice in a row at most: the count restarts after every real tool call, so long skill runs are not cut short). A message with a skill attached may take up to 40 tool rounds.
- Old tool output used to be shortened when the history was sent; it is now kept whole by default (Settings → Tools → *Old tool output*: Whole or Shorten).

## Safety
- Tools only reach your vault and the folders you allow; symlinks and `..` cannot escape. Hidden folders like `.git` and `.ssh` are never searched or offered.
- Every change asks first (`y`, `n`; `a` allows a tool for the session). A replaced file is backed up; `.git` and `.obsidian` are never written.
- Modes: **Ask**, **Auto** (changes run at once, backed up), **Plan** (read-only, the model writes a plan).
- New folders: an "Allow this folder?" dialog; your home folder and hidden folders are not offered.

## The tools
- Notes: `search_notes`, `list_notes`, `read_note`, `create_note`, `append_to_note`.
- Files: `list_dir` (also recursive), `find_files` (name or glob), `grep_files`, `read_file` (line ranges), `write_file`, `edit_file`, `delete_path`, `move_path`.
- Questions: `ask_user`. Time: `current_time` (the system prompt also carries today's date).
- Shell and web: `run_command`, `fetch_url`, `web_search`. Pseudo-tool: `request_access`, for a model that finds tools off.

## Path help for small models
- The model is told where the vault and your home are; a relative path that exists elsewhere in an allowed folder is found; a wrong path answers "Did you mean …?".
- Hidden files such as `.gitlab-ci.yml` are listed; searches go breadth-first, skip unreadable folders and stop after 25 seconds.
""".trimIndent(),
    ),
    ChangePage(
        "Chat", "Chat",
        """
# Chat
The conversation with your second brain.

## Always ready to type
- The message field always has the keyboard; actions are Ctrl keys, `/commands`, `@` and the palette. `Esc` browses and scrolls.
- `/new`, `/history`, `/clear`, `/save`, `/image`, `/note`, `/vault`, `/tools`, `/mode`, `/append`, `/replace`.

## Context
- `@` attaches a note; `Ctrl+O` attaches pictures (Gemma 4 sees them), **PDFs** and text files such as JSON, CSV and code, in Chat, AI notes and Capture alike.
- `/vault` answers from your notes and cites them as `[[links]]`.
- Chats are kept (the latest 50) and can be reopened or saved to the vault as notes.

## A console that reads like an IDE
- Boxed, aligned **tables** (numbers right-aligned), code panes with line numbers and syntax colours, highlighted figures, headings with level marks, task lists and quotes.
- Select mode copies a message, a code block or just the tables.
- ASCII-only output for screen readers.
""".trimIndent(),
    ),
    ChangePage(
        "Brain", "Library, Inbox, AI notes and Session",
        """
# The rest of the workspace

## Brain
- The map of your vault: notes, links, tags, inbox, islands, broken links, stale notes; a capture overlay (`Ctrl+N`) appends to today's daily note, with files if you like.

## Library
- Filter by all, recent, inbox, no links, no tags, broken links, stale or tag; read a note, open it in Obsidian, trash it (always to `.trash`).
- `a` runs AI on a note, `c` chats about it.

## Inbox
- Unprocessed notes are planned by the model (tags, links, a title, a folder) and applied only after you review; originals are backed up.

## AI notes
- Describe a note or attach pictures, PDFs and files; refine the draft with follow-up prompts; save it into the vault when you are happy.

## Session
- Everything you did is logged; at the end save a summary note or only the log. Leaving unsaved work asks first.
""".trimIndent(),
    ),
    ChangePage(
        "Interface", "Look and feel",
        """
# Look and feel

- A design system ported from CosmoCLI: panels, action bars, pickers, text fields with real editing (word jumps, selection, Ctrl+U/K/W) and select-and-copy.
- **Navigation** can sit on the left, on the right or on top; the sidebar shows badges and folds under 90 columns.
- Themes (dark, soft dark, light and more), three layout densities and every screen working at 80×24.
- Mosaic workarounds: no clipping, so panels measure themselves; emoji and wide glyphs are replaced with one-cell lookalikes; non-Latin keyboard layouts keep their shortcuts.
- `Ctrl+C` always asks twice before quitting, and the footer shows the keys of the screen you are on.
- **Settings** in eight groups with help text under each row, and a **Guide** with every screen explained.
""".trimIndent(),
    ),
    ChangePage(
        "Start", "Where it started",
        """
# Where it started
Arcana began as a small Kotlin command-line app that joins **Ollama**, the **Gemma** model and an **Obsidian** vault, and grew into the control panel of a second brain.

- Kotlin 2.3 with Mosaic (Compose for the terminal); `core` has no UI, `app` has the screens.
- A streaming Ollama client with usage tracking, token counts, vision and tool capability checks.
- A vault library that reads and writes notes safely: frontmatter, links, tags, daily notes, trash.
- Settings in `~/.config/arcana/config.json`, chats, sessions and usage kept next to it.
- `make install` puts the `arcana` command on your PATH.
- The `docs/` folder describes the whole architecture for people and for AI assistants.
""".trimIndent(),
    ),
)

@Composable
fun ChangelogScreen(app: AppContext) {
    val pages = remember { changePages() }
    var page by remember { mutableIntStateOf(0) }
    val output = remember { OutputState() }
    val current = pages[page]
    val entries = rememberEntries(current.markdown)
    var notice by remember { mutableStateOf<Notice?>(null) }

    if (output.selecting) Hints("↑↓  move", "Space  tick", "c  copy", "b  code only", "Esc  leave")
    else Hints("←→  page", "↑↓/Space  scroll", "s  select & copy")

    // a page is a document: it opens at its top
    androidx.compose.runtime.LaunchedEffect(page) { output.offset = output.maxOffset }

    fun go(delta: Int) {
        page = (page + delta).mod(pages.size)
        output.reset()
        notice = null
    }

    KeyHandler { key ->
        when {
            output.handle(key, entries, onNotice = { notice = it }) -> true
            key == Key.Right || key == Key.Tab -> { go(1); true }
            key == Key.Left || key == Key.BackTab -> { go(-1); true }
            else -> false
        }
    }

    Stack {
        ScreenHeader("Changelog", "What has been built · page ${page + 1} of ${pages.size}")
        VGap()
        TabBar(pages.map { it.tab }, page)
        VGap()
        Panel(current.title) {
            val width = outputWidth()
            val lines = remember(current, width, app.config.plainGlyphs, Palette.themeId) {
                renderMarkdown(parseMarkdown(current.markdown), width, plainGlyphs = app.config.plainGlyphs)
            }
            output.copyPlain = app.config.copyAs == "plain"
            // header 3, the page tabs and their gaps 2, the panel's frame 2, and a notice row only while there is one
            ScrollView(lines, outputRows(fixed = 7, notice = notice), output)
            NoticeLine(notice)
        }
    }
}
