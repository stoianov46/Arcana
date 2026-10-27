# Proposal: Oracul (second opinion and orchestration)

Status: **proposal, not started.** It touches the core loop (`Generation`, `ChatSession`, tool approval, usage, settings), so it should be agreed before any code.

## The idea

One model answers. An **Oracul** is a second (or third) opinion on that answer, and later the layer that decides *which* model should do *what*. The user stays in charge: Oracul never acts, it advises.

Why it fits Arcana: the user runs small local models (gemma, qwen) that are fast but make mistakes (wrong tool, empty reply, shallow analysis). A larger local model, or a different family, is slow and memory hungry, so it should be asked rarely and only where it pays off.

## What it could do, smallest first

| Level | Name | What happens | Risk |
|---|---|---|---|
| 0 | **Second opinion** | `/oracul` (or a key on a reply): the *last answer* and its question go to a chosen second model, read-only, with a "find mistakes, missing steps, unsafe advice" instruction. The critique is shown under the answer as its own block. Nothing is changed in the chat history unless the user accepts it. | low |
| 1 | **Judge / merge** | Two or more models answer the same question; a judge model (or the user) picks or merges. Shown side by side. | medium: time and memory |
| 2 | **Escalation** | A small model works; when it fails a check (empty reply twice, tool error loop, skill gate skipped a step, low confidence) Arcana offers "ask the Oracul to take over from here". | medium |
| 3 | **Orchestration** | Roles per step of a skill: *planner*, *worker*, *reviewer*, each a configured model. A skill's `requires:` steps can name the role. | high: this is the architecture change |

Recommendation: build **level 0 only**, learn from using it, and decide on levels 1 to 3 afterwards.

## Design sketch (level 0)

- **Config:** `oraculModel` (an installed Ollama model, blank = off), `oraculPrompt` (editable, default as above), optional `oraculContext` (how much of the chat it sees: last exchange / whole chat).
- **Call:** a new `Oracul.review(client, config, question, answer, toolsUsed)` in `core`, one non-streaming `OllamaClient.chat` with `model` overridden (the client reads the model from `AppConfig`, so a `config.copy(model = oraculModel)` is enough). No tools are offered: the Oracul can only talk.
- **Memory:** Ollama unloads one model to load another on small machines. Show "loading <model>…" and warn when the two together do not fit (`/api/ps` shows what is loaded).
- **UI:** `/oracul` in the `/` menu and the palette; the result is a dimmed block "◇ second opinion · <model>" under the reply (same family of lines as the thinking block); `Enter` on it can copy it, a key can send it back to the main model as "a reviewer says: …".
- **Usage:** recorded as feature `Oracul` (`app.recordUsage`), so the Usage screen shows what it costs; `chat_stats` can mention it.
- **Safety:** read-only by construction (no tools), never auto-triggered at level 0, never sends vault text beyond what is already in the chat.

## Blast radius

| Area | Level 0 | Level 1-3 |
|---|---|---|
| `Generation` / tool loop | untouched | gains a second request path, cancellation of two streams |
| `ChatSession` state | one new message kind (like the compaction summary) | per-step model, branching |
| Approval / tools | untouched (no tools) | the Oracul must never get write tools; roles need a permission model |
| Skills | untouched | `requires:` and `needs:` would name roles |
| Settings | 2-3 rows | a roles table |
| Tests | fake Ollama (exists: `SkillGateTest`, `CompactTest`) | much larger matrix |

## Open questions for the decision

1. Is the second opinion enough for now, or is orchestration (levels 2-3) the actual goal?
2. Which models: only local Ollama ones, or also a remote API (key handling, privacy of vault text)?
3. Should the Oracul see the vault (via `search_notes`), or only the chat?
4. Trigger: always manual, or also suggested by Arcana when a reply looks weak (empty reply, tool errors)?
5. Name: "Oracul" as the feature name in the UI, or "Second opinion"?

## Not in this proposal

Running tools in parallel, multi-agent planning loops, remote providers and any change to how skills are gated.
