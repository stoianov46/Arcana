# Semantic search (find notes by meaning)

`search_notes` finds notes that contain a word. The semantic search finds notes that are *about* something: the query and every passage of every note become vectors (embeddings) from a local Ollama embedding model, and the closest passages win. It is off until an embedding model is chosen (`AppConfig.embedModel`).

## What the user gets

- **Setting:** Settings → Vault → *Semantic search model* (Enter lists installed models, embedding ones first; `None` = off).
- **Command:** `/reindex` in Chat (also in the palette, `Ctrl+P`) builds or refreshes the index. While it runs, the conversation panel shows `◌ Indexing 120 notes… 40/120`; at the end a toast says `Semantic index ready: 120 notes, 842 passages (40 embedded, 0 dropped) in 31.2 s.`
- **Tool for the model:** `semantic_search(query, limit?)`, a Read tool (no approval, allowed in Plan mode), offered only when the model is set **and installed**. The tool description tells the model to prefer it for questions of meaning and to keep `search_notes` / `grep_files` for exact words.
- The first `semantic_search` call refreshes an empty or stale index by itself (a big vault: the first call takes as long as `/reindex`).

## Files and symbols

| What | Where |
|---|---|
| Embedding call | `OllamaClient.embedBlocking(model, inputs)` (blocking, because a tool runs outside coroutines) and `embed(...)` (suspend, same call); `OllamaProtocol.embedRequest` / `parseEmbeddings` (`/api/embed`: `{model, input:[…]}` → `{embeddings:[[…]]}`; an older single `embedding` is read too). Errors are `OllamaException` with the server's text and the hint `ollama pull nomic-embed-text` |
| Chunks, vectors, index | `core/Embeddings.kt`: `Embedder` (fun interface), `Chunk`, `Chunker`, `cosine`, `EmbeddingIndex`, `SemanticSearch` (the tool's side), `SemanticHit`, `RefreshResult` |
| Tool | `Tools.kt`: `ToolBox(semantic = SemanticSearch?)`, `semanticTool()` (only added with a vault and a `SemanticSearch`) |
| Wiring | `ChatSession.semanticIndexFor(config, vault)` (model set and in the server's installed list, via `ModelStatus.sameModel`; one cached index per vault and model), `toolboxFor` passes it, `ChatSession.reindex(done)` + `indexing` (progress text) |
| UI | `Chat.kt`: `/reindex` (`SlashCommands`, `runCommand`, palette, key map), the progress line under the conversation (`chat.indexing`, counted in `statusRow`); `Settings.kt`: `EmbedModel` (`Editor.Model(embed = true)`), section *Search* of the Vault group |
| Tests | `core/src/test/.../EmbeddingsTest.kt` (fake model, JDK `HttpServer` for `/api/embed`) |

## Data flow

```
/reindex or first semantic_search
  → EmbeddingIndex.refresh(): vault.notes() (hidden folders .arcana/.trash/.obsidian are not notes)
      → pending(): notes whose size or modification time differs from the index, notes that disappeared
      → Chunker.chunks(note): drop the properties block; split by headings (path "H1 › H2"), pack paragraphs to ~800 chars
        (a longer paragraph is cut at sentence ends; a code block stays whole while it fits; a # inside a code block is not a heading)
      → embedder in batches of 16 passages (passages of many small notes share a request); input = "<note title> › <heading path>\n\n<text>"
      → vectors are normalised; the file is written (temp file, then move)
semantic_search(query) → embed the query → dot product with every vector → best passage per note → top N (≤10)
  → "1. path  (score 0.71)\n   § heading\n   excerpt"
```

A failure half way (Ollama stops, model missing) keeps the notes that were finished and saves them; the error is thrown, and the next run continues with the rest.

## The index file

`~/.config/arcana/embeddings/<hash>/index.bin` (the config folder is `ConfigStore.defaultPath().resolveSibling("embeddings")`, so `ARCANA_CONFIG` moves it). `<hash>` = the first 8 bytes of SHA-256 of `"<vault path>\n<model>"`, so another vault or another model never reads this file. Binary, big-endian (`DataOutputStream`): `int magic 0x41524358 ("ARCX")`, `int version 1`, `UTF model`, `int dim`, `int notes`, then per note `UTF path, long size, long mtime, int passages`, per passage `UTF heading, UTF text, float × dim`. A file with another magic, version or model, or a damaged one, is ignored and rebuilt. Rough size: 768 dimensions × 4 bytes ≈ 3 KB a passage, so 4,000 passages ≈ 12 MB, loaded in well under a second.

## Privacy and limits

- Embeddings are computed by the Ollama server in the settings (`ollamaUrl`; local by default). If you point Arcana at a remote Ollama, the text of your notes goes there. The index is a plain file in the config folder (it contains passages of your notes: treat it like your notes). *Settings → Storage* does not list it yet.
- Changing the model rebuilds the index (a different file); the old file stays until removed by hand.
- Notes are re-embedded when their size or modification time changes (not by content), a touched note costs one re-embedding.
- Search is exact brute force over every passage (fine for thousands, not for hundreds of thousands).
- Only `.md` notes inside the vault are indexed; not attachments, PDFs, hidden folders, or the project/allowed folders.
- Chunk size is by characters (about 800), not tokens; an embedding model with a small window cuts a long passage on its own.
- `/reindex` cannot be cancelled while it runs (an in-flight request finishes first); it can be repeated and continues.
- The tool needs the model to be *installed*: with Ollama offline or the model missing, the tool is simply not offered, and `/reindex` says how to pull it.

## How to verify (manual)

1. `ollama pull nomic-embed-text` (about 270 MB), then `ollama list` shows it.
2. Start Arcana (isolated home as in `docs/development.md` if you do not want to touch your real config), open Settings → Vault → *Semantic search model*, press Enter. Expected: a list with `nomic-embed-text` first, marked `· embedding model`, and `∅ None (off)` at the top. Choose it; the row now shows the model name.
3. Open Chat and type `/reindex` + Enter. Expected: a blue line under the conversation `◌ Indexing N notes… 0/N`, counting up, then a toast `Semantic index ready: N notes, M passages (N embedded, 0 dropped) in X s.` and the line disappears. `~/.config/arcana/embeddings/<hash>/index.bin` now exists.
4. Run `/reindex` again at once. Expected: `Indexing: nothing to do…` flashes and the toast says `0 embedded`, in about a second.
5. Edit one note in Obsidian (add a sentence), run `/reindex`. Expected: `1 embedded`. Delete a note, run it again: `1 dropped`.
6. Turn tools on (`/tools`) and ask: *"Use semantic_search to find my notes about choosing a model for my Mac"*. Expected: a `semantic_search` call in the conversation and an answer naming notes from your LLM folders; ask the same with `/mode` set to plan: it still works (Read tool).
7. With the model set but Ollama stopped, ask the same: the tool is not offered (the model says it has no such tool); `/reindex` says it cannot reach Ollama or the model is not installed.
8. Settings → Vault → *Semantic search model* → `None`: `/reindex` says no model is set, and the tool disappears from `/tools`' list.

## Not verified / limits of this work

- **No real embedding model was available** when this was built (`ollama list` had only chat models). The Ollama call was checked against a fake server in tests and against the real server's refusal of a chat model: `POST /api/embed` with `llama3.1:8b` answered `HTTP 501 {"error":"This server does not support embeddings…"}` (Ollama 0.40.13), which the client turns into `Ollama could not embed with "llama3.1:8b" (HTTP 501): … Is it an embedding model (for example: ollama pull nomic-embed-text)?`. A real `nomic-embed-text` run, the real vector size, the speed on your vault and the quality of the matches are **not verified**: steps 1 to 6 above are the check.
- The UI (picker order, the progress line, the toast) was compiled and wired but not driven in the real terminal.
- The embedding model's own context window is not read; very long passages depend on the model's truncation.
- Not done: cancelling `/reindex`, listing the index under Settings → Storage, a status-bar indicator of the index age, indexing of non-note files.
