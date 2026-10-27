# Development setup, run, test and release

For the people (and agents) who change Arcana. What a user does is in [distribution.md](distribution.md).

## Environment

- **JDK 21** (the build pins `jvmToolchain(21)` in `buildSrc`; Gradle downloads one if it can). Kotlin 2.3, Compose compiler plugin, Mosaic 0.18 (Compose for the terminal). No Android, no network at build time beyond Gradle's own dependencies.
- **Ollama** on `http://localhost:11434` for manual runs. Tests never need it: they use fake servers.
- **A terminal** that reports its size and supports colour and Unicode. IntelliJ's built-in "Terminal" works; its "Run" console does not (no TTY).
- Modules: `core` (no UI), `app` (Mosaic UI), `buildSrc` (shared Gradle conventions). `utils/` is an unused template; ignore it.

## Everyday commands

| Command | What it does |
|---|---|
| `./gradlew installDist` | build the start script: `app/build/install/arcana/bin/arcana` |
| `./gradlew :app:run` | not useful for a TUI inside Gradle (stdin is not a TTY): use `installDist` and run the script in a terminal |
| `./gradlew test` / `:core:test` / `:app:test --tests '*CompactTest*'` | unit tests |
| `./gradlew :app:distZip` or `make dist` | `app/build/distributions/arcana.zip` |
| `make install` / `make uninstall` | copy to / remove from `~/.local` |
| `build/install/arcana/bin/arcana --help` | the command line (`--init <vault>`) |

## Run it without touching your real data

The app reads your real settings, chats and vault by default. For development use an isolated home:

```bash
export JAVA_OPTS="-Duser.home=$HOME/arcana-dev"    # the JVM takes user.home from the OS: setting HOME is NOT enough
mkdir -p "$HOME/arcana-dev/vault/.obsidian"
app/build/install/arcana/bin/arcana --init "$HOME/arcana-dev/vault"
app/build/install/arcana/bin/arcana
```
or point `ARCANA_CONFIG` at a scratch settings file (chats, usage, backups and trash follow it). `ARCANA_VAULT` only sets the default vault of a fresh settings file.

A test double for Ollama is a few lines (`/api/tags`, `/api/show`, `/api/ps`, a streaming NDJSON `/api/chat`): see `SkillGateTest` and `CompactTest` (JDK `HttpServer`), and [testing.md](testing.md) for driving the real UI in a pty and reading the screen.

## Where to change what

| I want to… | Start in |
|---|---|
| add a tool | [tools-and-agent.md](tools-and-agent.md) "How to add a tool"; `ToolBox` in `core/Tools.kt` |
| add a setting | [conventions.md](conventions.md) recipe; `AppConfig`, `screens/Settings.kt` |
| add a screen or a key | [conventions.md](conventions.md), [app-module.md](app-module.md) |
| change what a fresh vault gets | `core/Bootstrap.kt`, `StarterSkills.kt` (+ a test in `CoreTest`) |
| change the command line | `app/Cli.kt` (+ `SetupTest`) |
| touch the chat loop | `app/Generation.kt`, `app/ChatSession.kt`; read [proposals/oracul.md](proposals/oracul.md) first for multi-model ideas |

Layout rules that bite: a screen's top-level children go inside `Stack { }` (Mosaic draws siblings on top of each other); rows under a panel are counted exactly, so a new bar or line must be added to that count ([ui-internals.md](ui-internals.md)).

## Definition of done for a change

1. Code, and a test next to the existing ones (logic in `core` or a pure function; UI by snapshot).
2. `./gradlew test installDist` is green; for UI changes, look at the real screen (pty snapshot) at a small and a large terminal size.
3. **Docs in the same commit:** the matching `docs/*.md`, [roadmap.md](roadmap.md) status, the in-app Guide (`screens/Guide.kt`) and Changelog (`screens/Changelog.kt`, bump "Last updated"), `README.md` when the first-run or install story changes.
4. **The Arcana vault notes** (`Arcana/Arcana LLM Reference.md` = what the model may do, `Arcana Reference Documentation.md`, `Arcana Distribution and Setup.md`, `Skills/Skills Guide.md`) and the memory template (`Vault.MEMORY_TEMPLATE`, plus the user's `ARCANA.md`, whose first 4,000 characters are the only part the model reads).
5. Audit instead of assuming: list the real tools (`ToolBox.specs` names plus the pseudo-tools `ask_user`, `request_access`, `request_mode`, `chat_stats`) and grep every doc and note for each one.

## Release checklist

1. `./gradlew clean test installDist distZip`; unpack `arcana.zip` in a temp folder and run `arcana --help` and `arcana --init <temp vault>`.
2. Start it once in a terminal against an isolated home: the first-run setup appears, a chat works against a real model, `Esc` twice cancels, Settings → Storage lists files.
3. Changelog page and README say what is new; roadmap statuses are current.
4. Tag and attach `arcana.zip`. There is no auto-update: users re-run `make install` or unpack the new zip; settings and chats are not touched by either.
