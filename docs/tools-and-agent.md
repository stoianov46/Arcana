# Tools and the agent loop

Opt-in feature (`AppConfig.toolsEnabled`, default **false**). Lets the local model use tools to search/read/write the vault and
files, with user approval for every change. Shell execution is **not implemented** (planned later; same loop + permissions).

## Sequence

```
user message ──► ChatSession.send ──► Generation.start(tools = box.definitions(), maxSteps = config.toolSteps)
                                         │
              ┌──────────────────────────┴───────────────────────────────┐
              │ loop (step < maxSteps):                                  │
              │   OllamaClient.chat(messages, tools)  → stream           │
              │   if no tool_calls: finish (final reply, onComplete)     │
              │   else: ChatSession.runTools(text, calls, stats)         │
              │           add assistant(toolCalls) message to chat       │
              │           for each call:                                 │
              │              risk Write (or !toolAutoRead) and not       │
              │              "always allowed" → Approval panel, await    │
              │              y → run   a → run + remember   n/Esc → deny │
              │              ToolBox.execute (IO) → "Error: …" on failure│
              │              add tool-result message to chat             │
              │   messages += [assistant(toolCalls), tool results…]      │
              │   next round                                             │
              │ when step == maxSteps: tools are no longer offered, so   │
              │ the model must answer in text                            │
              └──────────────────────────────────────────────────────────┘
```

- Tool-call/result messages are real `ChatMessage`s stored in the chat (so later turns and saved chats keep them):
  assistant `toolCalls` (+ optional text) then `role="tool"` with `toolName` and the result text.
- State stays `GenState.Running` for the whole loop; `text` is reset between rounds (the pre-tool text is part of the stored
  assistant message). Each round's tokens are recorded to usage (`onUsage` per `done` chunk).
- Cancelling (`Esc` / `gen.cancel()`) cancels the coroutine; a pending approval's `await()` is cancelled and `approval` is cleared.
- If the model lacks the `tools` capability (`AppContext.tools == false`) the chat sends without tools and `/tools` warns.

## Key types (`core/Tools.kt`)

| Type | Role |
|---|---|
| `ToolCall(function: ToolFunction(name, arguments: JsonObject))` | what the model asked for (Ollama wire shape) |
| `ToolRisk { Read, Write }` | `Write` tools always need approval |
| `ToolSpec(name, description, parameters(JSON schema), risk, summary, preview, run)` | one tool: `summary(args)` = one-line subject for the chat; `preview(args)` = lines shown in the approval panel (`+ ` added, `- ` removed); `run(args)` does the work and returns text |
| `FileAccess(roots)` | sandbox: `resolve(raw, base)` accepts absolute, `~/…` or relative-to-vault paths and rejects anything whose **real path** (symlinks resolved, incl. not-yet-existing files) is outside the roots |
| `ToolBox(vault?, folders, backups, outputFolder, model)` | builds `specs`, `find(name)`, `definitions()` (Ollama `tools` JSON), `execute(call)` (exceptions → `"Error: …"`) |
| `TOOLS_PROMPT` / `NO_TOOLS_PROMPT` | appended to the system prompt when tools are on / off (off: the model is told to point the user to `/tools` instead of claiming it cannot do such things) |

## The tools

| Name | Risk | Arguments | Notes |
|---|---|---|---|
| `search_notes` | Read | `query`, `limit?` | `Vault.search`, ≤20 results with excerpts |
| `list_notes` | Read | `folder?` | vault-relative paths, ≤200 listed |
| `read_note` | Read | `path` | `.md` optional; truncated at 20,000 chars |
| `list_dir` | Read | `path`, `recursive?` | folders end with `/`; recursive = whole tree as relative paths (≤500, 10 levels; hidden files shown, .git/.ssh-style folders and build/media folders skipped) |
| `find_files` | Read | `name?`, `glob?`, `path?` | by part of the name and/or a glob (`**/*.kt`; without a `/` it matches the file name); 20 levels, ≤40 hits, 25 s; hidden files shown, `.git`/`.ssh`-style folders, Library, build, node_modules, media skipped |
| `grep_files` | Read | `pattern`, `path?`, `glob?` | regex (case-insensitive) inside text files: `file:line: text`, ≤60 hits; skips binary, PDFs and files over 2 MB; same folder skips |
| `read_file` | Read | `path`, `start_line?`, `end_line?` | text, code, JSON, CSV…, or PDF text layer; whole file cut at 20,000 chars; with a line range (≤400 lines) the lines come numbered (`12 \| text`) with a pointer to the next range |
| `create_note` | Write | `title`, `content`, `folder?` | `Vault.create` into `folder` or `outputFolder`; frontmatter with the configured model |
| `append_to_note` | Write | `path`, `content`, `heading?` | `Vault.append` |
| `write_file` | Write | `path`, `content` | create or replace; **backup** of the old file; creates parent dirs |
| `edit_file` | Write | `path`, `old`, `new` | `old` must occur exactly once; backup first |

Allowed roots = vault root + `toolFolders` (`;`-separated, `~` expanded). Writes additionally refuse any path containing a
`.git` or `.obsidian` segment. Backups: `<config dir>/backups/<yyyyMMdd-HHmmss>-<name>` (suffix `.2`, `.3` on collision).

## Modes (`AppConfig.toolMode`, `/mode`)

`ask` (default): `Write` tools wait for approval. `auto`: `Write` tools run at once (backups and the sandbox still apply; the "Allow this
folder?" prompt still appears). `plan`: `ToolBox(readOnly = true)` drops every `Write` spec, so the model cannot call them, and
`describe()` adds the PLAN MODE instruction (investigate, write a plan, ask for approval). The mode is read when a message is sent.

## Shell (`run_command`)

`ToolRisk.Shell`, off by default (`toolShell`; the first call asks "Turn on shell commands?"). `ShellPolicy` (core/Shell.kt): `blockedReason` refuses sudo, `rm -r` on `/ ~ * ..`, disk formatting, fork bombs and download-into-shell; `isSafe` lets only single simple commands (pwd, date, git status/log/diff…) run without a prompt when *Read without asking* is on. Everything else shows "Run this command?" with the command, folder and timeout (never "always"). `runShell`: `/bin/sh -c`, stdin closed, output merged and capped at 20,000 chars, 60 s default / 600 s max, process tree killed on timeout or cancel. `cwd` must be inside an allowed folder (the folder prompt applies). Plan mode never offers it.

## Asking, deleting, moving

- `ask_user(question, options?, multiple?)` is answered by `ChatSession.askUser` (a `Question` with `options`, `picked`, `typing`): `1`-`9` choose (several: tick, Enter confirms), `t` types an open answer in the message field, `Esc` skips. The result is "The user chose: …" / "answered: …" / "skipped". It is offered even when tools are off (with `request_access`) and in plan mode.
- `delete_path` never erases: files and folders move to `<vault>/.trash` (inside the vault) or `<config>/trash` (elsewhere) under a timestamped name. `move_path` renames or moves and refuses to overwrite or to move a folder into itself. Allowed roots, the home folder and `.git`/`.obsidian` are never deleted or moved. Both specs have `alwaysAsk = true`: they ask in Auto mode too and offer no "always".

## Web (`fetch_url`)

`ToolRisk.Web`, off by default (`toolWeb`; first call asks "Turn on web pages?"). `WebPolicy.refusal` (core/Web.kt): http/https only, no user info, and the host must resolve to public addresses only (loopback, link-local, site-local, 100.64/10, fc00::/7, multicast and `0.0.0.0` are refused). `fetchUrl` follows redirects by hand (≤5), re-checking every hop; reads ≤2 MB; HTML → `htmlToText` (title, headings `#`, list items `-`, links `text (url)`, scripts/styles dropped), JSON/text as is, PDF through `TextFiles`; 20,000 characters per call, continue with `offset`. Every call asks ("Fetch this web page?", never "always": the address itself can carry data out) and page text is untrusted data. Plan mode never offers it.

## Calls written as text

Small models sometimes answer `fetch_url(url="…")` as plain text instead of a structured call, and the loop would end with nothing done. `TextToolCalls.extract` (core/TextToolCalls.kt) finds such calls: only known tools, only at the end of the reply (nothing after them), as `tool(arg="value", …)` or a JSON object with `name` and `arguments`, so a reply that merely explains a tool is never executed. `AppConfig.textCalls`: **auto** (default) runs them as real calls (approvals for changes, commands and addresses still apply), **ask** shows "Run this tool call?" first, **off** leaves the text. The "keep going" nudge ignores the `?` of a web address (`announcesAction`).

## Limits explained on the spot

Whenever a limit is hit the user gets a dialog (same panel as approvals, `ChatSession.Approval` with `heading/icon/yes/always`) instead of a model saying "I cannot":
- **Tools off** (or **plan mode**): `looksLikeToolTask(message)` (a path, or files/folders/project words) offers "Turn on tools?" before the model answers; a model can also call the pseudo-tool `request_access(reason)` (the only tool it has when tools are off; added to the read-only set in plan mode). `y` changes the config at once and rebuilds the toolbox (`Generation.useTools`). `n` is remembered for the chat (`toolsDeclined`).
- **Folder not allowed**: "Allow this folder?" (see below). Hidden/home/system folders are not offered; the error names Settings → Tools → Extra folders.
- **Step limit**: `Generation.onStepLimit` asks "Step limit reached – N more steps?".
- **Model without tool support**: toast on send and `⚠ <model> cannot use tools` in the Chat header.

## Approval UI (`ChatSession` + `screens/Chat.kt`)

`ChatSession.approval: Approval?` (`tool`, `title`, `lines`, `answer: CompletableDeferred<ApprovalChoice>`).
When non-null, Chat replaces the message field with a `Panel("Allow this change?")` and sets `FocusMode(true)`. Keys:
`y` Allow, `n`/`Esc` Deny, `a` Always (this tool, this session, in-memory set `alwaysAllowed`). Other keys fall through (screen
numbers, Ctrl+C). The deny result text tells the model not to retry and to ask the user instead.

## Paths: what the model is told

A **relative path always means inside the vault**; anything else must be absolute or start with `~/`. The model cannot guess the
home folder, so `ToolBox.describe()` is appended to the system prompt (vault location, home folder, the path rule) via
`Prompts.chatMessages(..., toolsNote)`. Path errors say exactly what is wrong ("X does not exist (a relative path is looked up
inside the vault …). Use an absolute path or ~/…", "X is a file, not a folder") because the model reads them and retries.

## Allowing a new folder on the spot

A file tool (`list_dir`, `read_file`, `write_file`, `edit_file`) aimed outside the allowed folders raises `OutsideFoldersException`.
Before running a call, `ChatSession.runTools` asks `ToolBox.folderToGrant(call)`: the directory (or nearest existing parent) the user
could allow, or null when the folder is too broad or hidden (the home folder, anything with fewer than 3 path segments such as `/etc`,
any dot-folder such as `~/.ssh`). If non-null, an `Approval(tool="folder")` is shown ("Allow this folder?"). `y`/`a` →
`box.allow(folder)` and the folder is appended to `config.toolFolders` (persisted; removable in Settings → Tools); `n` → the call is
denied. Note for contributors: a `java.nio.file.Path` is an `Iterable<Path>`, so use `list + listOf(path)`, never `list + path`.

## Rendering in chat (`screens/Chat.kt`)

Assistant message with `toolCalls`: model name, optional text, then `⚙ <tool>  <path|query|title|folder>` per call.
`role="tool"` message: `  ↳ ✓ <first line> (N characters)` or `✕` in red for `Error:` / denied. Tool messages are **not** select-mode
entries (`entriesOf`), are skipped by "save chat" transcripts (`transcript`), and don't count as "last answer".

## Session log and vault refresh

A successful tool call logs `SessionKind.Tool` (`"<tool> <summary>"`). A successful `Write` tool calls `brain.changed()`
so the Brain/Library reread the vault.

## Security model

1. Capability: only vault + configured folders (real-path check; `..`, absolute paths and symlinks cannot escape). New folders are added only by the user (Settings, or the folder prompt above).
2. Consent: every change needs `y` (or an explicit session-wide `a`); read-only tools can also be made to ask (`toolAutoRead=false`).
3. Reversibility: replaced/edited files are backed up first; notes are created with unique names; vault writes use `Vault` methods.
4. Prompt injection: file/note text is untrusted. The approval panel shows exactly what will be written; do not add tools that act
   without consent on data read from files.
5. Off by default; model capability checked.

## How to add a tool

1. Add a `ToolSpec` in `ToolBox.vaultTools` or `fileTools` (`core/Tools.kt`): choose `ToolRisk`, write a clear `description` and a JSON
   schema (`schema(...)` helper), `summary`, and for writes a `preview`.
2. Keep path handling through `access.resolve(...)` (files) or `Vault` methods (notes); throw `IllegalArgumentException` with a
   helpful message (it becomes `Error: …` for the model).
3. Add a test in `CoreTest` (sandbox, backups, error cases). Update the Guide's "Letting it act" section and this file.
4. For a risky capability (shell, network) add a new risk level / allow-list and an explicit approval preview first.

## Planned / not done

- Shell tool (`run_command`) with safe-list auto-run and approval for everything else.
- MCP client support (reuse the same loop/permission layer).
- Per-folder read/write rights, tool use in AI notes, tool results truncated when stored.
