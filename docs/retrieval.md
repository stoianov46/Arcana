# Retrieval: finding the right passages (keywords, meaning, both)

How Arcana finds notes for the model and for `/vault` answers. The plan behind it, and what is still to come (project code, documents, chats, saved web pages, reranking, an evaluation set), is in [../proposals/0001-retrieval-and-rag.md](../proposals/0001-retrieval-and-rag.md). The meaning side (embeddings, `/reindex`, the download) is in [semantic-search.md](semantic-search.md).

## Three named modes (`RetrievalMode`)

| Mode | Finds by | Needs |
|---|---|---|
| `keywords` | the words in a passage (BM25) | nothing: no model, no index file |
| `meaning` | embedding vectors (a paraphrase matches) | an embedding model and its index |
| `both` (default) | the two lists fused with Reciprocal Rank Fusion | meaning is used when the model is set up; otherwise it is `keywords` and the result says so |

Nothing picks a method silently: the result carries the mode that **really ran** (`Retrieved.used`) next to the one asked for (`asked`) and a note when they differ ("meaning search is off (no embedding model is set up)").

## Where it is used

- **`/vault` answers** (Ask your vault): Settings → Search → *Search by* (`AppConfig.searchBy`: `both`, `keywords`, `meaning`; default `both`; the same setting decides for the model's own searches). The model is sent the **matching passages** of each note (up to *Chars per note*), not the start of it. The answer's `Sources` line names the method (`Sources (keywords + meaning)  ▤ A  ▤ B`, `ChatMessage.sourcesBy`). When nothing is found by words, the old whole-note keyword search (`Vault.search`) is tried as a last resort (its excerpts are the start of the note, so only then can the chat say "Only the start was read").
- **Tool `search_vault(query, limit?, mode?)`** (Read, no approval): the best first search for the model; `mode` is `both` (default), `keywords` or `meaning`; each hit says how it was found (`keywords`, `meaning`, `keywords + meaning`) and **the evidence behind it** (`words 3/4`: how many of the query's words the passage has; `similarity 0.64`: the vector cosine, only when meaning took part), and the answer says which mode ran. There is deliberately no "score": a fused rank is relative to the best rank possible, so a weak match could read 0.9; the evidence is what tells a good match from a weak one (the similarity scale depends on the embedding model: for `nomic-embed-text` real matches sit around 0.6–0.7 with most of the words present, unrelated notes around 0.5). `search_notes` stays words-only (note level, `Vault.search`), `semantic_search` stays meaning-only. An explicit `mode: meaning` without an embedding model starts the same one-question download as `semantic_search` (`ChatSession.ensureSemanticModel`); `both` never downloads, it just falls back to words.

## Search settings (Settings → Search tab, `Group.Search`)

**Three rows are enough** to set everything up: *Search in* (where), *Search by* (how: words, meaning, both) and *Embedding model (for meaning)* (what makes meaning work). Everything else is under *Show advanced search settings* (`searchAdvanced`, off by default): the table below.

They have a tab of their own (not under Vault) because retrieval is meant to grow beyond the vault (project code, documents, chats, saved pages: see the proposal) and those sources will share these settings.

All of them apply to `/vault` answers and to `search_vault` alike (`SearchTuning.of(config)`):

| Setting (`AppConfig`) | Default | What it does |
|---|---|---|
| *Search by* (`searchBy`) | `both` | HOW everything is searched: `/vault` answers and the default of every search tool (a tool call may name another `mode`) |
| *Search balance* (`searchBalance`, 0–10) | 5 | the weight of words against meaning in the fusion: `(10 − b) / 5` for words, `b / 5` for meaning (5 = equal, 0 = only words decide, 10 = only meaning decides) |
| *Noise filter: words* (`searchMinWords`, percent) | 35 | a passage stays if it has at least this share of the query's words … |
| *Noise filter: meaning* (`searchGap`, hundredths) | 8 | … OR its vector similarity is within this of the best hit's. A hit weak both ways is left out; the best hit always stays; 0 turns the rescue off, 0% the word filter |
| *Search candidates* (`searchPool`) | 40 | passages each method hands to the fusion |
| *Passages per note* (`searchPassages`) | 4 | passages of one note sent in a `/vault` answer |
| *Context notes*, *Chars per note* | 5, 1500 | notes sent and how much of each (as before) |

**Passages per note is 4 on purpose.** A note revised by appending (an ADR with a later "Validation" section) has its correction in a passage that fused ranking may place third: on the owner's vault, for "how long does a model stay in memory after my last message", the correction was the best vector match of the whole note (0.66) but only the third passage by the fused rank, so with 2 passages the model was sent the superseded decision; 3 includes it and 4 gives both passages of that section. The cost is context (at most Chars per note per note).

The defaults are calibrated on a real vault with `nomic-embed-text` (84 notes): they remove clearly unrelated hits (for `keep_alive`: five notes about a Kanban board, a simulation and an index that shared one word or sat at similarity 0.49–0.55) and keep every relevant one. Similarities of an embedding model sit in a narrow band (real matches 0.6–0.7, unrelated about 0.5), so the meaning gap is deliberately gentle; tighten it (for example 0.05) and the words share (for example 50%) if the results still carry noise, and widen them if a note you expect is left out. The result of the tool says how many weak matches were left out (only those that would have been among the shown ones), and a `/vault` answer's Sources line says it too (`vault search: keywords + meaning · 2 weak left out`). The filter also fills the places it frees with the next best hits.

## Files: code and documents (`search_files`)

The same keywords + meaning ranking over **files** (phases 3 and 4 of the proposal): code, configuration, text, and **documents** (PDF, Word `.docx`, HTML). Together with the vault's notes (`search_vault`) this covers the three things a person looks for: **notes, code, documents**.

- **One list, no kinds: *Search in*** (Enter opens a list editor, `Editor.Folders`: switch `vault` / `working` on and off, remove folders, **Add a folder…** with the same `DirectoryPicker` as the vault path, `e` for text; each folder row says what is wrong with it. *Allowed folders* in Settings → Tools uses the same editor). What is searched is exactly the entries of one list (Settings → Search → Search in, `AppConfig.searchIn`): `vault` (the notes), `working` (the Working folder) and folders of code or documents; remove an entry to stop searching it (`SearchScope`); in every folder code and documents are told apart by their extension. Whether a folder holds code or documents does not need a setting.
- **Permission is separate and stays in Settings → Tools.** The Working folder and the Allowed folders say where the model may read and write; the file search reads only folders that lie inside them (`ChatSession.projectRoots`), but it does **not** read everything that is allowed: with `~` allowed that would be a whole home folder (hundreds of thousands of files), so what is worth searching is chosen in one place, *Search in*. Settings → Search shows *Searched now* and the files counted per folder.
- **Notes are not duplicated:** a folder that contains the vault (for example `~`) searches the vault's PDFs, documents and code, but not its `.md` notes (those are `search_vault`'s: `FolderSource(vaultRoot = …)`).
- **Documents** (`Documents.kt`): PDF by its text layer page by page (PDFBox; no OCR: a scanned PDF gives nothing), `.docx` by its XML paragraphs, HTML without scripts and styles. Up to 30 MB and about 500 passages (400,000 characters) per document. A PDF passage has the heading `page N` and the page as its position, shown as `report.pdf p. 6`.

- **Where:** the entries of *Search in* (Settings → Search, separated by `;`: `working` and folders), each only when it exists and lies **inside a folder the tools may use** (the vault, the working folder, the allowed folders): the search never reaches a place the model could not read (`ChatSession.projectRoots`).
- **Which code and text files:** by extension (`PassageSource` → `FolderSource`): code (`kt java py js ts go rs c cpp cs swift rb php sh sql …`), config (`json yaml toml xml gradle properties …`), text and docs (`md txt rst adoc csv`). Not entered: hidden folders and build/dependency folders (`node_modules build out dist target venv Pods …`); not taken: files over *Largest file searched* (300 KB), files with a zero byte, lock files, minified files. At most 5,000 files (the newest), said in the answer.
- **Passages:** code is cut at declarations (`CodeChunker`: a function stays whole when it fits, about 800 characters; each passage carries its **line** and the declaration it sits in: `fun readConfig(): Map…`); Markdown by headings like a note. Hits are shown as `path:line` so the model reads the right part with `read_file` and `start_line`.
- **Keywords** need nothing and are always current (a folder of 174 files, 3,800 passages: 106 ms). **Meaning** needs the embedding model and an index per folder (`embeddings/<hash>/index.bin`, apart from the vault's). A small folder (up to 1,500 passages) is embedded by the first search; a bigger one is searched by words until `/reindex` has embedded it once (the answer says so); after that only changed files are embedded again. `/reindex` also indexes the project folders, with progress.
- **Real check on documents** (`RealProjectCheck`, `~/Dev Resources`, 7 technical PDFs, 244 passages): read in 0.6 s, embedded in 3 s; paraphrases find the right document and page (`how to measure the risk of a lending pool with machine learning` → `DeFi_ML_Riskmanagment.pdf p. 13`, similarity 0.79; `weekly study plan for learning machine learning` → the two study-plan PDFs first).
- **Real check on code** (`RealProjectCheck`, this repository, `nomic-embed-text`): 174 files, 3,812 passages, meaning index built in 49 s, a search 13–20 ms. Paraphrases find the right code (`how does the context meter estimate tokens right after a compaction` → `contextTokens` in `Chat.kt`, `which folders may the search look in` → `projectRoots`), exact names by words. Known weakness: tests and docs that mention a name as often as the code outrank it; a later phase may weigh source files above tests.
- **Not part of `/vault`:** files are searched only when the model calls `search_files` (`/vault` sends passages of the vault's notes before every question; the model can still search more when tools are on).

## What the screen shows (so it is plain which instruments were used)

- **Status bar, next to `◉ vault`** (and, with `/vault` off but tools on, on its own as `⌕ model: words+meaning`: nothing is searched in advance, the model searches by itself with `search_vault`, by both unless it asks for another mode; with `/vault` on Arcana searches automatically before the model sees your question): the search mode that will REALLY run: `⌕ words+meaning` (green), `⌕ words` (blue), `⌕ meaning` (purple); with Both or Meaning and no embedding model it says `⌕ words (no model)` in orange (`searchModeSegment`).
- **Under every answer, the sources as a list** (`sourceLines`), one note per line: `Sources (3)  ·  vault search: keywords + meaning`, then `▤ Title   <how it came to the answer>`: `read` (the model opened it with `read_note`), `context` (sent with the question by `/vault`), or `read · context`, followed for context notes by how it was found and its evidence (`keywords + meaning · words 3/3 · similarity 0.64`). The list is the notes the answer is built from: the ones the model read AND the ones `/vault` sent; a note that was only found by a tool search but never opened is not a source.
- **A `tools:` line** names the tools the model used for the answer, with the search method each really ran: `⚙ tools: search_vault (keywords + meaning) · grep_files · read_note ×2` (`ChatMessage.toolsUsed`, `ChatSession.noteToolUse`).

## How it works

```
query ─► LexicalIndex.search  (BM25 over passages, top 40)  ┐
       └► EmbeddingIndex.searchPassages (cosine, top 40)    ├─► RRF: score(p) = Σ 1 / (60 + rank)  ─► best passage per note
                                                            ┘     normalised to 0..1, top N notes
```

- **Passages** are the unit (`Chunker`: split by headings, packed to about 800 characters, title and heading path in front). Both lists rank the **same passages**, so they can be fused.
- **`Terms.of`** (the tokenizer): lower case; an identifier is kept **whole and in parts** (`qwen3.8:27b` → `qwen3.8:27b`, `qwen3`, `27b`; `keep_alive` → `keep_alive`, `keep`, `alive`); a plural ending is dropped; one-letter and very common words are not terms (`AI` is).
- **`Bm25`**: k1 = 1.2, b = 0.75, an inverted index; documents with no query term are not returned.
- **`LexicalIndex`**: the passages of every note, in memory, one per vault in the process (`LexicalIndex.forVault`); before each search every note's size and modification time are compared and only changed notes are read again (a deleted note is gone at once). No file, no rebuild step, no model.
- **`HybridSearch`**: `search` (the fusion, the fallback notes), `answer` (the tool's text), `hits` (the `SearchHit`s of a `/vault` answer, with the matching passages as excerpts).
- **Why fusion:** meaning alone misses exact things (`qwen3.8:27b`, `keep_alive`, an error code); words alone miss paraphrases. RRF needs no calibration between a BM25 score and a cosine, which is why it is the usual first choice.

## Files and symbols

| What | Where |
|---|---|
| Modes, tokenizer, BM25, lexical index, fusion | `core/.../Retrieval.kt` (`RetrievalMode`, `Terms`, `Bm25`, `LexicalIndex`, `HybridSearch`, `Found`, `Retrieved`) |
| Passages from the vectors | `EmbeddingIndex.searchPassages` (`Embeddings.kt`) |
| The tool | `Tools.kt`: `searchVaultTool` |
| `/vault` answers | `ChatSession.vaultHits`, `compose` (pending sources and method) |
| Settings | `AppConfig.searchBy`, `searchIn`, `searchAdvanced`, `searchBalance`, `searchGap`, `searchMinWords`, `searchPool`, `searchPassages`; Settings → Search (`Settings.kt`, `noiseNote`) |
| Tuning | `SearchTuning` (`Retrieval.kt`): weights, the noise filter (`HybridSearch.noiseFilter`), pool, passages per note |
| Sources line | `ChatMessage.sourcesBy`, `Chat.kt` (`messageLines`) |
| Real-model check | `core/.../RealOllamaRetrievalCheck.kt`: opt-in (`ARCANA_REAL_OLLAMA=1 ARCANA_REAL_VAULT=<folder> ARCANA_CHECK_OUT=<file> ./gradlew :core:test --tests '*RealOllamaRetrievalCheck*'`), indexes a temporary copy of a real vault with the real embedding model and prints what each mode finds **into the file named by `ARCANA_CHECK_OUT`** (the Gradle log only says `Real Ollama check done: <file>`; read it with `cat <file>`; `core/build.gradle.kts` makes the test always run when `ARCANA_REAL_OLLAMA=1`, because Gradle does not see environment variables as inputs and would otherwise skip it as up-to-date) (checked 2026-11-05 on the owner's vault: 84 notes, 1,070 passages, indexed in 13 s, a search 10–80 ms) |
| Sources | `core/.../Sources.kt` (`PassageSource`, `VaultSource`, `FolderSource`, `CodeChunker`, `ProjectSearch`), `Tools.kt` (`searchProjectTool`), `ChatSession.projectRoots` / `projectIndexFor`, Settings → Search → Project files (`searchFolders`, `searchMaxFileKb`) |
| Tests | `core/.../SourcesTest.kt` (folder rules, code chunker, a meaning index over code with lines, the project search, the tool), `app/.../ProjectRootsTest.kt` (only allowed folders), `core/.../RetrievalTest.kt` (crafted vault: an identifier only words find, a paraphrase only meaning finds, both, the fallback, live changes, the tool), `app/.../VaultAnswersTest.kt` (a `/vault` answer end to end) |

## Limits

- `/vault` answers and `search_vault` cover the vault's notes; project files are searched by the model with `search_files` (below). Chats and saved web pages are later phases of the proposal.
- The keyword ranking has no stemming beyond a plural ending and no typo tolerance.
- The vector side is an exact dot product over every passage (fine to about 100,000 passages).
