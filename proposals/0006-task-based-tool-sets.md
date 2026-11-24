# 0006 · Task-based tool sets

**Status:** proposed (2026-11-24)

## Problem

The chat model is offered every tool the toolbox has, every round: about 30 tools with shell and web on (vault notes, search, files, git, time, skills, shell, web, plus
`request_mode`, `chat_stats`, `chat_stats_json`, `todo`, `consult`). Several overlap (`search_notes`, `search_vault`, `semantic_search`, `search_files`, `grep_files`; `read_file`
and `cat`; `chat_stats` and `chat_stats_json`), and some descriptions run to 400–700 characters (`search_files`, `search_vault`). Small local models (9B–14B) pick the wrong tool or
invent names more often as the list grows, and every definition costs context on every request.

What already shipped (2026-11-24): switched-off shell and web tools are not offered (`turn_on_tools` asks for them), and the system prompt no longer repeats the list
(`cheatSheet`). Measuring is in place: Usage → Tools → *By model* counts each model's working calls, unknown tools and malformed calls, so a change here can be judged.

## Goals and non-goals

- Offer a smaller set that fits the task, without taking away a tool a task needs.
- Keep it predictable: the user can see which set is active and widen it.
- Not a model deciding which tools another model gets (no extra model call per message).

## Options

1. **Profiles chosen by rules** (like the content router): research (search, read, web), notes (the note tools), code (files, grep, git, shell); chosen from the message and the
   attached skill, with the full set one step away (`more_tools` or the model asks).
2. **Skill-declared sets:** a skill's properties name its tools (`tools:`), and only those (plus the basics) are offered while it runs.
3. **Merge overlaps:** one search tool with a `scope` (`notes`, `files`, `meaning`), `cat` folded into `read_file`, `chat_stats_json` as a `format` argument of `chat_stats`.
   Fewer tools for every task, but skills that name the old tools must be updated (migration with `arcana --migrate`).
4. **Shorter descriptions for small models:** a one-sentence description for models under a size, the long one for larger models.

## Decision

Not taken yet. A sensible order: measure with *By model* first (a baseline per model), then 2 (lowest risk, only skills that opt in), then 4, then 1 or 3 by the numbers.

## Phases

1. Baseline: a small tool-call eval (about 20 prompts with the expected tool and arguments) run against the installed models, in the style of the retrieval eval of proposal 0001.
2. Skill-declared tool sets (`tools:` in a skill's properties), with the completion gate's `requires:` tools always included.
3. Short descriptions for small models, measured against the baseline.
4. Profiles or merged tools, only if phases 2–3 leave a measured gap.

## Open questions

- Where the line for a "small" model is (parameters, or measured success rate on the eval).
- Whether the Oracul's tool agent gets the same sets.
