package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keywords (BM25), meaning (a fake embedder) and both fused (RRF), on a small crafted vault. */
class RetrievalTest {
    /** A fake embedding model by topic: "pause"/"unload"/"idle" all point to one direction, so a paraphrase matches without sharing words. */
    private class Topics : Embedder {
        private val topics = listOf(listOf("keep_alive", "unload", "idle", "pause", "minutes", "resident", "lull"), listOf("database", "sql", "postgres"), listOf("theme", "colour", "color"))
        override fun embed(inputs: List<String>): List<FloatArray> = inputs.map { t ->
            val l = t.lowercase()
            FloatArray(topics.size) { i -> topics[i].count { it in l }.toFloat() }
        }
    }

    private fun vault(vararg notes: Pair<String, String>): Pair<Vault, Path> {
        val dir = Files.createTempDirectory("retr")
        notes.forEach { (name, text) -> dir.resolve(name).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) } }
        return Vault(dir) to dir
    }

    private val notes = arrayOf(
        "Ollama Settings.md" to "# Ollama Settings\n\nThe `keep_alive` field decides how long a model stays loaded.\n",
        "Idle Models.md" to "# Idle Models\n\nAfter a pause of five minutes Ollama unloads the model from memory when it is idle.\n",
        "Database Choice.md" to "# Database Choice\n\nWe picked Postgres: SQL, transactions, one database for everything.\n",
        "Theme Notes.md" to "# Theme Notes\n\nThe colour theme is dark by default.\n",
        "Qwen Models.md" to "# Qwen Models\n\nqwen3.8:27b is a 27B model with thinking levels low, medium and xhigh.\n",
    )

    private fun hybrid(v: Vault, dir: Path, withMeaning: Boolean = true): HybridSearch {
        val index = if (withMeaning) EmbeddingIndex(dir.resolveSibling(dir.fileName.toString() + "-idx").resolve("index.bin"), v, "fake", Topics()).also { it.refresh() } else null
        return HybridSearch(LexicalIndex(v), index)
    }

    @Test
    fun termsKeepIdentifiersWholeAndInPartsAndDropNoise() {
        assertEquals(setOf("qwen3.8:27b", "qwen3", "8", "27b").filter { it.length > 1 }.toSet(), Terms.of("qwen3.8:27b").toSet())
        assertTrue(Terms.of("keep_alive").containsAll(listOf("keep_alive", "keep", "alive")))
        assertEquals(Terms.of("database"), Terms.of("Databases"), "a plural is the same term")
        assertTrue(Terms.of("the and how a").isEmpty(), "common and one-letter words are not terms")
    }

    @Test
    fun keywordsFindAnExactIdentifierThatMeaningMisses() {
        val (v, dir) = vault(*notes)
        val h = hybrid(v, dir)
        val words = h.search("qwen3.8:27b", 3, RetrievalMode.Keywords)
        assertEquals("Qwen Models.md", words.found.first().path)
        assertEquals("keywords", words.found.first().via)
        val meaning = h.search("qwen3.8:27b", 3, RetrievalMode.Meaning)
        assertTrue(meaning.found.all { (it.similarity ?: 0.0) <= 0.0 }, "the fake embedder knows no such topic: no note is closer in meaning than another (every similarity is 0), so meaning alone cannot single out the identifier")
    }

    @Test
    fun meaningFindsAParaphraseThatSharesNoWordWithTheQuestion() {
        val (v, dir) = vault(*notes)
        val h = hybrid(v, dir)
        // "resident" and "lull" are in no note: only the embedder knows they mean the same as "idle" and "unload"
        val question = "how long does it stay resident during a lull"
        val words = h.search(question, 3, RetrievalMode.Keywords)
        assertTrue(words.found.none { it.path == "Idle Models.md" }, "the words do not match the note: ${words.found.map { it.path }}")
        val meaning = h.search(question, 3, RetrievalMode.Meaning)
        assertEquals("Idle Models.md", meaning.found.first().path)
        assertTrue("Idle Models.md" in h.search(question, 3, RetrievalMode.Both).found.map { it.path }, "both keeps what meaning finds")
    }

    @Test
    fun bothFindsWhatEachFindsAloneAndSaysHowEachNoteWasFound() {
        val (v, dir) = vault(*notes)
        val h = hybrid(v, dir)
        val r = h.search("keep_alive idle pause", 5, RetrievalMode.Both)
        val byPath = r.found.associateBy { it.path }
        assertEquals(RetrievalMode.Both, r.used)
        assertEquals("keywords + meaning", byPath["Ollama Settings.md"]?.via, "found by the word keep_alive and by its topic")
        assertTrue("Idle Models.md" in byPath, "found by meaning (idle, pause)")
        assertTrue(r.found.first().score >= r.found.last().score && r.found.all { it.score in 0.0..1.0 })
        assertEquals("keywords + meaning", r.label)
    }

    @Test
    fun withoutAnEmbeddingModelBothFallsBackToKeywordsAndSaysSo() {
        val (v, dir) = vault(*notes)
        val h = hybrid(v, dir, withMeaning = false)
        val r = h.search("postgres database", 3, RetrievalMode.Both)
        assertEquals(RetrievalMode.Keywords, r.used)
        assertEquals(RetrievalMode.Both, r.asked)
        assertTrue("meaning search is off" in r.note, r.note)
        assertEquals("Database Choice.md", r.found.first().path)
        val text = h.answer("postgres database", 3, RetrievalMode.Both)
        assertTrue("by keywords" in text && "asked: both" in text && "meaning search is off" in text, text)
        assertTrue(h.search("x", 3, RetrievalMode.Meaning).found.isEmpty(), "meaning only, no model: nothing, with the reason")
    }

    @Test
    fun theKeywordIndexSeesChangesWithoutAnyRebuildStep() {
        val (v, dir) = vault(*notes)
        val lex = LexicalIndex(v)
        assertTrue(lex.search("zebra", 3).isEmpty())
        Files.writeString(dir.resolve("Zoo.md"), "# Zoo\n\nA zebra lives here.\n")
        assertEquals("Zoo.md", lex.search("zebra", 3).first().first.path)
        Files.delete(dir.resolve("Zoo.md"))
        assertTrue(lex.search("zebra", 3).isEmpty(), "a deleted note is gone")
    }

    @Test
    fun hitsForAVaultAnswerCarryTheMatchingPassagesNotTheStartOfTheNote() {
        val long = "# Long\n\n" + "Filler sentence about nothing at all. ".repeat(60) + "\n\n## Deep part\n\nThe secret word is marzipan and it matters.\n"
        val (v, dir) = vault("Long.md" to long, "Other.md" to "# Other\n\nunrelated\n")
        val (hits, r) = hybrid(v, dir, withMeaning = false).hits(v, "marzipan", 3, 400, RetrievalMode.Both)
        assertEquals("Long.md", hits.single().note.path)
        assertTrue("marzipan" in hits.single().excerpt, "the excerpt is the matching passage: ${hits.single().excerpt.take(80)}")
        assertEquals("keywords", r.label)
    }

    @Test
    fun everyHitCarriesTheEvidenceBehindItNotOnlyARelativeScore() {
        val (v, dir) = vault(*notes)
        val h = hybrid(v, dir)
        val f = h.search("keep_alive idle pause", 5, RetrievalMode.Both).found.associateBy { it.path }["Ollama Settings.md"]!!
        assertTrue(f.wordsTotal >= 3 && f.wordsMatched >= 1, "the note has the word keep_alive: ${f.evidence}")
        assertTrue(f.similarity != null && f.similarity!! > 0.0, "and its vector is close: ${f.evidence}")
        assertTrue("words " in f.evidence && "similarity " in f.evidence)
        val wordsOnly = h.search("postgres", 3, RetrievalMode.Keywords).found.first()
        assertEquals(null, wordsOnly.similarity, "meaning did not take part: no similarity")
        assertTrue("words 1/1" == wordsOnly.evidence, wordsOnly.evidence)
        assertTrue("(keywords + meaning" in h.answer("keep_alive idle pause", 5, RetrievalMode.Both) && "words " in h.answer("keep_alive idle pause", 5, RetrievalMode.Both))
    }

    @Test
    fun theModeIsReadFromAWordAndDefaultsToBoth() {
        assertEquals(RetrievalMode.Keywords, RetrievalMode.of("Keywords"))
        assertEquals(RetrievalMode.Meaning, RetrievalMode.of(" meaning "))
        assertEquals(RetrievalMode.Both, RetrievalMode.of("nonsense"))
        assertEquals(RetrievalMode.Both, RetrievalMode.of(null))
    }

    @Test
    fun theToolSearchesByBothAndIsOfferedWithAVault() {
        val (v, dir) = vault(*notes)
        val box = ToolBox(v, emptyList(), dir.resolveSibling("bk"))
        assertTrue(box.find("search_vault") != null && "search_vault" in box.cheatSheet())
        val call = ToolCall(ToolFunction("search_vault", kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("qwen3.8:27b")) }))
        val out = box.execute(call)
        assertTrue("Qwen Models.md" in out && "by keywords" in out, out)
        assertTrue(ToolBox(null, emptyList(), dir.resolveSibling("bk2")).find("search_vault") == null, "no vault, no tool")
    }

    private val withNoise = notes + arrayOf("Pomodoro.md" to "# Pomodoro\n\nTake a pause between pomodoros. The colour theme of the timer: colour, color, theme, theme, colour.\n")

    @Test
    fun theNoiseFilterLeavesOutAPassageThatSharesAWordButMeansSomethingElse() {
        val (v, dir) = vault(*withNoise)
        val query = "keep_alive idle pause"
        val filtered = hybrid(v, dir).search(query, 10, RetrievalMode.Both)
        assertTrue(filtered.found.none { it.path == "Pomodoro.md" }, "one shared word (pause) and a far vector: ${filtered.found.map { it.path to it.evidence }}")
        assertTrue(filtered.leftOut >= 1)
        assertTrue("Ollama Settings.md" in filtered.found.map { it.path } && "Idle Models.md" in filtered.found.map { it.path })
        // with the filter off, the same note comes back
        val index = EmbeddingIndex(dir.resolveSibling(dir.fileName.toString() + "-idx2").resolve("index.bin"), v, "fake", Topics()).also { it.refresh() }
        val off = HybridSearch(LexicalIndex(v), index, SearchTuning(minWords = 0.0, gap = 0.0)).search(query, 10, RetrievalMode.Both)
        assertTrue("Pomodoro.md" in off.found.map { it.path } && off.leftOut == 0, off.found.map { it.path }.toString())
        // the tool says how many it left out
        assertTrue("left out by the noise filter" in hybrid(v, dir).answer(query, 10, RetrievalMode.Both))
    }

    @Test
    fun theBestHitAlwaysStaysEvenWhenEveryFilterWouldDropIt() {
        val (v, dir) = vault(*withNoise)
        val index = EmbeddingIndex(dir.resolveSibling(dir.fileName.toString() + "-idx3").resolve("index.bin"), v, "fake", Topics()).also { it.refresh() }
        val strict = HybridSearch(LexicalIndex(v), index, SearchTuning(minWords = 1.0, gap = 0.001)).search("pause", 10, RetrievalMode.Both)
        assertTrue(strict.found.isNotEmpty(), "never an empty answer because of the filter")
    }

    @Test
    fun theSettingsBecomeWeightsFiltersAndLimitsWithinSaneBounds() {
        assertEquals(SearchTuning(wordsWeight = 1.0, meaningWeight = 1.0, gap = 0.08, minWords = 0.35, pool = 40, perNote = 4), SearchTuning.of(AppConfig()), "the defaults")
        val words = SearchTuning.of(AppConfig(searchBalance = 0))
        assertEquals(2.0 to 0.0, words.wordsWeight to words.meaningWeight, "balance 0: only the words count")
        val meaning = SearchTuning.of(AppConfig(searchBalance = 10))
        assertEquals(0.0 to 2.0, meaning.wordsWeight to meaning.meaningWeight)
        val wild = SearchTuning.of(AppConfig(searchBalance = 99, searchGap = 999, searchMinWords = 500, searchPool = 1, searchPassages = 99))
        assertTrue(wild.meaningWeight == 2.0 && wild.gap == 0.5 && wild.minWords == 1.0 && wild.pool == 10 && wild.perNote == 6, wild.toString())
    }

    @Test
    fun passagesPerNoteIsHowManyPassagesOfOneNoteAreKept() {
        val long = "# Long\n\n## One\n\nfirst marzipan passage\n\n## Two\n\nsecond marzipan passage\n\n## Three\n\nthird marzipan passage\n"
        val (v, dir) = vault("Long.md" to long)
        val one = HybridSearch(LexicalIndex(v), null, SearchTuning(perNote = 1)).search("marzipan", 3, RetrievalMode.Keywords).found.single()
        val three = HybridSearch(LexicalIndex(v), null, SearchTuning(perNote = 3)).search("marzipan", 3, RetrievalMode.Keywords).found.single()
        assertEquals(1, one.passages.size)
        assertEquals(3, three.passages.size)
    }
}
