package com.arcana.brain.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension

/** One file of a [PassageSource]: its path relative to the source's root, its size and its modification time (what tells a changed file). */
data class SourceFile(val path: String, val size: Long, val mtime: Long)

/**
 * Where the searched text comes from (proposals/0001-retrieval-and-rag.md): the notes of the vault, or the files of a folder (a project). The keyword index ([LexicalIndex]) and the
 * meaning index ([EmbeddingIndex]) both read a source through this, so every source gets both kinds of search and the same hybrid ranking.
 */
interface PassageSource {
    /** Identifies the source (its root), so an index is kept apart per source. */
    val key: String
    val root: Path

    /** The files that can be searched now, with their size and time. */
    fun files(): List<SourceFile>

    /** The text of [path] (relative to [root]); null when it cannot be read as text. */
    fun read(path: String): String?

    /** The passages of [text], with a line for each in a code file. */
    fun chunks(path: String, text: String): List<Chunk>
}

/** The notes of a vault: Markdown, split by headings ([Chunker]). */
class VaultSource(val vault: Vault) : PassageSource {
    override val key: String get() = vault.root.toString()
    override val root: Path get() = vault.root
    override fun files(): List<SourceFile> = vault.notes().map { n -> SourceFile(n.path, runCatching { Files.size(vault.root.resolve(n.path)) }.getOrDefault(-1L), n.modified) }
    override fun read(path: String): String? = runCatching { vault.read(path) }.getOrNull()
    override fun chunks(path: String, text: String): List<Chunk> = Chunker.chunks(path, text)
}

/**
 * The files of a folder, for example a project or a documents folder: code, configuration and text, and documents (PDF, Word, HTML, see [Documents]), found by their extension. Hidden folders, build output and dependency folders are not entered, a
 * file bigger than [maxFileBytes] or that is not text is left out, and at most [maxFiles] files are taken (the newest first, so a huge folder is limited, never walked for ever).
 * [truncated] says whether the limit cut the list.
 */
class FolderSource(
    override val root: Path, private val maxFileBytes: Long = DEFAULT_MAX_BYTES, private val maxFiles: Int = MAX_FILES,
    /** The vault, when the folder contains it: its Markdown notes are left out here (they are searched as notes), its PDFs, documents and code are not. */
    private val vaultRoot: Path? = null,
) : PassageSource {
    @Volatile var truncated: Boolean = false
        private set

    override val key: String get() = "$root#$maxFileBytes" // another size limit is another set of files, so another index

    override fun files(): List<SourceFile> {
        val found = ArrayList<SourceFile>()
        var total = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString().orEmpty()
                return if (dir != root && (name.startsWith(".") || name in SKIP_DIRS)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val document = Documents.isDocument(file)
                val limit = if (document) Documents.MAX_BYTES else maxFileBytes
                if (attrs.isRegularFile && wanted(file) && attrs.size() in 1..limit && !isVaultNote(file)) { total++; found += SourceFile(root.relativize(file).toString().replace('\\', '/'), attrs.size(), attrs.lastModifiedTime().toMillis()) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        truncated = found.size > maxFiles
        return if (truncated) found.sortedByDescending { it.mtime }.take(maxFiles) else found
    }

    private fun isVaultNote(file: Path): Boolean = vaultRoot != null && file.startsWith(vaultRoot) && file.extension.lowercase() in setOf("md", "mdx")

    override fun read(path: String): String? = runCatching {
        if (Documents.isDocument(root.resolve(path))) return@runCatching Documents.read(root.resolve(path))
        val bytes = Files.readAllBytes(root.resolve(path))
        if (bytes.take(2000).any { it.toInt() == 0 }) null else String(bytes, Charsets.UTF_8)
    }.getOrNull()

    override fun chunks(path: String, text: String): List<Chunk> = when (path.substringAfterLast('.').lowercase()) {
        "md", "mdx" -> Chunker.chunks(path, text)
        // a PDF page by page: the passages of page N have the heading `page N` and line N (shown as `p. N`)
        "pdf" -> text.split(Documents.PAGE_BREAK).withIndex().flatMap { (i, page) -> Chunker.chunks(path, page).map { it.copy(heading = "page ${i + 1}", line = i + 1) } }
        "docx", "html", "htm" -> Chunker.chunks(path, text)
        else -> CodeChunker.chunks(path, text)
    }

    private fun wanted(file: Path): Boolean {
        val name = file.fileName.toString()
        if (name in SKIP_FILES || name.endsWith(".min.js") || name.endsWith(".min.css") || name.endsWith(".lock")) return false
        return file.extension.lowercase() in EXTENSIONS || name in NAMES || Documents.isDocument(file)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 300_000L
        const val MAX_FILES = 5000
        private val SKIP_DIRS = setOf("node_modules", "build", "out", "dist", "target", "venv", "__pycache__", "Pods", "vendor", "bin", "obj", "coverage", "DerivedData")
        private val SKIP_FILES = setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "gradlew", "gradlew.bat")
        private val NAMES = setOf("Makefile", "Dockerfile", "Jenkinsfile", "Gemfile", "Rakefile", "Procfile")
        private val EXTENSIONS = setOf(
            "kt", "kts", "java", "scala", "groovy", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs", "swift", "m", "mm", "rb", "php", "sh", "bash", "zsh", "sql",
            "md", "mdx", "txt", "rst", "adoc", "json", "yaml", "yml", "toml", "xml", "html", "css", "scss", "gradle", "properties", "conf", "ini", "cfg", "csv",
        )
    }
}

/** Splitting a code (or other plain text) file into passages that keep their place: each carries the line it starts at and the declaration it sits in. */
object CodeChunker {
    /** About how many characters a passage has. */
    const val TARGET = 800

    private val declaration = Regex(
        """^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|private|protected|internal|open|abstract|override|suspend|inline|static|final|data|sealed|enum|export|default|async|pub|const|tailrec|operator|infix|external|actual|expect|lateinit)\s+)*(?:fun|class|interface|object|def|function|func|fn|struct|impl|trait|enum|type|module|namespace|record|protocol|extension)\b.*""",
    )

    /**
     * The passages of [text]: lines are packed up to [target] characters, a new passage starts at a declaration or a blank line once the current one is half full (so a function tends to stay
     * whole), and a very long run of lines is cut at [target]. [Chunk.heading] is the nearest declaration at or above where the passage starts (`fun search(query: String…`; the first one inside it when none is above), [Chunk.line] its first line.
     */
    fun chunks(path: String, text: String, target: Int = TARGET): List<Chunk> {
        val out = ArrayList<Chunk>()
        val buf = StringBuilder()
        var heading = ""
        var firstLine = 1
        var lastDeclaration = ""
        fun flush() { if (buf.isNotBlank()) out += Chunk(path, heading, buf.toString().trimEnd(), firstLine); buf.clear() }
        text.lines().forEachIndexed { i, line ->
            val isDeclaration = declaration.matches(line)
            // a passage ends at a declaration once it has some substance (a function stays whole), at a blank line once it is nearly full, and at the target at the latest
            if (buf.isNotEmpty() && (buf.length >= target || (isDeclaration && buf.length >= target / 6) || (line.isBlank() && buf.length >= target * 3 / 4))) flush()
            if (isDeclaration) lastDeclaration = line.trim().take(90)
            // a passage that began before any declaration is named by the first one inside it
            if (isDeclaration && buf.isNotEmpty() && heading.isEmpty()) heading = lastDeclaration
            if (buf.isEmpty()) {
                if (line.isBlank()) return@forEachIndexed // a passage does not start with an empty line
                heading = lastDeclaration
                firstLine = i + 1
            }
            buf.append(line.take(400)).append('\n')
        }
        flush()
        return out
    }
}


/**
 * Search of the user's PROJECT files, the same keywords + meaning ranking as the vault's ([HybridSearch]), over one or more folders (the working folder and the search folders of the
 * settings). Each folder is a [FolderSource] with its own keyword index (in memory, always current) and its own meaning index ([semanticFor]).
 *
 * Embedding a big folder takes minutes, so the first search never starts it: when a folder's meaning index is empty and the folder has more than [FIRST_INDEX_LIMIT] passages, only the words
 * are used and the answer says to run `/reindex` once; a small folder is embedded by the first search, and a folder already indexed is only brought up to date (changed files).
 */
class ProjectSearch(
    private val roots: List<Path>,
    private val semanticFor: (PassageSource) -> EmbeddingIndex?,
    private val tuning: SearchTuning = SearchTuning(),
    private val maxFileBytes: Long = FolderSource.DEFAULT_MAX_BYTES,
    private val home: String = System.getProperty("user.home").orEmpty(),
    /** The vault: its notes are left out of the folders that contain it (they are searched with search_vault). */
    private val vaultRoot: Path? = null,
) {
    /** The path as the model should write it to read the file: absolute, with `~` for the home folder. */
    private fun shown(path: Path): String = path.toString().let { if (home.isNotEmpty() && it.startsWith("$home/")) "~" + it.removePrefix(home) else it }

    /** One search over every folder: the best passages, best first, with the notes for the reader ([Retrieved.note]). */
    fun search(query: String, limit: Int, mode: RetrievalMode): Retrieved {
        val notes = mutableListOf<String>()
        val all = ArrayList<Found>()
        var usedMeaning = false
        var usedWords = false
        var leftOut = 0
        for (root in roots) {
            val source = FolderSource(root, maxFileBytes, vaultRoot = vaultRoot)
            val lexical = LexicalIndex.forSource(source)
            val sem = if (mode == RetrievalMode.Keywords) null else semanticFor(source)
            val passages = lexical.passages().size
            if (source.truncated) notes += "${root.fileName}: only the newest ${FolderSource.MAX_FILES} files are searched (narrow the folder)"
            val allowMeaning = sem != null && (!sem.isEmpty || passages <= FIRST_INDEX_LIMIT)
            if (sem != null && !allowMeaning) notes += "${root.fileName}: $passages passages are not embedded yet (minutes of work): words only; run /reindex once for meaning"
            val r = HybridSearch(lexical, if (allowMeaning) sem else null, tuning).search(query, limit, mode, refresh = allowMeaning)
            usedMeaning = usedMeaning || r.used != RetrievalMode.Keywords
            usedWords = usedWords || r.used != RetrievalMode.Meaning
            leftOut += r.leftOut
            // when the meaning was left out because the folder is big (said above), "no embedding model" would be a wrong reason
            if (r.note.isNotEmpty() && !(sem != null && !allowMeaning)) notes += "${root.fileName}: ${r.note}"
            r.found.forEach { f -> all += f.copy(path = shown(root) + "/" + f.path) }
        }
        val used = if (usedMeaning && usedWords) RetrievalMode.Both else if (usedMeaning) RetrievalMode.Meaning else RetrievalMode.Keywords
        return Retrieved(all.sortedByDescending { it.score }.take(limit.coerceIn(1, HybridSearch.MAX_LIMIT)), used, mode, notes.distinct().joinToString("; "), leftOut)
    }

    /** The tool's answer: the best passages with `path:line`, how each was found and the evidence, or why there is nothing. */
    fun answer(query: String, limit: Int, mode: RetrievalMode): String {
        if (query.isBlank()) return "Error: query is empty. Say what you are looking for."
        if (roots.isEmpty()) return "No project folder to search: set the Working folder (Settings → Tools) or add folders in Settings → Search → Search in."
        val r = search(query, limit, mode)
        val where = roots.joinToString(", ") { shown(it) }
        if (r.found.isEmpty()) return "Nothing found by ${r.label} for \"${query.trim()}\" in $where." + (if (r.note.isEmpty()) "" else " (${r.note})") + " Try other words, or grep_files for an exact text."
        val head = "Matches for \"${query.trim()}\" in project files ($where) by ${r.label}" + (if (r.note.isNotEmpty()) " (${r.note})" else "") + ". Read one with read_file (start_line near the line given; a PDF page: read the file and look for the page):\n\n"
        return head + (if (r.leftOut > 0) "(${r.leftOut} weak match${if (r.leftOut == 1) "" else "es"} left out by the noise filter)\n\n" else "") + r.found.withIndex().joinToString("\n\n") { (i, f) ->
            "${i + 1}. ${f.path}${locator(f)}  (${f.via}${if (f.evidence.isEmpty()) "" else " · " + f.evidence})" + (if (f.heading.isEmpty()) "" else "\n   § ${f.heading}") + "\n   ${f.excerpt}"
        }
    }

    /** Where in the file: `:123` (a line, for code) or ` p. 12` (a page, for a PDF); nothing for a file with no position. */
    private fun locator(f: Found): String = if (f.line <= 0) "" else if (f.path.endsWith(".pdf", ignoreCase = true)) " p. ${f.line}" else ":${f.line}"

    companion object {
        /** A folder with more passages than this is not embedded by a search (about 13 passages a second with nomic-embed-text: 1,500 is some 2 minutes at most, and a 3,800-passage project took 49 s): `/reindex` does it, with progress. */
        const val FIRST_INDEX_LIMIT = 1500
    }
}
