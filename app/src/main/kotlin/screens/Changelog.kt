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
Built on 24–25 October 2026.

## Usage became a dashboard
- Five periods (session, today, 7 days, 30 days, all time) and three pages: **Overview**, **Breakdown**, **Recent replies**.
- Overview: totals tiles, a column chart of tokens by hour or day, and insights (busiest day, peak hour, streak, week on week, typical and slowest reply, context load).
- Breakdown: by feature and by model with their share, when in the day you use it, and the speed trend. Recent replies: a table with tokens, time and tok/s.

## Chat got better to type in
- **Multi-line message field**: grows to a quarter of the screen with a **scrollbar**; `Alt+Enter`, or a `\` before Enter, starts a new line; Up/Down move between lines.
- **Pasting long text works**: a paste that lost its beginning *or its middle* is typed back whole from the clipboard.
- **Context meter** in the Chat header: `ctx ▰▰▱▱▱▱▱▱ 12% 13k/131k`, with a warning near 80%. Set the window in Settings → Ollama → *Context window*.
- A reply that ended in a tool call shows a quiet `step` line, so only the final answer says **Done**.

## More tools
- **`run_command`** (shell), off by default, every command asks first; harmless ones such as `git status` run at once; `sudo`, `rm -rf /` and similar are never run.
- **`ask_user`**: the model can ask you a question with options (a quiz or a decision); **`delete_path`** and **`move_path`** always ask first, even in Auto mode, and a deleted item goes to a trash folder you can restore from.
- **`fetch_url`** reads a public web page (HTML as readable text, JSON, PDF), off by default; every address asks first and is shown in full; localhost and your own network are refused, redirects are checked hop by hop.
- **`grep_files`** (search inside files), **globs** in `find_files`, and **line ranges** in `read_file`.
- Limits explain themselves: a dialog offers to turn tools on, leave plan mode, allow a folder, turn shell on or add more steps, right where the limit is hit.

## Smaller things
- Shortcuts stay **Ctrl** chords (Ctrl+P commands, Ctrl+N capture, Ctrl+O attach, Ctrl+S save…): ⌘ chords were tried and dropped because terminals and macOS claim them (Print, New window).
- Chat: type `/` for a menu of commands and tools (filter as you type, ↑↓ Tab/Enter).
- Library: `r` **Rename** a note and the links to it are updated everywhere; `c` **Chat about it** continues with a note attached (a saved chat note carries its whole conversation).
- This **Changelog** tab.
""".trimIndent(),
    ),
    ChangePage(
        "Tools", "Tools and the agent loop",
        """
# Tools and the agent loop
Gemma can act, not only talk: search your notes, read and edit files, run commands, with you in control.

## The loop
- The model asks for a tool, Arcana runs it and returns the result, and the model carries on, up to *Steps per question* rounds; at the limit a dialog offers more.
- If a model announces a step ("Next I will read…") instead of doing it, Arcana nudges it on (twice at most).
- Old tool output is shortened when the history is sent, so a long job does not fill the context.

## Safety
- Tools only reach your vault and the folders you allow; symlinks and `..` cannot escape. Hidden folders like `.git` and `.ssh` are never searched or offered.
- Every change asks first (`y`, `n`; `a` allows a tool for the session). A replaced file is backed up; `.git` and `.obsidian` are never written.
- Modes: **Ask**, **Auto** (changes run at once, backed up), **Plan** (read-only, the model writes a plan).
- New folders: an "Allow this folder?" dialog; your home folder and hidden folders are not offered.

## The tools
- Notes: `search_notes`, `list_notes`, `read_note`, `create_note`, `append_to_note`.
- Files: `list_dir` (also recursive), `find_files` (name or glob), `grep_files`, `read_file` (line ranges), `write_file`, `edit_file`, `delete_path`, `move_path`.
- Questions: `ask_user`.
- Shell and web: `run_command`, `fetch_url`. Pseudo-tool: `request_access`, for a model that finds tools off.

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
