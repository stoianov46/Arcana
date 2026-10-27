# Conventions, product rules and recipes

## Binding product rules

1. **Arcana is the control panel of the user's second brain.** UI/UX serves: capture → see → think → shape → keep. Don't turn it into a
   generic chat client.
2. **Chat opens unfocused; once you press `Enter` the message field has the keyboard.** (The Chat screen starts in browse mode so that opening the tab never drops you into editing; the screen state resets each time the tab is opened.) No letter shortcuts while typing; actions are Ctrl keys, `/commands`, `@`,
   the palette. `Esc` leaves the field for browse/select (while a reply runs, `Esc` twice cancels it and number keys switch screens only with an empty field). Shortcuts are Ctrl chords, never ⌘.
3. **Do not reintroduce separate "Summarize" or "Ask" screens.** They were merged into AI notes (tasks) and Chat (`/vault`).
4. **The sidebar stays** (number keys + palette) but must be informative: badges, auto-fold under 90 columns. Top bar / right sidebar are options.
5. **Follow CosmoCLI** (`/Users/stoianovk/IDEA Projects/CosmoCLI`, sibling project) for design system, navigation, hotkeys, text editing,
   scrolling, select mode and settings groups. Before building input/scroll/key behaviour, look at its equivalent; port rather than reinvent.
6. **Never bind `z`/`Z` as controls.**
7. **Nothing destructive without a way back**: deletes go to `.trash` (tools: a trash folder), replacements are backed up, tool writes need approval + backup; shell, web, delete and move always ask.
8. **Vault edits only via `Vault`** (path checks, unique names, frontmatter). Tools only inside allowed folders.
9. **Every screen must work at 80×24.** Test narrow layouts; use `rememberDensity()` for compact variants.
10. **Prompts live in `core` (`Prompts`, `TOOLS_PROMPT`)**, not in composables.
11. Keep docs in sync: [functionality.md](functionality.md), the matching Guide page in `screens/Guide.kt`, and the module doc.

## Code style

- Match the surrounding code: comment density (KDoc on non-obvious classes/functions explaining *why*), naming, idiom.
- Pure logic in `core` or in plain functions (testable); composables only compose and handle keys.
- Settings, prompts and limits are named constants or config fields, not magic numbers in screens.
- **Colour means something, and is never the only signal.** In the "/" menu (`SlashKind`): commands cyan, skills purple `◆`, read-only tools green `◎`, tools that change things orange `✎`, shell red `▶`, delete/move red `✕`, web blue `↗`, questions muted `?`; the menu is grouped commands, skills, tools and every row carries a `SlashCategory` badge (COMMAND, SKILL, TOOL, a fixed-width column); every kind except a plain command also shows a badge word (SKILL, READ, WRITE, SHELL, DELETE/MOVE, WEB, ASK) so the meaning survives a monochrome terminal. Reuse these roles for the same meanings elsewhere (green = safe/read, orange = changes, red = high risk, blue = leaves the computer) rather than inventing new ones.
- Use `Palette` roles, `Label`/`Stack`/`Panel`, `sanitizeForTerminal`; never raw `Text` for dynamic content.
- Errors surfaced to the user are plain sentences ("Could not read the file."), via `Notice`/toast, never stack traces.
- Blocking work in `Dispatchers.IO`; state mutation on the UI scope.

## Recipes

### Add a setting
1. `core/AppConfig.kt`: add a field **with a default** (old config files must load).
2. `screens/Settings.kt`: add a `toggle` / `choice` / `stepper` / `text` entry in `settings` under the right `Group` (new group → add to
   `enum Group`). Provide clear help text.
3. Read it where needed via `app.config`; changes apply immediately (`app.update(config.copy(...))`).
4. Document in [data-and-config.md](data-and-config.md) and the Guide if user-visible.

### Add a screen
1. `navigation/Navigator.kt`: add a `Screen` entry (title, icon, group); it gets a number key by position and appears in the palette
   and `ScreenOrder`/Settings → Tabs automatically.
2. `screens/<Name>.kt`: composable `fun <Name>Screen(app: AppContext)` (the Compose screen is `NoteComposerScreen`); publish `Hints`, optional `Commands`, `FocusMode`; one `KeyHandler`.
3. `App.kt`: add the case in the `when (screen)` inside `key(app.revision)`.
4. Row accounting with `outputRows(...)` for scrolling areas; test at 80×24.
5. Use `WithVault(app, title) { vault -> … }` when the screen needs a vault.
6. Add a Guide page/section and update [functionality.md](functionality.md).

### Add a palette command
Screen-specific: `Commands(listOf(Command(label, group, keys, enabled) { … }))` inside the screen.
Global: `paletteCommands` in `navigation/Overlays.kt`.

### Add a key
Add to `Key.kt` + map in `KeyMapping.kt` (and a test), handle it in the screen's `KeyHandler` (or `overlayKey` for overlays),
add it to `Hints`/`ActionBar` and the Guide's key page. Check it doesn't collide with text editing keys or Cyrillic mapping.

### Add an attachment-capable input
Create `val attachments = remember { app.attachments() }` (or `app.attachments(forModel = false)` for copy-only), pass it to
`TextInput(..., attachments = attachments)`, call `handleAttachmentKey` (Ctrl+O / Backspace) from the handler, route all keys to
`attachments.handle(key)` while `attachments.browsing`, and render `AttachBrowser(attachments)` instead of the screen. Count
`attachments.rows` in the screen's row accounting. Drain with `attachments.drain()` when sending.

### Add a model feature (a new AI task)
1. Prompt in `Prompts` (core) + unit test of its shape.
2. Run it through `Generation` (`rememberGeneration(app, "<Feature>")` so usage is recorded under that name).
3. Render with `GenerationView`/`renderMarkdown`; let the user review before anything is written to the vault.
4. Log to the session (`app.session.log(SessionKind.…)`); add a `SessionKind` + label if it is a new kind of activity.

### Add a tool
See [tools-and-agent.md](tools-and-agent.md#how-to-add-a-tool). A new tool also needs: a row in `Arcana LLM Reference.md` (vault), a `SlashKind.forTool` mapping if it is not Read/Write/Shell/Web, and its name in the Guide.

### Change skills (`requires:`, the gate, the chip, the menu)
Read [skills.md](skills.md) first. Keep `Skills.kt` pure (no UI): the gate's rules live in `SkillRun`, the app only feeds and asks it. Skill text must stay under
`Skills.MAX_CHARS` (18,000, including its properties). A change here updates `docs/skills.md`, the Guide page "Skills", the Changelog, the vault notes
`Skills/Skills Guide.md`, `Arcana/Arcana LLM Reference.md`, `Arcana/Arcana Reference Documentation.md`, and (when a model rule changes) `ARCANA.md` and `Vault.MEMORY_TEMPLATE`.

### Add a theme
Add a `ThemeSpec` to `Themes.all` in `design/Theme.kt` with the required role colours; it appears in Settings and previews itself.

## Known limitations / backlog

- Tools: no `http_request` (POST) tool (a model that calls it, or `curl`, `ls`, `grep`…, gets an error with a hint at the right tool: `ToolBox.wrongNameHint`); tool results are stored in full in chats (shortened only when sent); tools are not available in AI notes; shell commands are not sandboxed by folder (only their working directory is).
- Scanned PDFs have no text layer (rendering pages as pictures is not implemented).
- Vault search is keyword-based (no embeddings).
- The `utils/` template module is unused; it can be removed.
- Mosaic cannot clip or measure wide glyphs, hence `sanitizeForTerminal`.
