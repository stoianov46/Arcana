# Testing

## Unit tests

```bash
./gradlew test                      # everything
./gradlew :core:test                # core only
./gradlew :app:test --tests '*DesignTest*'
```

| Suite | Covers |
|---|---|
| `core/.../CoreTest.kt` | Ollama protocol (request/chunk/tool calls), vault CRUD/search/trash/replace, index (links, tags, orphans, code-block rules, health), daily notes + capture, inbox plan parse/apply, chat/session/usage stores, attachments (images, text, PDF built with PDFBox), tools (sandbox, symlink escape, backups, protected folders, edit uniqueness) |
| `app/.../DesignTest.kt` | Markdown rendering (headings, lists, tables box/alignment/fit, figure + code highlighting, ASCII fallback), wrapping, scroll thumb, select mode (walk, tick, tall entries), picker filtering, reply footer, stty parsing |
| `InputTest.kt` / `KeyMappingTest.kt` | text editing (caret, selection, word jumps), Cyrillic mapping, "no control uses z", non-Latin keys don't crash |
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
6. Real model checks need Ollama running with `gemma4:latest`; keep prompts short and deterministic ("Reply with exactly …").
   The screen dump shows text only (no colours); colour/style correctness is asserted in unit tests on `Run`s.

Always remove test artifacts you created in the test vault (notes, attachments, daily note lines) afterwards.

## Checklist before declaring a change done

- `./gradlew test installDist` passes.
- The affected screen was driven in a pty at about 120×40 **and** 80×24 (no overflow, borders intact, row accounting exact).
- Docs updated: [functionality.md](functionality.md), the Guide page, and the module doc if structure changed.
