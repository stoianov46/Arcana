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
| `TOOLS_PROMPT` | appended to the system prompt when tools are on |

## The tools

| Name | Risk | Arguments | Notes |
|---|---|---|---|
| `search_notes` | Read | `query`, `limit?` | `Vault.search`, ≤20 results with excerpts |
| `list_notes` | Read | `folder?` | vault-relative paths, ≤200 listed |
| `read_note` | Read | `path` | `.md` optional; truncated at 20,000 chars |
| `list_dir` | Read | `path` | folders end with `/` |
| `read_file` | Read | `path` | text, code, JSON, CSV…, or PDF text layer (`TextFiles.load`) |
| `create_note` | Write | `title`, `content`, `folder?` | `Vault.create` into `folder` or `outputFolder`; frontmatter with the configured model |
| `append_to_note` | Write | `path`, `content`, `heading?` | `Vault.append` |
| `write_file` | Write | `path`, `content` | create or replace; **backup** of the old file; creates parent dirs |
| `edit_file` | Write | `path`, `old`, `new` | `old` must occur exactly once; backup first |

Allowed roots = vault root + `toolFolders` (`;`-separated, `~` expanded). Writes additionally refuse any path containing a
`.git` or `.obsidian` segment. Backups: `<config dir>/backups/<yyyyMMdd-HHmmss>-<name>` (suffix `.2`, `.3` on collision).

## Approval UI (`ChatSession` + `screens/Chat.kt`)

`ChatSession.approval: Approval?` (`tool`, `title`, `lines`, `answer: CompletableDeferred<ApprovalChoice>`).
When non-null, Chat replaces the message field with a `Panel("Allow this change?")` and sets `FocusMode(true)`. Keys:
`y` Allow, `n`/`Esc` Deny, `a` Always (this tool, this session, in-memory set `alwaysAllowed`). Other keys fall through (screen
numbers, Ctrl+C). The deny result text tells the model not to retry and to ask the user instead.

## Rendering in chat (`screens/Chat.kt`)

Assistant message with `toolCalls`: model name, optional text, then `⚙ <tool>  <path|query|title|folder>` per call.
`role="tool"` message: `  ↳ ✓ <first line> (N characters)` or `✕` in red for `Error:` / denied. Tool messages are **not** select-mode
entries (`entriesOf`), are skipped by "save chat" transcripts (`transcript`), and don't count as "last answer".

## Session log and vault refresh

A successful tool call logs `SessionKind.Tool` (`"<tool> <summary>"`). A successful `Write` tool calls `brain.changed()`
so the Brain/Library reread the vault.

## Security model

1. Capability: only vault + configured folders (real-path check; `..`, absolute paths and symlinks cannot escape).
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
