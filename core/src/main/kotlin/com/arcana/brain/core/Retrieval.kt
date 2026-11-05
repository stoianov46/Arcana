package com.arcana.brain.core

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * How the vault is searched, named so nothing picks a method silently (proposals/0001-retrieval-and-rag.md): [Keywords] ranks passages by the words in them (BM25, no model needed),
 * [Meaning] by embedding vectors (needs an embedding model), [Both] fuses the two (Reciprocal Rank Fusion), and is the default.
 */
enum class RetrievalMode(val word: String) {
    Keywords("keywords"), Meaning("meaning"), Both("both");

    companion object {
        fun of(word: String?, default: RetrievalMode = Both): RetrievalMode = entries.firstOrNull { it.word.equals(word?.trim(), ignoreCase = true) } ?: default
    }
}

/**
 * Where the search looks, from the one list of Settings → Search → Search in (`vault; working; ~/Dev Resources`): [notes] = the vault's notes, [working] = the Working folder, [folders] = the
 * other entries (folders of code or documents, as typed).
 */
data class SearchScope(val notes: Boolean, val working: Boolean, val folders: List<String>) {
    companion object {
        fun of(searchIn: String): SearchScope {
            val parts = searchIn.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            return SearchScope(
                notes = parts.any { it.equals("vault", ignoreCase = true) || it.equals("notes", ignoreCase = true) },
                working = parts.any { it.equals("working", ignoreCase = true) },
                folders = parts.filter { !it.equals("vault", ignoreCase = true) && !it.equals("notes", ignoreCase = true) && !it.equals("working", ignoreCase = true) },
            )
        }
    }
}

/** Words of a text as the keyword ranking sees them. */
object Terms {
    private val token = Regex("""[\p{L}\p{N}]+(?:[._:\-/][\p{L}\p{N}]+)*""")
    private val part = Regex("""[\p{L}\p{N}]+""")
    private val stop = setOf(
        "is", "it", "of", "to", "in", "on", "at", "be", "as", "an", "or", "if", "by", "do", "we", "he", "me", "my", "no", "so", "up", "us", "am", "i",
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "was", "were", "with", "that", "this", "from", "have", "has", "had", "how", "what", "which", "when", "where",
        "who", "why", "does", "did", "into", "about", "than", "then", "them", "they", "their", "there", "its", "our", "your", "any", "some", "also", "will", "would", "could", "should",
    )

    /**
     * The terms of [text], lower case: every word, and every identifier **both whole and in parts** (`qwen3.8:27b` gives `qwen3.8:27b`, `qwen3`, `27b`; `keep_alive` gives `keep_alive`,
     * `keep`, `alive`), so an exact name and a loose word both hit. A plural ending is dropped (`databases` = `database`); very short and very common words are not terms.
     */
    fun of(text: String): List<String> {
        val out = ArrayList<String>()
        for (m in token.findAll(text.lowercase())) {
            val whole = m.value
            val parts = part.findAll(whole).map { it.value }.toList()
            if (parts.size > 1) add(out, whole)
            parts.forEach { add(out, it) }
        }
        return out
    }

    private fun add(out: MutableList<String>, raw: String) {
        if (raw.length < 2 || raw in stop) return
        out += if (raw.length > 3 && raw.endsWith("s") && !raw.endsWith("ss") && raw.any { it.isLetter() } && raw.none { it.isDigit() }) raw.dropLast(1) else raw
    }
}

/** BM25 over [docs] (the text each is found by) with the usual k1 = 1.2 and b = 0.75; documents that share no term with the query are not returned. */
class Bm25<T>(private val docs: List<T>, text: (T) -> String) {
    private class Posting(val doc: Int, val tf: Int)

    private val postings = HashMap<String, MutableList<Posting>>()
    private val lengths = IntArray(docs.size)
    private val average: Double

    init {
        var total = 0L
        docs.forEachIndexed { i, d ->
            val terms = Terms.of(text(d))
            lengths[i] = terms.size
            total += terms.size
            terms.groupingBy { it }.eachCount().forEach { (t, n) -> postings.getOrPut(t) { ArrayList() } += Posting(i, n) }
        }
        average = if (docs.isEmpty()) 1.0 else (total.toDouble() / docs.size).coerceAtLeast(1.0)
    }

    /** The best [k] documents for [query], best first, with their scores. */
    fun search(query: String, k: Int): List<Pair<T, Double>> {
        val terms = Terms.of(query).distinct()
        if (terms.isEmpty() || docs.isEmpty()) return emptyList()
        val scores = HashMap<Int, Double>()
        for (t in terms) {
            val list = postings[t] ?: continue
            val idf = ln(1.0 + (docs.size - list.size + 0.5) / (list.size + 0.5))
            for (p in list) {
                val norm = 1.2 * (1 - 0.75 + 0.75 * lengths[p.doc] / average)
                scores.merge(p.doc, idf * p.tf * 2.2 / (p.tf + norm), Double::plus)
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(k).map { docs[it.key] to it.value }
    }
}

/**
 * The passages of every note of a vault, ranked by words. Built in memory from the notes (a vault of hundreds of notes takes milliseconds; no model, no file), brought up to date by
 * each note's size and time before a search, so it is always current. One per vault in the process ([forVault]), because tools are made again for every message.
 */
class LexicalIndex(private val source: PassageSource) {
    /** The index of a vault's notes, as before. */
    constructor(vault: Vault) : this(VaultSource(vault))

    private class Cached(val size: Long, val mtime: Long, val chunks: List<Chunk>)

    private var cache = HashMap<String, Cached>()
    private var bm25: Bm25<Chunk>? = null

    /** The passages of all notes, after reading again the ones that changed. */
    @Synchronized
    fun passages(): List<Chunk> {
        val files = source.files()
        var changed = bm25 == null || files.size != cache.size
        val next = HashMap<String, Cached>()
        for (f in files) {
            val old = cache[f.path]
            if (old != null && old.size == f.size && old.mtime == f.mtime) { next[f.path] = old; continue }
            changed = true
            next[f.path] = Cached(f.size, f.mtime, runCatching { source.chunks(f.path, source.read(f.path) ?: "") }.getOrDefault(emptyList()))
        }
        if (changed) { cache = next; bm25 = null }
        return cache.values.flatMap { it.chunks }
    }

    /** The best [k] passages for [query] by their words, best first. */
    @Synchronized
    fun search(query: String, k: Int): List<Pair<Chunk, Double>> {
        val all = passages()
        val index = bm25 ?: Bm25(all) { it.embedInput() }.also { bm25 = it }
        return index.search(query, k)
    }

    companion object {
        private val byVault = ConcurrentHashMap<String, LexicalIndex>()
        fun forVault(vault: Vault): LexicalIndex = forSource(VaultSource(vault))

        /** The one keyword index of [source] in this process (kept by its root, so tools made again for every message do not read everything again). */
        fun forSource(source: PassageSource): LexicalIndex = byVault.getOrPut(source.key) { LexicalIndex(source) }
    }
}

/**
 * The knobs of the retrieval, set in Settings → Search and used by `/vault` answers and the `search_vault` tool alike.
 *
 * - [meaningWeight] / [wordsWeight] (from the balance, 0..10): the share each method has in the fusion; equal by default.
 * - [gap] and [minWords]: the **noise filter**. A hit stays when it is strong in either way: it has at least [minWords] (a share, 0..1) of the query's words, OR its vector similarity is
 *   within [gap] of the best hit's. A hit weak in both ways (a passage that shares a word or two and means something else) is left out; the best hit always stays, and the answer says how many
 *   were left out. Similarity needs meaning to have run; without it only the words decide.
 * - [pool]: candidates per method; [perNote]: passages of one note sent in a `/vault` answer.
 */
data class SearchTuning(val wordsWeight: Double = 1.0, val meaningWeight: Double = 1.0, val gap: Double = 0.08, val minWords: Double = 0.35, val pool: Int = 40, val perNote: Int = 4) {
    companion object {
        fun of(c: AppConfig): SearchTuning {
            val b = c.searchBalance.coerceIn(0, 10)
            return SearchTuning(wordsWeight = (10 - b) / 5.0, meaningWeight = b / 5.0, gap = c.searchGap.coerceIn(0, 50) / 100.0, minWords = c.searchMinWords.coerceIn(0, 100) / 100.0, pool = c.searchPool.coerceIn(10, 400), perNote = c.searchPassages.coerceIn(1, 6))
        }
    }
}

/**
 * A note found by [HybridSearch]: its best passage ([heading], [excerpt]), how it was found ([via]) and its best [passages] (for building an excerpt). [score] (0 to 1) only orders the
 * notes: it is relative to the best a fused ranking can give, so a weak match can still say 0.9. What says how GOOD a match is: [wordsMatched] of [wordsTotal] (how many of the query's words
 * the passage has) and [similarity] (the cosine of the passage's vector with the query's, null when meaning did not take part; the scale depends on the embedding model).
 */
data class Found(
    val path: String, val heading: String, val excerpt: String, val score: Double, val via: String, val passages: List<Chunk>,
    val wordsMatched: Int = 0, val wordsTotal: Int = 0, val similarity: Double? = null, val line: Int = 0,
) {
    /** `words 2/3 · similarity 0.71`: the evidence behind a hit, for the model and the reader. */
    val evidence: String get() = listOfNotNull(
        if (wordsTotal > 0) "words $wordsMatched/$wordsTotal" else null,
        similarity?.let { "similarity ${"%.2f".format(java.util.Locale.ROOT, it)}" },
    ).joinToString(" · ")
}

/** What a search did: the notes, the mode that **really** ran (it can be fewer than asked: no embedding model means keywords only) and a note for the reader when it differs. */
data class Retrieved(val found: List<Found>, val used: RetrievalMode, val asked: RetrievalMode, val note: String = "", val leftOut: Int = 0) {
    val label: String get() = when (used) { RetrievalMode.Both -> "keywords + meaning"; RetrievalMode.Keywords -> "keywords"; RetrievalMode.Meaning -> "meaning" }
}

/**
 * Keywords and meaning together: both rank the **same passages**, so their lists can be fused with Reciprocal Rank Fusion (a passage scores the sum of `1 / (60 + rank)` over the lists it
 * is in; no score calibration between BM25 and cosine is needed). The result is the best passage per note. With no embedding model (or an empty index, or a failing one) the
 * keywords carry on alone and the result says so.
 */
class HybridSearch(private val lexical: LexicalIndex, private val semantic: EmbeddingIndex?, private val tuning: SearchTuning = SearchTuning()) {
    /** True when meaning can take part: an embedding index exists (it may still be empty: [refresh] fills it). */
    val canUnderstandMeaning: Boolean get() = semantic != null

    fun search(query: String, limit: Int, mode: RetrievalMode = RetrievalMode.Both, refresh: Boolean = false): Retrieved {
        if (query.isBlank()) return Retrieved(emptyList(), mode, mode, "the query is empty")
        val notes = mutableListOf<String>()
        var meaning: List<Pair<Chunk, Float>> = emptyList()
        val sem = semantic
        if (mode != RetrievalMode.Keywords) {
            if (sem == null) notes += "meaning search is off (no embedding model is set up)"
            else runCatching {
                if (refresh && (sem.isEmpty || sem.stale())) sem.refresh()
                meaning = sem.searchPassages(query, tuning.pool)
                if (meaning.isEmpty()) notes += "meaning search has nothing indexed yet (run /reindex)"
            }.onFailure { notes += "meaning search failed (${it.message?.take(80)})" }
        }
        val words = if (mode == RetrievalMode.Meaning) emptyList() else lexical.search(query, tuning.pool)
        val used = when {
            mode == RetrievalMode.Meaning -> RetrievalMode.Meaning
            meaning.isEmpty() -> RetrievalMode.Keywords
            else -> mode
        }
        val lists = listOf(words.map { it.first }, meaning.map { it.first }).filter { it.isNotEmpty() }
        val fused = LinkedHashMap<Chunk, Double>()
        val seenBy = HashMap<Chunk, MutableSet<String>>()
        words.map { it.first }.forEachIndexed { r, c -> fused.merge(c, tuning.wordsWeight / (RRF_K + r + 1), Double::plus); seenBy.getOrPut(c) { sortedSetOf() } += "keywords" }
        meaning.map { it.first }.forEachIndexed { r, c -> fused.merge(c, tuning.meaningWeight / (RRF_K + r + 1), Double::plus); seenBy.getOrPut(c) { sortedSetOf() } += "meaning" }
        val best = ((if (words.isNotEmpty()) tuning.wordsWeight else 0.0) + (if (meaning.isNotEmpty()) tuning.meaningWeight else 0.0)).coerceAtLeast(1e-9) / (RRF_K + 1.0)
        val cosine = meaning.associate { it.first to it.second.toDouble() }
        val queryTerms = Terms.of(query).distinct()
        val byNote = fused.entries.sortedByDescending { it.value }.groupBy { it.key.path }
        val all = byNote.entries.map { (path, items) ->
            val top = items.first()
            val have = Terms.of(top.key.embedInput()).toSet()
            Found(
                path, top.key.heading, excerpt(top.key.text), (top.value / best).coerceIn(0.0, 1.0), viaOf(seenBy[top.key].orEmpty()), items.take(tuning.perNote).map { it.key },
                wordsMatched = queryTerms.count { it in have }, wordsTotal = queryTerms.size, similarity = cosine[top.key], line = top.key.line,
            )
        }.sortedByDescending { it.score }
        val strong = noiseFilter(all)
        val wanted = limit.coerceIn(1, MAX_LIMIT)
        val found = strong.take(wanted)
        // only what would have been SHOWN counts as left out: a weak candidate far down the list was never going to be
        return Retrieved(found, used, mode, notes.joinToString("; "), leftOut = all.take(wanted).count { it !in strong })
    }

    /**
     * The noise filter ([SearchTuning]): a hit stays when it has enough of the query's words OR a vector similarity near the best one's; a hit weak both ways goes. The best hit always stays.
     * Without meaning (no similarity anywhere) only the words decide.
     */
    private fun noiseFilter(hits: List<Found>): List<Found> {
        if (hits.size <= 1) return hits
        val bestSimilarity = hits.mapNotNull { it.similarity }.maxOrNull()
        val kept = hits.filter { h ->
            val enoughWords = h.wordsTotal == 0 || h.wordsMatched.toDouble() / h.wordsTotal >= tuning.minWords - 1e-9
            val nearBest = bestSimilarity != null && tuning.gap > 0 && h.similarity != null && h.similarity >= bestSimilarity - tuning.gap - 1e-9
            enoughWords || nearBest
        }
        return if (hits.first() in kept) kept else listOf(hits.first()) + kept
    }

    /** The tool's answer: the best notes with heading, excerpt, a match score and how each was found, or why there are none. */
    fun answer(query: String, limit: Int, mode: RetrievalMode): String {
        if (query.isBlank()) return "Error: query is empty. Say what you are looking for, in words."
        val r = search(query, limit, mode, refresh = true)
        if (r.found.isEmpty()) return "No notes found by ${r.label} for \"${query.trim()}\"." + (if (r.note.isEmpty()) "" else " (${r.note})") + " Try other words, or read a note you know with read_note."
        val head = "Matches for \"${query.trim()}\" by ${r.label}" + (if (r.asked != r.used || r.note.isNotEmpty()) " (asked: ${r.asked.word}; ${r.note.ifEmpty { "meaning could not take part" }})" else "") + ". Open one with read_note:\n\n"
        return head + (if (r.leftOut > 0) "(${r.leftOut} weak match${if (r.leftOut == 1) "" else "es"} left out by the noise filter: too few of your words and not close in meaning)\n\n" else "") + r.found.withIndex().joinToString("\n\n") { (i, f) ->
            "${i + 1}. ${f.path}  (${f.via}${if (f.evidence.isEmpty()) "" else " · " + f.evidence})" + (if (f.heading.isEmpty()) "" else "\n   § ${f.heading}") + "\n   ${f.excerpt}"
        }
    }

    /**
     * The notes for a `/vault` answer, as [SearchHit]s with an excerpt of their best passages (up to [excerptChars]) so the model reads the part that matched, not the start of the note.
     * [Retrieved.used] says which method really found them.
     */
    fun hits(vault: Vault, query: String, notes: Int, excerptChars: Int, mode: RetrievalMode): Pair<List<SearchHit>, Retrieved> {
        val r = search(query, notes, mode)
        val hits = r.found.mapNotNull { f ->
            val note = vault.notes().firstOrNull { it.path == f.path } ?: return@mapNotNull null
            val text = f.passages.joinToString("\n\n…\n\n") { (if (it.heading.isEmpty()) "" else "§ ${it.heading}\n") + it.text }.take(excerptChars)
            SearchHit(note, (f.score * 100).toInt().coerceAtLeast(1), text, runCatching { vault.read(f.path).length }.getOrDefault(text.length))
        }
        return hits to r
    }

    private fun viaOf(by: Set<String>) = when { by.size > 1 -> "keywords + meaning"; "meaning" in by -> "meaning"; else -> "keywords" }

    private fun excerpt(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > EXCERPT) it.take(EXCERPT).trimEnd() + "…" else it }

    companion object {
        const val RRF_K = 60.0
        const val MAX_LIMIT = 10
        const val DEFAULT_LIMIT = 5
        private const val EXCERPT = 300
    }
}
