# 0007 · Architecture cleanup

**Status:** accepted (2026-11-24), phases in progress on branch `refactor/architecture-cleanup`

## Problem

Measured on 2026-11-24 (after the context inspector work):

- `app/.../ChatSession.kt` is 1,965 lines with 89 functions and 74 fields: the tool loop's side (`runTools`, about 190 lines), approvals, the loop guard and the turn's call
  cache, the Oracul (second opinions, comparisons, takeovers, its own tool loop), flow, roles, goal, compaction, semantic indexing and the model download, saving chats,
  and stamping the backlog and reports. Every feature lands in the same file.
- There are **two tool loops**: `Generation` (retries, loop guard, empty-reply nudge, cut-off handling) and `ChatSession.oraculAgent` (a short copy without any of that).
- **The ask-or-allow decision is made twice**: in `runTools` for the chat model and in `oraculCall` for the Oracul, each with its own rules.
- **Pseudo-tools have no single list**: `ask_user`, `request_access`, `request_mode`, `turn_on_tools`, `chat_stats`, `chat_stats_json`, `todo`, `consult` are answered through a chain of
  `call.name == …` checks, their definitions live in four files (Tools, ChatStats, Flow, Todos), and the list is repeated in `definitionsFor` and in the docs' audit list.
- **Logic depends on a screen**: `ChatSession` imports `contextTokens`, `contextUsage`, `effectiveContext` and `thinkOptions` from `screens/Chat.kt`; the `Prompts` object lives inside `Vault.kt`.
- `screens/Chat.kt` is 2,208 lines: 16 dialog modes in one enum and a 245-line key handler.
- `ToolBox` takes 22 constructor parameters, put together by hand in `ChatSession.toolboxFor`; `Tools.kt` is 1,306 lines.
- `CoreTest.kt` is 2,649 lines with 142 tests; `webSearchParsesAllThreeBackendsAndAsksBeforeEverySearch` has failed for weeks, so a new failure is easy to miss.

## Goals and non-goals

- Smaller units with one job each, testable without a fake HTTP server where the logic allows it.
- One implementation of each rule (the tool loop, the approval policy, the tool list).
- **No behaviour change**: every phase ends with the same tests passing (plus new ones), and the app driven in a terminal against a fake Ollama looks and acts the same.
- Not doing: dependency injection (a framework or hand-written container is ceremony at this size; constructors are enough); an engine abstraction for Strata or other
  servers (not planned, see proposal 0003); splitting `AppConfig` into nested groups (saved configs would need migrating for little gain); moving Compose state out of the
  session classes (the normal Mosaic pattern).

## Decision

Do all eight, smallest and safest first, each phase its own commit:

## Phases

1. **Code in the right place.** Move `contextTokens`, `contextUsage`, `effectiveContext` (and what they need) out of `screens/Chat.kt` into `core` (`ContextMath`), and `thinkOptions`
   next to the thinking levels; move `Prompts` out of `Vault.kt` into `Prompts.kt`. Test: the existing tests, unchanged.
2. **Tests that say what broke.** Split `CoreTest.kt` by area (tools, chat stats, setup and skills, vault, sounds…); fix or quarantine the failing web search test with its reason.
3. **Tool settings and tool files.** `ToolSettings.of(config)` builds what `ToolBox` needs from the settings in one place; `Tools.kt` splits into vault, file, web, shell, git and
   time tools plus the definitions of the pseudo-tools. Test: a `ToolSettings` test; the tool tests unchanged.
4. **One list of pseudo-tools.** A `PseudoTool` registry: name, definition, when it is offered, and its handler; `definitionsFor` and the dispatch in `runTools` read from it.
   Test: every pseudo-tool is offered exactly when it was before (a table test).
5. **One approval policy.** `ApprovalPolicy.decide(spec, settings, actor)` in `core` says ask, allow or always ask, for the chat model and for the Oracul. Test: a table of
   risk × mode × actor.
6. **One tool loop.** The Oracul's tool loop uses the same core loop as the worker, so it gets the loop guard and the retries. Test: the loop's existing tests plus an
   Oracul run through it.
7. **`ChatSession` split.** `ToolRunner` (running calls, the turn cache, the loop guard, approvals), `OraculCoordinator` (opinions, comparisons, takeovers, flow, roles,
   goal), `SemanticService` (indexes, reindex, model download), `Compactor` and `ChatPersistence`; `ChatSession` keeps the conversation and wires them.
8. **`Chat.kt` dialogs.** Each dialog (history, pickers, save, confirmations, artifacts, context inspector, effort, sampling) becomes its own state + keys + view; `Chat.kt` keeps
   the conversation and the message field.

Each phase: build, all tests, a pty run of the Chat against a fake Ollama (send, a tool call, `/context`, a dialog), docs (`core-module.md`, `app-module.md`, `architecture.md`,
`testing.md`) brought in line. No Guide or Changelog entry: nothing the user sees changes.

## Open questions

- None blocking. If a phase turns out to change behaviour, it stops there and the difference is written here first.
