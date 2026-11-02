package com.arcana.brain.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.sqrt

/** Turns texts into vectors. The real one asks Ollama ([OllamaClient.embedBlocking]); tests hand in a fake. One vector per text, in order. */
fun interface Embedder {
    fun embed(inputs: List<String>): List<FloatArray>
}

/** One passage of a note: [heading] is its path of headings (`Intro › Setup`, blank before the first heading), [text] what is under it. */
data class Chunk(val path: String, val heading: String, val text: String) {
    /** What is embedded: the note's title and heading path in front of the text, so a passage "knows" where it sits. */
    fun embedInput(): String {
        val title = path.substringAfterLast('/').removeSuffix(".md")
        return (if (heading.isEmpty()) title else "$title › $heading") + "\n\n" + text
    }
}

/** A found passage: where it is, a short [excerpt], and the cosine [score] (1.0 = the same direction). */
data class SemanticHit(val path: String, val heading: String, val excerpt: String, val score: Float)

/** What [EmbeddingIndex.refresh] did. */
data class RefreshResult(val notes: Int, val chunks: Int, val embeddedNotes: Int, val removedNotes: Int, val millis: Long)

/** Splitting a note into passages for embedding. */
object Chunker {
    /** About how many characters a passage has; a paragraph is never cut unless it is longer than this by itself. */
    const val TARGET = 800

    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")

    /**
     * The passages of [text] (a note's whole text; the properties block is skipped): split by headings first, then by paragraphs packed up to [target]
     * characters, a longer paragraph at a sentence or word. Code blocks stay whole while they fit. Empty passages are dropped.
     */
    fun chunks(path: String, text: String, target: Int = TARGET): List<Chunk> {
        val body = Vault.splitFrontmatter(text).second
        val out = ArrayList<Chunk>()
        val stack = ArrayList<Pair<Int, String>>()
        val paragraphs = ArrayList<String>()
        val current = StringBuilder()
        var inFence = false

        fun path() = stack.joinToString(" › ") { it.second }
        fun flushParagraph() {
            if (current.isNotBlank()) paragraphs += current.toString().trim()
            current.clear()
        }
        fun flushSection() {
            flushParagraph()
            val heading = path()
            val pack = StringBuilder()
            fun emit() { if (pack.isNotBlank()) out += Chunk(path, heading, pack.toString().trim()); pack.clear() }
            for (p in paragraphs) {
                for (piece in split(p, target)) {
                    if (pack.isNotEmpty() && pack.length + piece.length + 2 > target) emit()
                    if (pack.isNotEmpty()) pack.append("\n\n")
                    pack.append(piece)
                }
            }
            emit()
            paragraphs.clear()
        }

        for (line in body.lines()) {
            if (line.trimStart().startsWith("```")) { inFence = !inFence; current.append(line).append('\n'); continue }
            val h = if (inFence) null else heading.matchEntire(line)
            when {
                h != null -> {
                    flushSection()
                    val level = h.groupValues[1].length
                    while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.lastIndex)
                    stack += level to h.groupValues[2]
                }
                !inFence && line.isBlank() -> flushParagraph()
                else -> current.append(line).append('\n')
            }
        }
        flushSection()
        return out
    }

    /** [paragraph] as pieces of at most about [target] characters: whole when it fits, else cut at the last sentence end or space before the limit. */
    private fun split(paragraph: String, target: Int): List<String> {
        if (paragraph.length <= target * 3 / 2) return listOf(paragraph)
        val pieces = ArrayList<String>()
        var rest = paragraph
        while (rest.length > target * 3 / 2) {
            val window = rest.substring(0, target)
            val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("\n"), window.lastIndexOf("? "), window.lastIndexOf("! ")).let { if (it > target / 2) it + 1 else window.lastIndexOf(' ').let { s -> if (s > target / 2) s else target } }
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}

/** Cosine similarity of two vectors of the same size (0 when either has no length). */
fun cosine(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "vectors of different size: ${a.size} and ${b.size}" }
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
}

/**
 * The semantic index of one vault for one embedding model: every note's passages with their vectors, kept in a file in the config folder
 * (`embeddings/<hash of vault and model>/index.bin`) so it survives restarts. Incremental: [refresh] embeds only notes whose size or modification
 * time changed and forgets deleted ones. Vectors are stored normalised, so a search is a dot product. Hidden folders (`.arcana`, `.trash`,
 * `.obsidian`) are not indexed ([Vault.notes] leaves them out).
 */
class EmbeddingIndex(private val file: Path, private val vault: Vault, val model: String, private val embedder: Embedder, private val batch: Int = BATCH) {
    private class Entry(val size: Long, val mtime: Long, val chunks: List<Chunk>, val vectors: List<FloatArray>)

    private var entries: MutableMap<String, Entry> = load()

    val noteCount: Int @Synchronized get() = entries.size
    val chunkCount: Int @Synchronized get() = entries.values.sumOf { it.chunks.size }
    val isEmpty: Boolean @Synchronized get() = entries.isEmpty()

    private fun stat(path: String): Pair<Long, Long>? = runCatching {
        val p = vault.root.resolve(path)
        Files.size(p) to Files.getLastModifiedTime(p).toMillis()
    }.getOrNull()

    /** Notes that are new or changed since they were embedded, and notes that were deleted: what [refresh] would do. */
    @Synchronized
    fun pending(): Pair<List<String>, List<String>> {
        val current = vault.notes().map { it.path }
        val changed = current.filter { p -> val s = stat(p); val e = entries[p]; s != null && (e == null || e.size != s.first || e.mtime != s.second) }
        return changed to (entries.keys - current.toSet()).toList()
    }

    /** True when the vault differs from what was embedded (or nothing was embedded yet). */
    fun stale(): Boolean = pending().let { it.first.isNotEmpty() || it.second.isNotEmpty() }

    /**
     * Brings the index up to date. [progress] gets (notes done, notes to do) as the work goes. When embedding fails half way, the notes finished so far are kept
     * (saved) and the error is thrown, so the next call goes on from there.
     */
    @Synchronized
    fun refresh(progress: (done: Int, total: Int) -> Unit = { _, _ -> }): RefreshResult {
        val started = System.currentTimeMillis()
        val (changed, removed) = pending()
        removed.forEach { entries.remove(it) }
        progress(0, changed.size)
        // every passage of every changed note in one list, embedded in batches, so small notes share a request
        class Item(val note: String, val chunk: Chunk)
        val items = ArrayList<Item>()
        val sizes = LinkedHashMap<String, Int>()
        val stats = HashMap<String, Pair<Long, Long>>()
        for (p in changed) {
            val text = runCatching { vault.read(p) }.getOrNull() ?: continue
            val s = stat(p) ?: continue
            val chunks = Chunker.chunks(p, text)
            stats[p] = s
            sizes[p] = chunks.size
            chunks.forEach { items += Item(p, it) }
            if (chunks.isEmpty()) entries[p] = Entry(s.first, s.second, emptyList(), emptyList())
        }
        val vectors = ArrayList<FloatArray>(items.size)
        // the position of each note's last passage, so "which notes are done" is a comparison, not a search
        val lastAt = HashMap<String, Int>()
        items.forEachIndexed { i, it -> lastAt[it.note] = i }
        var finished = sizes.count { it.value == 0 }
        try {
            var at = 0
            while (at < items.size) {
                val part = items.subList(at, minOf(items.size, at + batch))
                val got = embedder.embed(part.map { it.chunk.embedInput() })
                if (got.size != part.size) throw IllegalStateException("The embedding model returned ${got.size} vectors for ${part.size} passages.")
                got.forEach { vectors += normalise(it) }
                at += part.size
                // notes whose last passage is now embedded are done
                finished = sizes.count { (note, n) -> n == 0 || (lastAt[note] ?: Int.MAX_VALUE) < vectors.size }
                progress(finished, changed.size)
            }
        } finally {
            // keep every note that is complete, also when a batch failed
            var offset = 0
            for ((note, n) in sizes) {
                if (n > 0 && offset + n <= vectors.size) {
                    val s = stats.getValue(note)
                    entries[note] = Entry(s.first, s.second, items.subList(offset, offset + n).map { it.chunk }, vectors.subList(offset, offset + n).toList())
                }
                offset += n
            }
            save()
        }
        return RefreshResult(entries.size, entries.values.sumOf { it.chunks.size }, changed.size, removed.size, System.currentTimeMillis() - started)
    }

    /** The best passages for [query], at most one per note, best first. Needs no refresh to run (it searches what is embedded). */
    @Synchronized
    fun search(query: String, k: Int = 5): List<SemanticHit> {
        if (entries.isEmpty() || query.isBlank()) return emptyList()
        val q = normalise(embedder.embed(listOf(query)).single())
        val best = ArrayList<SemanticHit>()
        for ((path, e) in entries) {
            var top = -2f; var at = -1
            for (i in e.vectors.indices) {
                val v = e.vectors[i]
                if (v.size != q.size) continue
                var d = 0f
                for (j in v.indices) d += v[j] * q[j]
                if (d > top) { top = d; at = i }
            }
            if (at >= 0) best += SemanticHit(path, e.chunks[at].heading, excerpt(e.chunks[at].text), top)
        }
        return best.sortedByDescending { it.score }.take(k.coerceIn(1, 50))
    }

    private fun excerpt(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > EXCERPT) it.take(EXCERPT).trimEnd() + "…" else it }

    private fun normalise(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        if (n == 0.0) return v
        val inv = (1.0 / sqrt(n)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    // ---- the file: magic, version, model, then per note: path, size, mtime, passages (heading, text, vector)

    private fun load(): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        if (!Files.isRegularFile(file)) return out
        runCatching {
            DataInputStream(Files.newInputStream(file).buffered(1 shl 16)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return out
                if (input.readUTF() != model) return out
                val dim = input.readInt()
                repeat(input.readInt()) {
                    val path = input.readUTF(); val size = input.readLong(); val mtime = input.readLong()
                    val n = input.readInt()
                    val chunks = ArrayList<Chunk>(n); val vectors = ArrayList<FloatArray>(n)
                    repeat(n) {
                        chunks += Chunk(path, input.readUTF(), input.readUTF())
                        vectors += FloatArray(dim) { input.readFloat() }
                    }
                    out[path] = Entry(size, mtime, chunks, vectors)
                }
            }
        }.onFailure { out.clear() } // a damaged or older file is simply rebuilt
        return out
    }

    private fun save() {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        val dim = entries.values.firstNotNullOfOrNull { it.vectors.firstOrNull()?.size } ?: 0
        DataOutputStream(Files.newOutputStream(tmp).buffered(1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(model); out.writeInt(dim)
            val saved = entries.filter { (_, e) -> e.vectors.all { it.size == dim } }
            out.writeInt(saved.size)
            for ((path, e) in saved) {
                out.writeUTF(path); out.writeLong(e.size); out.writeLong(e.mtime); out.writeInt(e.chunks.size)
                for (i in e.chunks.indices) {
                    out.writeUTF(e.chunks[i].heading.take(MAX_UTF)); out.writeUTF(e.chunks[i].text.take(MAX_UTF))
                    e.vectors[i].forEach { out.writeFloat(it) }
                }
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val BATCH = 16
        private const val MAGIC = 0x41524358 // "ARCX"
        private const val VERSION = 1
        private const val EXCERPT = 240
        private const val MAX_UTF = 8000

        /** `~/.config/arcana/embeddings`, next to the settings. */
        fun defaultDir(): Path = ConfigStore.defaultPath().resolveSibling("embeddings")

        /** The index file of [vault] with [model]: a folder named by a hash of both, so another vault or another model never reads this one. */
        fun fileFor(vault: Vault, model: String, base: Path = defaultDir()): Path {
            val hash = MessageDigest.getInstance("SHA-256").digest("${vault.root}\n$model".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            return base.resolve(hash).resolve("index.bin")
        }
    }
}

/**
 * The semantic search as the `semantic_search` tool uses it: a ready [index] that is brought up to date by itself when it is empty or the vault changed
 * (so the first call of a session may take a while on a big vault; `/reindex` does the same with progress on screen).
 */
class SemanticSearch(val index: EmbeddingIndex) {
    /** The tool's answer: the best notes for [query] with heading, excerpt and score, or why there is nothing. */
    fun answer(query: String, limit: Int): String {
        if (query.isBlank()) return "Error: query is empty. Say what you are looking for, in words."
        val refreshed = if (index.isEmpty || index.stale()) index.refresh() else null
        val hits = index.search(query, limit.coerceIn(1, MAX_LIMIT))
        if (hits.isEmpty()) return "No notes to search: the vault has no embedded notes yet (${index.noteCount} indexed). Use search_notes for words."
        val head = "Semantic matches for \"${query.trim()}\" (embedding model ${index.model}; ${index.noteCount} notes, ${index.chunkCount} passages indexed" +
            (refreshed?.let { "; index refreshed: ${it.embeddedNotes} notes embedded, ${it.removedNotes} dropped" } ?: "") + "). Open one with read_note:\n\n"
        return head + hits.withIndex().joinToString("\n\n") { (i, h) ->
            "${i + 1}. ${h.path}  (score ${"%.2f".format(java.util.Locale.ROOT, h.score)})" + (if (h.heading.isEmpty()) "" else "\n   § ${h.heading}") + "\n   ${h.excerpt}"
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 10
    }
}
