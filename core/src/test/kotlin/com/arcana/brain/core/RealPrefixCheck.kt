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
        val vault = RealVaultBench.copy(source)
        val client = OllamaClient { AppConfig(ollamaUrl = System.getenv("ARCANA_OLLAMA") ?: "http://localhost:11434") }
        val embedder = Embedder { texts -> client.embedBlocking(model, texts) }
        val plain = EmbeddingIndex(Files.createTempDirectory("idx-a").resolve("index.bin"), vault, model, embedder, prefixes = EmbedPrefixes.NONE)
        val prefixed = EmbeddingIndex(Files.createTempDirectory("idx-b").resolve("index.bin"), vault, model, embedder, prefixes = EmbedPrefixes.NOMIC)
        plain.refresh(); prefixed.refresh()

        val questions = RealVaultBench.questions(vault)
        val out = StringBuilder("model $model · ${vault.notes().size} notes · plain ${plain.chunkCount} / prefixed ${prefixed.chunkCount} passages · ${questions.size} questions\n\n")
        out.append(String.format("%-10s %6s | %-24s | %-24s\n", "kind", "n", "no prefix: top1 top5 MRR", "prefix:    top1 top5 MRR"))
        for (kind in RealVaultBench.KINDS) {
            val qs = RealVaultBench.of(kind, questions)
            if (qs.isEmpty()) continue
            val a = RealVaultBench.score(plain, qs); val b = RealVaultBench.score(prefixed, qs)
            out.append(String.format("%-10s %6d | %5.1f%% %5.1f%% %.3f      | %5.1f%% %5.1f%% %.3f\n", kind, qs.size, a.top1, a.top5, a.mrr, b.top1, b.top5, b.mrr))
        }
        Files.writeString(Path.of(System.getenv("ARCANA_CHECK_OUT")), out.toString())
    }
}
