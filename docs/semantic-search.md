# Semantic search (find notes by meaning)

(How the two kinds of search are used together, and the plan for more sources, is in [retrieval.md](retrieval.md).) `search_notes` finds notes that contain a word. The semantic search finds notes that are *about* something: the query and every passage of every note become vectors (embeddings) from a local Ollama embedding model, and the closest passages win. It is off until an embedding model is chosen (`AppConfig.embedModel`).

## What the user gets

- **Always offered, sets itself up on first use:** `semantic_search` is in the model's toolset whenever there is a vault, also before any embedding model exists (its description says `NOT SET UP YET`). The first time the model calls it without one, Arcana **asks the user once** (dialog "Download a model?": `nomic-embed-text`, about 270 MB, from the Ollama library), downloads it through Ollama's `POST /api/pull` (what `ollama pull` does over HTTP: no command line and no `ollama` binary needed, works for a remote Ollama too) with progress under the conversation (`Downloading nomic-embed-text 54% (150 of 274 MB)`; `Esc` cancels the turn and the download, Ollama keeps what it fetched), selects it in Settings → Vault, and the same call goes on: the index is built by that first search. If the user says no (or the download fails) the model gets `Error: …` and falls back to `search_notes`. There is no command to type. **Or choose it yourself:** Settings → Search → *Embedding model (for meaning)* lists, while no embedding model is installed, a first row `⇩ Download nomic-embed-text and use it (about 270 MB)`; choosing it downloads and selects it right away (no further question: choosing the row is the consent), with the progress in the Settings notice (`ChatSession.installEmbeddingModel`, the same code the tool uses).
- **Setting:** Settings → Search → *Embedding model (for meaning)* (Enter lists installed models, embedding ones first; `None` = off).
- **Command:** `/reindex` in Chat (also in the palette, `Ctrl+P`) builds or refreshes the index. While it runs, the conversation panel shows `◌ Indexing 120 notes… 40/120`; at the end a toast says `Semantic index ready: 120 notes, 842 passages (40 embedded, 0 dropped) in 31.2 s.`
- **Tool for the model:** `semantic_search(query, limit?)`, a Read tool (no approval, allowed in Plan mode), offered only when the model is set **and installed**. The tool description tells the model to prefer it for questions of meaning and to keep `search_notes` / `grep_files` for exact words.
- The first `semantic_search` call refreshes an empty or stale index by itself (a big vault: the first call takes as long as `/reindex`).

## Files and symbols

| What | Where |
|---|---|
| Embedding call | `OllamaClient.embedBlocking(model, inputs)` (blocking, because a tool runs outside coroutines) and `embed(...)` (suspend, same call); `OllamaProtocol.embedRequest` / `parseEmbeddings` (`/api/embed`: `{model, input:[…]}` → `{embeddings:[[…]]}`; an older single `embedding` is read too). Errors are `OllamaException` with the server's text and the hint `ollama pull nomic-embed-text` |
| Chunks, vectors, index | `core/Embeddings.kt`: `Embedder` (fun interface), `Chunk`, `Chunker`, `cosine`, `EmbeddingIndex`, `SemanticSearch` (the tool's side), `SemanticHit`, `RefreshResult` |
| Tool | `Tools.kt`: `ToolBox(semantic = SemanticSearch?)`, `semanticTool()` in `VaultTools.kt` (only added with a vault and a `SemanticSearch`) |
| Wiring | `ChatSession.semanticIndexFor(config, vault)` (model set and in the server's installed list, via `ModelStatus.sameModel`; one cached index per vault and model), `toolboxFor` passes it, `ChatSession.reindex(done)` + `indexing` (progress text) |
| UI | `Chat.kt`: `/reindex` (`SlashCommands`, `runCommand`, palette, key map), the progress line under the conversation (`chat.indexing`, counted in `statusRow`); `Settings.kt`: `EmbedModel` (`Editor.Model(embed = true)`), section *Search* of the Vault group |
| Setup | `ChatSession.ensureSemanticModel` (called by `runTools` before the first `semantic_search` without a model: ask → `OllamaClient.pull(model)` → `app.update(embedModel)` → `refreshConnection`, then the toolbox is rebuilt), `semanticOn`; `SemanticSetup` (model, size, progress text) and `PullProgress` in core |
| Tests | `core/src/test/.../EmbeddingsTest.kt` (fake model, JDK `HttpServer` for `/api/embed` and `/api/pull`, the tool offered and honest before setup) and `app/.../SemanticSetupTest.kt` (the model calls the tool with no embedding model: asked once, downloaded, selected, the same call answers; no = an error for the model and nothing downloaded) |

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

## Task prefixes (measured, off)

Some embedding models are trained with a task prefix (nomic-embed-text: `search_document: ` for stored text, `search_query: ` for a question). `EmbeddingIndex` can apply them
(`EmbedPrefixes`: `document`, `query`, and a `tag` written with the model into the index file, so turning them on or off rebuilds the index instead of mixing vectors), but
`EmbedPrefixes.forModel` returns none for every model. Measured on 2026-11-24 on the owner's vault (116 notes, 1,562 passages, 274 questions: each note's title, a section heading
and a sentence from its middle, meaning only), nomic-embed-text with its prefixes did slightly worse:

| Questions | n | plain: top 1 · top 5 · MRR | prefixed |
|---|---|---|---|
| title | 108 | 87.0% · 95.4% · 0.912 | 88.0% · 95.4% · 0.917 |
| heading | 84 | 21.4% · 46.4% · 0.330 | 14.3% · 38.1% · 0.248 |
| sentence | 82 | 65.9% · 86.6% · 0.750 | 65.9% · 89.0% · 0.757 |
| all | 274 | 60.6% · 77.7% · 0.685 | 58.8% · 75.9% · 0.664 |

Re-run before turning them on for this or another model: `ARCANA_REAL_OLLAMA=1 ARCANA_REAL_VAULT=<folder> ARCANA_CHECK_OUT=<file> ./gradlew :core:test --tests '*RealPrefixCheck*'`
(`ARCANA_EMBED_MODEL` picks the model; it indexes temporary copies, the real index is not touched).

## Which embedding model (benchmark, results pending)

`RealModelCheck` compares embedding models on the same notes and questions (`RealVaultBench`, shared with `RealPrefixCheck`: a temporary copy of the vault, each note's title, a section heading and a sentence from its middle, meaning only). Per model it gives the passages, how long building the index took, and top 1, top 5 and MRR by kind of question. A name ending in `+q` is the same model with Qwen3-Embedding's query instruction in front of the question (`EmbedPrefixes.QWEN3`; passages stay as they are). `EmbedPrefixes.QWEN3` is not used by the app until it is measured better.

`ARCANA_REAL_OLLAMA=1 ARCANA_REAL_VAULT=<folder> ARCANA_CHECK_OUT=<file> ARCANA_EMBED_MODELS="nomic-embed-text,qwen3-embedding:0.6b,qwen3-embedding:0.6b+q" ./gradlew :core:test --tests '*RealModelCheck*'`
(the result file is written after each model, so a slow one does not lose the others; the real index is not touched).

Measured on 2026-11-24 on the owner's vault (116 notes, 274 questions: 108 titles, 84 headings, 82 sentences; 1,562 passages for every model), by meaning only:

| Model | Index time | Title top 1 / top 5 / MRR | Heading | Sentence | All |
|---|---|---|---|---|---|
| `nomic-embed-text` | 25 s | 87.0% / 95.4% / 0.912 | 21.4% / 46.4% / 0.330 | 65.9% / 86.6% / 0.750 | **60.6% / 77.7% / 0.685** |
| `qwen3-embedding:0.6b` | 55 s | 75.9% / 88.9% / 0.816 | 7.1% / 20.2% / 0.134 | 57.3% / 86.6% / 0.694 | 49.3% / 67.2% / 0.570 |
| `qwen3-embedding:0.6b+q` (query instruction) | 51 s | 72.2% / 94.4% / 0.827 | 16.7% / 35.7% / 0.250 | 53.7% / 87.8% / 0.698 | 49.6% / 74.5% / 0.611 |

So `nomic-embed-text` stays the recommended model: it is better on every kind of question and twice as fast to index. Qwen3's instruction helps the 0.6b model (top 5 67.2% → 74.5%) but not enough; `EmbedPrefixes.forModel` stays `NONE` for every model. The 4b and 8b sizes were not measured (they need 2.5 and 4.7 GB); add them to `ARCANA_EMBED_MODELS` to check.

## The bottom bar shows it

When the embedding model is loaded in Ollama next to the chat model (after a search by meaning or `/reindex`), the bottom bar shows a blue `◇ embed 0.6 GB` (plain glyphs: `embed 0.6 GB`): it takes memory of its own (`ModelStatus.embedLoaded`, `embedSegment`). The loaded models are refreshed after each reply and after `/reindex`. The start screen's **Search** line says whether search is by words only or by words + meaning, and warns when the chosen embedding model is not installed.

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
2. Start Arcana (isolated home as in `docs/development.md` if you do not want to touch your real config), open Settings → Search → *Embedding model (for meaning)*, press Enter. Expected: a list with `nomic-embed-text` first, marked `· embedding model`, and `∅ None (off)` at the top. Choose it; the row now shows the model name.
3. Open Chat and type `/reindex` + Enter. Expected: a blue line under the conversation `◌ Indexing N notes… 0/N`, counting up, then a toast `Semantic index ready: N notes, M passages (N embedded, 0 dropped) in X s.` and the line disappears. `~/.config/arcana/embeddings/<hash>/index.bin` now exists.
4. Run `/reindex` again at once. Expected: `Indexing: nothing to do…` flashes and the toast says `0 embedded`, in about a second.
5. Edit one note in Obsidian (add a sentence), run `/reindex`. Expected: `1 embedded`. Delete a note, run it again: `1 dropped`.
6. Turn tools on (`/tools`) and ask: *"Use semantic_search to find my notes about choosing a model for my Mac"*. Expected: a `semantic_search` call in the conversation and an answer naming notes from your LLM folders; ask the same with `/mode` set to plan: it still works (Read tool).
7. With the model set but Ollama stopped, ask the same: the tool is not offered (the model says it has no such tool); `/reindex` says it cannot reach Ollama or the model is not installed.
8. Settings → Search → *Embedding model (for meaning)* → `None`: `/reindex` says no model is set, and the tool disappears from `/tools`' list.

## Not verified / limits of this work

- **No real embedding model was available** when this was built (`ollama list` had only chat models). The Ollama call was checked against a fake server in tests and against the real server's refusal of a chat model: `POST /api/embed` with `llama3.1:8b` answered `HTTP 501 {"error":"This server does not support embeddings…"}` (Ollama 0.40.13), which the client turns into `Ollama could not embed with "llama3.1:8b" (HTTP 501): … Is it an embedding model (for example: ollama pull nomic-embed-text)?`. A real `nomic-embed-text` run, the real vector size, the speed on your vault and the quality of the matches are **not verified**: steps 1 to 6 above are the check.
- The UI (picker order, the progress line, the toast) was compiled and wired but not driven in the real terminal.
- The embedding model's own context window is not read; very long passages depend on the model's truncation.
- Not done: cancelling `/reindex`, listing the index under Settings → Storage, a status-bar indicator of the index age, indexing of non-note files.
