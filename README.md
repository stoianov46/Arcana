# Arcana

Terminal control panel for your second brain: a local [Ollama](https://ollama.com) model (Gemma) working with your Obsidian vault.
Built with Kotlin and [Mosaic](https://github.com/JakeWharton/mosaic) (Compose for the terminal).

```
./gradlew installDist
app/build/install/arcana/bin/arcana        # or: make install, then "arcana"
make dist                                  # app/build/distributions/arcana.zip: unzip anywhere (needs Java 17+) and run bin/arcana
```

**Developing Arcana:** see [docs/development.md](docs/development.md) (JDK 21, isolated dev home, tests, definition of done); users: [docs/distribution.md](docs/distribution.md).

**First start:** with no vault chosen yet Arcana opens a short setup: pick your Obsidian vault folder, then tick what to create in it (the memory file and a skills folder with a guide and the starter skills, both inside the hidden `.arcana/` folder so your vault shows only content; and the output folder). It only creates what is missing. The same can be done without a screen, for a script or a new machine: `arcana --init ~/Obsidian/Vault [--skip memory,skills,output]`; `arcana --migrate` moves an older vault's `Skills/` and `ARCANA.md` into `.arcana/`; `arcana --help` lists it. The setup can be run again any time from the command palette ("Set up the vault").

The loop it is built around: **capture** a thought from anywhere (`Ctrl+N` → today's daily note) → **see** how the vault is doing
(Brain: notes, links, tags, inbox, islands, broken links, stale notes) → **think** (Chat: attach notes with `@`, pictures, PDFs and text files with `Ctrl+O` (also in AI notes and Capture),
or answer from the vault with `/vault`; turn on `/tools` and it can search and read your notes and files, ask you questions, and (always after you confirm) change, move or delete them, run commands and read web pages; **skills**: runbooks in your vault (`Skills/<name>/…`) that it follows step by step, picked from the `/` menu; type `/` for the menu of commands, skills and tools, coloured by what each can do) → **shape** (AI notes: summarize, improve, translate, connect…) → **keep** (each session can be
saved into the vault as a note of what you did and learned; from the Session screen, whenever you like).

Screens: Brain, Chat, Library (browse by inbox / no links / no tags / broken links / stale / tags, with links, backlinks and preview),
Inbox (AI proposes a title, folder, tags and links for each new note; you review and apply, the original is always kept),
AI notes, Session, Usage (a dashboard of tokens, speed, estimated price and habits), Settings, Guide, Changelog. `Ctrl+P` opens a command palette with every action.
The vault note `ARCANA.md` holds your standing instructions for the assistant; Arcana creates it the first time and adds it to every chat.
Settings are stored in `~/.config/arcana/config.json` (override with `ARCANA_CONFIG`; `ARCANA_VAULT` sets the default vault path);
chats, sessions and token usage live next to it. The `core` module holds the Ollama client, the vault and its index; `app` holds the
Mosaic UI: `design/` is the design system, `navigation/` the sidebar shell, overlays (palette, capture) and footer, `screens/` one file per screen.

Keys (same conventions as CosmoCLI): `1`-`9` and `0` switch screens, `?` shows the key map, `Ctrl+B` folds the sidebar,
`Ctrl+C` twice quits, `Esc` leaves editing and then goes Home.
- Scrolling: `↑`/`↓` (or `k`/`j`) a line, `PgUp`/`PgDn` or `Space` a page, `g`/`G` top/latest.
- Select mode (`s`): `↑`/`↓` move over blocks, `Space` ticks, `a` all, `c` copies, `b` copies code and tables only.
- Text fields: caret and `Home`/`End`, `Alt`/`Ctrl`+`←`/`→` word jumps, `Shift`+movement selects, `Alt+A` selects all,
  `Ctrl+W` / `Alt+Backspace` delete a word, `Ctrl+U` / `Ctrl+K` delete to the line start / end. Pastes arrive whole.
- Lists: `/` filters. Settings: `Tab` next group, `←`/`→` change a value, `Enter` edit, `R` reset.


This project uses [Gradle](https://gradle.org/).
To build and run the application, use the *Gradle* tool window by clicking the Gradle icon in the right-hand toolbar,
or run it directly from the terminal:

* Run `./gradlew run` to build and run the application.
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`).
This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

This project follows the suggested multi-module setup and consists of the `app` and `utils` subprojects.
The shared build logic was extracted to a convention plugin located in `buildSrc`.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies
and both a build cache and a configuration cache (see `gradle.properties`).

## Documentation

Architecture, features, module maps, the tool loop, data/config and contributor rules are in [`docs/`](docs/README.md)
(written to be read by AI coding agents as well as people).
