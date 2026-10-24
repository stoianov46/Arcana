package com.arcana.brain.core

import kotlinx.serialization.Serializable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path

/** A text file attached to a message: its name and what it says. The text goes to the model inside the message. */
@Serializable
data class FileAttachment(val name: String, val text: String, val truncated: Boolean = false)

/** The rules for text files (JSON, CSV, code, logs, Markdown…) sent with a message, and loading them. */
object TextFiles {
    val extensions = setOf(
        "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
        "log", "html", "htm", "css", "sql", "sh", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h",
        "cpp", "swift", "rb", "php", "gradle", "env",
    )
    const val MAX_COUNT = 5
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val MAX_PDF_BYTES = 20L * 1024 * 1024
    const val MAX_CHARS = 30_000

    fun isText(path: Path) = path.fileName.toString().substringAfterLast('.', "").lowercase() in extensions

    fun isPdf(path: Path) = path.fileName.toString().endsWith(".pdf", ignoreCase = true)

    /** What can be read as text: plain text files and PDFs. */
    fun isReadable(path: Path) = isText(path) || isPdf(path)

    /** Pictures and readable files can share one picker. */
    fun isAttachable(path: Path) = ImageFiles.isImage(path) || isReadable(path)

    /** Why [paths] cannot be attached as text, or null when they can. */
    fun validate(paths: List<Path>, already: Int = 0): String? {
        if (already + paths.size > MAX_COUNT) return "A message can carry at most $MAX_COUNT files (${already + paths.size} chosen)."
        for (path in paths) {
            val name = path.fileName.toString()
            if (!Files.isRegularFile(path)) return "$name is not a file."
            val limit = if (isPdf(path)) MAX_PDF_BYTES else MAX_FILE_BYTES
            if (Files.size(path) > limit) return "$name is larger than ${limit / (1024 * 1024)} MB."
        }
        return null
    }

    /** Reads [path] as UTF-8, cut to [MAX_CHARS]. Fails for a binary file. */
    fun load(path: Path): FileAttachment {
        val text = if (isPdf(path)) pdfText(path) else Files.readString(path)
        require('\u0000' !in text) { "${path.fileName} is not a text file." }
        return FileAttachment(path.fileName.toString(), text.take(MAX_CHARS), truncated = text.length > MAX_CHARS)
    }

    /** The text layer of a PDF, page by page. A scanned PDF with no text layer is refused. */
    private fun pdfText(path: Path): String {
        val name = path.fileName
        val text = try {
            Loader.loadPDF(path.toFile()).use { doc ->
                require(!doc.isEncrypted) { "$name is password-protected." }
                val stripper = PDFTextStripper()
                (1..doc.numberOfPages).joinToString("\n\n") { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    "[page $page]\n" + stripper.getText(doc).trim()
                }
            }
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("$name is not a readable PDF.")
        }
        require(text.replace(Regex("\\[page \\d+]"), "").isNotBlank()) { "$name has no text layer (a scan?). Attach its pages as pictures instead." }
        return text
    }

    /** The file as it is shown to the model, fenced so its structure survives. */
    fun block(file: FileAttachment): String {
        val lang = file.name.substringAfterLast('.', "").lowercase().let { if (it == "pdf") "" else it }
        val fence = if ("```" in file.text) "~~~~" else "```"
        val cut = if (file.truncated) "\n(cut after $MAX_CHARS characters)" else ""
        return "Attached file “${file.name}”:\n$fence$lang\n${file.text}\n$fence$cut"
    }
}
