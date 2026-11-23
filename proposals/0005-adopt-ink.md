# 0005 — Adopt Ink

Status: phases 1 and 2 shipped, phase 3 (docs) done with them

## Problem

Arcana's `app/.../design/` and `input/` (about 4,300 lines) are a copy of what was extracted into the Ink project (`../Ink`, `com.idea:ink`, shared with CosmoCLI). Two copies drift: Arcana has since gained things Ink lacks (pickers, folder search, a few chart and status helpers), Ink has cleaned-up versions of the rest. A fix made in one never reaches the other.

## Goals

- Arcana depends on Ink for everything generic: colours and themes, text editing and keys, Mosaic components, markdown and console rendering, scrolling, charts, docks.
- What is only Arcana's stays in Arcana: slash kinds, attachments, the model icons, the reply footer (they read `core`), the screens.
- No change a user can see. Every Arcana test that passes today passes after.

## Non-goals

- Redesigning anything. Publishing Ink anywhere but `~/.m2`.

## Decision

Fill the gaps **inside Ink first** (with tests), publish it locally, then attach it to Arcana and delete Arcana's duplicate files. Doing it the other way round would break Arcana halfway.

## Phases

1. **Fill Ink's gaps**: pieces Arcana has and Ink lacks (picker, directory and file pickers, theme swatches, status header and memory bar segment, price text, key dispatcher local, screen memory, markdown parse/lines API), and any behaviour that differs from Arcana's current files. Tests in Ink. Publish `0.3.0` locally.
2. **Attach**: Gradle dependency, `Palette.use(...)` from `InkColors`, themes and colour maths first (smallest), then `input/`, then the Mosaic components one file at a time, running Arcana's tests after each.
3. **Delete** Arcana's copies; docs (`app-module.md`, `ui-internals.md`, `conventions.md`, Guide, Changelog) say where the design layer lives.

## Open questions

- Ink is not a git repository; it needs one (or a place in this repo) before it is a dependency other people can build.
- `mavenLocal()` makes a fresh checkout of Arcana unbuildable without running `make publish-local` in Ink first.

## What shipped

Ink 0.3.0 gained: `Line` and `ScrollView(List<Line>)` (Arcana's row type; `MdLine` overloads stay), `parseMarkdown` / `renderMarkdown(blocks)` / `markdownLines`, `Picker`, `DirectoryPicker`, `FilePicker`, `TextInput(above = …)`, `KeyHandler`, `ScreenMemory`, `ThemeSwatches`, `memoryBarSegment`, `StatusHeader`, `priceText`, `actionBarRows`, `Chart.heatCalendar`, and a public `OutputState` / `ScrollState` (Arcana's tests drive them). Arcana lost `design/` (19 files) and `input/` (8 files); its 189 tests pass unchanged apart from imports and `Colors(spec)` becoming `MosaicColors(InkColors(spec))`. Visible change: the theme list is Ink's 19 (Arcana had 13).
