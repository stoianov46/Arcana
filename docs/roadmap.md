# Roadmap

The TODO list, grouped into phases and worked through in order. Status: `todo`, `doing`, `done`, `partial`, `needs repro`, `proposal`, `skipped`.
Update this file in the same commit as the work. The `#` numbers are the original TODO numbers.

Decisions: show only the real Ollama model name everywhere (`gemma4:12b`, never a prettified "Gemma4"). #15 and #28 are skipped for now.

Closing review (2026-10-28): #11 stays *needs repro* (skipped until a screenshot with terminal size and Interface scale shows it); #15 and #28 stay skipped; the design choices were reviewed and kept (split-view colours and the 38-cell column minimum, quiet action bars with filled buttons only for questions and confirmations, stuck defaults 4 failed calls and 3 failing turns); no real-model smoke test was run (everything was tested with fake Ollama servers and the real UI against them), so the first real use of the Oracul, stuck detection and split view is the remaining check.

## Phase 1: Chat polish

| # | Task | Status |
|---|---|---|
| 27 | Show the original model name in the thinking line (and anywhere else a prettified name appears) | done |
| 17 | Chat labels `◆ model` and a user glyph; per-family icon set in the design system (`ModelIcon`) | done |
| 9 | Cancel a reply with `Esc` twice instead of once (no misclicks) | done |
| 11 | Console output fills the remaining chat height | needs repro: the conversation panel fills the height in every state tried (idle, a 150-message chat, running reply, approval dialog, question dialog, finished reply) at 24, 30, 36, 40 and 60 rows and after resizing the terminal live. If it still happens, send the terminal size, the Settings → Appearance → Interface scale value and what the screen looked like |
| 30 | Split the thinking process from the answer (`ChatMessage.thinking`, setting *Thinking in Chat*) | done |
| 31 | Handle `The model returned an empty response`: one hidden retry before the error (not a setting yet) | done |
| 33 | Library: narrower notes list, wider note content (30 % / 70 %) | done |

## Phase 2: Safety and approval

| # | Task | Status |
|---|---|---|
| 18 | Diff preview with approve/reject before a file or note edit (`Diff`; `edit_file` and `write_file` over an existing file) | done |
| 2 | Auto-approve for commands: setting, `/autorun`, red `▶ auto-run` pill in the Chat status bar | done |
| 10 | Tool for the model to request a mode change (`request_mode`, always asks the user) | done |
| 1 | Ctrl+S "Save to vault": `Ctrl+O` in its dialog overwrites an existing note picked from the vault | done |

## Phase 3: LLM tools

| # | Task | Status |
|---|---|---|
| 12 | `cat` tool | done |
| 32 | Git tools for the model: `git_diff`, `git_status`, `git_log` (read-only; writes go through `run_command`, which asks) | done |
| 13 | `http_request` does not exist: documented, and a wrong tool name now gets a hint at the right one (`fetch_url` is GET only) | done |
| 3 | Tags: `add_tags` and `list_tags` tools | done |
| 4 | Tags added by the agent show in the Library "tags" tab (the index now reads `tag:`, `Tags:`, `a b` and `#a #b`) | done |
| 24 | Tool for the model to read current chat statistics (`chat_stats`) | done |
| 14 | Tool usage statistics: Usage → **Tools** page (calls, ≈ tokens of results put into the context, average time, failures, denials) | done |

## Phase 4: Context and memory

| # | Task | Status |
|---|---|---|
| 29 | Context window consistent between Settings and Chat (`256k` everywhere); real window read from Ollama `/api/ps`; warning below 32k | done |
| 8 | `/compact` and auto-compact of the context (`Compaction`; nothing is deleted, the model is sent from the latest summary) | done |
| 7 | Memory file path configurable (`memoryPath`: vault-relative, `~/…` or absolute) | done |
| 34 | Project context: `projectFolder` (allowed, relative base, listing and its own instructions in the system prompt, `▣` pill) | done |

## Phase 5: Attention and sound

| # | Task | Status |
|---|---|---|
| 22 | Attention icon on the Chat tab: pulsing `!` (approval) or `?` (question) from every screen | done |
| 23 | Sound signals as in CosmoCLI (same choice ids, `afplay`, bell fallback) | done |
| 20 | Sound support: Settings → Sounds (all off by default) | done |

## Phase 6: Onboarding and distribution

| # | Task | Status |
|---|---|---|
| 19 | First-run setup wizard: vault, `ARCANA.md` from the template, Skills folder, output folder (`SetupScreen`, `Bootstrap`) | done |
| 5 | Starter skills shipped with the setup (`StarterSkills`) | done |
| 21 | Decomposition skill as in CosmoCLI: `decompose` (quiz with `ask_user`, plan note, one item at a time) | done |
| 25 | Manage Arcana's own files in Settings → Storage (size per area, clear, prune backups and trash by age) | done |
| 26 | Distributable build (`make dist` → arcana.zip) with a configurable init: the wizard, or `arcana --init <vault> [--skip …]` | done |
| 16 | Oracul: levels 0-1 built (`/oracul`, `/compare`, `/revise`, automatic trigger, models in Settings → Oracul, bottom-bar badge), with the worker's tools (changes always ask). Escalation built (`/takeover`). Roles per skill built (`worker:`/`reviewer:` in a skill). Second Ollama server built (`oraculUrl`). Judge for more than two built (`oraculExtra`). Complete, with the planner role, the role table and a multi-select picker (architecture: [oracul.md](oracul.md)) | done |

## Added later (not on the original list)

| Task | Where | Status |
|---|---|---|
| Oracul as a whole: second opinion, judge, `/takeover`, skill roles (worker / reviewer / planner and a role table), a second Ollama server, several models in `/compare`, stepping into a worker that keeps failing (thresholds are settings), status bars | [oracul.md](oracul.md) | done |
| Side-by-side comparison in Chat (setting Console → Comparison view) | [oracul.md](oracul.md) | done |
| Memory fit and a suggested Oracul and judge in the model pickers | [oracul.md](oracul.md) | done |
| Slash commands take text (`/revise now`, `/oracul model`, a skill with its request), a usage example under the highlighted menu entry | [functionality.md](functionality.md) | done |
| Quiet action bars inside screens, filled buttons only for dialogs (`ActionStyle`) | [conventions.md](conventions.md) | done |
| `http_request` gate; `curl` / `wget` refused in the shell | [tools-and-agent.md](tools-and-agent.md) | done |
| `search_notes` explains a miss and searches short or common words as written | [tools-and-agent.md](tools-and-agent.md) | done |

## Skipped

| # | Task | Status |
|---|---|---|
| 15 | Install gemma:12b | skipped |
| 28 | Logo `🧙 Cooking up some terminal magic...` | skipped |
