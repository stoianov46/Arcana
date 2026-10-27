# Tools and the agent loop

Opt-in feature (`AppConfig.toolsEnabled`, default **false**). The local model may use tools to search, read and change the vault and
files, ask the user questions, run shell commands and read web pages. Everything that changes something, runs something or reaches
the network needs the user's confirmation in a dialog. Code: `core/Tools.kt`, `core/Shell.kt`, `core/Web.kt`,
`core/TextToolCalls.kt` (logic) and `app/ChatSession.kt`, `app/Generation.kt`, `app/screens/Chat.kt` (loop and dialogs).

## Sequence

```
user message ──► ChatSession.send ──► (maybe offerTools) ──► ask() ──► Generation.start(tools = definitions, maxSteps = config.toolSteps)
                                         │
              ┌──────────────────────────┴───────────────────────────────┐
              │ loop (step < limit):                                     │
              │   OllamaClient.chat(messages, tools)  → stream           │
              │   no tool_calls? TextToolCalls.extract (call written as  │
              │     text at the end of the reply) → treated as a call    │
              │   still none, and the reply only announces a step        │
              │     (announcesAction)? → hidden nudge, ≤2 in a row     │
              │   else finish (final reply, onComplete)                  │
              │   calls: ChatSession.runTools(text, calls, stats)        │
              │     add assistant(toolCalls) message to the chat         │
              │     for each call: dialogs (folder, switches, approval)  │
              │       → ToolBox.execute on IO (runInterruptible)         │
              │       → add tool-result message to the chat              │
              │   messages += [assistant(toolCalls), tool results…]      │
              │ at the limit: onStepLimit asks "N more steps?"; if no    │
              │ the tools are no longer offered and the model must answer│
              └──────────────────────────────────────────────────────────┘
```

- Tool-call/result messages are real `ChatMessage`s stored in the chat: assistant `toolCalls` (+ optional text), then
  `role="tool"` with `toolName` and the result text. When history is sent, tool output older than the latest user message is cut to
  1,200 characters (`Prompts.chatMessages`).
- State stays `GenState.Running` for the whole loop; the generation belongs to `ChatSession`, so leaving the Chat screen does not stop it.
  Each round's tokens are recorded to usage. A round that ended in a tool call shows a quiet `· step` footer; only the last says `✓ Done`.
- Cancelling (`Esc` twice in Chat, `Generation.requestCancel`) cancels the coroutine; pending dialogs are cancelled and a running command is interrupted.
- The model's capability is checked (`AppContext.tools`); without it Chat sends without tools and says so (toast, `⚠ no tools` pill).

## The tools

| Name | Risk | Arguments | Notes |
|---|---|---|---|
| `search_notes` | Read | `query`, `limit?` | `Vault.search`, ≤20 results with excerpts |
| `list_notes` | Read | `folder?` | vault-relative paths, ≤200 |
| `read_note` | Read | `path` | `.md` optional; truncated at 20,000 chars |
| `list_dir` | Read | `path`, `recursive?` | folders end with `/`; recursive = whole tree as relative paths (≤500, 10 levels; hidden files shown, `.git`/`.ssh`-style folders and build/media folders skipped) |
| `find_files` | Read | `name?`, `glob?`, `path?` | by part of the name and/or a glob (`**/*.kt`; without a `/` it matches the file name); breadth-first, 20 levels, ≤40 hits, 25 s; no `path` = every allowed folder |
| `grep_files` | Read | `pattern`, `path?`, `glob?` | regex (case-insensitive) inside text files: `file:line: text`, ≤60 hits; skips binary, PDFs, files over 2 MB |
| `read_file` | Read | `path`, `start_line?`, `end_line?` | text, code, JSON, CSV…, PDF text layer; whole file cut at 20,000 chars; with a line range (≤400 lines) the lines come numbered with a pointer to the next range |
| `create_note` | Write | `title`, `content`, `folder?` | `Vault.create` into `folder` or `outputFolder`; frontmatter with the configured model; a properties block at the top of `content` is **merged** with Arcana's (its keys win), never written as a second block |
| `append_to_note` | Write | `path`, `content`, `heading?` | `Vault.append` |
| `set_properties` | Write | `path`, `properties` | `Vault.setProperties`: one `key: value` per line (list values may continue on indented / `- item` lines); an existing key is replaced in place, a new one added, the rest and the text stay; a note without a block gets one; plan mode has no write tools, so none |
| `remember` | Write, always asks | `text` | appends a line under "Remembered" in `ARCANA.md` |
| `write_file` | Write | `path`, `content` | create or replace; **backup** of the old file; creates parent dirs |
| `edit_file` | Write | `path`, `old`, `new` | `old` must occur exactly once; backup first |
| `delete_path` | Write, always asks | `path` | never erases: moves to `<vault>/.trash` (inside the vault) or `<config>/trash`; allowed roots, home and `.git`/`.obsidian` are refused |
| `move_path` | Write, always asks | `from`, `to` | rename/move; refuses to overwrite or to move a folder into itself; Obsidian links are not updated (Library rename does that) |
| `current_time` | Read | `timezone?` | date, weekday, time zone, ISO and Unix time (`describeNow`); also `Prompts.chatMessages(today = …)` puts only the **date** in the system prompt (a clock there would make the model re-read the whole chat every message) |
| `elapsed_time` | Read | `from`, `to?` | time between two moments (default: until now): `total_work_time=5 min 12 s`, `seconds=312`, `from=`, `to=` (local ISO, so the model can copy them into properties); `from`/`to` accept ISO with an offset or `Z`, a local date-time (`T` or space; this computer's zone) or Unix seconds/ms (`parseMoment`, `describeDuration`, `describeElapsed`); error when the end is before the start; pairs with `current_time` for "how long did this take" (models cannot subtract clock times reliably) |
| `wait` | Read | `seconds`, `reason?` | sleeps 1–`MAX_WAIT_SECONDS` (120) seconds on the IO thread, then returns "Waited N s." and the time (`describeNow`); for rate limits (CoinGecko allows only ~5–7 requests a minute): wait, then retry; longer waits = call again; `Esc` interrupts it; no approval |
| `use_skill` | Read | `name` | (see [skills.md](skills.md)) **only offered when the vault has at least one skill** (`ToolBox.skills`, folder `AppConfig.skillsFolder`, switch `skillsEnabled`); returns `Skills.load`: folder, file list, entry-note text without properties, cut at 20,000 characters; allowed in plan mode; see "Skills" |
| `ask_user` | Read (answered by the chat) | `question`, `options?`, `multiple?` | see "Questions" below; offered even when tools are off |
| `git_diff` | Read | `cwd?`, `staged?`, `summary?` (`stat`/`names`/`status`), `path?` | runs `git --no-pager diff --no-color --no-ext-diff --no-textconv` (fixed flags and one single-quoted path, so it can only look) in a folder inside the allowed ones through `runShell` (30 s, output cut like `run_command`); needs no shell switch and no approval, and is allowed in plan mode |
| `run_command` | Shell | `command`, `cwd?`, `timeout_seconds?` | off by default (`toolShell`); see "Shell" |
| `fetch_url` | Web | `url`, `offset?` | off by default (`toolWeb`); see "Web" |
| `web_search` | Web | `query`, `limit?` | same switch as `fetch_url`; see "Web"; returns title, address and snippet per result (≤10) |
| `request_access` | pseudo-tool | `reason` | the only tool a model has when tools are off (plus `ask_user`); asks the user to turn tools on or leave plan mode |

Allowed roots = vault root + `toolFolders` (`;`-separated, `~` expanded). Writes additionally refuse any path containing a `.git`
or `.obsidian` segment. Backups: `<config dir>/backups/<yyyyMMdd-HHmmss>-<name>` (suffix `.2`, `.3` on collision).
`ToolBox.cheatSheet()` lists every tool with its exact parameter names (optional ones end in `?`); it goes into the system prompt
with `ToolBox.describe()` so small models neither forget nor invent tools.

## Key types (`core/Tools.kt`)

| Type | Role |
|---|---|
| `ToolCall(function: ToolFunction(name, arguments: JsonObject))` | what the model asked for (Ollama wire shape) |
| `ToolRisk { Read, Write, Shell, Web }` | everything but `Read` needs approval; `Web` covers `fetch_url` and `web_search` |
| `ToolSpec(name, description, parameters, risk, summary, preview, alwaysAsk, confirmHeading, run)` | one tool; `summary(args)` is the one-line subject shown in the chat, `preview(args)` the lines of the approval dialog (`+`/`-` for changes); `alwaysAsk` = asks in every mode and offers no "always" |
| `FileAccess(roots)` | sandbox: `resolve(raw, base)` accepts absolute, `~/…` or relative-to-vault paths; a relative path that does not exist in the vault is looked for in the other roots; rejects anything whose **real path** (symlinks resolved) is outside the roots (`OutsideFoldersException`) |
| `ToolBox(vault?, folders, backups, outputFolder, model, readOnly, shell, web)` | builds `specs`, `find`, `definitions()` (Ollama `tools` JSON), `execute(call)` (exceptions → `"Error: …"`), `folderToGrant(call)`, `allow(folder)`, `describe()`, `cheatSheet()`; `readOnly` (plan mode) keeps only `Read` specs |
| `TOOLS_PROMPT`, `REQUEST_ACCESS_PROMPT`, `NO_TOOLS_PROMPT` | added to the system prompt when tools are on / off-but-requestable / not possible |

## Paths: what the model is told

A **relative path means inside the vault**; anything else must be absolute or start with `~/`. `ToolBox.describe()` tells the model the
vault location, the home folder, the folders already allowed, and the tool list. Path errors say exactly what is wrong ("X does not
exist … Did you mean: /Users/…/X?" using a bounded search of the allowed folders), because the model reads them and retries.

## Modes (`AppConfig.toolMode`, `/mode`)

`ask` (default): `Write` tools wait for approval. `auto`: `Write` tools run at once (backups and the sandbox still apply; the folder
prompt still appears; `alwaysAsk` tools still ask). `plan`: `ToolBox(readOnly = true)` drops every non-`Read` spec, and `describe()`
adds the PLAN MODE instruction (investigate, write a plan, ask for approval, call `request_access` to leave plan mode). The mode is read
when a message is sent.

## Dialogs (`ChatSession.Approval` + `Chat.kt`)

`ChatSession.approval: Approval?` (`tool`, `title`, `lines`, `heading`, `icon`, `yes`, `always`, `answer`). Chat draws it as a `Panel`
with its buttons **inside the panel** (`ActionBar(items, panelWidth())`); keys `y` yes, `n`/`Esc` no, `a` always this session (only
when `always`). Other keys (screen numbers, Ctrl+C) fall through. While one is pending the sidebar's Chat badge shows `!`.

Dialogs used: "Allow this change?" (writes), "Delete this?" / "Move this?" / "Remember this?" (always ask), "Allow this folder?",
"Run this command?", "Fetch this web page?", "Turn on tools?" / "Leave plan mode?", "Turn on shell commands?", "Turn on web pages?",
"Step limit reached", "Run this tool call?" (calls written as text, ask mode), "Memory file created".

### Limits explained on the spot

Whenever a limit is hit the user gets a dialog instead of a model saying "I cannot":
- **Tools off** (or **plan mode**): `looksLikeToolTask(message)` (a path, or files/folders/project words) offers "Turn on tools?" before
  the model answers (`offerTools`; `n` is remembered for the chat in `toolsDeclined`); the model can also call `request_access(reason)`.
  `y` changes the config at once and rebuilds the toolbox (`Generation.useTools`).
- **Folder not allowed**: a file tool, `run_command` (`cwd`) or `move_path` aimed outside the allowed folders → `ToolBox.folderToGrant(call)`
  (the directory, or its nearest existing parent) → "Allow this folder?". Not offered: the home folder, anything with fewer than 3 path
  segments (`/etc`), hidden folders. `y`/`a` → `box.allow(folder)` and the folder is appended to `config.toolFolders`. The error otherwise
  names Settings → Tools → Extra folders.
- **Step limit**: `Generation.onStepLimit` asks "N more steps?".
- **Shell / web off**: the first call asks to turn them on (below).
- **Model without tool support**: toast on send and the `⚠ no tools` pill.

## Questions (`ask_user`)

`ChatSession.askUser(call, beside)` shows a `Question(text, options, multiple)` panel: options numbered `1`–`9`, ↑↓ moves a `▸` cursor,
Enter chooses (several: Space ticks, Enter confirms the ticked ones), `1`–`9` pick directly, the last row "✎ Type my own answer" is an
inline text field (`Question.field`; `t` or ↓ moves to it, ↑ goes back, Enter sends), `Esc` skips. The result is "The user chose: …" /
"answered: …" / "skipped". Cleaning: `cleanOption` removes `A)`, `1.`, `(b)` labels, backticks and bold marks from options; the question
text is taken from `question` (or `prompt`/`text`/`message`/`title`/`query`/`ask`), else from the text beside the call, else the model
gets an error telling it to call again. Offered in plan mode and when tools are off.

## Shell (`run_command`)

`ToolRisk.Shell`, off by default (`toolShell`; the first call asks "Turn on shell commands?"). `ShellPolicy` (core/Shell.kt):
`blockedReason` refuses sudo, `rm -r` on `/ ~ * ..`, disk formatting, fork bombs and download-into-shell; `isSafe` lets only single simple
commands (pwd, date, whoami, uname, hostname, which, git status/log/diff/show/branch/…) run without a prompt when *Read without asking* is
on. Everything else shows "Run this command?" with the command, folder and timeout (never "always"). `runShell`: `/bin/sh -c`, stdin
closed, stdout+stderr merged, output capped at 20,000 characters (start and end kept), 60 s default / 600 s max, the process tree is killed
on timeout or cancel. `cwd` must be inside an allowed folder. Plan mode never offers it.

## Web (`fetch_url`)

`ToolRisk.Web`, off by default (`toolWeb`; first call asks "Turn on web pages?"). `WebPolicy.refusal` (core/Web.kt): http/https only, no
user info, and the host must resolve to public addresses only (loopback, link-local, site-local, 100.64/10, fc00::/7, multicast and
`0.0.0.0` are refused). `fetchUrl` follows redirects by hand (≤5) re-checking every hop; reads ≤2 MB; HTML → `htmlToText` (title,
headings `#`, list items `-`, links `text (url)`, scripts/styles dropped), JSON/text as is, PDF through `TextFiles`; 20,000 characters per
call, continue with `offset`. Every call asks ("Fetch this web page?", never "always": the address itself can carry data out); page text
is untrusted data. Plan mode never offers it.

`web_search` (core/WebSearch.kt) asks one of three backends (`AppConfig.searchBackend`): **duckduckgo** (default; the plain HTML page `html.duckduckgo.com/html/`, no key, parsed by `parseDuckDuckGo`; ads skipped, the `uddg=` redirect unwrapped; it can answer with a human check, which becomes an error telling the user to pick another backend), **searxng** (the user's own server at `searchUrl`, JSON API `…/search?q=…&format=json`; its address may be on the local network because the user configured it), **brave** (`api.search.brave.com`, header `X-Subscription-Token` from `searchKey`; the key is stored in `config.json`). Only the search words leave the computer. Every search asks ("Search the web?", never "always"; the dialog names the backend host). Results are formatted as a numbered list ending the hint "open one with fetch_url". The first call while `toolWeb` is off asks "Turn on web pages?" (one switch for both tools).

## Skills (`core/Skills.kt`) — summary; the full explanation is in [skills.md](skills.md)

A **skill** is a runbook in the vault (`<skills folder>/<name>/<name>.skill.md` with `reference/` and `scripts/`; its results go to a separate folder
named by `output:`). In the tool loop it shows up in four places:

- **`use_skill(name)`** (*Read*; only offered when the vault has ≥ 1 skill and skills are on): returns `Skills.load` (folder, results folder, file list, the entry note
  without its properties, cut at 20,000 characters). `ToolBox.describe()` adds the catalog (`- name: description`) and tells the model to call it first when a request matches.
- **A skill chip** on the user's message (`/` menu or `@`): the note is an attachment; `ChatSession.noteTexts` loads it with `Skills.loadEntry` and `Prompts.chatMessages`
  appends it ("SKILL “name”: … already loaded, do not call use_skill"). `Skills.stepsFor` raises that question's tool-round limit to at least `RUN_STEPS` (40).
- **The completion gate**: a skill's `requires:` list is tracked by a `SkillRun` that `runTools` feeds with every call and result; when the model answers without a tool
  call while a step is missing, `Generation(gate = { skillRun?.nudge() })` sends it back with a hidden STOP message (3 strikes per step, then the step is skipped). The Chat
  status bar shows `◆ <skill> 7/11`. Generic: only skills with a non-empty `requires:` get a run; the run ends at the next user message or `/new`.
- **The "/" menu and `/skills`**: skills are scanned from the folder (never hardcoded) every time the menu opens; `/skills` toggles `skillsEnabled` and lists problems.

Everything a skill then does goes through the normal tools and their approvals. Tests: `CoreTest.skillsAreFoundLoadedAndOfferedToTheModel`,
`CoreTest.aSkillCanRequireStepsAndTheRunIsTracked`, `SkillGateTest` (the real loop against a fake Ollama server).

## Calls written as text

Small models sometimes answer `fetch_url(url="…")` as plain text instead of a structured call, and the loop would end with nothing done.
`TextToolCalls.extract` finds such calls: only known tools, only at the end of the reply, as `tool(arg="value", …)` or a JSON object with
`name` and `arguments`, so a reply that merely explains a tool is never executed. `AppConfig.textCalls`: **auto** (default) runs them as
real calls (approvals still apply), **ask** shows "Run this tool call?" first, **off** leaves the text.

The "keep going" nudge (`announcesAction`): a reply that only announces a step ("I will read…", "Let me check…", "This will help me
locate…") gets a hidden "continue now" message (≤2 **in a row**: the counter resets after every real tool call, because a long skill narrates before many of its steps; with a per-question limit the third narration ended a skill run before its analysis step). "Let me know…" and real questions (`?` followed by space/end, not the
`?` of a URL) do not count.

## Memory file

The vault note `ARCANA.md` is added to the system prompt (see [functionality.md](functionality.md)); the `remember` tool appends to it.
The template holds fourteen rules for using tools. `ToolBox.cheatSheet()` supplies the exact tool list, so the note needs no copy of it.

## Rendering in chat (`screens/Chat.kt`)

Assistant message with `toolCalls`: model name, optional text, then `⚙ <tool>  <summary>` per call (`callSummary`: command, url, question,
from, path, query, pattern, name, glob, title, folder, reason, seconds). `role="tool"` message: `↳ ✓ <first line> (N characters)` or red `✕` for
`Error:` / denied / non-zero exit code / timeout. Tool messages are **not** select-mode entries, are skipped by "save chat" transcripts,
and don't count as "last answer".

## Session log and vault refresh

A successful tool call logs `SessionKind.Tool` (`"<tool> <summary>"`). A successful non-`Read` tool calls `brain.changed()` so the
Brain/Library reread the vault.

## Security model

1. Capability: only vault + configured folders (real-path check; `..`, absolute paths and symlinks cannot escape). New folders are added
   only by the user (Settings, or the folder dialog). Shell commands are not sandboxed by folder (they run with the user's rights) except
   their working directory; that is why every one is shown first.
2. Consent: every change, command and fetch needs `y`; `delete_path`, `move_path`, `remember`, shell and web never offer "always".
3. Reversibility: replaced/edited files are backed up first; deletions go to a trash folder; notes are created with unique names; vault
   writes use `Vault` methods.
4. Prompt injection: file, note and web text is untrusted. The dialog shows exactly what will be written, run or fetched; do not add
   tools that act without consent on data read from files or pages.
5. Off by default: tools, shell and web; model capability checked.

## How to add a tool

1. Add a `ToolSpec` in `ToolBox.vaultTools` or `fileTools` (or a dedicated `xxxTool()` like `shellTool`, `webTool`): choose `ToolRisk`,
   write a clear `description` and a JSON schema (`schema(...)` helper), `summary`, and for anything that acts a `preview`
   (`alwaysAsk = true` for destructive ones). Add it to `specs`.
2. Keep path handling through `access.resolve(...)` (files) or `Vault` methods (notes); throw `IllegalArgumentException` with a helpful
   message (it becomes `Error: …` for the model). If it takes a folder argument, add it to `folderToGrant`.
3. A tool that needs UI (like `ask_user`) is intercepted in `ChatSession.runTools`.
4. Add a test in `CoreTest` (sandbox, backups, error cases, the cheat sheet picks it up automatically). Update the Guide's "Letting it act"
   section, the Changelog page and this file.
5. For a risky capability add a risk level and an explicit preview first.

## Planned / not done

- A Skills screen (list with problems, open the entry note, create from a template); skill-level `needs` checks (warn when a needed tool is off).

- An `http_request` tool (POST/headers) designed like `fetch_url`; more search backends.
- MCP client support (reuse the same loop/permission layer); per-folder read/write rights; tools in AI notes.
