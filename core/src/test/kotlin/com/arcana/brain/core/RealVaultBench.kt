package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * What the opt-in checks against the real Ollama share (`RealPrefixCheck`, `RealModelCheck`): a copy of a real vault without its hidden folders, the questions asked of it
 * (per note its title, a section heading and a sentence from its middle; the right answer is that note) and the score of an index on them (top 1, top 5, mean reciprocal rank).
 */
object RealVaultBench {
    /** One question: its kind (`title`, `heading`, `sentence`), the text asked, and the vault path of the note that should come first. */
    data class Question(val kind: String, val query: String, val expected: String)

    data class Score(val top1: Double, val top5: Double, val mrr: Double)

    /** A copy of the Markdown notes of [source] (hidden folders left out), so an index built for a check never touches the real vault. */
    fun copy(source: Path): Vault {
        val copy = Files.createTempDirectory("bench-vault")
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") && source.relativize(it).none { p -> p.toString().startsWith(".") } }.forEach {
                val target = copy.resolve(source.relativize(it).toString()); Files.createDirectories(target.parent); Files.copy(it, target)
            }
        }
        return Vault(copy)
    }

    fun questions(vault: Vault): List<Question> {
        val out = ArrayList<Question>()
        for (note in vault.notes()) {
            val path = note.path
            val text = runCatching { Vault.splitFrontmatter(vault.read(path)).second }.getOrDefault("")
            if (text.length < 200) continue
            out += Question("title", note.title, path)
            text.lines().drop(1).firstOrNull { it.startsWith("## ") && it.length in 8..80 }?.let { out += Question("heading", it.removePrefix("## ").trim(), path) }
            val sentences = text.replace('\n', ' ').split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length in 50..220 && !it.startsWith("|") && !it.startsWith("#") }
            if (sentences.size >= 3) out += Question("sentence", sentences[sentences.size / 2], path)
        }
        return out
    }

    fun score(index: EmbeddingIndex, qs: List<Question>): Score {
        var top1 = 0; var top5 = 0; var rr = 0.0
        for (q in qs) {
            val rank = index.search(q.query, 20).indexOfFirst { it.path == q.expected }
            if (rank == 0) top1++
            if (rank in 0..4) top5++
            if (rank >= 0) rr += 1.0 / (rank + 1)
        }
        return Score(top1 * 100.0 / qs.size, top5 * 100.0 / qs.size, rr / qs.size)
    }

    val KINDS = listOf("title", "heading", "sentence", "all")

    fun of(kind: String, qs: List<Question>) = if (kind == "all") qs else qs.filter { it.kind == kind }
}
