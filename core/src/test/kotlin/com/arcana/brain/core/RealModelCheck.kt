package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * An opt-in comparison of embedding models against the REAL Ollama and a copy of a REAL vault: which model finds the right note most often by meaning alone?
 * `ARCANA_REAL_OLLAMA=1 ARCANA_REAL_VAULT=<folder> ARCANA_CHECK_OUT=<file> ARCANA_EMBED_MODELS="nomic-embed-text,qwen3-embedding:0.6b,qwen3-embedding:0.6b+q" ./gradlew :core:test --tests '*RealModelCheck*'`.
 * A model name ending in `+q` is the same model with Qwen3's query instruction ([EmbedPrefixes.QWEN3]); every model gets the same notes and questions ([RealVaultBench]),
 * and the table says, per kind of question, top 1, top 5 and the mean reciprocal rank, with how long building the index took.
 */
class RealModelCheck {
    @Test
    fun modelsSideBySide() {
        if (System.getenv("ARCANA_REAL_OLLAMA") != "1") return
        val vault = RealVaultBench.copy(Path.of(System.getenv("ARCANA_REAL_VAULT")))
        val models = (System.getenv("ARCANA_EMBED_MODELS") ?: "nomic-embed-text").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val client = OllamaClient { AppConfig(ollamaUrl = System.getenv("ARCANA_OLLAMA") ?: "http://localhost:11434") }
        val questions = RealVaultBench.questions(vault)
        val out = StringBuilder("${vault.notes().size} notes · ${questions.size} questions (${RealVaultBench.KINDS.dropLast(1).joinToString { k -> "${RealVaultBench.of(k, questions).size} ${k}s" }})\n\n")
        out.append(String.format("%-32s %8s %8s | %-22s | %-22s | %-22s | %-22s\n", "model", "passages", "index s", "title: top1 top5 MRR", "heading", "sentence", "all"))
        for (entry in models) {
            val instruct = entry.endsWith("+q")
            val model = entry.removeSuffix("+q")
            val embedder = Embedder { texts -> client.embedBlocking(model, texts) }
            val index = EmbeddingIndex(Files.createTempDirectory("idx").resolve("index.bin"), vault, model, embedder, prefixes = if (instruct) EmbedPrefixes.QWEN3 else EmbedPrefixes.NONE)
            val started = System.currentTimeMillis()
            index.refresh()
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            val cells = RealVaultBench.KINDS.map { k -> RealVaultBench.score(index, RealVaultBench.of(k, questions)).let { String.format("%5.1f%% %5.1f%% %.3f", it.top1, it.top5, it.mrr) } }
            out.append(String.format("%-32s %8d %8.1f | %-22s | %-22s | %-22s | %-22s\n", entry, index.chunkCount, seconds, cells[0], cells[1], cells[2], cells[3]))
            Files.writeString(Path.of(System.getenv("ARCANA_CHECK_OUT")), out.toString()) // written after each model, so a slow one does not lose the others
        }
    }
}
