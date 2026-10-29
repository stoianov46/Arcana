package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.arcana.brain.app.navigation.Screen
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle

/** One page of the guide: a short tab title and the Markdown it shows. */
private class GuidePage(val tab: String, val title: String, val markdown: String)

private fun pages(): List<GuidePage> = listOf(
    GuidePage(
        "Start", "Your second brain, with a control panel",
        """
# Your second brain, with a control panel
Arcana connects your Obsidian vault with a local AI model (Gemma, run by Ollama). Your notes never leave your computer. It is built around one loop:

1. **Capture**: get a thought out of your head right now (`Ctrl+N`, from any screen).
2. **See**: the **Brain** screen shows how healthy the vault is and what needs attention.
3. **Think**: **Chat** with Gemma about your notes, attach notes, pictures, PDFs and files.
4. **Shape**: **AI notes** summarizes, improves, translates and connects notes.
5. **Keep**: every **Session** can be saved into the vault as a note of what you did and learned.

## Moving around
- Press the **number** next to a screen in the sidebar to open it.
- `Ctrl+P` opens the **command palette**: every action by name, searchable. If you forget a key, use this.
- `Ctrl+N` captures a thought into today's daily note. `?` shows the keys of the current screen. `Ctrl+B` folds the sidebar.
- `Esc` leaves what you are doing; from a top-level screen it goes back to Brain.
- Quit with `Ctrl+C` twice. If the session has unsaved activity, Arcana first offers to save it.

## First steps
1. Start Ollama with a Gemma model (`ollama pull gemma4`).
2. Open **Settings → Vault** and choose your vault folder.
3. Look at the **Brain** screen, then try `Ctrl+N` to capture a thought.
""".trim(),
    ),
    GuidePage(
        "Setup", "Connect Ollama and your vault",
        """
# Connect Ollama and your vault
Everything is in **Settings**. Pages are tabs: `Tab` moves to the next group, `←` `→` change a value, `Enter` edits it, `R` resets all.

## Ollama
- **Ollama URL**: where the server listens, normally `http://localhost:11434`.
- **Model**: `Enter` lists the models installed on that server, and you pick one.
- **Temperature** and **Timeout** tune how adventurous and how patient the model is.
- The badge at the bottom left shows the state: green means ready, red means Ollama is not running, orange means the chosen model is not installed. Press `t` in Settings to test the connection.

## Vault
- **Vault path**: `Enter` opens a folder browser. Folders with an `.obsidian` directory are marked as vaults.
- **Output folder**: where new notes are saved, inside the vault.
- **Inbox folder**: where unprocessed notes land. The Brain screen counts what waits there.
- **Daily notes folder** and **format**: used by capture. If your vault uses Obsidian's Daily notes plugin, Arcana reads *its* settings and these are only fallbacks.
- **Stale after**: how many days without a change make a note "stale".
- **Context notes** and **Chars per note**: how much of your vault Chat sends when it answers from the vault. A longer note is cut, the model is told, and the answer says `⚠ Only the start was read of: …`. A note you attach with `@` that is over 60,000 characters asks first: send the first part, send it whole (it may not fit the context window), or cancel.

Settings are saved at once to `~/.config/arcana/config.json`.
""".trim(),
    ),
    GuidePage(
        "Brain", "The Brain screen: your control panel",
        """
# The Brain screen
The first screen shows how your vault is doing and what to do next.

## Vital signs
One line: notes, words, links, tags, the share of notes that are **linked** to something and that carry a **tag**, and how many notes changed this week.

## Needs attention
A short list of things worth fixing. Each one opens the **Library** on exactly those notes:
- **Inbox**: notes waiting in your inbox folder; `Enter` opens the Inbox screen, where AI proposes how to file each one.
- **Not linked to anything**: islands that nothing points to and that point to nothing.
- **No tags**.
- **Broken links**: `[[links]]` to notes that do not exist.
- **Untouched for 90+ days**: notes that may have been forgotten.

When everything is in order it says so.

## Capture and jump back in
- `Capture a thought` (`Ctrl+N` from anywhere) adds a timestamped line to today's daily note, creating it if needed.
- **Jump back in**: continue your chat, open AI notes, see this session, open the last saved session, or open a recently changed note.

On a wide window there are two columns: **Now** (capture, what needs attention, your top tags) and **Jump back in**. `←` `→` switch columns, `↑` `↓` choose, `Enter` opens, `r` reads the vault again (it also refreshes by itself after changes).

## The sidebar tells you things
A badge shows what is waiting: the **Inbox** count, the number of unsaved events in the **Session**, and `…` on **Chat** while a reply is being written. On a narrow window (under 90 columns) the sidebar folds to icons by itself; the badge becomes a dot.
""".trim(),
    ),
    GuidePage(
        "Library", "Browse and understand your notes",
        """
# Library
Every note in the vault, with the web of links around it.

## The tree
Notes are shown as **folders that fold and unfold**, like in a file manager: folders first (with how many notes are inside), then the notes of that level, each level indented. `Enter` (or `Space`) folds and unfolds the folder under the cursor; `l` unfolds it (and steps into it when it is already open); `h` folds it, or, on a note or a folded folder, jumps to the folder above. `+` unfolds every folder and `-` folds them all. `t` switches to a **flat list** with the full path of every note (and back); Settings → Vault → *Library as a tree* sets the default. Typing a filter (`/`) lists the matches flat with their paths; `Esc` brings the tree back. The tree remembers which folders were open while Arcana runs, and a note you jump to from the Brain screen opens its folders by itself. Every view (Recent, Stale…) is a tree of just its notes, each folder in the view's own order.

## Views
`←` `→` switch the view: **All**, **Recent**, **Inbox**, **No links**, **No tags**, **Broken links**, **Stale**, and **Tags** (pick a tag to see its notes). The numbers on the tabs are live counts. `/` filters the list; typing several words finds notes containing all of them.

## The preview
Beside the list (or on `Enter` in a narrow window) you see the selected note: its path, word count and when it changed, its tags, what it **links to**, what is **linked from** it, **related** notes (sharing tags or neighbours), any **broken links**, and then the text. `↑` `↓` and `Space` scroll it.

## Actions on the selected note
- `a` **AI on this note**: opens AI notes with the note already chosen.
- `r` **Rename**: type a new name (the note stays in its folder); the `[[links]]` to it in your other notes are rewritten, the way Obsidian does, and renaming back undoes it.
- `c` **Chat about it**: starts a new chat with the note attached, so you can keep working on it by talking. A saved chat note works too: it carries the whole conversation, so the model picks up where it stopped.
- `o` **Open in Obsidian**: jumps to the note in Obsidian itself.
- `d` moves the note to the vault's `.trash` folder, after you confirm.
""".trim(),
    ),
    GuidePage(
        "Inbox", "Process the inbox with AI",
        """
# Process the inbox with AI
New notes pile up in your **inbox folder** (Settings → Vault → Inbox folder). The Inbox screen turns them into filed, tagged, linked notes, one at a time.

## How it works
1. Open **Inbox** (or press `Enter` on the inbox item on the Brain screen). `p` starts with the first note, `Enter` on a note starts there.
2. Gemma reads the note, and proposes a **title**, a **folder** (one that already exists), **tags** (preferably ones you already use), **links** to related notes (only notes that exist) and a one-line **summary**. While you review one note, the next is already being read.
3. The proposal is a small form: `Tab` or `↑` `↓` move between fields and you can edit anything. If other notes link to this one by name, the title is locked so those links keep working.
4. `Enter` **applies** it, `Ctrl+X` **skips** the note, `Esc` goes back to the list.

## What applying does
- Tags are merged into the note's frontmatter, the summary becomes a callout at the top, links are added under a **Related** heading, and the note is renamed and moved to the folder.
- Your note's own text is never changed.
- The version before is always copied into the vault's `.trash` folder, so nothing is lost.

Each filed note is recorded in the session, so the session summary shows what you processed.
""".trim(),
    ),
    GuidePage(
        "Chat", "Chat: the hub",
        """
# Chat
Talk to the model (Gemma, Qwen… whichever you chose). The message field **always has the keyboard**: letters never act as shortcuts, so you can just type. The conversation is saved as you go, comes back when you restart, and keeps streaming if you switch to another screen.

## Typing
- `Enter` sends; `Alt+Enter`, or a `\` before Enter, starts a new line. The field grows (up to a quarter of the screen) and shows a scrollbar; `↑` `↓` move between its lines.
- **Type `/`** for a menu of commands and tools: keep typing to filter, `↑` `↓` choose, `Tab` or `Enter` use it, `Esc` closes. Settings such as `/vault`, `/tools`, `/mode` show whether they are ON now.
- The **status bar** under the field shows the model, whether your memory file is in use (`✎ memory`), a **context gauge** (how full the model's memory window is), tokens (`↑` prompt, `↓` reply: the reply count moves live while it streams and becomes exact when the request ends; the blue `⚡ N tok` badge in the bottom bar is the total for this session), an **estimated price** (what the same tokens would cost on a paid service; set the price in Settings → Ollama), the session time, the tool mode and, while a skill runs, its progress (`◆ name 7/11`).
- Long pasted text is restored whole.

## Giving it context
- `@` attaches a **note** (type to search, `Enter`). Its text goes with your next message.
- `Ctrl+O` attaches **files**, in every field that has one (Chat, AI notes, Capture): pictures (PNG, JPEG, WebP; up to 5; Gemma 4 can see them), **PDFs** (text layer, up to 20 MB) and text files (JSON, CSV, code, logs, Markdown; up to 5, 2 MB each). Their text goes to the model with your message. In Capture any file is copied into the vault and embedded.
- `/vault` makes Chat **answer from your vault**: each question searches your notes, and the answer lists the **Sources** it used and cites them as `[[Note]]`. Switch it off the same way.
- `Backspace` on an empty message takes the last attachment back.

## Letting it act: tools
Turn on **Settings → Tools → Tools in Chat** (or `/tools`) and Gemma can use tools instead of only talking:
- **Asking for what it needs:** with tools off (or in Plan mode) the model can call `request_access` and you are asked, right there, to turn them on or leave Plan mode; `use_skill` loads a skill when your request matches one (see the **Skills** page).
- **Tags:** `list_tags` shows the vault's tags, `add_tags` (asks first) adds tags to a note without losing its own; the Library's Tags tab picks up tags however a model wrote them.
- **Reading** (runs at once): `cat` (a file with line numbers), `search_notes`, `list_notes`, `read_note`, `list_dir` (also recursive), `current_time` (the date and time, in any time zone), `elapsed_time` (how long since a moment, so the model does not do clock math), `wait` (pauses up to 120 s, to sit out a web rate limit before retrying), `find_files` (by name or glob such as `**/*.kt`), `grep_files` (search *inside* files, with line numbers), `read_file` (text, code, JSON, PDF; long files in parts with a line range).
- **Changing** (always asks first): `create_note` (a properties block at the top of its text is merged with Arcana's), `append_to_note`, `set_properties` (change a note's properties in place), `write_file`, `edit_file`. A panel shows exactly what would change (for `edit_file` and an overwrite it is a diff like `git diff`, `-` red and `+` green); `y` allows, `n` denies, `a` allows that tool for the rest of the session.
- Each step shows in the chat as `⚙ tool  what` and `↳ ✓ result`. It may take up to *Steps per question* rounds before it must answer. At that limit a dialog offers more steps; *Settings → Tools → At the step limit* can give them automatically (up to 3 times) or stop at once. Changes, commands and web addresses still ask one by one.
- **Watching the model think:** `/thinking` (or *Settings → Console → Thinking pane*) opens a split window under the conversation that follows a reasoning model's thoughts while it works, and shows the last ones when it is idle; *Thinking pane height* sets its rows. It needs a model that can think and a terminal at least 26 rows tall. (The `/compare` split view is something else: columns of answers from several models.)
- **Nothing is cut silently.** Results of tools from earlier questions are sent to the model whole (*Settings → Tools → Old tool output*: Whole, or Shorten for small context windows). When a tool itself leaves something out (a long file, list, command output or web page) the chat shows an orange `✂` line under the result saying what.
- **Repeated reads:** if the model asks for the exact same read, search or web page again in one question, Arcana answers "you already made this call" instead of running it (and asking you) again. *Settings → Tools → Remember repeated reads* sets how many seconds that lasts (default 45, 0 = off); changes, commands, waits, failures and your next message reset it.
- **Search says "DuckDuckGo is limiting requests (HTTP 202)":** DuckDuckGo has no key and blocks some connections with a human check. Arcana then tries the other backends you set up and leaves DuckDuckGo alone for 10 minutes. To fix it for good: *Settings → Tools → Search backend* → **Brave** with a free key from api-dashboard.search.brave.com (paste it in *Search API key*), or **SearXNG** with the address of your own server.
- **No key and no server?** Then `web_search` works only while DuckDuckGo allows your connection (a block often clears after a few hours; Arcana asks again after 10 minutes). Nothing else depends on it: tell the model an address (documentation, an API, a Wikipedia article) and it reads it with `fetch_url`; skills call their own APIs.
- **New folders:** when the model needs a folder that is not allowed yet (say `~/Desktop`), Arcana asks "Allow this folder?": `y` remembers it (see *Allowed folders*), `n` refuses. Home itself, system folders and hidden folders such as `~/.ssh` are never offered.
- **Modes** (`/mode` cycles them, or Settings → Tools → Mode): *Ask* (default) waits for your `y` on every change; *Auto* runs changes at once, with the old file always backed up (new folders still ask); *Plan* is read-only: Gemma investigates and writes a plan, then you switch to Ask or Auto and tell her to go ahead. The header shows `⚙ tools ask|auto|plan`.
- **Questions, deleting and moving:** `ask_user` puts a question with numbered options to you in the chat (`↑↓` and Enter or `1`-`9` choose; the last row, `t` or ↓ to it, is a text field inside the dialog for an answer of your own; `Esc` skips; quizzes work even with tools off). `delete_path` moves a file or folder to a trash folder (the vault's `.trash`, or `~/.config/arcana/trash`) and `move_path` renames or moves; both ask **every time, even in Auto mode**, and neither can be set to "always".
- **Git:** `git_diff`, `git_status` and `git_log` show the model what changed in a Git repository (they only look, so they never ask); a commit goes through `run_command`.
- **Usage → Tools** (Tab on the Usage screen) shows what each tool cost: calls, about how many tokens its results added to the conversation, time, failures and denials.
- **`todo`** lets the model keep a short task list for a job of three or more steps: you see the plan under the call in the chat and a `☑ 2/5` pill in the status bar, so you can follow a long job. It changes nothing and asks nothing.
- **`chat_stats`** lets the model read this chat's tokens, estimated cost, time, tool calls, context use, token spend per model and in total, and which other models (the Oracul) were consulted and how often, so it can answer "how much did this cost?" with real numbers.
- **The model can ask for a mode:** with `request_mode` it can propose Auto (before many edits), Plan (to investigate first) or Ask; you always get a "Switch to … mode?" question and can refuse.
- **More models in a comparison:** *Settings → Helpers → More Oracul models* (Space ticks several) makes `/compare` ask all of them; the judge compares every answer. Each model loads in turn, so it takes longer.
- **Which model fits:** the model pickers in Settings show whether each installed model fits this machine's memory and mark a `★ suggested` Oracul and judge. A model marked `too big` would swap to disk or run on the CPU. A model you have already chosen that does not suit this memory is flagged under its row and after you save it, with a better installed choice, and the Chat status bar shows an orange `⚠` after the Oracul when it is too big.
- **Oracul server:** *Settings → Helpers → Oracul server* sends the Oracul and the judge to another Ollama server (a bigger machine on your network); blank keeps them on the main server.
- **Chat context for the Oracul:** the Oracul, the judge, the planner, the analyst and the controller are shown the earlier chat before your question, newest first, up to about 30,000 characters (Settings → Helpers → *Chat context: messages*, 0 = all, or a cap; *characters each*, default 3,000), so a follow-up like "do option C" makes sense to them. The main chat itself is sent whole (Settings → Console → *Chat history*, 0 = everything) and compacted when the window fills.
- **Judge automatically:** Settings → Opinions → *Judge automatically* (off by default). When an automatic second opinion is due, a full comparison runs instead (the Oracul answers too, the judge compares). Slow: three model runs.
- **Stopping the Oracul:** Esc cancels an Oracul or judge run that is working on its own; nothing is added to the chat. Inside the worker's turn, Esc asks "Cancel this reply?" and Enter confirms (Settings → Console → Confirm before cancelling turns the question off).
- **Seeing the Oracul:** with an Oracul model set, `Ψ model ⚖ judge · auto` sits in the Chat status bar and the bottom bar; it turns orange while the Oracul (`Ψ`) or the judge (`⚖`) is working. The model that works now always comes first: the main model is the filled pill and the Oracul's setup follows it quietly; while the Oracul answers, its orange pill comes first and the main model follows as `waiting` (in the bottom bar its pill turns grey). In Chat the bottom bar keeps only the glyphs, `Ψ` and `⚖` for a judge, to show they are on.
- **Side-by-side comparisons:** `/compare` shows the answers in columns, each under a coloured name, the judge's verdict below in orange. *Settings → Console → Comparison view* switches to stacked; a narrow window stacks them by itself.
- **Tab and Enter in the menu:** Tab completes the highlighted name into the field so you can add your text (`/revise focus on the typo`), Enter runs it (a command runs, a skill is attached, a tool writes its request).
- **Examples in the menu:** the highlighted entry shows a second line with an example of how to type it, for example `e.g. /read_note Trip Planner`.
- **"Waiting for the model…":** the line adds why (the model is still loading into memory, or it is reading your conversation, or part of it runs on the CPU, or memory is tight), and the status bar shows `● loaded … GPU` or `○ not loaded`. Ollama does not say how far it has read, so there is no countdown.
- **"Request timed out":** usually the model is too slow, not Ollama down: a context window that is too large for your memory makes the model run partly on the CPU (the bottom bar then shows a red `⚠ … on CPU` badge). Lower Settings → Ollama → Context window (32k is a good start) or use a smaller model.
- **Switches for testing:** `/helpers` turns every helper model off or on at once (Oracul, judge, flow, specialists, router; the settings stay), and `/roles` turns the worker, planner and reviewer of skills off or on. Independent: helpers off + roles on = one model plus skills with their roles; both off = one model only.
- **Specialists as tools:** switch on Settings → Flow → Specialists as tools and the chat model can ask the analyst, the planner or the reviewer for help by itself when a task is hard or unclear (up to 3 times per message). Unlike the flow, it decides when; most messages call nobody.
- **Routing by content:** in Settings → Helpers you can name a model for messages with an image and one for messages with code. Arcana then picks the model by simple rules (no model decides) and says `Routed to …`; leave them blank to always use the chat model.
- **What the model wrote:** the status bar shows `✦ 3 changed` once a tool has written or changed files or notes in this chat; `/artifacts` lists them (what happened to which path) and `1`-`9` opens a note in the Library. It starts empty in every new chat.
- **Memory check:** `/memory check` lists lines in your memory file that repeat each other and lines that name a note that is not there; `y` removes the repeats (dead paths you fix yourself). The `remember` tool no longer adds the same fact twice and refuses a note path that does not exist.
- **Goal:** `/goal every note in Inbox is tagged` sets a goal and starts the work. After each reply a checker says whether it is met; while it is not, Arcana keeps working by itself (up to 5 turns, Settings → Flow → Goal turns). `/goal` shows it, `/goal clear` drops it. A message of yours resets the count.
- **Judge verdict:** with a Judge model set, each automatic second opinion is followed by the judge saying AGREE, PARTLY or DISAGREE and why (Settings → Opinions → Judge verdict on opinions).
- **Flow:** `/flow` switches the careful flow on or off (`/flow careful`, `/flow off`): every message of 60+ characters goes through an analyst, a planner, the worker, a reviewer and a controller, each its own model (Settings → Flow). The reply line says which stage is working. It is slow, so it is off until you switch it on.
- **Typing a command with text:** `/revise focus on the typo`, `/oracul qwen3:30b` (ask that model just this once), `/compare other-model`, or a skill with its request (`/forecast ETH`). Other commands take no text and say so.
- **Oracul next steps:** if the main model fails a turn, `/takeover` lets the Oracul finish it (offered in a message, never automatic); after an opinion, `/revise` makes the main model correct its answer with it; the bottom bar shows `Ψ model · auto` while an Oracul is set. A good start for a 32 GB Mac with a 9B main model: Oracul `qwen3:30b`, judge `gemma4:12b`, automatic *On problems*.
- **The Oracul can check with tools:** with *Settings → Helpers → Oracul may use tools* on, it may read files, search notes and run harmless checks to verify an answer; anything that changes something, runs a command or reaches the web asks you first, even in Auto mode (dialog headed Ψ Oracul).
- **Oracul (second opinion):** *Settings → Helpers*: choose an Oracul model (ideally a bigger or different one than your main model) and a judge. `/oracul` shows what it thinks is wrong or missing in the last answer; `/compare` lets it answer too and a judge picks the better one. With *Automatic second opinion* on (default: on problems) it speaks up by itself after failed tool calls or skipped skill steps, when several turns in a row had a failed call, and in the middle of a turn when the main model has failed several tool calls and keeps trying (the numbers are *Stuck: failed calls* and *Stuck: failing turns* in the same group, default 4 and 3): the Oracul reads the attempts and its advice goes into the loop. It only advises: it changes nothing by itself. With *Oracul may use tools* on it can read your files and notes to check an answer, and anything that changes something or runs a command asks you first.
- **Storage:** *Settings → Storage* lists the files Arcana keeps (backups, trash, chat history, sessions, usage) with their size; Enter clears one after you confirm, and backups and trash can be pruned to what is older than 30 days.
- **Setup:** a new Arcana starts with a short setup (choose the vault, then tick the memory file, skills folder, guide and starter skills to create). It only creates what is missing; run it again from the palette ("Set up the vault"). Without a screen: `arcana --init <vault>`. The starter skill **decompose** turns a todo list into a plan, asks you the open decisions, then works through it.
- **Sounds:** *Settings → Sounds* plays a sound when the model waits for you, finishes or fails, so you can work in another screen or window; the Chat tab shows a pulsing `!` (approval) or `?` (question) too. Off by default.
- **Web asks every time, even in Auto mode** (a search or an address can carry your data out). *Settings → Tools → Search and fetch without asking* (off by default) lets `web_search` and `fetch_url` run at once (the words or the address can carry data out, so keep it for research chats); HTTP requests still ask.
- **Allowed folders or Working folder?** *Allowed folders* decides WHERE the model may read and write (`~/Projects;~/Documents`, or `~` for your whole home folder); it is all you need to let it work somewhere. *Working folder* is optional on top: the one folder you work in right now, where relative paths and commands start, with its own instruction file. It is allowed automatically and grants nothing outside itself.
- **Working on a project:** set *Settings → Tools → Working folder* to a folder (say your web project) and chat with Arcana about it: it is told what is in the folder, relative paths and commands start there, and the project's own `ARCANA.md`, `CLAUDE.md` or `AGENTS.md` is read as instructions. Changes still ask first; `▣ name` in the status bar shows the project.
- **Long chats:** `/compact` summarizes the older part of the chat (a dim divider shows where) so the context window has room again; the model is sent the summary and your latest messages (as many as fit in 30% of the window, Settings → Console → Keep after compacting), and the old messages stay visible and in saved notes. *Settings → Console → Auto-compact* does it for you when the window is 80% full.
- **Auto-run:** *Settings → Tools → Run commands without asking* (or `/autorun`) lets shell commands run without `y`; a red `▶ auto-run` in the status bar shows it. Commands Arcana never runs stay refused.
- **Shell and web** (both off by default, in Settings → Tools): `run_command` runs a command after you press `y` (harmless ones like `git status` run at once; `sudo` and `rm -rf /` never); `web_search` looks things up on the web (DuckDuckGo by default, no key; or your own SearXNG server, or Brave with a key: Settings → Tools) `fetch_url` reads a public web page, and **`http_request`** sends a request (a POST, headers, a body: the model must use it instead of `curl`, which is refused); all ask every time and show what they send, with credentials hidden (pages on your own computer or network are refused). If the model asks while they are off, a dialog offers to turn them on right there.
- **Skills:** runbooks in your vault that the model follows step by step; pick one from the `/` menu (it is attached as a `◆ skill` chip) and type your task. The **Skills** page of this guide explains how they work, the progress badge in the status bar and what to do when something looks wrong.
- **Required steps in skills:** a skill can list the steps that must happen; Arcana watches them while it runs (badge `◆ name 7/11` in the status bar) and sends the model back if it tries to finish early. See the **Skills** page.
- **Colours in the `/` menu:** the list is grouped commands, then skills, then tools, and every row says what it is in a badge (`COMMAND`, `SKILL`, `TOOL`). Skills are read from your skills folder each time the menu opens, not built in. Commands are cyan; skills purple `◆`; tools are coloured by what they can do: green `◎` only reads, orange `✎` changes notes or files (asks first), blue `↗` reaches the internet, red `▶` runs a command and red `✕` deletes or moves (these ask every time). Each also shows a badge word, so you never have to rely on colour alone.
- **The `/` menu:** type `/` at the start of a message and a list of commands, skills and tools opens above the field; keep typing to filter, `↑↓` to choose, `Tab` or Enter to use it, `Esc` to close. A command runs; a tool writes "Use the … tool to " into the field for you to finish (the tools are called by the model, not typed as commands).
- **When the model does not make the call:** small models sometimes write a tool call as text, or only announce a step. Arcana runs such a call as a real one (Settings → Tools → *Calls written as text*: Auto, Ask or Off) and nudges a model that only announces what it will do. When a job needs more steps than allowed, a dialog offers more.
- **Safety:** tools only touch your vault and the folders you allow in *Settings → Tools → Allowed folders*; `..` and symlinks cannot escape them, and `.git` and `.obsidian` are never written. A file that is replaced or edited is backed up first (in `~/.config/arcana/backups`). Text in a note or file is only data: the model is never obliged to follow instructions found in it, and every change still needs your `y`.

## Sending things to the vault
- `Ctrl+S` saves the chat as a new note (whole chat or just the last answer). In that dialog `Ctrl+O` picks a note that already exists and **overwrites** it instead (the old text is kept in `.trash`; you confirm with `y`).
- Palette or `/append`: add the last answer to an existing note. `/replace`: replace a note with it (the old text is kept in `.trash`).
- Chats are kept in the history automatically, so leaving a chat never asks (only with *Keep chats* off). **Ctrl+S "Save to vault"** turns the chat into a note.

## Scrolling and copying
`↑` `↓` scroll while typing. Chat opens with the field not focused (press `Enter` to start typing). `Esc` leaves the field again: then `j` `k` scroll, `Space` pages, and `s` starts **select mode** (`Space` ticks, `c` copies, `b` copies only code). `Enter` starts typing.

**While the model works** you can keep typing: `Enter` puts your message in a queue (`⏳ queued` above the field). In a tool turn it joins at the next step, so a correction or a clarification reaches the model before it goes further; otherwise it is sent right after the reply. `Backspace` on an empty field removes the newest queued message (nothing is sent for it); `Esc` `Esc` cancels the reply and gives the queued text back to the field. Commands and attachments cannot be queued.

## Chats
`/new` starts a fresh chat, `/history` opens an older one, `/clear` erases this one. Every action is also in the palette (`Ctrl+P`). Chats are **kept in the history automatically**, so leaving never loses one and never asks; `Ctrl+S` **Save to vault** is only for turning a chat into a note.

## Your memory file
The memory file (the `✎ memory` pill in the Chat status bar turns orange, `⚠ memory 8k of 11k: end cut`, when the file is longer than what is sent), by default the vault note **`ARCANA.md`** (move it with *Settings → Prompts → Memory file path*: into a vault folder, or anywhere on your computer), holds standing instructions and facts (who you are, where your projects are, rules for using tools). Its first 8,000 characters go into every chat. Arcana creates it the first time you open Chat and offers to open it; `/memory` opens it any time, and you can say "remember that…" so the model adds a line (after you confirm). Switch it off in Settings → Prompts.
""".trim(),
    ),
    GuidePage(
        "Skills", "Skills: runbooks the model follows",
        """
# Skills: runbooks the model follows
A **skill** is a runbook that lives in your vault: a note with numbered steps, plus the scripts and reference notes it needs. You give it to the model, and the model follows it step by step. Skills are how a small local model does a long job reliably (analyse a pool, file notes, write a report) instead of improvising.

## What a skill looks like
```
Skills/
  coingecko-forecast/
    coingecko-forecast.skill.md   the runbook (its name ends in .skill.md)
    reference/                    notes the skill reads only when needed
    scripts/                      programs the skill runs for you
Reports/
  coingecko-forecast/             what it produced (outside the skill)
```
A folder is a skill when it has `<folder name>.skill.md` inside it. Nothing is registered anywhere: add a folder and it is there. The first lines of the note are its **properties**: `description:` (what it does and when to use it; the model chooses by it), `output:` (where its results go) and `requires:` (its required steps, see below). Optional properties name models: `planner:` writes a short plan first, `worker:` runs the skill with that installed model instead of the chat model, `reviewer:` asks that model for a second opinion when the run is done (Settings → Helpers has a table of defaults for skills that name none). Both apply only to that message; a model that is not installed or cannot use tools is skipped with a message.

## Using one
1. Turn on **Tools in Chat** (`/tools`) and **Skills** (`/skills`, or Settings → Tools; on by default).
2. Type `/` in Chat. The list is grouped **commands, then skills, then tools**, and every row has a badge saying what it is (`COMMAND`, `SKILL`, `TOOL`). Skills are read from your skills folder every time the menu opens, so one you just added in Obsidian is already there.
3. Pick a skill: a chip `◆ skill name` appears above the field (Backspace on an empty field removes it). Type what you want ("analyse this pool: arbitrum, 0x…, 30d") and press Enter.
4. Arcana puts the skill's text into your message, so the model does not have to remember to load it. That question may take up to 40 tool rounds (a skill run is long). Every change, command and web fetch the skill leads to still asks for your approval, as always.

The model can also load a skill by itself when your request matches its description (it is told which skills exist). Then there is no chip, but everything else works the same.

## The progress badge
If a skill lists `requires:` steps, the Chat status bar shows `◆ coingecko-forecast 2/9`: **2 of the skill's 9 required steps are done**.
- The steps tick off when the model makes the matching tool call (for example "ran the analysis command", "created the report note", "the check said `REPORT_COMPLETE=yes`"). Order does not matter.
- Purple means running, green with ✓ means every step is done, red `✕2 skipped` means the model ignored steps and Arcana gave up on them.
- If the model tries to finish while a step is missing, Arcana does **not** accept it: it sends a hidden message ("STOP: … this step is still missing: …") and the model carries on. You do not see these messages. After three ignored tries on the same step, Arcana gives up on that step so the run can end.
- Only skills that list steps get a badge and this protection; a skill without `requires:` simply runs from its text. The badge disappears at your next message or `/new`.
- It checks that steps **happened**, not that their content is right. That is why good skills end with a verification command whose result must be `yes`.
- **A controlled flow with roles** (example: `defi-pool-analysis` in the skills folder: give it a token pair such as `ETH/USD` and it compares the pools of that pair and writes one report with charts and a recommended pool): the **Planner** (`planner:` in the properties, or Settings → Helpers) writes a plan first, the **Worker** follows the required steps, and a **Validator** checks the result: a script command that compares the saved note with the real data and prints `VALID=yes` only when it matches (a `requires:` step waits for that text), plus the `reviewer:` model's second opinion. No code is needed: it is all in the skill.

## Where things live
- Arcana keeps its own working files (skills, the memory file) in a hidden **`.arcana/`** folder in the vault, so your vault shows only content: skills in `.arcana/skills/`, memory in `.arcana/ARCANA.md`. An older vault keeps `Skills/` and `ARCANA.md` until you run **`arcana --migrate`** in a terminal (it moves them, never overwrites). Skills and a memory file in `~/.config/arcana/` apply to every vault (a vault skill of the same name wins). *Settings → Tools → Skills folder* can name another folder. Obsidian does not show hidden folders, and a `[[link]]` from a visible note to a note inside `.arcana/` shows as unresolved there (write the name in backticks instead): edit a skill in your editor, or name a visible folder. Results belong outside it: a skill names its results folder with `output:` (the forecast skill writes to `Reports/coingecko-forecast/`). This keeps the skill small and easy to share.
- `/skills` lists what Arcana found and what needs attention: a missing `description:`, a name that differs from the folder, an entry note longer than 18,000 characters (the model could not read the end), an unreadable `requires:` line.
- Writing your own: the vault note `Skills/Skills Guide.md` has the layout, the properties, the `requires:` format, a template and the lessons learned for small models.

## When something looks wrong
- **The skill is not in the menu:** the folder needs `<name>.skill.md` with the same name as the folder (an old `<name>.md` is not found); check `/skills` and the *Skills folder* setting.
- **"Skills need tools":** turn tools on with `/tools`.
- **The badge stays below full:** the model has not done a step, or its result condition has not been met (for example the check still says `REPORT_COMPLETE=no`). Let it continue; after three tries per step Arcana lets it end, and the badge shows what was skipped.
- **A red `exit code 1` in the chat:** a skill's verification command uses it to say "not complete yet". It is not a crash.
- **The report says `incomplete`:** the skill ended with steps missing; its note lists what is missing. If the note looks complete (the charts are there), the model probably fixed it after its last check without checking again, so the status is stale: the skill's final check (`check --final`) catches this and gives the exact fix.
- **You asked the model why a run failed and its answer sounds odd:** small models make up a reason (for example blaming a data limit). The badge (`◆ name 7/11`, `✕ skipped`) and the report's `status` / `missing` properties say what really happened; the chat file in `~/.config/arcana/chats/` has every tool call in order.
- **The model ends without doing anything:** small models sometimes only describe a step. Arcana nudges them; if it persists, use a larger model (Settings → Ollama → Model).
""".trim(),
    ),
    GuidePage(
        "AI notes", "AI notes: shape your notes",
        """
# AI notes
Create or change notes with AI. Nothing in your vault changes until you apply a result.

## 1. Choose what AI should do
- **Write a new note** from a prompt (attach pictures, PDFs or files with `Ctrl+O`), or **turn pictures into a note**.
- On an existing note: **Summarize**, **Improve**, **Continue**, **Translate**, **Extract action items**, and **Suggest tags and links**. The last one works from your real vault: it proposes tags that already exist and notes worth linking to.
- **Do something else**: type your own instruction.

You can start from the **Library**: press `a` on a note and the note is already chosen.

## 2. Work on the draft
The result is a **draft**. Keep talking to it: `f` **Refine** rewrites it, `m` **Add more** adds a section, `u` undoes a step, `r` redoes.

## 3. Apply it
- `w` **Replace note** (Improve, Translate, your own tasks): keeps the note's frontmatter and saves the old text in `.trash` first.
- `a` **Add to note**, `o` add to another note, `n` save as a new note.
- With pictures, saving a new note can also copy them into the vault and embed them (`![[picture.png]]`).

Leaving with a draft that was not applied asks before it is thrown away.
""".trim(),
    ),
    GuidePage(
        "Session", "Sessions: what you did, kept",
        """
# Sessions
Each time Arcana opens, a **session** starts and records what you do: questions, AI tasks, captured thoughts, notes created, changed or trashed. It is written to disk as it happens, so nothing is lost if the terminal closes.

## The Session screen
A timeline of the activity, with counts and the tokens used. From here:
- `s` **Save summary note**: Gemma writes notes on the session (what you did, what you learned or decided, open questions, next steps) from the activity and the chat. Review it, then `n` saves it as a note.
- `l` **Save log only**: the activity as a plain list, no AI.
- `n` **New session**: closes this one and starts fresh without restarting Arcana. The chat starts empty (the old one stays in the history).

Notes are saved in `Sessions` inside your output folder, with `[[links]]` to the notes the session touched, so Obsidian connects them.

## Before you quit
When the session has activity that is not saved to the vault, the first `Ctrl+C` opens the Session screen with **Before you go**: `s` or `l` saves and then quits, `Esc` keeps working, and `Ctrl+C` again quits without saving.

The palette (`Ctrl+P`) has the same actions.
""".trim(),
    ),
    GuidePage(
        "LLM", "Working with the model",
        """
# Working with the model (LLM)

## What the model sees
- Only what Arcana sends: your **system prompt**, the conversation, and, for notes, the note's text or excerpts. It cannot browse your vault on its own and knows nothing about notes you did not give it.
- Its memory is limited. Long notes are cut (about 60,000 characters in AI notes and for attached notes), **Chat in vault mode** sends only the best few matches, and **Chat** sends the latest messages (Settings → Console → Chat history).

## Asking well
- Say the **goal, the format and the language**: "a checklist in Ukrainian", "three bullet points".
- Give it the material by **picking the note**, not by pasting.
- **Iterate**: in AI notes use `f` Refine and `m` Add more instead of starting over. Each follow-up sees the whole conversation and the current draft.
- Models can be wrong or make details up. Check names, numbers and dates. In Chat with `/vault` on, the *Sources* line under an answer shows exactly which notes were used.

## Settings that change the result
- **Temperature**: low (0 to 0.3) for faithful summaries and rewrites, higher (0.8 and up) for ideas and brainstorming.
- **System prompt** (Settings → Prompts): its role and tone, for example "Answer briefly, in the language of the note".
- **Model**: bigger models write better but slower. Settings → Ollama → Model lists the installed ones with their size and what they can do (tools, vision, thinking); pick one that can use tools if you want the model to act. The Usage screen shows tokens per second.
- **Context window**: how much of a chat the model can hold at once. A bigger window remembers more but needs more memory and is slower; the status-bar gauge shows how full it is (and never counts beyond what the model supports). With *Model default* Ollama picks its own window, often only 4k; Arcana reads the real one from Ollama and shows an orange `⚠ ctx 4k` when it is under 32k, so choose 32k or more in Settings → Ollama → Context window for tools and long chats. Start a fresh chat with `/new` when it fills up.

## Tokens and speed
- A token is a piece of a word, roughly three quarters of a word. *Prompt tokens* are what you sent (including note text); *reply tokens* are what the model wrote.
- A picture costs tokens too (a small one is under a hundred, a large photo can be several hundred), so it counts towards the limit above.
- While a reply is running you can leave the Chat screen: press a screen number (1-9, 0) with the message field empty, or use `Ctrl+P`. The reply keeps streaming and the sidebar shows `…` (or `!` when the model is waiting for your answer). `Esc` pressed twice in Chat cancels the reply.
- A long note means a longer wait before the first words. "<model> is thinking…" means a reasoning model is working before it answers.

## Privacy
Everything goes to the Ollama server set in Settings. With the default `localhost`, **nothing leaves your computer**.
""".trim(),
    ),
    GuidePage(
        "Obsidian", "Obsidian and your files",
        """
# Obsidian and your files

## How Arcana uses the vault
- A vault is a plain folder of Markdown (`.md`) files. Arcana reads and writes those files **directly**. Obsidian does not have to be running, and it picks up changes on its own.
- Hidden folders such as `.obsidian` and `.trash` are not listed as notes.

## What gets written
- **New notes** start with frontmatter (`created`, `generator: arcana`, `model`, `tags: [arcana]`) followed by Markdown. In Obsidian, search `tag:#arcana` to find everything AI made.
- **File names**: characters that cannot be used in links (`\ / : * ? " < > | # ^ [ ]`) become spaces. A name that already exists becomes `Name 2`.
- **Where**: the *Output folder* in Settings → Vault (empty means the vault root).
- **Add to note** appends to the end under a `## Heading`. **Replace note** swaps the text but keeps your own frontmatter.

## A safety net
- **Replace** and **Delete** never erase anything. The old text goes to the vault's `.trash` folder (`Name (backup date).md`), which Obsidian uses as its trash. To undo, move the file back.
- A draft is only a draft: nothing is written until you press `n`, `a`, `o` or `w`.
- Chats are written to the vault only when you save them (`Ctrl+S`).

## Links and tags
- In vault mode, Chat cites notes as `[[Note title]]`. When you save the chat or an answer those become real Obsidian links, and a saved chat lists its *Sources* as links.
- Your own links and tags inside a note are sent to the model as plain text.

## Tips
- Clear titles and short notes help: the vault search finds notes by names and words.
- **AI notes → Extract action items** turns meeting notes into a checklist, and **Suggest tags and links** helps connect a note to the rest.
- Sync tools (iCloud, Dropbox, Obsidian Sync) just see ordinary file changes.
""".trim(),
    ),
    GuidePage(
        "Keys", "Typing, lists and scrolling",
        """
# Typing, lists and scrolling
The same keys work on every screen.

## In a text field
- `←` `→` move the cursor, `Home` `End` go to the ends of the line.
- `Alt` or `Ctrl` with `←` `→` jump by word. Add `Shift` to select. `Alt+A` selects everything.
- `Backspace` deletes, `Ctrl+W` or `Alt+Backspace` deletes a word, `Ctrl+U` clears to the start of the line, `Ctrl+K` to the end.
- Pasting works as usual, even long texts.

## In a list
- `↑` `↓` move, `PgUp` `PgDn` jump. Press `/` to filter by typing; `Esc` clears the filter.

## Long output
- `↑` `↓` or `k` `j` scroll a line, `PgUp` `PgDn` or `Space` a page, `g` and `G` jump to the top and the latest text.
- `s` starts **select mode** to copy parts of the output (`c` copies, `b` copies only code).

Letters like `k` `j` `s` only act as keys when you are not typing in a field.
""".trim(),
    ),
    GuidePage(
        "More", "Usage, settings and troubleshooting",
        """
# Usage, settings and troubleshooting

## Usage
The **Usage** screen is a dashboard of what the model did, over this session, today, 7 days, 30 days or all time (`←` `→`). `Tab` switches page: **Team** (what the helper models cost and did: tokens by chat and helpers, models, flow acceptance, redos, verdicts, goals, routes; scroll with ↑↓), **Overview** (totals, a chart of tokens by hour or day, and insights: busiest day, peak hour, streak, week on week, typical and slowest reply, context load), **Breakdown** (by feature and model with their share, what the Oracul, Planner and Judge cost, when in the day you use it, speed trend) and **Recent replies** (a table of the latest ones with tokens, time and speed). Panels that do not fit a small terminal are left out. The footer also shows a running total. Everything is stored in `~/.config/arcana/usage.jsonl`.

## Make it yours (Settings)
- **Appearance**: colour theme (Dark, Light, Soft, Darcula, Solarized, Nord), sidebar, logo animation.
- **Console**: Markdown rendering, chat history length, whether chats are kept, what copying puts on the clipboard.
- **Accessibility**: interface density and a plain-words mode for screen readers.
- **Tabs**: put the screens in the order you like. A tab's position is its number key.

## Where things are kept
Settings, usage and saved chats live in `~/.config/arcana/`. Your notes stay in your vault.

## If something is wrong
- *Red badge, "Ollama offline"*: start Ollama, or fix the URL in Settings.
- *Orange badge*: the chosen model is not installed. Pick another in Settings → Ollama → Model.
- *"Terminal too small"*: make the window at least 60 columns by 12 rows.
- Colours look washed out: use a terminal with 24-bit colour.
""".trim(),
    ),
)

@Composable
fun GuideScreen(app: AppContext) {
    val guide = remember { pages() }
    var page by remember { mutableIntStateOf(0) }
    val output = remember { OutputState() }
    val current = guide[page]
    val entries = rememberEntries(current.markdown)
    var notice by remember { mutableStateOf<Notice?>(null) }

    if (output.selecting) {
        Hints("↑↓  move", "Space  tick", "c  copy", "b  code only", "Esc  leave")
    } else {
        Hints("←→  page", "↑↓/Space  scroll", "s  select & copy")
    }

    // A page is a document, not a stream: it opens at its top (the output view otherwise follows the latest text).
    LaunchedEffect(page) { output.offset = output.maxOffset }

    fun go(delta: Int) {
        page = (page + delta).mod(guide.size)
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
        ScreenHeader("Guide", "How to use Arcana · page ${page + 1} of ${guide.size}")
        VGap()
        TabBar(guide.map { it.tab }, page)
        VGap()
        Panel(current.title) {
            val width = outputWidth()
            val lines = remember(current, width, app.config.plainGlyphs, Palette.themeId) {
                renderMarkdown(parseMarkdown(Shortcuts.label(current.markdown)), width, plainGlyphs = app.config.plainGlyphs)
            }
            output.copyPlain = app.config.copyAs == "plain"
            // header 3, the page tabs and their gaps 2, the panel's frame 2, and a notice row only while there is one
            ScrollView(lines, outputRows(fixed = 7, notice = notice), output)
            NoticeLine(notice)
        }
    }
}
