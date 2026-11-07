# Proposals

Plans for things that **do not exist yet** (or only partly): the problem, the options, the decision, the phases. They are not documentation: `docs/` describes what Arcana does today, and a proposal moves its facts into `docs/` when a phase ships (see "When a phase ships" below).

One file per proposal, numbered in the order they were written: `NNNN-short-name.md`.

| # | Proposal | Status | Summary |
|---|---|---|---|
| [0001](0001-retrieval-and-rag.md) | Retrieval and RAG | phases 1, 3 and 4 shipped | One retrieval layer for notes, project code, documents, chats and saved web pages: keywords + meaning fused, explicit modes, measured quality |
| [0002](0002-work-items-and-provenance.md) | Work items and provenance | phases 1 and 2 shipped | Backlog questions, tasks and thoughts as items with an immutable id and an event log (state is derived); every note a run writes carries where it came from; the app links and suggests (never the model); the Markdown note becomes a generated view. |
| [0003](0003-strata-and-openai-compatible-providers.md) | Strata and OpenAI-compatible providers | proposed | A client interface, then an OpenAI-compatible client, so Arcana can run Qwen3.8-Flash-Next (125B MoE) through Strata on a 32 GB Mac with real tool calls and thinking; one engine at a time (helpers and semantic search off in Strata mode, pinned v0.2.12); neutral wording and an engine indicator; local only (hosted providers not planned). Phases 0 to 5, 6 not planned. Phase 0 partly measured (tool calls work, about 9 tok/s); waiting for the owner's Ollama comparison. |

## Status values

- **proposed**: written, not decided.
- **accepted**: decided, not started.
- **phase N in progress**: at least one phase is being built or has shipped; the file says which.
- **done**: every phase shipped.
- **dropped**: decided against; the file says why (kept so the question is not asked again).

## A proposal has

1. **Problem**: what is wrong or missing today, with facts checked in the code.
2. **Goals and non-goals.**
3. **Options** with their trade-offs, and the **decision** with its reason.
4. **Phases**: each one releasable on its own, each with how it is tested.
5. **Open questions.**

## When a phase ships

The facts go to the right file in `docs/` (and the Guide, the Changelog and the vault's reference notes, as for any change); the proposal keeps the *why* and ticks the phase.
