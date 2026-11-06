# Arcana documentation

Arcana is a terminal app (Kotlin + [Mosaic](https://github.com/JakeWharton/mosaic), i.e. Compose for the terminal) that is
a **local-first research AI-agent system** around a personal "second brain": local LLMs served by Ollama (default model
`gemma4:latest`, which supports vision and tool calling) act as research agents (tools, skills, helper models, questions to the
user) over an Obsidian vault, which is their memory and the home of the structured reports they write. Command name: `arcana`.

These docs are written for **AI coding agents first, humans second**: short facts, exact file paths, explicit invariants,
no marketing. Read this page, then open only the file you need.

## Read this first (30-second orientation)

- Two Gradle modules that matter: **`core`** (no UI; Ollama client, vault logic, stores, tools) and **`app`** (Mosaic UI).
  `utils/` is an unused template module; ignore it.
- Package roots: `com.arcana.brain.core` and `com.arcana.brain.app` (sub-packages `design`, `input`, `navigation`, `screens`).
- Single process, single window. State lives in Compose snapshot state held by `AppContext` (one instance per run).
- Everything the user does ends up as: a vault file change, a chat file, a session event, a usage record, or config.
- The LLM is only reached through `OllamaClient` (`/api/chat` streaming, `/api/show`, `/api/tags`, `/api/ps`).
- **Skills** are plain Markdown + files in the vault, found by folder layout and enforced by the harness (`requires:`); read [skills.md](skills.md) before touching `Skills.kt`, `use_skill`, the gate or the Chat badge.
- The product rules in [conventions.md](conventions.md) are binding (e.g. "Chat's field always has the keyboard").

## Map of these docs

| File | Read it when you need to… |
|---|---|
| [architecture.md](architecture.md) | understand modules, layers, data flow, threading, startup/shutdown |
| [functionality.md](functionality.md) | know what every screen, key and feature does (user-visible behaviour) |
| [core-module.md](core-module.md) | change Ollama/vault/index/session/chat-store/usage logic |
| [app-module.md](app-module.md) | find the right file in the UI layer; state holders; navigation; design system; input |
| [tools-and-agent.md](tools-and-agent.md) | work on the tool-calling loop, dialogs, shell/web/questions, or add a tool |
| [development.md](development.md) | set up a dev environment, run without touching real data, where to change what, the definition of done (code, tests, docs, vault notes) and the release checklist |
| [distribution.md](distribution.md) | build `arcana.zip`, install, the first-run setup, `arcana --init`, where files live and how to move them |
| [oracul.md](oracul.md) | work on anything that asks another model than the chat model: second opinions, `/compare`, `/takeover`, skill roles, the second Ollama server, the split view: principles, flow, message conventions, safety, decisions |
| [roadmap.md](roadmap.md) | see the TODO list by phase and what is done; update it with every piece of work |
| [plan-next.md](plan-next.md) | proposals for the next TODOs (draft for decision), with the plan of each research feature R1-R10 |
| [try-it.md](try-it.md) | a hands-on tour: things to type and press in a real session with the usual models |
| [skills.md](skills.md) | understand or change **skills** (vault runbooks), the `/` menu entries, the skill chip, the required-steps gate and its progress badge |
| [data-and-config.md](data-and-config.md) | touch `config.json`, on-disk stores, vault conventions, frontmatter |
| [ui-internals.md](ui-internals.md) | debug rendering: Mosaic quirks, row accounting, key dispatch, Markdown renderer |
| [testing.md](testing.md) | run/add tests, drive the real app in a pty |
| [conventions.md](conventions.md) | follow project rules; recipes for "add a screen / setting / key / tool" |
| [research-reports.md](research-reports.md) | the Library's *Reports* view: how reports (`type: report`) are found, marks, stale rules, `s` to run a report's skill again |
| [vault-hygiene.md](vault-hygiene.md) | the Brain screen's hygiene checks: each rule, the list, open-in-Library and chat-to-fix |
| [brain.md](brain.md) | the Brain screen: four pages (Overview, Work, Health, Activity), the charts, how the numbers and the bottlenecks are made |
| [research-backlog.md](research-backlog.md) | the research backlog: `? ` in Capture, `Research/Research Backlog.md`, the Brain list, auto-tick |
| [report-lifecycle.md](report-lifecycle.md) | what happens to a report after it is written: tokens, work time and rating, refreshing a stale report, the reviewer pass |
| [retrieval.md](retrieval.md) | how notes and project files are found: `keywords` / `meaning` / `both`, BM25 + vectors fused, the `search_vault` tool, `/vault` answers, the Sources line |
| [semantic-search.md](semantic-search.md) | the embedding index, the `semantic_search` tool, `/reindex`, privacy and limits |

## Build and run

```bash
./gradlew installDist          # build  -> app/build/install/arcana/bin/arcana
./gradlew test                 # all unit tests (core + app)
make install                   # copies to ~/.local/lib/arcana and links ~/.local/bin/arcana
make uninstall
arcana                         # run (needs a terminal of at least ~60x20)
```

Requirements: JDK 21, Gradle wrapper (9.2.1), Ollama running locally, a model pulled (`ollama pull gemma4`).
Versions are pinned in `gradle/libs.versions.toml` (Kotlin 2.3.20, Mosaic 0.18.0, kotlinx-serialization/coroutines/datetime,
Apache PDFBox 3.0.4 for PDF text).

Environment variables: `ARCANA_CONFIG` (config file path), `ARCANA_VAULT` (default vault path),
`ARCANA_COLUMNS` / `ARCANA_ROWS` (force terminal size; diagnostics).

Note: the vault note `ARCANA.md` (see [functionality.md](functionality.md)) is part of the product, not of this repository; a vault gets one automatically the first time Chat opens.

## Glossary

| Term | Meaning |
|---|---|
| Vault | An Obsidian vault: a folder of Markdown notes (`.md`) with `[[wikilinks]]`, `#tags`, YAML frontmatter |
| Second brain | The product idea: capture → see → think → shape → keep |
| Brain | The home/dashboard screen (`Screen.Home`) showing vault health |
| Session | One run of Arcana, logged to disk, can be saved to the vault as a summary note |
| Capture | `Ctrl+N`: append a timestamped line to today's thought file (`Thoughts/thought-YYYY-MM-DD.md`) |
| Palette | `Ctrl+P`: searchable list of every action |
| Tools | Model-callable functions: read/search notes and files, change or delete them, ask the user, run commands, read web pages; changes, commands and fetches need the user's confirmation |
| Memory file | The vault note `ARCANA.md`: standing instructions and facts added to every chat |
| Skill | A runbook in the vault (`.arcana/skills/<name>/<name>.skill.md`, or the older `Skills/…` + `reference/` + `scripts/`) that a model follows step by step; see [skills.md](skills.md) |
| Skill run / completion gate | One execution of a skill whose `requires:` steps Arcana ticks off; if the model tries to finish early the gate sends it back (badge `◆ name 7/11`) |
| Skill chip | `◆ skill name` attached to a message (from the `/` menu or `@`): Arcana puts the skill's text into the message |
| Status bar | The reusable one-line summary (`design/StatusBar.kt`): model, context gauge, tokens, estimated price, time |
| Overlay | Help / palette / capture drawn over the current screen without losing its state |
| Entry | A selectable unit in select mode (a user message, or one Markdown block of a reply) |
