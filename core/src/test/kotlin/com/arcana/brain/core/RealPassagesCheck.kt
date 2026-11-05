package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Opt-in (ARCANA_REAL_OLLAMA=1): which passages of a note reach the model for a question, for 2, 3, 4 passages per note, on a copy of a real vault with the real embedding model. */
class RealPassagesCheck {
    @Test
    fun passages() {
        if (System.getenv("ARCANA_REAL_OLLAMA") != "1") return
        val source = Path.of(System.getenv("ARCANA_REAL_VAULT"))
        val copy = Files.createTempDirectory("real-vault")
        Files.walk(source).use { ps -> ps.filter { Files.isRegularFile(it) && it.toString().endsWith(".md") && source.relativize(it).none { p -> p.toString().startsWith(".") } }.forEach {
            val t = copy.resolve(source.relativize(it).toString()); Files.createDirectories(t.parent); Files.copy(it, t) } }
        val vault = Vault(copy)
        val client = OllamaClient { AppConfig() }
        val index = EmbeddingIndex(Files.createTempDirectory("idx").resolve("index.bin"), vault, "nomic-embed-text", { client.embedBlocking("nomic-embed-text", it) })
        index.refresh()
        val out = StringBuilder()
        for (q in listOf("how long does a model stay in memory after my last message", "keep_alive")) {
            out.append("### $q\n")
            for (n in listOf(2, 3, 4)) {
                val r = HybridSearch(LexicalIndex(vault), index, SearchTuning(perNote = n)).search(q, 5, RetrievalMode.Both)
                val adr = r.found.firstOrNull { "ADR qwen3.8 conversation cache" in it.path }
                out.append("  perNote=$n  ADR passages: " + (adr?.passages?.joinToString(" | ") { it.heading.ifEmpty { "(top)" } } ?: "(ADR not in results)") + "\n")
            }
            // all headings of the ADR with their rank for reference
            val adrChunks = index.searchPassages(q, 80).filter { "ADR qwen3.8 conversation cache" in it.first.path }
            out.append("  ADR passages by similarity: " + adrChunks.joinToString(" | ") { "${it.first.heading.ifEmpty { "(top)" }} ${"%.2f".format(java.util.Locale.ROOT, it.second)}" } + "\n\n")
        }
        Files.writeString(Path.of(System.getenv("ARCANA_CHECK_OUT")), out.toString())
    }
}
