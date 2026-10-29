# Try it

Models: chat `gemma4:e4b`, helpers `qwen3.5:9b`, judge `gemma4:12b`, code `qwen2.5-coder:7b`. Run `arcana`, press `2` (Chat), Enter to type.

0. **Combinations:** `/helpers` (all helper models on/off) and `/roles` (skill roles on/off) are independent. Helpers off + roles on = one model plus skills with their roles; both off = one model only.
1. **Flow:** `/flow`, then send: `Compare quicksort and mergesort for sorting a linked list: time, memory, stability, and which one I should use.` Expect Analysis, Plan, answer, Second opinion, Control blocks (scroll: `Esc`, PageUp). `thanks` gets none. `/flow` again turns it off.
2. **Cancel:** send the same question and press `Esc` while it works: "Cancel this reply?" Enter stops it, any other key keeps going.
3. **Goal:** `/goal Write a 5-step plan to learn Kotlin in a month, and each step must name a concrete exercise`. It starts working, checks after each reply and carries on (max 5 turns). The status bar shows `◎ goal`. `/goal clear` drops it.
4. **Router:** send `Why does this fail?` followed by a code block, e.g.
   ```` ```kotlin
   val x: Int = "5"
   ``` ````
   You get a toast `Routed to qwen2.5-coder:7b`.
5. **Verdict:** Settings → Oracul → Automatic second opinion = Always; ask `Is 91 a prime number?`; after the reply a Verdict block (AGREE / PARTLY / DISAGREE).
6. **Context:** ask 4-5 short questions, then `/compact`; Settings → Console shows Chat history = everything, Keep after compacting = 30 %.
7. **Memory:** `/memory check`. **Files:** ask `Create a note "Test" in my vault with three bullet points about sorting`, then `/artifacts`.
8. **Library:** `3` opens on All; Settings → Library has the options.
9. **Usage:** `7` → Breakdown: Oracul, Analyst, Planner, Controller, Judge.
