package com.arcana.brain.core

/** A skill that [Bootstrap] puts into a new vault: its name (the folder) and the text of its entry note. */
class StarterSkill(val name: String, val text: String)

/** The skills (and the guide to writing more) a new vault starts with. Plain Markdown: the user is meant to read, change and copy them. */
object StarterSkills {
    val all: List<StarterSkill> = listOf(
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

    /** `Skills/Skills Guide.md`: short, for the person who writes the next skill. */
    val GUIDE: String = """
---
tags: [skills, arcana, guide]
type: guide
---

# Skills Guide

A **skill** is a runbook in the vault that a local model follows step by step. Arcana lists the skills to the model, which loads one with `use_skill` when your request matches (or you pick one with `/` in Chat).

## Layout

```
Skills/
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
