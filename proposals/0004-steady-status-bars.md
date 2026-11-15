# 0004 Steady status bars

Status: **proposed** · written 2026-11-15.

## Problem

The block under the conversation (composer status line, hint line, bottom bar) changes height and content while you work. Seen in a real screenshot of the Chat with a model that was loading:

- **It wraps.** `loading qwen3.6:35b-a3b into memory (qwen3.8:27b is loaded and may be unloaded first)` is long and variable. It pushes `ram`, `models` and `cpu` onto a second row, the bar grows by a row, and the panels above it shift. When the message goes, everything jumps back.
- **Numbers move their neighbours.** `16%`, `5.5k`, `900k`, `10h 31m` have no fixed width, so a change in digits moves what stands after them.
- **The same facts are shown four times:**

| Fact | Where it appears |
|---|---|
| model name | Status pane, composer line, bottom bar, Thinking frame title |
| sampling | Status pane, composer line |
| `think on` | Status pane, composer hint (`Ctrl+G ✦ think on`), composer line |
| "loading …" | Status pane, conversation footer, bottom bar |

- **Important things cannot be cut.** Model, memory and ram must always be visible, so a fix that simply crops the line is not acceptable.

## Goals and non-goals

1. The bottom block **never changes height** and never wraps.
2. Nothing stands in a different place because a *value* changed (fixed slots, fixed number widths).
3. Every fact lives in **one** place at a time. Nothing important is cut: what does not fit is abbreviated or moved, by priority.

Non-goals: a new settings screen for this (the layout adapts by itself; see "Open questions"), moving the Status pane, changing what the Status pane contains.

## Design

### 1. A fixed block: 2 status rows + 1 hint row

```
Message
▏Ask, think, or give an instruction…                        Ctrl+G ✦ think on
◆ qwen3.6:35b-a3b │ ✦ think on │ ✎ memory 5.5k │ Σ 900k │ est. $2.99 │ ◷ 10h 31m   ← row 1: the session
⌕ words+meaning │ ⚙ plan │ ▣ Arcana                                              ← row 2: the working setup
Enter Send · @ Note · Ctrl+O Attach · Ctrl+S Save to vault · Ctrl+P Commands      ← hints
● qwen3.6:35b-a3b │ ϟ 900k tok │ ram ▰▰▰▱▱ │ models 25.1 │ cpu  16% │ ◌ loading…  ← one row: the system
```

Rules:

- The block is always the same number of rows. A row that has nothing to show keeps its row and carries hints instead of blank space, so the height does not depend on content.
- Nothing wraps. A row that is too long shrinks by the rules in section 5.

### 2. Stable part and transient part

Split every bar into two kinds of segments:

```
╭─ stable: fixed slots ─────────────────────────╮ ╭─ transient: ONE slot ──────╮
│ ● model │ ϟ tokens │ ram ▰▰▰▱▱ │ cpu  16%      │ │ ◌ loading qwen3.6:35b-a3…  │
╰───────────────────────────────────────────────╯ ╰────────────────────────────╯
```

- **Stable:** model, tokens, `ram` bar, `models`, `cpu`, `memory`, cost, time. Each has a **minimum width** and padded numbers (` 16%`, ` 5.5k`), so `16%` → `100%` or `5.5k` → `12.3k` moves nothing.
- **Transient:** loading, compacting, saving, waiting for approval. **One** slot at the end of the row. Only this slot is cut with `…`; the full text is in the conversation footer (it already has a spinner and a timer) and in the palette on demand.
- The long explanation (`qwen3.8:27b is loaded and may be unloaded first`) is **not** part of the transient slot. It belongs to the conversation footer, where there is room, or to a one-time notice.

### 3. One home for every fact

| Fact | Shown in | Not shown in |
|---|---|---|
| model name | the bottom bar (always); the Status pane | the composer line while the Status pane is open; the Thinking frame title keeps it |
| sampling | the Status pane (editable) | the composer line while the pane is open; with the pane closed, the composer line shows it compactly (`T1 P.95 K20`) |
| thinking | the Status pane; the composer hint `Ctrl+G ✦ think on` | the composer line (it repeats the hint) |
| loading | the conversation footer (spinner, time, full text) | the Status pane (its Model row keeps only `◌ loading`), the bottom bar (only the short `◌ loading…` marker) |

So with the pane open the composer line becomes short and calm:

```
◆ qwen3.6:35b-a3b │ ✎ memory 5.5k │ Σ 900k │ est. $2.99 │ ◷ 10h 31m
```

and with the pane closed it carries the extra facts again:

```
◆ qwen3.6:35b-a3b │ T1 P.95 K20 │ ✦ think on │ ✎ memory 5.5k │ Σ 900k │ ◷ 10h 31m
```

### 4. Group by meaning

| Row | Contents |
|---|---|
| composer row 1 (the session) | model · thinking · memory · tokens · cost · time |
| composer row 2 (the setup) | search mode `⌕` · tool mode `⚙` · project `▣` |
| bottom bar (the system) | model state `●/○` · tokens `ϟ` · `ram` `▰▱` · `models` · `cpu` · transient slot · `?` keys |

The capabilities (`vision · thinking · tools · MoE 256`) change only when the model changes, so they move out of the bottom bar into the Status pane's Model section (a row under the model name) and the Thinking frame title.

### 5. Priority-based shrinking when the terminal is narrow

Each segment has a priority. When a row does not fit, the **lowest** priority goes first, one step at a time, until it fits:

```
 drop first                                                          pinned: never dropped
 ───────────────────────────────────────────────────────────────────────────────────────────
 1 capabilities   vision · thinking · tools          (also in the Status pane)
 2 time           ◷ 10h 31m
 3 cost           est. $2.99
 4 totals         Σ 900k all models → Σ 900k → dropped
 5 sampling       T1 P.95 K20 → T1 P.95 → dropped    (also in the Status pane)
 6 thinking       ✦ think on → dropped               (also in the composer hint)
 ───────────────────────────────────────────────────────────────────────────────────────────
 P1 model name    ◆ qwen3.6:35b-a3b → cut in the middle: qwen3.6:…-a3b
 P2 ram bar       ram ▰▰▰▱▱ (a spill onto the CPU shows as a warning, P5)
 P3 memory        ✎ memory 5.5k → ✎ 5.5k
 P4 transient     ◌ loading qwen3.6:35b-a3… → ◌
 P5 warnings      ⚠ and errors: shown when they appear, above everything except P1 and P2
```

Pinned segments never disappear; they only shrink (the right-hand arrows). The model name is cut in its **middle** as the very last step, never at the end, since the end carries the size. Context fill is not pinned in the bar: the Status pane shows it.

## Phases

1. **No wrap, fixed height** (fixes the jumping). Fixed slots and padded numbers; one transient slot cut with `…`; the block keeps its height. Test: snapshots (pty + pyte) of the Chat while a model loads, at 3 widths, with a short and a very long loading message. The row count must be identical in every frame.
2. **One home for every fact.** Drop the repeats (section 3). Test: with the pane open and closed, each fact appears once.
3. **Grouping and priority shrinking** (sections 4 and 5), with the capabilities moved into the Status pane. Test: a table of widths with the expected segments.

Each phase is releasable on its own. After each: docs (`ui-internals.md`, `functionality.md`), the Guide, the Changelog and the vault's reference notes, as for any change.

## Open questions

- ~~Should the transient message ever expand (a key that shows the full text over the panel), or is the conversation footer enough?~~ **Decided (2026-11-15): the conversation footer is enough.** The transient slot is cut with `…`; the full text is read in the footer (spinner, timer, full message). A key with a popup is added only if a message turns up that is cut and has no other place to be read.
- ~~Is blank space on a row acceptable when there is nothing to show (the row keeps its height), or should unused rows be filled with the hints?~~ **Decided (2026-11-15): fill unused rows with hints.** A row with nothing to show still keeps its height, but carries hints (the keys that fit the current state) instead of blank space.
- ~~Which segments does the user want to pin as "never drop"?~~ **Decided (2026-11-15):** model name, `ram` bar, `✎ memory`, the transient marker `◌` (in its shortest form) and warnings / errors by severity. Everything else is dropped in the order of section 5. If cost should stay visible longer, it moves above time in that order.
