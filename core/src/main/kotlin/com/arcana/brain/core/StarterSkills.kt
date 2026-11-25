package com.arcana.brain.core

/** A skill that [Bootstrap] puts into a new vault: its name (the folder) and the text of its entry note. */
class StarterSkill(val name: String, val text: String)

/** The skills (and the guide to writing more) a new vault starts with. Plain Markdown: the user is meant to read, change and copy them. */
object StarterSkills {
    val all: List<StarterSkill> = listOf(
        StarterSkill(
            "obsidian-doctor",
            """
---
name: obsidian-doctor
description: "Check the health of the Obsidian vault and fix what the user agrees to: notes without a type, emoji or generic names, duplicates, loose notes in the root, unfinished or stale reports, notes linked to nothing, and dead links. It writes a health report with a fix plan and fixes only what the user picks. Use it when the user asks to check, clean up, tidy, lint or doctor the vault, or asks what is wrong with their notes."
usage: "[a kind to focus on, for example dead links or islands]"
version: 1
type: skill
output: Research/Vault/Reports
tags: [skill, vault, hygiene, obsidian]
needs: [vault_health, ask_user, search_notes, read_note, list_notes, grep_files, set_properties, add_tags, append_to_note, create_note, edit_file, move_path, delete_path]
requires:
  - "vault_health | step 1: check the vault with vault_health"
  - "ask_user | step 2: ask the user which kinds of problems to fix"
  - "create_note | step 3: save the health report"
  - "vault_health x2 | step 5: check the vault again to see what changed"
---

# Obsidian doctor

Finds what keeps the vault from being easy to navigate and fixes it with the user's consent. The checks are done by the app (`vault_health`): never count or judge notes yourself.

## Rules

- Change nothing before step 2 is answered. Fix only the kinds the user picked.
- Never delete a note. Duplicates are merged only when the user says so, and the older copy goes to the trash with `delete_path` (which asks).
- Prefer the note tools: `set_properties` for a property, `add_tags` for tags, `append_to_note` to add a link. Use `edit_file` only to change a link inside a note's text, and `move_path` to rename or move (both ask).
- A rename breaks `[[links]]` to the old name: before renaming, find them with `grep_files` (`[[Old Name`) and fix each one after the rename.
- One fix at a time. If a tool fails, say which note and go on with the next.

## 1. Check
Call `vault_health` with no arguments. If the user named a kind, call it again with that `kind` and `limit` 50.

## 2. Ask
Call `ask_user` with `multiple: true`: one option per kind that has problems, worst first, written as `<count> <kind>` (for example `12 notes without a type property`), plus `Only the report, fix nothing`.

## 3. Report
Save one note with `create_note` in `Research/Vault/Reports`, titled `Vault Health <YYYY-MM-DD>`, starting with these properties:

```
---
type: report
kind: health
date: <YYYY-MM-DD>
status: done
tags: [report, vault, hygiene]
---
```

Then: a table `| Kind | Count |` of every kind from step 1, a section `## To fix now` with the kinds the user picked and the notes of each, and a section `## Later` with the rest. Link the notes as `[[Note title]]`.

## 4. Fix
Work through the picked kinds in this order, one note at a time:

| Kind | How to fix it |
|---|---|
| missingtype | read the note, choose one type (index, reference, analysis, comparison, checklist, template, article, guide, report, thought), `set_properties` with `type:` and, if it has none, `add_tags` |
| island | find the index or hub of its folder (`list_notes` on the folder, a note named like the folder or `… Index`), then `append_to_note` a line `- [[Note title]]` to it; no hub: add it to `Home` |
| dead_links | `search_notes` the missing title; one clear match: `edit_file` the link to it; no match: tell the user, do not invent a note |
| reportnotdone | read it: finished in substance → `set_properties` `status: done`; not finished → list it for the user |
| stalereport | list them for the user with their date: a refresh is a new run of the report's skill, not an edit |
| symbolname, genericname | propose the new name in the chat, then `grep_files` the old links, `move_path` to the new name, fix the links |
| looseroot | propose the folder of its subject, then `move_path` it there |
| duplicatename | read both, say how they differ, and `ask_user` which to keep; merge only if told to |

## 5. Check again
Call `vault_health` again. `append_to_note` a section `## Result` to the report: each picked kind with its count before and after, and anything left undone with the reason.

## 6. Answer
In the chat: what was fixed, what is left, and the report as `[[Vault Health <YYYY-MM-DD>]]`.
""".trimStart(),
        ),
        StarterSkill(
            "decompose",
            """
---
name: decompose
description: "Turn a pasted todo list or a big request into a plan: look at what exists, ask the user the open decisions with ask_user, save the plan as a note, then work through it one item at a time. Use when the user pastes several todos, or asks to decompose, plan or break down a task list."
version: 1
type: skill
output: Plans
tags: [skill, planning]
needs: [ask_user, list_dir, find_files, grep_files, read_file, create_note, append_to_note]
requires:
  - "ask_user | step 2: ask the user the open decisions (at least the order of work)"
  - "create_note | step 3: save the plan as a note"
---

# Decompose a todo list

Turns a raw list of todos into finished work in four phases. Do not skip a phase, and do not change anything before the user has answered the questions.

## 1. Analyze
- For each todo find where it lives (use `find_files`, `grep_files`, `list_dir`, `search_notes`), read what is already there, and note what exists, what is missing and any fact that changes the design.
- Keep what you learn short: one or two lines per todo.

## 2. Ask
- Every open decision is a question to the user with `ask_user`: 2 to 4 short options each, the one you recommend first. Never ask in plain text and wait.
- Always ask in which order to do the work (small and enabling things first).
- Ask only what the files and the todo cannot answer. A conventional default goes into the plan instead of a question.
- Read the answers literally: a free-text answer can change the scope.

## 3. Plan
- Write the plan as a note with `create_note` (folder `Plans`): a numbered list, one line per todo with the files to change, how it will be checked, and its status (`todo`).
- Show the same list in the chat.

## 4. Do it, one item at a time
- Work in the agreed order. After each item check it (run the tests or read the result) and fix what failed before the next one.
- Mark the item `done` in the plan note with `append_to_note` (a line `- done: <item>`), or say why it was skipped.
- Finish with a summary per item: done, partly, or skipped and why.
""".trimStart(),
        ),
    )

    /** `Skills Guide.md` in the skills folder: short, for the person who writes the next skill. */
    val GUIDE: String = """
---
tags: [skills, arcana, guide]
type: guide
---

# Skills Guide

A **skill** is a runbook in the vault that a local model follows step by step. Arcana lists the skills to the model, which loads one with `use_skill` when your request matches (or you pick one with `/` in Chat).

## Layout

```
.arcana/skills/            (or Skills/ in an older vault; Settings → Tools → Skills folder)
  Skills Guide.md          this note (not a skill)
  <name>/                  lower-case words joined by -, for example decompose
    <name>.skill.md        the entry note: the folder name + .skill.md
    reference/             notes the skill reads only when needed
    scripts/               helper programs, run with run_command and a full path
```

A folder without `<name>.skill.md` is not a skill. Keep what a skill produces **outside** its folder (`output:` below), so the skill stays clean.

## Properties of the entry note

```
---
name: my-skill                  # the folder name
description: "What it does AND when to use it."   # the model picks a skill by this: required
version: 1
type: skill
output: Reports/my-skill        # where results go
planner: qwen3:30b             # optional: the model that writes a short plan first
worker: qwen3:30b              # optional: the model that runs this skill (an installed one) instead of the chat model
reviewer: gemma4:12b            # optional: the model that gives a second opinion when the run is done
requires:                       # optional: steps that must happen before the run may end
  - "create_note | step 4: save the report"
---
```

A required step is `tool`, then optionally `~ text` (the call must contain it), `=> text` (the result must contain it), `x3` (three times) and `| what to tell the model when it is missing`.

## Writing one

- Number the steps. One action per step, naming the tool to use (`read_note`, `create_note`, `run_command`, `ask_user`).
- Say what to do when something fails, and when to stop and ask.
- Keep the entry note under 18,000 characters; put long reference material in `reference/` and tell the model when to read it.
- Save results with `create_note`, `append_to_note` and `set_properties`.
- A skill is only text: scripts run through `run_command`, which asks you every time.
""".trimStart()
}
