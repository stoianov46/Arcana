package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The semantic search: chunks, vectors, the on-disk index, the `semantic_search` tool, and the Ollama call (a JDK HTTP server stands in for Ollama). */
class EmbeddingsTest {
    /** A fake embedding model: one dimension per topic word, counting how often it occurs (so "database" and "databases" match, "cooking" does not). */
    private class Topics(val log: MutableList<List<String>> = ArrayList(), val failOnCall: Int = -1) : Embedder {
        private val words = listOf("database", "kotlin", "cooking", "navigation")
        override fun embed(inputs: List<String>): List<FloatArray> {
            log += inputs
            if (log.size == failOnCall) throw IllegalStateException("model unavailable")
            return inputs.map { t -> FloatArray(words.size) { i -> Regex(words[i], RegexOption.IGNORE_CASE).findAll(t).count().toFloat() } }
        }
        val texts: Int get() = log.sumOf { it.size }
    }

    private fun vault(vararg notes: Pair<String, String>): Pair<Vault, Path> {
        val dir = Files.createTempDirectory("sem")
        notes.forEach { (name, text) -> dir.resolve(name).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) } }
        return Vault(dir) to dir
    }

    private fun index(v: Vault, dir: Path, e: Embedder, model: String = "fake-embed", batch: Int = 16) =
        EmbeddingIndex(dir.resolveSibling(dir.fileName.toString() + "-idx").resolve("index.bin"), v, model, e, batch)

    @Test
    fun chunksFollowHeadingsAndSkipTheProperties() {
        val text = "---\ntags: [x]\n---\n\nIntro text.\n\n# Setup\nInstall it.\n\n## Config\nSet the thing.\n\n## Run\nRun it.\n\n# Notes\nLast words.\n"
        val chunks = Chunker.chunks("Dev/Guide.md", text)
        assertEquals(listOf("", "Setup", "Setup › Config", "Setup › Run", "Notes"), chunks.map { it.heading })
        assertEquals("Intro text.", chunks[0].text)
        assertTrue(chunks.none { "tags" in it.text }, "the properties block is not embedded")
        assertTrue(chunks[2].embedInput().startsWith("Guide › Setup › Config\n\n"), "the title and heading path go in front of the text")
    }

    @Test
    fun chunksPackParagraphsSplitLongOnesAndKeepCodeWhole() {
        val para = "One sentence here. ".repeat(12).trim() // about 230 characters
        val note = (1..10).joinToString("\n\n") { "$it $para" }
        val chunks = Chunker.chunks("n.md", note, target = 800)
        assertTrue(chunks.size in 3..4, "ten 230-character paragraphs make about three passages: ${chunks.size}")
        assertTrue(chunks.all { it.text.length <= 1200 })
        val long = Chunker.chunks("n.md", "Word. ".repeat(600), target = 800)
        assertTrue(long.size >= 4 && long.all { it.text.length <= 1200 }, "one huge paragraph is cut at sentences")
        val code = Chunker.chunks("n.md", "Text\n\n```kotlin\nfun a() {}\n\n# not a heading\nfun b() {}\n```\n")
        assertEquals(listOf(""), code.map { it.heading }, "a # inside a code block is not a heading")
        assertTrue("fun b()" in code.single().text)
        assertEquals(emptyList(), Chunker.chunks("n.md", "---\na: b\n---\n\n   \n"))
    }

    @Test
    fun cosineOfVectors() {
        assertEquals(1f, cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-6f)
        assertEquals(0f, cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(0f, cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
        assertFailsWith<IllegalArgumentException> { cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)) }
    }

    @Test
    fun theEmbedProtocolAndTheOllamaCall() {
        assertEquals("""{"model":"m","input":["a","b"]}""", OllamaProtocol.embedRequest("m", listOf("a", "b")).toString())
        assertEquals(2, OllamaProtocol.parseEmbeddings("""{"embeddings":[[1,2.5],[3,4]]}""").size)
        assertEquals(listOf(0.5f), OllamaProtocol.parseEmbeddings("""{"embedding":[0.5]}""").single().toList())
        assertEquals(emptyList(), OllamaProtocol.parseEmbeddings("""{"model":"m"}"""))
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/api/embed") { ex ->
            val body = ex.requestBody.readBytes().decodeToString(); seen += body
            val bytes: ByteArray; val code: Int
            if ("broken" in body) { bytes = """{"error":"model \"broken\" does not support embeddings"}""".toByteArray(); code = 400 }
            else { bytes = """{"embeddings":[[1.0,0.0],[0.0,1.0]]}""".toByteArray(); code = 200 }
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = OllamaClient { AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}") }
            val vectors = runBlocking { client.embed("nomic-embed-text", listOf("one", "two")) }
            assertEquals(listOf(listOf(1f, 0f), listOf(0f, 1f)), vectors.map { it.toList() })
            assertTrue(seen.single().contains("nomic-embed-text") && seen.single().contains("two"))
            val e = assertFailsWith<OllamaException> { client.embedBlocking("broken", listOf("x", "y")) }
            assertTrue("does not support embeddings" in e.message!! && "ollama pull nomic-embed-text" in e.message!!, e.message)
            assertEquals(emptyList(), client.embedBlocking("anything", emptyList()), "nothing to embed, nothing asked")
        } finally { server.stop(0) }
    }

    @Test
    fun theIndexRanksByMeaningWithOneHitPerNote() {
        val (v, dir) = vault(
            "Data/Postgres.md" to "# Storage\nWe chose a database for the orders. The database is Postgres.\n\n# Backups\nNightly database dumps.",
            "Food/Pasta.md" to "# Dinner\nCooking pasta takes ten minutes. More cooking tips follow.",
            "Dev/Compose.md" to "# Navigation\nNavigation in Kotlin apps uses a back stack. Kotlin navigation libraries differ.",
        )
        val idx = index(v, dir, Topics())
        val result = idx.refresh()
        assertEquals(3, result.notes); assertEquals(3, result.embeddedNotes); assertEquals(0, result.removedNotes)
        val hits = idx.search("which database did we pick", 5)
        assertEquals("Data/Postgres.md", hits.first().path)
        assertEquals(3, hits.map { it.path }.distinct().size, "one hit per note")
        assertTrue(hits.first().score > hits[1].score && hits.first().score > 0.9f)
        assertEquals("Dev/Compose.md", idx.search("kotlin navigation", 1).single().path)
        assertEquals("Dinner", idx.search("cooking", 1).single().heading)
        assertEquals(emptyList(), idx.search("   ", 5))
    }

    @Test
    fun refreshEmbedsOnlyWhatChangedAndDropsDeletedNotes() {
        val (v, dir) = vault("a.md" to "# A\ndatabase notes", "b.md" to "# B\ncooking notes", "c.md" to "# C\nkotlin notes")
        val model = Topics()
        val idx = index(v, dir, model)
        idx.refresh()
        assertEquals(3, model.texts)
        assertTrue(!idx.stale(), "nothing changed since")
        assertEquals(0, idx.refresh().embeddedNotes); assertEquals(3, model.texts, "an unchanged vault asks the model nothing")
        // change one note (a new size), delete another, add a third
        Files.writeString(dir.resolve("a.md"), "# A\ndatabase notes, now longer")
        Files.setLastModifiedTime(dir.resolve("a.md"), FileTime.fromMillis(System.currentTimeMillis() + 5000))
        Files.delete(dir.resolve("b.md"))
        Files.writeString(dir.resolve("d.md"), "# D\nnavigation notes")
        assertTrue(idx.stale())
        val result = idx.refresh()
        assertEquals(2, result.embeddedNotes); assertEquals(1, result.removedNotes); assertEquals(3, result.notes)
        assertEquals(5, model.texts, "only the changed and the new note were embedded again")
        assertEquals(emptyList(), idx.search("cooking", 5).filter { it.path == "b.md" })
    }

    @Test
    fun theIndexSurvivesARestartAndIsRebuiltForAnotherModel() {
        val (v, dir) = vault("a.md" to "# A\ndatabase notes", "b.md" to "# B\nkotlin notes")
        val first = Topics()
        index(v, dir, first).refresh()
        val second = Topics()
        val again = index(v, dir, second)
        assertEquals(2, again.noteCount); assertEquals(2, again.chunkCount)
        assertTrue(!again.stale()); assertEquals("a.md", again.search("database", 1).single().path)
        assertEquals(1, second.log.size, "loading the file asked nothing; only the query was embedded")
        val other = index(v, dir, Topics(), model = "another-model")
        assertEquals(0, other.noteCount, "a file written for another model is not used")
        // fileFor: another vault or another model gives another file
        val base = Files.createTempDirectory("emb")
        assertTrue(EmbeddingIndex.fileFor(v, "m1", base) != EmbeddingIndex.fileFor(v, "m2", base))
        assertTrue(EmbeddingIndex.fileFor(v, "m1", base) != EmbeddingIndex.fileFor(vault("x.md" to "x").first, "m1", base))
    }

    @Test
    fun aFailureHalfWayKeepsTheFinishedNotesAndTheNextRunContinues() {
        val (v, dir) = vault("a.md" to "# A\ndatabase", "b.md" to "# B\ncooking", "c.md" to "# C\nkotlin")
        val failing = Topics(failOnCall = 3)
        val idx = index(v, dir, failing, batch = 1)
        val e = assertFailsWith<IllegalStateException> { idx.refresh() }
        assertTrue("model unavailable" in e.message!!)
        assertEquals(2, idx.noteCount, "two notes were finished before the failure")
        val healthy = Topics()
        val result = index(v, dir, healthy, batch = 1).refresh()
        assertEquals(1, result.embeddedNotes, "the next run embeds only the missing note")
        assertEquals(3, result.notes)
    }

    @Test
    fun progressCountsFinishedNotes() {
        val (v, dir) = vault(*(1..5).map { "n$it.md" to "# N$it\ndatabase $it" }.toTypedArray())
        val seen = ArrayList<Pair<Int, Int>>()
        index(v, dir, Topics(), batch = 2).refresh { done, total -> seen += done to total }
        assertEquals(0 to 5, seen.first()); assertEquals(5 to 5, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first }, "progress never goes back: $seen")
    }

    @Test
    fun hiddenFoldersAreNotIndexed() {
        val (v, dir) = vault("Open.md" to "# O\ndatabase", ".arcana/skills/x.md" to "# X\ndatabase", ".trash/old.md" to "# T\ndatabase")
        val idx = index(v, dir, Topics())
        idx.refresh()
        assertEquals(listOf("Open.md"), idx.search("database", 10).map { it.path })
    }

    @Test
    fun theToolFormatsHitsRefreshesLazilyAndIsReadOnly() {
        val (v, dir) = vault(
            "Data/Postgres.md" to "# Storage\nWe chose a database for the orders.",
            "Food/Pasta.md" to "# Dinner\nCooking pasta takes ten minutes.",
        )
        val model = Topics()
        val idx = index(v, dir, model)
        val backups = Files.createTempDirectory("bk")
        val box = ToolBox(v, emptyList(), backups, semantic = SemanticSearch(idx))
        val spec = box.find("semantic_search")!!
        assertEquals(ToolRisk.Read, spec.risk)
        assertTrue("MEANING" in spec.description && "search_notes" in spec.description, "it tells the model when to use which search")
        assertTrue(idx.isEmpty)
        val out = box.execute(call("semantic_search", "query" to "the database decision"))
        assertTrue(!idx.isEmpty, "the first call fills the empty index")
        assertTrue(out.startsWith("Semantic matches for \"the database decision\" (embedding model fake-embed; 2 notes, 2 passages indexed; index refreshed: 2 notes embedded"), out)
        assertTrue("1. Data/Postgres.md  (score 1.00)\n   § Storage\n   We chose a database for the orders." in out, out)
        assertTrue(out.indexOf("Data/Postgres.md") < out.indexOf("Food/Pasta.md"))
        assertTrue("read_note" in out)
        assertTrue(box.execute(call("semantic_search", "query" to "cooking", "limit" to "1")).lines().count { it.matches(Regex("\\d+\\. .*")) } == 1)
        assertTrue(box.execute(call("semantic_search", "query" to " ")).startsWith("Error: query is empty"))
        // a plan-mode (read-only) toolbox keeps it
        assertTrue(ToolBox(v, emptyList(), backups, readOnly = true, semantic = SemanticSearch(idx)).find("semantic_search") != null)
        // an embedding failure comes back as a tool error the model can read
        val broken = ToolBox(v, emptyList(), backups, semantic = SemanticSearch(index(v, Files.createTempDirectory("sem2").also { Files.writeString(it.resolve("n.md"), "# N\nx") }, Topics(failOnCall = 1))))
        assertTrue(broken.execute(call("semantic_search", "query" to "x")).startsWith("Error: model unavailable"))
    }

    @Test
    fun withoutAnEmbeddingModelTheToolIsStillOfferedAndSaysItIsNotSetUp() {
        val (v, _) = vault("a.md" to "# A\nx")
        val box = ToolBox(v, emptyList(), Files.createTempDirectory("bk"))
        assertTrue(box.find("semantic_search") != null && "semantic_search" in box.cheatSheet(), "offered, so the model can use it and the app sets the model up on the first call")
        val answer = box.execute(call("semantic_search", "query" to "x"))
        assertTrue(answer.startsWith("Error: semantic search is not set up"), answer)
        assertEquals("", AppConfig().embedModel, "no model is chosen by default")
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall(ToolFunction(name, JsonObject(args.associate { (k, v) -> k to (if (k == "limit") JsonPrimitive(v.toInt()) else JsonPrimitive(v)) })))

    @Test
    fun aModelDownloadStreamsItsProgressAndAnErrorLineThrows() {
        assertEquals(PullProgress("pulling manifest"), OllamaProtocol.parsePull("""{"status":"pulling manifest"}"""))
        val part = OllamaProtocol.parsePull("""{"status":"pulling 970aa74c0a90","digest":"sha256:970","total":274290560,"completed":137145280}""")
        assertEquals(50, part.percent)
        assertEquals(-1, OllamaProtocol.parsePull("""{"status":"verifying sha256 digest"}""").percent)
        assertTrue(OllamaProtocol.parsePull("""{"status":"success"}""").done)
        assertFailsWith<OllamaException> { OllamaProtocol.parsePull("""{"error":"pull model manifest: file does not exist"}""") }
        assertEquals("Downloading nomic-embed-text 50% (137 of 274 MB)", SemanticSetup.progressText("nomic-embed-text", part))
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        var asked = ""
        server.createContext("/api/pull") { e ->
            asked = e.requestBody.readBytes().decodeToString()
            val body = listOf("""{"status":"pulling manifest"}""", """{"status":"pulling aa","total":200,"completed":100}""", """{"status":"success"}""").joinToString("\n").toByteArray()
            e.sendResponseHeaders(200, body.size.toLong()); e.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = OllamaClient { AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}") }
            val seen = runBlocking { client.pull("nomic-embed-text").toList() }
            assertEquals(listOf("pulling manifest", "pulling aa", "success"), seen.map { it.status })
            assertTrue("\"model\":\"nomic-embed-text\"" in asked && "\"stream\":true" in asked, asked)
        } finally { server.stop(0) }
    }

    @Test
    fun theToolIsAlwaysOfferedAndSaysItIsNotSetUpWhileNoEmbeddingModelExists() {
        val (v, dir) = vault("a.md" to "x")
        val off = ToolBox(v, emptyList(), dir.resolveSibling("b1"))
        val spec = off.find("semantic_search")!!
        assertTrue("NOT SET UP YET" in spec.description && "asks the user once" in spec.description, spec.description)
        assertTrue(off.execute(ToolCall(ToolFunction("semantic_search", kotlinx.serialization.json.buildJsonObject { put("query", JsonPrimitive("databases")) }))).startsWith("Error: semantic search is not set up"))
        val on = ToolBox(v, emptyList(), dir.resolveSibling("b2"), semantic = SemanticSearch(index(v, dir, Topics()))).find("semantic_search")!!
        assertTrue("NOT SET UP" !in on.description)
        assertTrue(ToolBox(null, emptyList(), dir.resolveSibling("b3")).find("semantic_search") == null, "no vault, no tool")
    }
}
