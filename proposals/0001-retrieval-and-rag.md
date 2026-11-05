# 0001 Retrieval and RAG

Status: **phases 1, 3 and 4 shipped, phase 2 next** · written 2026-11-05 · decisions by the project owner on the same day.

## Problem

Arcana is a research system and a second brain for **local** models. Local models are slow and have small windows, so what matters most is that the right few passages reach the model, fast, and that the model (and the user) can tell where they came from. Checked in the code on 2026-11-05:

- **Meaning search covers notes only.** `EmbeddingIndex` indexes `.md` notes of the vault (hidden folders left out): not project code, not PDFs or other files, not chats, not saved web pages.
- **Three ways to search that do not talk to each other.** `search_notes` (keywords, per note), `semantic_search` (meaning, per passage), and the `/vault` answer mode, which uses **keywords only** (`Vault.search`) and says nothing about it. The model has to guess which tool to call; the user cannot tell what `/vault` did.
- **No fusion.** Pure vector search is weak on exact things (`qwen3.8:27b`, `keep_alive`, an error code); pure keyword search is weak on meaning. Neither is enough alone, especially for code.
- **No measuring.** Nothing says whether a change to retrieval made it better.

## Goals

1. Retrieval that is as strong as local hardware allows: keywords and meaning together, for every source the user cares about.
2. **Explicit, named modes.** Nothing silently picks a method: `keywords`, `meaning`, `both`; the user chooses the default, the model chooses per call, and every answer says which was used.
3. Works with the small local models Arcana targets (few tools, short descriptions, small results).
4. Works **without** an embedding model (keywords only) and gets better when one is installed.
5. Measured: a small set of real questions with the passages that should be found, run on every retrieval change.

Non-goals: a hosted vector database, an ANN library (an exact dot product is fast until about 100,000 passages; the vault has 121 notes), anything that sends notes off the machine.

## Decisions (owner, 2026-11-05)

- **First phase: hybrid retrieval** (keywords + meaning fused), in `/vault` answers and in a tool.
- **`/vault` is explicit:** a setting *Search by*: **Both** (default; keywords + meaning, keywords only when no embedding model is set up), **Keywords**, **Meaning**. The answer's `Sources` line names the method that found the notes.
- **Sources, in this order after phase 1:** project code folders, documents in the vault (PDF, text), chat history and thoughts, saved web pages.
- This plan lives in `proposals/`, not in `docs/`.

## Architecture

```
sources (collections)            one passage store                 retrieval
  vault notes (.md)      ─┐                                         ┌─ keywords (BM25, in memory, no model)
  project code folders    │   chunker per type  →  passages  ──────►│
  documents (pdf, txt)    ├─► (headings / code blocks / pages)      ├─ meaning (embedding vectors, exact dot product)
  chats and thoughts      │   + metadata: path, type, heading,      └─ fusion: Reciprocal Rank Fusion (k = 60)
  saved web pages        ─┘     mtime, source                              │
                                                                            ▼
                                                  best passage per file, neighbours on request, score, `via`
```

- **Passages are the unit** (about 800 characters, split at headings or code blocks, prefixed with the note title and heading path: already so for notes). Keywords and vectors rank the **same passages**, so their lists can be fused.
- **Keywords** use BM25 over the passages, built in memory from the notes (a vault of this size indexes in milliseconds), no model. The tokenizer keeps identifiers whole *and* split (`qwen3.8:27b` → `qwen3.8:27b`, `qwen3`, `8`, `27b`; `keep_alive` → `keep_alive`, `keep`, `alive`), so an exact name and a loose word both hit.
- **Meaning** is the existing embedding index (one model per index, incremental by size and time).
- **Fusion** is Reciprocal Rank Fusion: a passage scores `Σ 1 / (60 + rank)` over the lists it appears in. It needs no score calibration between BM25 and cosine, which is why it is the standard first choice.
- **Modes are part of the API** (`RetrievalMode.Keywords | Meaning | Both`), so the tool, the `/vault` answer and the evaluation use the same code.

## Phases

Each phase is releasable alone and ends with tests, docs, Guide, Changelog and the vault's reference notes.

1. **Hybrid retrieval on notes** *(shipped 2026-11-05; facts in [docs/retrieval.md](../docs/retrieval.md))*: BM25 + fusion (`core`), `RetrievalMode`; the tool `search_vault(query, limit?, mode?)` (default `both`; `search_notes` stays keywords, `semantic_search` stays meaning); `/vault` answers use the setting *Search by*; the `Sources` line names the method. Tested with a crafted corpus where only fusion finds both an exact identifier and a paraphrase.
2. **Evaluation set:** a file of questions with the notes/passages that should come back, a runner (`retrievalEval`) that prints hit rate and rank for each mode, run in the test suite on a small fixed corpus and on demand on the real vault.
3. **Project code folders** *(shipped 2026-11-05; facts in [docs/retrieval.md](../docs/retrieval.md))*: the working and allowed folders as a source; a code chunker (by function, class or blank-line block; file path and symbol in the metadata), refresh by size and time; `scope` in the tools.
4. **Documents** *(shipped 2026-11-05: PDF, Word, HTML in any searched folder, vault attachments included; facts in [docs/retrieval.md](../docs/retrieval.md))*: PDF (the existing text-layer reader), text, CSV and other readable files, page-aware chunks.
5. **Chats and thoughts:** saved chats, sessions and `Thoughts/` as a source, so the model can recall earlier work.
6. **Saved web pages:** pages fetched during research, kept with their address and date, as a source.
7. **Better passages:** neighbouring-passage expansion, optional query rewriting (a few phrasings), optional reranking of the top 20 with the Oracul model (slower, off by default).
8. **Freshness and speed:** background indexer with progress, query-vector cache, compact vector storage, a per-source on/off in Settings.

## Risks

- **Index size and time** grow with the sources (code especially); each source is opt-in and shows its size before it is indexed.
- **A small model with five search tools picks badly.** Descriptions stay short, `search_vault` is named the best first choice, and the tools list shows the modes explicitly.
- **Embedding models differ** (language coverage, size); the index is per model and rebuilt when it changes.

## Open questions

- Which chunker for code: regex blocks first (no dependency), or a parser per language later?
- Should chats be indexed automatically, or only the ones the user saved?
- Where does the evaluation set live: in the repo (fixed corpus) and in the vault (the real one)?
