# Arcana documentation

Arcana is a terminal app (Kotlin + [Mosaic](https://github.com/JakeWharton/mosaic), i.e. Compose for the terminal) that is
the **control panel of a personal "second brain"**: an Obsidian vault + a local LLM served by Ollama (default model
`gemma4:latest`, which supports vision and tool calling). Command name: `arcana`.

These docs are written for **AI coding agents first, humans second**: short facts, exact file paths, explicit invariants,
no marketing. Read this page, then open only the file you need.

## Read this first (30-second orientation)

- Two Gradle modules that matter: **`core`** (no UI; Ollama client, vault logic, stores, tools) and **`app`** (Mosaic UI).
  `utils/` is an unused template module; ignore it.
- Package roots: `com.arcana.brain.core` and `com.arcana.brain.app` (sub-packages `design`, `input`, `navigation`, `screens`).
- Single process, single window. State lives in Compose snapshot state held by `AppContext` (one instance per run).
- Everything the user does ends up as: a vault file change, a chat file, a session event, a usage record, or config.
- The LLM is only reached through `OllamaClient` (`/api/chat` streaming, `/api/show`, `/api/tags`).
- The product rules in [conventions.md](conventions.md) are binding (e.g. "Chat's field always has the keyboard").

## Map of these docs

| File | Read it when you need to… |
|---|---|
| [architecture.md](architecture.md) | understand modules, layers, data flow, threading, startup/shutdown |
| [functionality.md](functionality.md) | know what every screen, key and feature does (user-visible behaviour) |
| [core-module.md](core-module.md) | change Ollama/vault/index/session/chat-store/usage/inbox logic |
| [app-module.md](app-module.md) | find the right file in the UI layer; state holders; navigation; design system; input |
| [tools-and-agent.md](tools-and-agent.md) | work on the tool-calling loop, approvals, or add a tool |
| [data-and-config.md](data-and-config.md) | touch `config.json`, on-disk stores, vault conventions, frontmatter |
| [ui-internals.md](ui-internals.md) | debug rendering: Mosaic quirks, row accounting, key dispatch, Markdown renderer |
| [testing.md](testing.md) | run/add tests, drive the real app in a pty |
| [conventions.md](conventions.md) | follow project rules; recipes for "add a screen / setting / key / tool" |

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

## Glossary

| Term | Meaning |
|---|---|
| Vault | An Obsidian vault: a folder of Markdown notes (`.md`) with `[[wikilinks]]`, `#tags`, YAML frontmatter |
| Second brain | The product idea: capture → see → think → shape → keep |
| Brain | The home/dashboard screen (`Screen.Home`) showing vault health |
| Session | One run of Arcana, logged to disk, can be saved to the vault as a summary note |
| Inbox | A vault folder (default `Inbox`) of unprocessed notes that AI can file |
| Capture | `Ctrl+N`: append a timestamped line to today's daily note |
| Palette | `Ctrl+P`: searchable list of every action |
| Tools | Model-callable functions (read/write notes and files) with user approval for writes |
| Overlay | Help / palette / capture drawn over the current screen without losing its state |
| Entry | A selectable unit in select mode (a user message, or one Markdown block of a reply) |
