# 0003 Strata and OpenAI-compatible providers

Status: **proposed** (phase 0 partly measured, nothing built) · written 2026-11-07 · decisions by the owner on the same day (below) · research in the vault: `Research/Dev/Reports/Strata Local LLM Inference on a 32GB MacBook Pro M5 2026-11-07.md` and `Research/Dev/Engineering/ADR Strata OpenAI adapter for Qwen3.8-Flash-Next.md` (corrected the same day).

## Problem

Arcana can talk to one kind of server: **Ollama**. Facts checked in the code:

- **Every model call goes through `OllamaClient`** (`core/.../OllamaClient.kt`, 596 lines) and its wire format: NDJSON from `/api/chat`, `tool_calls` whose `arguments` is a JSON *object*, `message.thinking`, the `think` field, `keep_alive`, `num_ctx`, and the model facts from `/api/show` (capabilities, `thinking.values`, context length, experts), `/api/ps`, `/api/tags`, `/api/pull`, `/api/embed`.
- **The callers know it is Ollama.** `ChatSession` (about 10 call sites: `listModels`, `capabilities`, `pull`, `embedBlocking`, `chat`, and `clientFor()` which builds a client per role), `AppContext` (`connection`, `show`, `loadedContext`, `loadedModels`, `modelSizes`), `Generation` (the agent loop), `Oracul` (takes an `OllamaClient`), `Settings` and `Common` (pickers, pull, loaded badges), `ModelFit` and `ModelStatus` (memory rules that assume a model must fit in RAM). About 80 references in 13 test files, most of them against a fake HTTP server speaking Ollama.
- **The stored chat has Ollama's shape.** `ChatMessage` is saved as JSON in `chats/` with `tool_calls` (no call id) and `tool_name`.
- **The setting names say Ollama:** `ollamaUrl`, `oraculUrl`, `keepAlive`, `contextTokens` (sent as `num_ctx`).
- **The rule "Local Ollama only"** is written in `docs/oracul.md`; `docs/plan-next.md` N4 (ChatGPT and Gemini) is postponed, with the note that tool calling, thinking, usage and capability checks "all assume Ollama's API today", and that an OpenAI-compatible client is the cheap way in.

The new fact that makes this concrete: **Strata** (`Niko1221/Strata`, MIT) is a local engine that runs **Qwen3.8-Flash-Next** (125B MoE, about 6B active) on a 32 GB Mac by streaming experts from the SSD. Ollama cannot host that model on 32 GB, and Strata is the only way. It speaks the **OpenAI API** (`/v1/chat/completions`, `/v1/models`), not Ollama's. The research notes first said its tools work only through MCP; the source (`serve/server.py`) shows the opposite: standard `tools`, `tool_choice`, streamed `tool_calls`, `reasoning_content`, `reasoning_effort`, a final `usage` chunk, and `meta.n_ctx` in `/v1/models`. Details and the correction are in the ADR.

Why it matters beyond one model: this is **one direction of Arcana's growth**, running the strongest model a 32 GB machine can reach, locally and offline. The same client opens `llama.cpp`'s server, LM Studio, vLLM, and later hosted OpenAI-compatible APIs (N4).

## Goals

1. **Arcana runs with Qwen3.8-Flash-Next through Strata** on the owner's 32 GB M5 with real tool calls and real thinking: chat, the agent loop and skills (run by the chat model alone). Strata mode is a **research mode** for exploring the model and later improvements, not a full replacement of the Ollama setup.
2. **A client interface**, so a provider is a class with a profile, not an `if` in the callers. `OllamaClient` becomes one implementation with **no change in behaviour** (the test suite proves it).
3. **Saved chats stay readable** and keep their shape; conversion is at the wire.
4. **One engine at a time for the whole app** (Ollama or Strata), chosen once. The model picker shows the models of that engine. What needs a second model in memory is switched off in Strata mode, with the reason shown (see Decisions).
5. **Honest status for a slow, streamed server:** no false "too big for memory", a visible "waiting for the first token", and no job that would compete with the chat for the one resident sequence.
6. **Local only.** The engines are Ollama and Strata, both on this machine. No cloud, no API keys, no vault text leaving the computer.
7. **The user always sees which engine is working:** an engine indicator in the status bar, and neutral words everywhere (no "Ollama" shown while Strata is the engine).

Non-goals: running Strata from inside Arcana (installing, starting, updating it); a model download manager for Strata; an MCP client (R10 stays skipped); the Anthropic route of Strata; image generation; **cloud providers, keys and billing (not planned: local only for now)**.

## Decisions (owner, 2026-11-07)

1. **One engine at a time, to keep memory for the model.** Ollama and Strata are not meant to run together on the 32 GB Mac. The owner accepts that **in Strata mode the features that need a second model are switched off**: the Oracul, the judge, the planner and skill roles (a skill is then run by the chat model alone, a mode that already exists), and the **semantic search** (it needs an Ollama embedding model; the keyword search, which needs no model, keeps working). Strata mode exists for research on the model's possibilities, with improvements possible later. Settings shows these rows as unavailable with one line of reason (`not with Strata: one model in memory, one request at a time`), not as silently ignored.
2. **The choice is one setting for the whole app, not per role and not per model.** (The earlier open question "per role or per model" is closed by decision 1: with one engine there is nothing to choose per role.) Per-role engines become a deferred, optional phase.
3. **The Strata version is pinned explicitly: the latest release at the time of work, today `v0.2.12` (2026-11-07).** The version is written in this proposal's phase 0 note, in the fixtures' folder name and in the docs, and is changed only on purpose. The text of `serve/server.py` at `v0.2.12` was checked on 2026-11-07 and is identical to `main`.

4. **Neutral wording, in phase 3, in one change with the *Engine* setting.** Settings group, rows, warnings, the status bar, the Guide and the Changelog stop saying "Ollama" where they mean "the engine". Where a fact is specific (a setting only Ollama has), the row says so.
5. **An engine indicator** is always visible (status bar, next to the model block): `Ollama` or `Strata`, with the state (`online`, `offline`, `waiting for first token`). Details in design 6.
6. **Local only for now.** Only Ollama and Strata, both local. Hosted providers (OpenAI, Gemini, other clouds) are **not planned**; the owner may be interested in the future. The interface is built so that a third engine can be added later, but no key store, privacy layer or billing is designed now (the old phase 6 is a note, not a phase).
7. **The model to test on Strata is Qwen3.8-Flash-Next.** Which build of it (`Q2_0`, `IQ3_XXS`, `Gyro-S`, quantizations of different size) is chosen in phase 0 by measurement on the M5: the largest that fits and gives readable tool calls.

## What a provider is

A provider is described by a small **profile** plus an implementation:

| Capability | Ollama | Strata / OpenAI-compatible |
|---|---|---|
| chat stream | NDJSON `/api/chat` | SSE `/v1/chat/completions` |
| tool call | `tool_calls[].function.arguments` object | streamed `tool_calls` deltas; `arguments` is a JSON string, with `id` and `tool_call_id` |
| thinking in | `think`: true/false/level, values from `/api/show` | `reasoning_effort`: `low`, `medium`, `xhigh` (a fixed list in the profile) |
| thinking out | `message.thinking` | `delta.reasoning_content` |
| usage | `prompt_eval_count`, `eval_count`, durations | final chunk `usage` and `timings` |
| model list | `/api/tags` | `/v1/models` (one model for Strata) |
| capabilities, context | `/api/show` | `/v1/models` `meta.n_ctx` and `input_modalities`; tools and thinking from the profile |
| loaded models, offload, size | `/api/ps`, `/api/tags` | none (the server holds its model) |
| pull | `/api/pull` | none |
| embeddings | `/api/embed` | none for Strata (stay on Ollama) |
| keep-alive, `num_ctx` | per request | server config; the settings are hidden for this provider |

The interface (`LlmClient`) is the **union the app needs**: `chat`, `models`, `show` (as `ModelFacts`: capabilities, context, thinking levels), `loaded` (optional), `embed` (optional), `pull` (optional). A provider says which optional parts it has (`ProviderProfile`), and the app asks the profile instead of the class. This is the "client abstraction" N4 asked for.

## Options considered

| Option | Verdict |
|---|---|
| **A. Interface first, then an OpenAI-compatible client, then per-role providers (recommended)** | The cost is mostly phase 1, which is useful alone and changes nothing for the user. Each phase ships alone. |
| B. A separate `StrataClient` copied from `OllamaClient` and wired where needed | Fast to a demo, but it doubles every `if (provider)` in `ChatSession`, `AppContext` and `Settings`, and N4 later has to redo it. Rejected. |
| C. Put an adapter in front of Strata that speaks Ollama (a small proxy) | No Arcana change, but a second process to run, and Ollama's `/api/show` and `tool_calls` would be faked, with the OpenAI features (usage, `timings`, `reasoning_content`) hidden. Rejected for the product; fine as a throw-away experiment. |
| D. Use Strata's Anthropic route | Arcana has no Anthropic client; the OpenAI route is the common denominator. Rejected. |
| E. Use MCP (`strata_mcp: true`) for tools | Not needed: the request's own `tools` work. Would also add the MCP surface Arcana skipped (R10). Rejected. |

**Decision (proposed):** option A.

## Design

### 1. The interface and the profile

`core` gets `LlmClient` and `ProviderProfile` (provider kind, which optional parts exist, the thinking levels it offers when it does not list them, whether memory-fit rules apply, whether requests queue one at a time). `OllamaClient` implements it unchanged in behaviour. `AppContext` holds `client` as `LlmClient` and a `ProviderProfile`; `ChatSession.clientFor(role)` returns the client of the role's provider.

### 2. The OpenAI wire (a separate, pure `core` class)

- **Request:** `model`, `messages` (converted), `stream: true` with `stream_options.include_usage`, `tools`, `tool_choice` only when forced, `reasoning_effort`, `temperature`, `max_tokens`. No `keep_alive`, no `num_ctx`. `format: json` (the app's `jsonMode`) becomes `response_format` where the server accepts it, and **is refused with tools** (Strata rejects the combination), so the app uses text mode for those calls.
- **Message conversion at the wire:** `arguments` object ↔ JSON string; one `id` per call, created in memory and matched by the next `tool` message; `thinking` and `think` are never sent (as today); images as `image_url` data URLs when the provider reports `image` input.
- **Stream:** SSE `data:` lines; content and `reasoning_content` deltas; `tool_calls` pieces merged by `index`; `finish_reason` (`tool_calls`, `stop`, `length`); the last chunk's `usage` and `timings` become the same `ReplyStats` the Usage screen already counts. `data: [DONE]` ends the stream.
- **Errors:** a 400 with a context message is a typed error (`ContextOverflow`), not text; connection errors keep their message shape.

### 3. One engine for the whole app

Settings gets one *Engine* choice (`Ollama`, `Strata`; later `OpenAI-compatible`). `AppContext.client` is the client of that engine, and `ChatSession.clientFor(...)` returns it (or the Ollama client for the Oracul server `oraculUrl`, which is only reachable in Ollama mode). `ollamaUrl` and `oraculUrl` stay as they are and keep working (nothing to migrate); a new address belongs to the Strata engine.

**What the engine switches off** (the profile says which): the Oracul, judge, planner and skill roles, and the semantic search and its index. The Settings rows stay visible, marked unavailable with the reason, and the commands (`/oracul`, `/compare`, `/revise`, `/takeover`, `/reindex`) answer with the same reason instead of failing. Nothing is deleted or reset: switching back to Ollama restores them as they were.

### 4. Provider-aware behaviour (the part that makes Strata feel right)

- **`ModelFit` / `ModelStatus`:** skipped for a provider whose profile says the model is streamed or hosted elsewhere. The footer says `Strata · qwen3.8-flash-next · 262k` instead of a RAM warning.
- **Waiting:** the first token may take a minute on a long prompt. The existing "waiting for the model" line says it is the server reading the prompt (Strata gives `timings`; the line can show the prompt speed).
- **One at a time:** the engine's profile says requests queue, so what would run beside the worker is off (Decisions, 1). Inside the chat the only parallel work is the automatic compaction and the title, which wait for the reply.
- **Overflow:** a `ContextOverflow` runs the existing compaction and retries once.
- **Repeat guard:** `finish_reason: length` after a degenerate repeat is a normal end with a note, not an error.
- **Unreadable tool calls:** Strata reports calls it could not read (`strata_rejected_tool_calls`); Arcana shows them under the answer and falls back to `TextToolCalls` for a call written as text.
- **Settings that do not apply** (keep alive, context size to request, pull, loaded models) are hidden for a provider that has no such thing (few settings, one place per concept).

### 5. Settings (kept small)

Visible: *Engine* (`Ollama`, `Strata`), the server address (the Strata default `127.0.0.1:8080`), the model (from the list). Everything else follows from the profile. There is no key field: both engines are local.

### 6. Engine indicator and neutral wording

- **Indicator:** one segment in the status bar, always shown (Chat and the bottom bar), `◆ Strata · online`, `◆ Ollama · online`, `○ Strata · offline`, and while the first token is awaited `Strata · reading prompt 41s`. It replaces `○ Ollama offline`.
- **Words:** `Ollama URL` becomes *Server address*; the group *Ollama* becomes *Engine*; `Ollama is not running, so AI features are off` becomes `The engine (<name>) is not running…`; *Keep model loaded* says "Ollama only" in its help and is hidden under Strata; code comments and docs are updated in the same change. The setting keys (`ollamaUrl`, `keepAlive`…) are **not** renamed, so saved settings keep working.

## Phases

Each phase is releasable on its own and ends with tests, `docs/`, the Guide, the Changelog and the vault's reference notes. Sizes: S under half a day, M about a day, L several days.

0. **Confirm on the real machine (S, no code).** Install Strata **v0.2.12** (the pinned version; write it in the note and in the fixtures' folder name), run it on the M5, and record by `curl`: a non-stream and a stream `chat/completions` with one tool and `reasoning_effort: "low"`, `GET /v1/models`, a 4K prompt and its time. Save the answers as **fixtures** in `core/src/test/resources/openai/` (they become the tests of phase 2). Output: a short note with the real tok/s, first-token time and tool-call quality of the build the owner will use. If tool calls are unreadable at that quantization, the proposal still stands (the client is useful for other servers) but goal 1 changes.
   **Phase 0 result so far (owner's M5, 32 GB, 2026-11-07, Strata v0.2.12, build `Q2_0`, context 32k, id `qwen3.8-flash-next-q2_0`).**
   - `GET /v1/models`: `meta.n_ctx` 32768, input modalities `text` and `image`, status `loaded`. Strata set the model to one request at a time.
   - Non-stream `chat/completions` with one tool and `reasoning_effort: "low"`: a real `tool_calls` entry (`get_weather({"city":"Oslo"})`), `reasoning_content`, `finish_reason: "tool_calls"`, `usage`, `timings`. 293 prompt tokens at 62 tok/s, 51 generated tokens at 9.1 tok/s (a draft model was active: 39 of 50 draft tokens accepted).
   - Agent loop through `pi` (a terminal agent, only a test tool, not part of Arcana): it wrote a file, ran a shell listing and checked the content, one run, correct answer. So native tool calls and a multi-step loop work at `Q2_0`.
   - The built-in web page `http://127.0.0.1:8080/` is a plain chat: it attaches text files only.
   - **Not done yet:** the stream request, the 4K-prompt timing, saved fixtures, tool-call quality on harder prompts, a comparison with Ollama. The owner is running the same base prompts on Ollama to compare; phases 1 to 5 wait for that result.
1. **Client interface, no behaviour change (L).** `LlmClient`, `ProviderProfile`, `ModelFacts`; `OllamaClient` implements it; `AppContext`, `ChatSession`, `Generation`, `Oracul`, `Settings`, `Common` use the interface; the Ollama-specific facts (`/api/ps` loaded, offload, pull) become optional parts. **Tested by the existing suite passing unchanged** (the fake Ollama servers stay) plus a test that a client without the optional parts does not crash any caller. Nothing visible to the user.
2. **`OpenAiClient` in `core` (M).** The wire of design 2, as pure functions plus the client. **Tested** with a fake SSE server and the phase-0 fixtures: text, reasoning, a tool call in pieces, two tool calls, `length`, 400 overflow, usage; the message conversion both ways (a stored chat with tool calls becomes a valid OpenAI request, and a reply becomes the stored shape). No UI yet: reachable through a test and an env/config switch.
3. **Engine setting, connection, indicator and neutral wording (M).** *Engine* choice (Ollama, Strata) in Settings, the engine indicator in the status bar, neutral words in Settings, messages, Guide and Changelog (design 6); `Connection` states for it (`/v1/models` instead of `/api/tags`); capabilities and context from `/v1/models`; thinking levels from the profile; `/think` and Ctrl+G work with `reasoning_effort`. **Tested** in the pty harness against a fake OpenAI server (`docs/testing.md`): chat answers, a tool call runs and asks permission as today, thinking shows.
4. **Provider-aware status and fit (M).** The behaviours of design 4: no `ModelFit` warning, waiting line, the Oracul, judge, planner, skill roles and semantic search switched off with their reason, overflow → compact and retry, repeat guard, rejected calls shown, hidden settings. **Tested** unit by unit (profile rules) and in the pty harness (a slow fake server).
5. **Mixed engines per role (S–M, deferred, optional).** Only if the owner later wants Strata for the worker and Ollama for helpers on a bigger machine: an engine on each helper role, the `ModelFit` rule counting Strata's arena as taking the machine, embeddings on Ollama. Not needed for Strata mode as decided.
6. **Not planned: hosted providers (N4).** Recorded only so the question is not asked again: the owner wants local engines only for now and may look at clouds in the future. It would need a key store outside logs, usage files and the vault, privacy wording and a first-use confirmation, and money in the Usage screen. If it comes back it gets its own proposal.

Phases 1 and 2 are the foundation (no visible change); phase 3 is the first one the owner can use; 4 makes it pleasant; 5 is optional and 6 is not planned.

## Risks

- **Phase 1 is wide and touches the hot paths** (`ChatSession` is about 1,600 lines). Mitigation: no behaviour change, the existing tests as the guard, one call site at a time, commits per area.
- **Speed.** About 10 tok/s on 32 GB, slow long prompts (16K tokens took 70 s on an M1 Max in the report). Arcana loops (tools, Oracul, compaction, skills) may feel slow. Measure in phase 0 before building 3 to 5.
- **Tool-call quality on small quantizations** (Q2_0 matches the 8-bit top token 80.6%). Mitigation: show rejected calls, keep `TextToolCalls`, prefer the larger build the machine allows.
- **A young Mac engine** (measured on three Macs, one hotfix, no M5 or 48/64 GB numbers). Mitigation: phase 0 on the owner's machine; the Ollama path stays the default.
- **One resident sequence** (FIFO): parallel jobs would queue. Mitigation: decision 1, the helpers are off in Strata mode.
- **Switching engines with a half-working setup.** Features that are off in Strata mode must come back unchanged on Ollama (an existing semantic index, Oracul roles, skill roles). Mitigation: the engine only masks them, never rewrites settings; tested by switching there and back.
- **`ModelFit` misleads** if the profile is ignored. Mitigation: it takes the profile, tested.
- **Strata's own API can change** (a young project: five releases between 2026-10-29 and 2026-11-07, latest `v0.2.12`). Mitigation: fixtures from phase 0, parser tolerant of unknown fields (as `ignoreUnknownKeys` already is).

## Open questions

None open for the planned phases (1 to 4). Closed on 2026-11-07: two servers (one engine at a time), engine per role or model (one for the app), Strata version (`v0.2.12`), hosted providers (not planned), neutral wording (phase 3, with an engine indicator), model (Qwen3.8-Flash-Next).

To settle by measurement, not by asking (the first numbers are in phase 0; the Ollama comparison is in progress): **which build of Qwen3.8-Flash-Next** (phase 0), and whether the speed on the M5 makes Arcana's loops usable (if not, the client is still useful for other local servers, but Strata mode is only an experiment).

## Where it sits in the roadmap

A direction of improvement ("run the strongest local model on a 32 GB Mac") recorded in `docs/roadmap.md`, *Directions (proposals)*, next to 0001 and 0002. It supersedes the open question N4 in `docs/plan-next.md` for the local part; the hosted part is not planned.
