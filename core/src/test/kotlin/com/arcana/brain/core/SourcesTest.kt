package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Folders as a source of passages: which files are searched, how code is cut, and the same keywords + meaning search over a project. */
class SourcesTest {
    private fun project(vararg files: Pair<String, String>): Path {
        val dir = Files.createTempDirectory("proj")
        files.forEach { (name, text) -> dir.resolve(name).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) } }
        return dir
    }

    private val kotlin = """
        package demo

        /** Reads the config. */
        class ConfigReader(private val path: String) {
            fun readConfig(): Map<String, String> {
                val lines = java.io.File(path).readLines()
                return lines.associate { it.substringBefore('=') to it.substringAfter('=') }
            }
        }

        fun unrelated() {
            println("nothing to see")
        }
    """.trimIndent()

    @Test
    fun theFolderSourceTakesTextFilesAndSkipsHiddenBuildBigAndBinary() {
        val dir = project(
            "src/Main.kt" to "fun main() {}", "README.md" to "# Demo", "Makefile" to "all:\n\techo hi",
            ".git/config" to "hidden", "node_modules/lib/index.js" to "x", "build/out.kt" to "fun built() {}", "src/Big.kt" to "x".repeat(400),
            "image.png" to "binary", "package-lock.json" to "{}",
        )
        Files.write(dir.resolve("src/Bin.kt"), byteArrayOf(65, 0, 66, 0))
        val source = FolderSource(dir, maxFileBytes = 300)
        val names = source.files().map { it.path }.toSet()
        assertEquals(setOf("src/Main.kt", "README.md", "Makefile", "src/Bin.kt"), names, "code, docs and known names; not hidden, build, dependency, too big, lock or other types")
        assertNull(source.read("src/Bin.kt"), "a file with a zero byte is not text")
        assertEquals("fun main() {}", source.read("src/Main.kt"))
    }

    @Test
    fun aHugeFolderIsLimitedToTheNewestFilesAndSaysSo() {
        val dir = project(*(1..12).map { "f$it.kt" to "fun f$it() {}" }.toTypedArray())
        val source = FolderSource(dir, maxFiles = 5)
        assertEquals(5, source.files().size)
        assertTrue(source.truncated)
        assertTrue(!FolderSource(dir).also { it.files() }.truncated)
    }

    @Test
    fun codeIsCutAtDeclarationsAndEveryPassageKnowsItsLineAndItsDeclaration() {
        val chunks = CodeChunker.chunks("ConfigReader.kt", kotlin, target = 200)
        assertTrue(chunks.size >= 2, chunks.map { it.heading }.toString())
        assertTrue(chunks.all { it.line >= 1 && it.text.isNotBlank() }, chunks.map { it.line to it.heading }.toString())
        val read = chunks.first { "readLines" in it.text }
        assertTrue("fun readConfig" in read.heading || "class ConfigReader" in read.heading, "heading of the passage with readLines: [${read.heading}] all: ${chunks.map { it.line to it.heading }}")
        // the line of a passage is the line of the file where its text begins
        chunks.forEach { c -> assertEquals(c.text.lines().first().trim(), kotlin.lines()[c.line - 1].trim(), "passage at line ${c.line}") }
        val last = chunks.last()
        assertTrue("unrelated" in last.text && "fun unrelated" in last.heading, "last: ${last.line} [${last.heading}] ${last.text.take(40)}")
        assertEquals("ConfigReader.kt", CodeChunker.chunks("ConfigReader.kt", kotlin).first().path, "path")
    }

    @Test
    fun markdownInAProjectIsCutByHeadingsLikeANote() {
        val dir = project("docs/Guide.md" to "# Guide\n\n## Setup\n\nInstall the thing.\n\n## Run\n\nRun it.\n")
        val chunks = FolderSource(dir).let { it.chunks("docs/Guide.md", it.read("docs/Guide.md")!!) }
        assertEquals(listOf("Guide › Setup", "Guide › Run"), chunks.map { it.heading })
    }

    /** A fake embedding model: one axis per topic word, so "reads settings" and "readConfig" point the same way. */
    private class Topics : Embedder {
        private val topics = listOf(listOf("readconfig", "configreader", "settings", "readlines"), listOf("unrelated", "println", "nothing"))
        override fun embed(inputs: List<String>) = inputs.map { t -> t.lowercase().let { l -> FloatArray(topics.size) { i -> topics[i].count { it in l }.toFloat() } } }
    }

    @Test
    fun aMeaningIndexOverAFolderKeepsTheLineOfEachPassage() {
        val dir = project("src/ConfigReader.kt" to kotlin)
        val source = FolderSource(dir)
        val index = EmbeddingIndex(Files.createTempDirectory("idx").resolve("i.bin"), source, "fake", Topics()).also { it.refresh() }
        val hit = index.searchPassages("how are settings loaded", 3).first().first
        assertTrue(hit.line >= 1 && "readLines" in hit.text, hit.toString())
        // the index file keeps the lines: a second index on the same file reads them back
        val file = Files.createTempDirectory("idx3").resolve("i.bin")
        EmbeddingIndex(file, source, "fake", Topics()).refresh()
        val again = EmbeddingIndex(file, source, "fake", Topics())
        assertTrue(!again.isEmpty && again.searchPassages("how are settings loaded", 3).first().first.line == hit.line, "the line survives a restart")
    }

    @Test
    fun theProjectSearchFindsAnIdentifierByWordsAndAParaphraseByMeaningWithPathAndLine() {
        val dir = project("src/ConfigReader.kt" to kotlin, "src/Other.kt" to "fun other() { println(\"hello\") }\n")
        val idx = Files.createTempDirectory("pidx")
        val search = ProjectSearch(listOf(dir), { s -> EmbeddingIndex(idx.resolve("i.bin"), s, "fake", Topics()) }, home = "/nowhere")
        val words = search.answer("readConfig", 3, RetrievalMode.Keywords)
        assertTrue("ConfigReader.kt:" in words && "by keywords" in words && "readConfig" in words, words)
        val both = search.answer("how are settings loaded from the file", 3, RetrievalMode.Both)
        assertTrue("ConfigReader.kt:" in both && "keywords + meaning" in both || "by meaning" in both, both)
        assertTrue("start_line" in both, "it tells the model how to read the hit")
    }

    @Test
    fun withoutAnEmbeddingModelTheProjectSearchUsesWordsAndSaysSo() {
        val dir = project("src/ConfigReader.kt" to kotlin)
        val r = ProjectSearch(listOf(dir), { null }).search("readConfig", 3, RetrievalMode.Both)
        assertEquals(RetrievalMode.Keywords, r.used)
        assertTrue("meaning search is off" in r.note, r.note)
        assertTrue(r.found.first().path.endsWith("ConfigReader.kt") && r.found.first().line > 0)
        assertEquals("No project folder to search: set the Working folder (Settings → Tools) or add folders in Settings → Search → Search in.", ProjectSearch(emptyList(), { null }).answer("x", 3, RetrievalMode.Both))
    }

    @Test
    fun theToolIsOfferedOnlyWithAProjectAndAnswersLikeTheSearch() {
        val dir = project("src/ConfigReader.kt" to kotlin)
        val vaultDir = Files.createTempDirectory("v")
        val none = ToolBox(Vault(vaultDir), emptyList(), vaultDir.resolveSibling("b1"))
        assertTrue(none.find("search_files") == null)
        val box = ToolBox(Vault(vaultDir), emptyList(), vaultDir.resolveSibling("b2"), project = dir, projectSearch = ProjectSearch(listOf(dir), { null }))
        assertTrue(box.find("search_files") != null && "search_files" in box.cheatSheet())
        val call = ToolCall(ToolFunction("search_files", kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("readConfig")) }))
        assertTrue("ConfigReader.kt:" in box.execute(call), box.execute(call))
    }

    private fun pdf(file: Path, vararg pages: String) {
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            pages.forEach { text ->
                val page = org.apache.pdfbox.pdmodel.PDPage()
                doc.addPage(page)
                org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { c ->
                    c.beginText(); c.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f); c.newLineAtOffset(50f, 700f); c.showText(text); c.endText()
                }
            }
            doc.save(file.toFile())
        }
    }

    private fun docx(file: Path, vararg paragraphs: String) {
        java.util.zip.ZipOutputStream(Files.newOutputStream(file)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            z.write(("<w:document><w:body>" + paragraphs.joinToString("") { "<w:p><w:r><w:t>${it.replace("&", "&amp;")}</w:t></w:r></w:p>" } + "</w:body></w:document>").toByteArray())
            z.closeEntry()
        }
    }

    @Test
    fun documentsAreReadAsTextPdfPageByPageWordAndHtmlToo() {
        val dir = project("page.html" to "<html><head><style>p{color:red}</style><script>var x=1;</script></head><body><h1>Title</h1><p>The invoice total is 500 &amp; paid.</p></body></html>")
        pdf(dir.resolve("report.pdf"), "First page about budgets.", "Second page about the quarterly forecast.")
        docx(dir.resolve("letter.docx"), "Dear customer,", "Your contract renews in March.")
        val html = Documents.read(dir.resolve("page.html"))!!
        assertTrue("The invoice total is 500 & paid." in html && "color:red" !in html && "var x" !in html, html)
        val pages = Documents.read(dir.resolve("report.pdf"))!!.split(Documents.PAGE_BREAK).filter { it.isNotBlank() }
        assertEquals(2, pages.size)
        assertTrue("budgets" in pages[0] && "forecast" in pages[1], pages.toString())
        val word = Documents.read(dir.resolve("letter.docx"))!!
        assertTrue("Dear customer," in word && "contract renews in March" in word && "\n\n" in word, word)
        assertNull(Documents.read(dir.resolve("missing.pdf")), "a missing or damaged file gives nothing, not an error")
    }

    @Test
    fun aPdfIsCutPerPageAndEveryPassageKnowsItsPage() {
        val dir = Files.createTempDirectory("docs")
        pdf(dir.resolve("report.pdf"), "First page about budgets.", "Second page about the quarterly forecast.")
        val source = FolderSource(dir)
        assertEquals(listOf("report.pdf"), source.files().map { it.path })
        val chunks = source.chunks("report.pdf", source.read("report.pdf")!!)
        assertEquals(listOf("page 1" to 1, "page 2" to 2), chunks.map { it.heading to it.line })
    }

    @Test
    fun theFileSearchFindsADocumentByMeaningAndGivesThePage() {
        val dir = Files.createTempDirectory("docs2")
        pdf(dir.resolve("report.pdf"), "First page about budgets.", "Second page about the quarterly forecast.")
        docx(dir.resolve("letter.docx"), "Your contract renews in March.")
        val words = ProjectSearch(listOf(dir), { null }, home = "/nowhere").answer("quarterly forecast", 3, RetrievalMode.Keywords)
        assertTrue("report.pdf p. 2" in words, words)
        val word = ProjectSearch(listOf(dir), { null }, home = "/nowhere").answer("contract renews", 3, RetrievalMode.Keywords)
        assertTrue("letter.docx" in word, word)
    }

    @Test
    fun aFolderThatContainsTheVaultSearchesItsDocumentsButNotItsNotes() {
        val parent = Files.createTempDirectory("home")
        val vault = Files.createDirectories(parent.resolve("Vault"))
        Files.writeString(vault.resolve("Note.md"), "# Note\n\nA note.")
        pdf(vault.resolve("Attachment.pdf"), "An attached paper.")
        Files.writeString(parent.resolve("Readme.md"), "# Readme\n\nOutside the vault.")
        val names = FolderSource(parent, vaultRoot = vault).files().map { it.path }.toSet()
        assertEquals(setOf("Vault/Attachment.pdf", "Readme.md"), names, "the note is searched as a note; the PDF in the vault and the Markdown outside it are files")
    }
}
