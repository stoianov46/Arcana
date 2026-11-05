package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * An opt-in check against the REAL Ollama and a copy of a REAL vault, not part of the normal run: `ARCANA_REAL_OLLAMA=1 ARCANA_REAL_VAULT=<folder> ARCANA_CHECK_OUT=<file> ./gradlew :core:test --tests '*RealOllamaRetrievalCheck*'`.
 * It indexes a temporary copy with the real embedding model (a temporary index file) and prints what each mode finds for a few questions.
 */
class RealOllamaRetrievalCheck {
    @Test
    fun realModelRealVault() {
        if (System.getenv("ARCANA_REAL_OLLAMA") != "1") return
        val source = Path.of(System.getenv("ARCANA_REAL_VAULT"))
        val out = StringBuilder()
        val copy = Files.createTempDirectory("real-vault")
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") && source.relativize(it).none { p -> p.toString().startsWith(".") } }.forEach {
                val target = copy.resolve(source.relativize(it).toString()); Files.createDirectories(target.parent); Files.copy(it, target)
            }
        }
        val vault = Vault(copy)
        val client = OllamaClient { AppConfig(ollamaUrl = System.getenv("ARCANA_OLLAMA") ?: "http://localhost:11434") }
        val index = EmbeddingIndex(Files.createTempDirectory("real-idx").resolve("index.bin"), vault, "nomic-embed-text", { texts -> client.embedBlocking("nomic-embed-text", texts) })
        val t0 = System.currentTimeMillis()
        val r = index.refresh()
        out.append("indexed ${r.notes} notes, ${r.chunks} passages in ${System.currentTimeMillis() - t0} ms\n\n")
        val h = HybridSearch(LexicalIndex(vault), index)
        val questions = listOf(
            "qwen3.8:27b" to "an exact identifier",
            "keep_alive" to "an exact field name",
            "how long does a model stay in memory after my last message" to "a paraphrase of keep_alive",
            "why is the answer slow after a long pause" to "a paraphrase of the idle reload",
            "what did we decide about the database" to "a paraphrase, maybe nothing",
            "which skills create research reports" to "a plain question",
        )
        for ((q, what) in questions) {
            out.append("### $q   ($what)\n")
            // the noise filter: what the default setting leaves out of the fused result, compared with no filter at all
            val unfiltered = HybridSearch(LexicalIndex(vault), index, SearchTuning(minWords = 0.0, gap = 0.0)).search(q, 8, RetrievalMode.Both)
            val filtered = h.search(q, 8, RetrievalMode.Both)
            val dropped = unfiltered.found.map { it.path }.toSet() - filtered.found.map { it.path }.toSet() // the top 8 with and without the filter (the filter also fills the places it frees with the next best)
            out.append("  [noise filter] left out ${filtered.leftOut}: " + (if (dropped.isEmpty()) "(nothing)" else unfiltered.found.filter { it.path in dropped }.joinToString(" | ") { "${it.path.substringAfterLast('/').removeSuffix(".md").take(36)} (${it.evidence})" }) + "\n")
            for (mode in RetrievalMode.entries) {
                val t = System.currentTimeMillis()
                val res = h.search(q, 4, mode)
                out.append("  [${mode.word}] ${System.currentTimeMillis() - t} ms: " + res.found.joinToString(" | ") { "${it.path.substringAfterLast('/').removeSuffix(".md").take(40)} (${it.via}; ${it.evidence})" } + (if (res.note.isNotEmpty()) "  NOTE: ${res.note}" else "") + "\n")
            }
            out.append("\n")
        }
        Files.writeString(Path.of(System.getenv("ARCANA_CHECK_OUT")), out.toString())
    }
}
