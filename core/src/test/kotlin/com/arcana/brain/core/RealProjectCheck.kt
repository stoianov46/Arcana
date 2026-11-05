package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Opt-in (ARCANA_REAL_OLLAMA=1, ARCANA_REAL_PROJECT=<folder>, ARCANA_CHECK_OUT=<file>): the project search on a real folder with the real embedding model, with the time the first index takes. */
class RealProjectCheck {
    @Test
    fun project() {
        if (System.getenv("ARCANA_REAL_OLLAMA") != "1") return
        val root = Path.of(System.getenv("ARCANA_REAL_PROJECT"))
        val out = StringBuilder()
        val client = OllamaClient { AppConfig() }
        val source = FolderSource(root)
        val files = source.files()
        val lexical = LexicalIndex.forSource(source)
        val t0 = System.currentTimeMillis()
        val passages = lexical.passages().size
        out.append("folder $root: ${files.size} files (truncated=${source.truncated}), $passages passages, keyword index ${System.currentTimeMillis() - t0} ms\n")
        val idx = EmbeddingIndex(Files.createTempDirectory("pidx").resolve("i.bin"), source, "nomic-embed-text", { client.embedBlocking("nomic-embed-text", it) })
        val t1 = System.currentTimeMillis()
        val r = idx.refresh()
        out.append("meaning index: ${r.notes} files, ${r.chunks} passages in ${System.currentTimeMillis() - t1} ms\n\n")
        val search = ProjectSearch(listOf(root), { idx }, SearchTuning())
        val fromEnv = System.getenv("ARCANA_CHECK_QUERIES")?.split("||")?.map { it.trim() to "from ARCANA_CHECK_QUERIES" }
        for ((q, what) in fromEnv ?: listOf(
            "chatRequest" to "an exact identifier",
            "where is keep_alive sent to Ollama" to "words + paraphrase",
            "how does the context meter estimate tokens right after a compaction" to "a paraphrase of contextTokens",
            "what leaves out weak search results" to "a paraphrase of the noise filter",
            "which folders may the search look in" to "a paraphrase of projectRoots",
        )) {
            out.append("### $q   ($what)\n")
            for (mode in RetrievalMode.entries) {
                val t = System.currentTimeMillis()
                val res = search.search(q, 5, mode)
                out.append("  [${mode.word}] ${System.currentTimeMillis() - t} ms: " + res.found.joinToString(" | ") { "${it.path.substringAfterLast('/')}:${it.line} (${it.via}; ${it.evidence})" } + "\n")
            }
            out.append("\n")
        }
        Files.writeString(Path.of(System.getenv("ARCANA_CHECK_OUT")), out.toString())
    }
}
