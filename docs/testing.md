# Testing

## Unit tests

```bash
./gradlew test                      # everything
./gradlew :core:test                # core only
./gradlew :app:test --tests '*DesignTest*'
```

| Suite | Covers |
|---|---|
| `core/.../CoreTest.kt` | Ollama protocol (request/chunk/tool calls), vault CRUD/search/trash/replace, skills (discovery, problems, `use_skill`, required steps and the run's progress), `wait`, index (links, tags, orphans, code-block rules, health), daily notes + capture, inbox plan parse/apply, chat/session/usage stores, attachments (images, text, PDF built with PDFBox), tools (sandbox, symlink escape, backups, protected folders, edit uniqueness, grep/glob/line ranges, shell policy and runner, web policy and `fetchUrl` against a local server, delete/move, `ask_user` option cleaning, text-written calls, cheat sheet, `current_time`), memory file, note rename with link rewriting, usage insights, model list details |
| `app/.../SkillGateTest.kt` | the completion gate end to end: the real `Generation` loop against a fake Ollama server (a premature "all done" is sent back with the STOP message naming the missing step; a model that keeps ignoring a step is let go after 3 strikes and the step is marked skipped; no skill run = no change) |
| `app/.../CompactTest.kt` | `/compact` against a fake Ollama: the summary lands at the cut, nothing is removed, a short chat or an empty summary changes nothing |
| `app/.../OraculTest.kt` | the Oracul against a fake Ollama that answers per model: only the Oracul model is asked for an opinion, the judge gets every answer (two, several, an extra that fails is left out), the block lands in the chat, tokens are recorded under the right model, no model or no answer changes nothing; `/revise` needs an open opinion and sends the worker the opinion, your note and the request; `/compare model` for one call; `/takeover`; skill roles (`worker:` / `reviewer:` / `planner:` and the role table, the skill's own value wins, only for that message); the second Ollama server; the Oracul stepping into a worker that keeps failing (a scripted worker that fails until advised) and not when off or without a model; with tools: the Oracul reads a note and names it, a write asks even in Auto mode and a denial changes nothing, a model without the `tools` capability (or the setting off, or tools off) only talks |
| `app/.../SetupTest.kt` | `arcana --init` / `--help` (`Cli.run`): creates what is missing, remembers the vault, `--skip`, bad input |
| `core/.../CoreTest.kt` (newer cases) | the Oracul's chat context (`background`: the end of a long reply kept, no tool output, 0 = off, more than 20 messages), `Prompts.window` (a long tool run keeps the user's request), the reviewer caveat and older chats, cut notes (`cutNote`, `hitText`), the memory advice (`ModelFit.advice`), `http_request` against a local server (what goes out on the wire, redirects, the gate, masked preview) and `curl` refused, `Diff`, change previews, `git_*`, `cat`, `add_tags`/`list_tags`, `chat_stats`, tool usage store, `Compaction`, `Storage`, `Bootstrap` and the starter skills, memory path, project folder, sounds (fake player), `request_mode`, `/api/ps` parsing, `search_notes` (short words searched as written, a miss explains itself), `ModelFit` (fit and suggestion), `comparisonParts` (split by the names in the title), `failedStreak` / `stuckLevel` / `due` with the thresholds, the Oracul's status text, `chat_stats` Oracul lines, `SkillRoles` |
| `app/.../DesignTest.kt` | the Library tree (`libraryRows`: folders first, only open folders list their notes, the view's order kept; `ancestorsOf`, `allFolders`), Markdown rendering (headings, lists, tables box/alignment/fit, closed code boxes, figure + code highlighting, ASCII fallback), wrapping, scroll thumb, select mode (walk, tick, tall entries), picker filtering, reply footer, stty parsing, status bar fitting and formats, axis labels, context gauge, the `/` menu and its badges, its usage lines and typed-command parsing (`splitSlash`, `unknownCommand`), action bar widths for both styles, the side-by-side layout (`columnCount`, `sideBySide`), `announcesAction` |
| `InputTest.kt` / `KeyMappingTest.kt` | text editing (caret, selection, word jumps), Cyrillic mapping, "no control uses z", non-Latin keys don't crash |
| `PasteRepairTest.kt` | `KeyDispatcher`: a paste that lost its beginning or its middle is typed back whole from the clipboard |
| `FilePickerState` tests (`FilePickerTest.kt`) | folder browsing, ticking, limits, hidden files |
| `AppSessionTest.kt` | session logging/saving state |

Conventions: core tests use `Files.createTempDirectory`; tests never touch the user's real config or vault (the app reads
`ARCANA_CONFIG`; core classes take paths in their constructors). Pure functions (`OllamaProtocol`, `InboxProcessing.parse`,
`renderMarkdown`, `UsageStats`, `ToolBox`) are the main test targets: keep logic out of composables so it stays testable.

When you change rendering or behaviour, add or update a test in the matching suite. When a bug is fixed, add a regression test
(examples: frontmatter closing `---` parsed as a tag; table header fill past the table edge; select-mode jump at the end of a tall entry).

## Driving the real app (end-to-end, in a pty)

Unit tests do not cover layout, key flow or the model. The reliable method used during development: spawn the installed binary
in a pseudo-terminal, feed keys, and read the screen through a terminal emulator.

1. `./gradlew installDist` → binary `app/build/install/arcana/bin/arcana`.
2. Use a throwaway config and vault: `ARCANA_CONFIG=/tmp/x/config.json` with `vaultPath` pointing to a copy of a test vault
   (keep a pristine backup; tests mutate it). Optionally force the size with `ARCANA_COLUMNS` / `ARCANA_ROWS`.
3. Python harness: `pty.fork()` the binary, set the window size (`TIOCSWINSZ`), write key bytes, read output into a `pyte.Screen`,
   print `screen.display` after pausing.
4. Pitfalls:
   - **Pause ~0.3 s between keys**: bursts under 20 ms are treated as a paste, so Tab/Enter get swallowed as text.
   - Long single writes can lose their start; type text in chunks.
   - Esc followed quickly by `[`/`O` is read as an escape sequence; wait after Esc.
   - Leaving AI notes with an unapplied draft asks first (answer `Esc`/`y`); Chat asks only when *Keep chats* is off.
5. Useful keys: `1`-`9` screens, `Ctrl+P`=`\x10`, `Ctrl+N`=`\x0e`, `Ctrl+O`=`\x0f`, `Ctrl+C`=`\x03`, arrows `\x1b[A/B/C/D`, Enter `\r`.
6. The helper is a small script (`drv.py`: `pty.fork`, `TIOCSWINSZ`, a `pyte.Screen`; `pip install --target <dir> pyte`) kept in the scratchpad, not in the repository. Real model checks need Ollama running with `gemma4:latest`; keep prompts short and deterministic ("Reply with exactly …").
   The screen dump shows text only (no colours); colour/style correctness is asserted in unit tests on `Run`s.

Always remove test artifacts you created in the test vault (notes, attachments, daily note lines) afterwards.

## Checklist before declaring a change done

- `./gradlew test installDist` passes.
- The affected screen was driven in a pty at about 120×40 **and** 80×24 (no overflow, borders intact, row accounting exact).
- Docs updated: [functionality.md](functionality.md), the Guide page, and the module doc if structure changed.
