package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * The text of documents that are not plain text, for the file search: PDF (its text layer, page by page), Word (`.docx`) and HTML. A scanned PDF with no text layer gives nothing (no OCR).
 * Reading is best effort: a damaged, encrypted or odd file gives null and is left out.
 */
object Documents {
    val EXTENSIONS = setOf("pdf", "docx", "html", "htm")

    /** The biggest document read (a book is a few MB; a bigger file is mostly pictures). */
    const val MAX_BYTES = 30_000_000L

    /** The most text taken from one document, so a huge one cannot flood the index (about 500 passages). */
    const val MAX_CHARS = 400_000

    /** Between the pages of a PDF's text. */
    const val PAGE_BREAK = '\u000C'

    fun isDocument(file: Path): Boolean = file.fileName.toString().substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** The text of [file] (PDF pages separated by [PAGE_BREAK]), or null when it cannot be read or has no text. */
    fun read(file: Path): String? = runCatching {
        val text = when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "pdf" -> pdf(file)
            "docx" -> docx(file)
            "html", "htm" -> html(String(Files.readAllBytes(file), Charsets.UTF_8))
            else -> null
        }
        text?.take(MAX_CHARS)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun pdf(file: Path): String? = Loader.loadPDF(file.toFile()).use { doc ->
        if (doc.isEncrypted) return null
        val stripper = PDFTextStripper()
        val out = StringBuilder()
        for (page in 1..doc.numberOfPages) {
            stripper.startPage = page
            stripper.endPage = page
            out.append(stripper.getText(doc).trim()).append(PAGE_BREAK)
            if (out.length > MAX_CHARS) break
        }
        out.toString()
    }

    private fun docx(file: Path): String? = ZipFile(file.toFile()).use { zip ->
        val xml = zip.getEntry("word/document.xml")?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) } ?: return null
        decode(xml.replace("</w:p>", "\n\n").replace(Regex("<w:tab\\s*/>"), "\t").replace(Regex("<w:br\\s*/>"), "\n").replace(Regex("<[^>]+>"), ""))
    }

    private fun html(source: String): String =
        decode(source.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("(?i)</(p|div|h[1-6]|li|tr|br)\\s*>|<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), " ")).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n")

    private fun decode(text: String): String = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
}
