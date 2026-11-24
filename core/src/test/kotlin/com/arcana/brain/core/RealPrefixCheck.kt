package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * An opt-in A/B check against the REAL Ollama and a copy of a REAL vault: does nomic-embed-text find the right note more often with its task prefixes
 * (`search_document: ` / `search_query: `) than without? `ARCANA_REAL_OLLAMA=1 ARCANA_REAL_VAULT=<folder> ARCANA_CHECK_OUT=<file> ./gradlew :core:test --tests '*RealPrefixCheck*'`.
 * Each note is looked for by meaning only, with three kinds of question: its title, one of its section headings, and a sentence from its middle; the score is how often it is
 * first (top 1), in the top 5, and the mean reciprocal rank. The same notes, passages and questions go to both indexes, so only the prefixes differ.
 */
class RealPrefixCheck {
    @Test
    fun prefixesAgainstNone() {
        if (System.getenv("ARCANA_REAL_OLLAMA") != "1") return
        val source = Path.of(System.getenv("ARCANA_REAL_VAULT"))
        val model = System.getenv("ARCANA_EMBED_MODEL") ?: "nomic-embed-text"
        val copy = Files.createTempDirectory("prefix-vault")
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") && source.relativize(it).none { p -> p.toString().startsWith(".") } }.forEach {
                val target = copy.resolve(source.relativize(it).toString()); Files.createDirectories(target.parent); Files.copy(it, target)
            }
        }
        val vault = Vault(copy)
        val client = OllamaClient { AppConfig(ollamaUrl = System.getenv("ARCANA_OLLAMA") ?: "http://localhost:11434") }
        val embedder = Embedder { texts -> client.embedBlocking(model, texts) }
        val plain = EmbeddingIndex(Files.createTempDirectory("idx-a").resolve("index.bin"), vault, model, embedder, prefixes = EmbedPrefixes.NONE)
        val prefixed = EmbeddingIndex(Files.createTempDirectory("idx-b").resolve("index.bin"), vault, model, embedder, prefixes = EmbedPrefixes.NOMIC)
        plain.refresh(); prefixed.refresh()

        // the questions: per note its title, a heading (not the first line) and a sentence from the middle of its text
        val questions = ArrayList<Triple<String, String, String>>() // kind, query, expected path
        for (note in vault.notes()) {
            val path = note.path
            val text = runCatching { Vault.splitFrontmatter(vault.read(path)).second }.getOrDefault("")
            if (text.length < 200) continue
            questions += Triple("title", note.title, path)
            text.lines().drop(1).firstOrNull { it.startsWith("## ") && it.length in 8..80 }?.let { questions += Triple("heading", it.removePrefix("## ").trim(), path) }
            val sentences = text.replace('\n', ' ').split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length in 50..220 && !it.startsWith("|") && !it.startsWith("#") }
            if (sentences.size >= 3) questions += Triple("sentence", sentences[sentences.size / 2], path)
        }
        val out = StringBuilder("model $model · ${vault.notes().size} notes · plain ${plain.chunkCount} / prefixed ${prefixed.chunkCount} passages · ${questions.size} questions\n\n")
        out.append(String.format("%-10s %6s | %-24s | %-24s\n", "kind", "n", "no prefix: top1 top5 MRR", "prefix:    top1 top5 MRR"))
        fun score(index: EmbeddingIndex, qs: List<Triple<String, String, String>>): Triple<Double, Double, Double> {
            var top1 = 0; var top5 = 0; var rr = 0.0
            for ((_, q, expected) in qs) {
                val rank = index.search(q, 20).indexOfFirst { it.path == expected }
                if (rank == 0) top1++
                if (rank in 0..4) top5++
                if (rank >= 0) rr += 1.0 / (rank + 1)
            }
            return Triple(top1 * 100.0 / qs.size, top5 * 100.0 / qs.size, rr / qs.size)
        }
        for (kind in listOf("title", "heading", "sentence", "all")) {
            val qs = if (kind == "all") questions else questions.filter { it.first == kind }
            if (qs.isEmpty()) continue
            val a = score(plain, qs); val b = score(prefixed, qs)
            out.append(String.format("%-10s %6d | %5.1f%% %5.1f%% %.3f      | %5.1f%% %5.1f%% %.3f\n", kind, qs.size, a.first, a.second, a.third, b.first, b.second, b.third))
        }
        Files.writeString(Path.of(System.getenv("ARCANA_CHECK_OUT")), out.toString())
    }
}
