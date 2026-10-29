# Try it

Models: chat `gemma4:e4b`, helpers `qwen3.5:9b`, judge `gemma4:12b`, code `qwen2.5-coder:7b`. Run `arcana`, press `2` (Chat), Enter to type.

1. **Flow:** `/flow`, then a 60+ character question. Expect Analysis, Plan, answer, Second opinion, Control blocks (scroll: `Esc`, PageUp). `thanks` gets none. `/flow` again turns it off.
2. **Cancel:** send a long question, press `Esc`: "Cancel this reply?" Enter stops it, any other key keeps going.
3. **Goal:** `/goal every claim in your answer has a source`. It starts working, checks after each reply and carries on (max 5 turns). The status bar shows `◎ goal`. `/goal clear` drops it.
4. **Router:** send a message with a code block: toast `Routed to qwen2.5-coder:7b`.
5. **Verdict:** Settings → Oracul → Automatic second opinion = Always; after a reply a Verdict block (AGREE / PARTLY / DISAGREE).
6. **Context:** `/compact` after a few messages; Settings → Console shows Chat history = everything, Keep after compacting = 30 %.
7. **Memory:** `/memory check`. **Files:** after a tool writes a file, `/artifacts`.
8. **Library:** `3` opens on All; Settings → Library has the options.
9. **Usage:** `7` → Breakdown: Oracul, Analyst, Planner, Controller, Judge.
