# UI internals: Mosaic quirks and rendering rules

Mosaic 0.18 renders Compose to a character grid. It differs from normal Compose in ways that shaped every primitive here.
Read this before touching layout, borders, scrolling, input or Markdown.

## Hard facts about Mosaic (and our workarounds)

| Fact | Consequence / workaround |
|---|---|
| **No clipping**: content wider/taller than its box overflows and wrecks neighbours | use `Label`, `Stack`, `Panel` (they clip/measure); never `Text` for dynamic strings; cut strings with `fit`/`take` |
| Emoji and CJK are 2 terminal cells but counted as 1 | `sanitizeForTerminal` on **all** model/user text before measuring or drawing (replaces with one-cell lookalikes) |
| Non-ASCII key events crash Mosaic 0.18's key mapping | `com/jakewharton/mosaic/compat.kt` shadows the mapping (from CosmoCLI); `KeyMapping.kt` converts events |
| Mosaic handles Ctrl+C itself only on exact bytes (breaks with Kitty lock bits) | Arcana handles Ctrl+C always, cancels Mosaic from outside, then `Runtime.halt(0)` |
| Terminal size comes from queries that some terminals answer badly | `TerminalSize` polls `stty size`; `ResizeSignalGuard` for SIGWINCH; `ARCANA_COLUMNS/ROWS` override |
| Screen is not cleared on start | `main()` clears it first |
| Pasted text arrives as a burst of key events | `KeyDispatcher` treats keys <20 ms apart as a paste (tests must pause between keys) |

## Layout: exact row accounting

Output areas (conversation, draft, scroll lists) must fill the height **exactly**: too many rows scroll the whole screen,
too few leave a hole.

- `LocalContentWidth/Height` (provided by `Shell`) are the area given to a screen.
- `bodyRows(extra)` = content height − 3 (header+gap) − extra; `outputRows(fixed, notice, actions)` = height − fixed − notice rows −
  action-bar rows. `fixed` is everything else on the screen **counted by hand**, e.g. in Chat:
  `3 (header+gap) + 2 (panel borders) + statusRow + below` where `below` is the message field
  (`2 + inputRows + attachmentRows`) or the approval panel (`lines + 3`).
- When you add a row to a screen, add it to that screen's `fixed`. Row counts of wrapped text come from `wrapRows`/`wrap`.
- `panelWidth()` = width − 4 (borders+padding), `outputWidth()` = panelWidth − 3 (room for the scrollbar).
- `rememberDensity().inline` switches compact layouts at small sizes (e.g. `InlineField` instead of two-row fields).
- Minimum size is `Dimens.MinColumns × MinRows`; below it `Shell` shows a "enlarge the window" message.

## Key handling

Order: **overlay → screen `KeyHandler` → global (Shell)**. A handler returns `true` if it used the key.

- Typing screens call `FocusMode(true)` so digits/letters are not global shortcuts; `false` lets `1`-`9`, `?`, etc. work.
- Chat's field always has focus (`FocusMode(!browsing)`). Letters never trigger actions there; actions are Ctrl keys, `/commands`,
  `@`, the palette.
- Pending approval (tools) and the file browser (`attachments.browsing`) take the keys first inside the screen.
- Ctrl+C: first press → confirmation (or session-save flow), second → quit. Never swallow Ctrl+C in a screen handler (return `false`).
- Text editing (caret, selection, word moves, Ctrl+U…) is `TextEdit`/`TextFieldState`; call `field.onKey(key)` last in a handler.
- Cyrillic keys map to the QWERTY key at the same position for shortcuts (`KeyboardLayout`), but text fields receive what was typed.
- Never use `z`/`Z` as control keys.

## Scrolling and select mode (`design/ScrollView.kt`)

`OutputState` owns scroll offset, select-mode cursor, ticked entries and copy. `Line.entry` tags each rendered row with the
selectable entry it belongs to (`-1` = none; gaps between messages are entry-less so the cursor never rests on them).
`entryRanges(lines)` maps entry → row range. Tall entries scroll inside (`stepWithinEntry`), `followWindow` keeps the cursor
visible. `copyPlain` switches copy to `plainText(raw)`; `b` copies `codeAndTables(raw)`. Arrow keys and Space scroll; letters only
act when the caller passes `letters = true` (never while typing).

Entry numbering must match between the `entries` list passed to `output.handle(...)` and the `entry` numbers given to
`messageLines` (Chat uses `entriesOf(m)` for both).

## Markdown renderer (`design/Markdown.kt`)

Two stages: `parseMarkdown(text)` → `MdBlock(raw, kind)` blocks (these are the select-mode entries; `raw` is what copy returns),
then `renderMarkdown(blocks, width, entry, rich, plainGlyphs)` → `List<Line>` (each with styled `Run`s).

`renderStyled` works line by line plus lookahead for structures:

- **Code fences** → `renderCode`: a closed box (`╭─ lang ───╮`, `│` on both sides of every row, `╰───╯`, every row exactly the width), line numbers, per-token colours (`highlightCode`: comments dim italic, strings green,
  numbers orange, keywords purple bold, calls blue, Capitalised types cyan, JSON keys blue, `diff` lines green/red), closed bottom edge,
  whole block on `Palette.ConsoleCodeBg` via `MdLine.fill`.
- **Tables** (`startsTable` → `renderTable`): box drawing (`╭ ┬ ╮ ├ ┼ ┤ ╰ ┴ ╯ │`), column widths from content, shrunk (widest first,
  min 3) and wrapped to fit the width; header row tinted by putting the background **on the runs** (not `fill`, which would run past the
  table's edge); numeric columns right-aligned; `:---:`/`---:` alignment honoured.
- **Headings** with level marks (`█`, `▌`, `▸`, `·`) and rules under H1/H2; bullets by depth (`●◦▪▫`), tasks `☐/☑`, numbered items,
  quotes with an accent bar, `---` as a rule with `◆`.
- **Inline**: `` `code` `` chips, `**bold**`, `*italic*`, `~~strike~~`, `[text](url)`, plus **figure highlighting** in plain text
  (`highlightNumbers`): money/percent/long or decimal numbers are bold cyan; leading `-` red; leading `+` green; dates/identifiers/small
  integers are left alone. `unmath` turns short LaTeX (`$\rightarrow$`) into symbols.
- Word wrap: `wrapRuns` (greedy, chips with a background are atomic, over-long words are chunked).
- `rich=false` shows raw text; `plainGlyphs=true` maps box/bullet glyphs to ASCII (`MdLine.asciiOnly`).

Anything with a `background` run is treated as an unbreakable chip by `wrapRuns` — keep that in mind when tinting text.

## Theming

`Palette` exposes the current theme (`Palette.use(spec)` on theme change). Use **roles** (`Palette.Accent`, `Cta`, `Green`, `Red`,
`ConsoleText`, `ConsoleDim`, `ConsoleCodeBg`, `UserBg`…), never raw colours. A theme (`ThemeSpec`) is a map of role → RGB; optional keys
override derived values. New roles must be added to `Colors` with a derivation so every existing theme still works.

## Logo and status

`Shell` animates the sidebar/top-bar logo while `app.busy > 0` (a counter incremented per running generation). `ReplyStatus`
shows spinner/thinking/elapsed/tokens while generating; `replyFooter` prints the closing stats line.

## Chat layout: what sits under the conversation

Chat counts every row under the conversation by hand (`below`), then `outputRows(fixed = 3 + 2 + statusRow + below, actions)` gives the rest to the
conversation. `below` is the sum of: the message field (`2 + inputRows + attachment rows`, `inputRows` up to a quarter of the screen, 3–8),
the `/` menu rows (≤8, only while a message starts with `/`), the **status bar** row (`statusRows`, 0 when the density is `tiny`), and, for
a dialog (approval or question), its panel instead of the field: `lines + 3` plus its button rows. **Dialog buttons live inside the panel**
(`ActionBar(items, panelWidth())`), so their rows are counted with `breakIntoLines(items, panelWidth()).size + 1` and `actions = null` is
passed to `outputRows`; the message field keeps its buttons below the status bar (`actions = chatActions(…)`). Add a row to any of these and
add it to the count.

## Status bar (`design/StatusBar.kt`)

A reusable one-line summary: `StatusSegment(parts, priority)` (a part is coloured text, optionally bold or on a background), built with
`statusSegment(label, value, color, priority)`, `pillSegment(text, background)`, `gaugeSegment(label, fraction, detail)` (green < 60 %,
orange from 60 %, red from 85 %). `fitSegments(segments, width)` drops the lowest priority first (keeping the order) until the line fits;
`StatusBar(segments)` draws it with `│` separators (`|` with plain glyphs); `StatusHeader(title, segments)` is a screen header with the bar
under the title. Chat (`chatStatus`) and Usage use it; any screen can.

## Scrollbars and scrolling text

`WrappedText` (multi-row text fields) keeps its first visible row between frames so it scrolls only when the caret leaves the window, and
draws a scrollbar in its last column when the text is taller than the window: a `█` thumb (as tall as the visible share, at the window's
position) on a `│` track. `ScrollView` does the same for the conversation.

