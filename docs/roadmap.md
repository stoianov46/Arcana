# Roadmap

The TODO list, grouped into phases and worked through in order. Status: `todo`, `doing`, `done`, `partial`, `needs repro`, `skipped`.
Update this file in the same commit as the work. The `#` numbers are the original TODO numbers.

Decisions: show only the real Ollama model name everywhere (`gemma4:12b`, never a prettified "Gemma4"). #15 and #28 are skipped for now.

## Phase 1: Chat polish

| # | Task | Status |
|---|---|---|
| 27 | Show the original model name in the thinking line (and anywhere else a prettified name appears) | done |
| 17 | Chat labels `◆ model` and a user glyph; per-family icon set in the design system (`ModelIcon`) | done |
| 9 | Cancel a reply with `Esc` twice instead of once (no misclicks) | done |
| 11 | Console output fills the remaining chat height | needs repro (idle chat fills the height at 24, 40 and 60 rows; the gap may only show while a reply runs or an approval is open) |
| 30 | Split the thinking process from the answer | todo |
| 31 | Handle `The model returned an empty response`: one hidden retry before the error (not a setting yet) | done |
| 33 | Library: narrower notes list, wider note content (30 % / 70 %) | done |

## Phase 2: Safety and approval

| # | Task | Status |
|---|---|---|
| 18 | Diff preview with approve/reject before a file or note edit | todo |
| 2 | Auto-approve for commands: setting and indicator on the main screen | todo |
| 10 | Tool for the model to request a mode change (ask → auto → plan) | todo |
| 1 | Ctrl+S "Save to vault" that overwrites an existing note via the file picker | todo |

## Phase 3: LLM tools

| # | Task | Status |
|---|---|---|
| 12 | `cat` tool | todo |
| 32 | Git tools for the model (`git_diff` done; status, log, writes behind approval remain) | partial |
| 13 | Document `fetch_url` vs `http_request`; add or update | todo |
| 3 | Tags | todo |
| 4 | Tags added by the agent show in the Library "tags" tab | todo |
| 24 | Tool for the model to read current chat statistics (tokens, cost, duration, model) | todo |
| 14 | Tool usage statistics (per job, and in the Usage tab) | todo |

## Phase 4: Context and memory

| # | Task | Status |
|---|---|---|
| 29 | Context window consistent between Settings and Chat; read the real `num_ctx` from Ollama; warn below 32k | todo |
| 8 | `compact` and auto-compact of the context | todo |
| 7 | Memory file path configurable (vault or file system) | todo |
| 34 | Project context: a folder added to the system prompt, file tools scoped to it | todo |

## Phase 5: Attention and sound

| # | Task | Status |
|---|---|---|
| 22 | Attention icon on the Chat tab when a job needs `ask_user`, approval or a tool | todo |
| 23 | Sound signals as in CosmoCLI | todo |
| 20 | Sound support (setting) | todo |

## Phase 6: Onboarding and distribution

| # | Task | Status |
|---|---|---|
| 19 | First-run setup: vault, `ARCANA.md` from a template, `skills/` folder | todo |
| 5 | Starter skills | todo |
| 21 | Decomposition skill as in CosmoCLI | todo |
| 25 | Manage Arcana's cache and tmp files in Settings | todo |
| 26 | Distributable build with configurable init on first launch | todo |
| 16 | Oracul concept (needs a design discussion first) | todo |

## Skipped

| # | Task | Status |
|---|---|---|
| 15 | Install gemma:12b | skipped |
| 28 | Logo `🧙 Cooking up some terminal magic...` | skipped |
