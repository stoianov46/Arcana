# Distribution, install and first run

## Build and hand out

| Command | Result |
|---|---|
| `./gradlew installDist` | `app/build/install/arcana/bin/arcana` (a start script plus `lib/*.jar`) |
| `make install` | copies that to `~/.local/lib/arcana` and links `~/.local/bin/arcana` (`PREFIX=` changes the place; `make uninstall` removes it) |
| `make dist` (= `./gradlew :app:distZip`) | `app/build/distributions/arcana.zip`: unzip anywhere and run `arcana/bin/arcana` |

Requirements on the target machine: a **Java 21 runtime** (the build uses `jvmToolchain(21)`), a terminal with colour and Unicode, and **Ollama** running (default `http://localhost:11434`) with a model that supports tools for the tool features (`ollama pull <model>`). An Obsidian vault is a plain folder of Markdown; Obsidian itself is not required. The zip contains no model, no vault and no settings.

## First start

`AppContext.setupOpen` is true while no vault is chosen and `setupDone` is false: `App.kt` then shows `SetupScreen` instead of the screens (step 1 choose the vault with `DirectoryPicker`, step 2 tick what `Bootstrap` creates, then a report). `Bootstrap` (core) only creates what is missing: the memory file (`ARCANA.md` from `Vault.MEMORY_TEMPLATE`, or `AppConfig.memoryPath`), the skills folder, `Skills Guide.md`, the starter skills (`StarterSkills`, today `decompose`) and the output folder. An existing file is never touched. `s` on step 1 skips the setup; the palette command "Set up the vault" opens it again at step 2.

## Without a screen (scripts, a new machine)

```
arcana --init ~/Obsidian/Vault                  # vault + memory file + Skills + output folder
arcana --init ~/Obsidian/Vault --skip skills    # leave parts out: memory, skills, output (comma separated)
arcana --help
```
`Cli.run` (app/Cli.kt) saves `vaultPath` and `setupDone=true` in the settings file, prints what it created and exits; exit codes: 0 ok, 1 bad folder or write error, 2 bad arguments. Tests: `SetupTest`.

## Where things live and how to move them

| What | Default | Override |
|---|---|---|
| settings | `~/.config/arcana/config.json` | `ARCANA_CONFIG` (path of the file) |
| default vault for a fresh config | none | `ARCANA_VAULT` |
| chats, sessions, usage logs, tool usage, backups, trash | next to the settings file | follow `ARCANA_CONFIG` |
| memory file | `<vault>/ARCANA.md` | Settings → Prompts → *Memory file path* (`memoryPath`) |
| skills | `<vault>/Skills/` | Settings → Tools → *Skills folder* |
| start script JVM options | | `JAVA_OPTS` (for example `-Xmx512m`) |

Settings → Storage lists the files Arcana itself keeps (not your notes or settings) and clears them (`Storage`). To reset a machine: delete the settings folder.

Developers: [development.md](development.md) (environment, isolated run, release checklist).

## Checklist when you change the distribution

1. `./gradlew test installDist distZip` is green and `arcana --help` runs from the unpacked zip.
2. README "First start" paragraph, this page, the Guide's *Setup* bullet, the Changelog and `Arcana Reference Documentation.md` (vault) still say the same thing.
3. A new thing a fresh vault needs goes into `Bootstrap` (with a test in `CoreTest`), not into a manual step.
