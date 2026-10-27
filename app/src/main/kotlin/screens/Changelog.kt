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
Built on 24–25 October 2026. Last updated 26 October 2026.

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
- **Wrong tool names get a hint**: when a model calls a tool Arcana does not have (`http_request`, `curl`, `ls`, `grep`, `head`…) the error now says which tool does that job (`fetch_url` is GET only; a POST goes through `curl` in `run_command`), so it stops retrying the same name.
- **`cat`**: prints a file with line numbers (like `cat -n`), the way small models expect from a shell; it shares `read_file`'s limits. `git_diff` on a folder that is not allowed yet now asks "Allow this folder?" like `run_command` does.
- **Save to vault can overwrite**: in the `Ctrl+S` dialog, `Ctrl+O` lets you pick a note that already exists and replace it with the whole chat (or the last answer). You confirm first, and the old text is kept in the vault's `.trash`.
- **`request_mode`**: the model can ask to switch the tool mode (Ask, Auto or Plan) with a reason, for example Auto before a long series of edits or Plan to investigate first. You are always asked ("Switch to Auto mode?"); a refusal keeps the current mode.
- **Auto-run for shell commands**: Settings → Tools → *Run commands without asking* (or `/autorun` in Chat) lets commands run without your `y`. A red `▶ auto-run` pill in the Chat status bar says it is on; `sudo`, `rm -rf /` and the like are still refused.
- **Changes show as a diff before you allow them**: when the model wants to `edit_file` or overwrite a file with `write_file`, the approval panel shows what would really change, like `git diff` (`@@ -3,5 +3,5 @@`, two lines of context, red `-` and green `+` lines), not just the new text. A long diff is cut to fit the screen with a "… N more lines" note; a file that is not text or is over 500 KB falls back to the plain preview.
- **Thinking is kept apart from the answer**: what a reasoning model thinks is stored with its reply and shown as a dim `◌ thinking · 412 words` line above the answer (Settings → Console → *Thinking in Chat*: Summary, Full or Hidden). It is never copied with the answer and never sent back to the model.
- **Library gives the note more room**: the notes list takes 30 % of the width (it was 42 %) and the note preview the rest, with the divider line under its header running the whole width.
- **Who is speaking**: in Chat, replies are labelled `◆ qwen3:30b` and your messages `● You`; the glyph follows the model family (`◆` gemma, `◈` qwen, `▲` llama, `◐` mistral, `◇` phi, `◉` deepseek, …) and the status bar uses it too. The name is always exactly what Ollama calls the model.
- **Empty replies get a second chance**: a model that answers with nothing (after a tool result or a finished skill) is asked once more, out of sight, for a short answer; only a second empty reply shows "The model returned an empty response."
- **Cancelling a reply takes two `Esc` presses** in Chat (`Esc twice to cancel` in the status line, `Esc again to cancel` after the first), so a stray key does not throw away a long reply.
- **Real model names**: the thinking line in Chat shows the model exactly as Ollama names it (`gemma4:12b is thinking…`), the same as the status bar, not a prettified "Gemma4".
- **`git_diff`**: the model can see what changed in a repository (the patch, `stat`, or just the file names; staged or not; one path). Read-only and needs no approval, so it also works in Plan mode.
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
- Old tool output is shortened when the history is sent, so a long job does not fill the context.

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
