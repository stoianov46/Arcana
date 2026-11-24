package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.arcana.brain.core.ToolBox.Companion.READ_LIMIT
import com.arcana.brain.core.ToolBox.Companion.LIST_LIMIT
import com.arcana.brain.core.ToolBox.Companion.FIND_LIMIT
import com.arcana.brain.core.ToolBox.Companion.GREP_LIMIT
import com.arcana.brain.core.ToolBox.Companion.GREP_LINE
import com.arcana.brain.core.ToolBox.Companion.RANGE_LINES
import com.arcana.brain.core.ToolBox.Companion.HIDDEN_SKIP
import com.arcana.brain.core.ToolBox.Companion.TREE_LIMIT
import com.arcana.brain.core.ToolBox.Companion.TREE_DEPTH
import com.arcana.brain.core.ToolBox.Companion.FIND_DEPTH
import com.arcana.brain.core.ToolBox.Companion.FIND_VISIT_LIMIT
import com.arcana.brain.core.ToolBox.Companion.FIND_SECONDS
import com.arcana.brain.core.ToolBox.Companion.FIND_SKIP
import com.arcana.brain.core.ToolBox.Companion.PREVIEW_LINES
import com.arcana.brain.core.ToolBox.Companion.PROJECT_FILES
import com.arcana.brain.core.ToolBox.Companion.PROJECT_NOTES_LIMIT
import com.arcana.brain.core.ToolBox.Companion.PROJECT_NOISE
import com.arcana.brain.core.ToolBox.Companion.MAX_DIFF_LINES
import com.arcana.brain.core.ToolBox.Companion.MAX_DIFF_BYTES
import com.arcana.brain.core.ToolBox.Companion.PROTECTED
import com.arcana.brain.core.ToolBox.Companion.FILE_TOOLS
import com.arcana.brain.core.ToolBox.Companion.schema
import com.arcana.brain.core.ToolBox.Companion.bool
import com.arcana.brain.core.ToolBox.Companion.str
import com.arcana.brain.core.ToolBox.Companion.int

// The tools on files in the allowed folders (list, find, grep, read, write, edit, delete, move) and their helpers: backups, the trash, the walk over folders. Split out of Tools.kt (proposal 0007, phase 3): extension functions of ToolBox, which offers them in `specs`.

/** [file] must exist: says so (and how paths are read) instead of a vague "not a file/folder". */
/**
 * Breadth-first, so shallow matches come first. Unreadable folders are skipped (a plain walk dies on the first one, e.g. in ~/Library),
 * as are hidden folders and bulky ones that never hold what people look for.
 */
internal fun ToolBox.findByName(start: Path, needle: String): List<String> {
    val found = ArrayList<String>()
    scan(start) { child, isDir ->
        if (child.fileName.toString().lowercase().contains(needle)) found += if (isDir) "$child/" else child.toString()
        found.size <= FIND_LIMIT
    }
    return found
}

/**
 * Visits what is below [start], breadth-first, calling [visit] with each entry (and whether it is a folder) until it returns false
 * or a limit is hit (entries visited, time). Bulky and secret folders are not entered, unreadable ones are skipped.
 */
internal fun ToolBox.scan(start: Path, visit: (Path, Boolean) -> Boolean) {
    val queue = ArrayDeque<Pair<Path, Int>>().apply { add(start to 0) }
    var visited = 0
    val deadline = System.nanoTime() + FIND_SECONDS * 1_000_000_000L
    while (queue.isNotEmpty() && visited < FIND_VISIT_LIMIT && System.nanoTime() < deadline) {
        val (dir, depth) = queue.removeFirst()
        val children = try { Files.newDirectoryStream(dir).use { it.toList() } } catch (e: Exception) { continue }
        for (child in children.sortedBy { it.fileName.toString().lowercase() }) {
            visited++
            val isDir = Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!visit(child, isDir)) return
            val name = child.fileName.toString()
            if (isDir && depth + 1 < FIND_DEPTH && name !in FIND_SKIP && name !in HIDDEN_SKIP) queue.add(child to depth + 1)
        }
    }
}

/** The folders a search tool looks in: the one named by [raw], or every allowed folder when it is empty. */
internal fun ToolBox.searchRoots(raw: String): List<Path> =
    if (raw.isBlank()) access.roots else listOf(access.resolve(raw, base).also { dir ->
        requireExists(raw, dir)
        require(Files.isDirectory(dir)) { "$raw is a file, not a folder." }
    })

// A glob (for example any .kt file anywhere, or Paywall in the name); without a slash it is matched against the file name only.
internal fun ToolBox.globMatcher(glob: String): (Path, Path) -> Boolean {
    val pattern = glob.trim()
    val matcher = try { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern") } catch (e: Exception) { throw IllegalArgumentException("\"$glob\" is not a valid glob. Examples: *.kt, **/*.java, *Paywall*.{kt,java}") }
    val byName = '/' !in pattern
    return { root, file -> if (byName) matcher.matches(file.fileName) else matcher.matches(root.relativize(file)) }
}

internal fun ToolBox.grep(start: Path, regex: Regex, glob: String, limit: Int): List<String> {
    val hits = ArrayList<String>()
    val accepts = if (glob.isBlank()) null else globMatcher(glob)
    scan(start) { file, isDir ->
        if (!isDir && Files.isRegularFile(file) && (accepts == null || accepts(start, file)) && !TextFiles.isPdf(file)) {
            val size = runCatching { Files.size(file) }.getOrDefault(Long.MAX_VALUE)
            if (size <= TextFiles.MAX_FILE_BYTES) {
                val lines = runCatching { Files.readAllLines(file) }.getOrNull()
                if (lines != null) {
                    for ((i, line) in lines.withIndex()) {
                        if (regex.containsMatchIn(line)) {
                            hits += "$file:${i + 1}: ${line.trim().take(GREP_LINE)}"
                            if (hits.size > limit) return@scan false
                        }
                    }
                }
            }
        }
        true
    }
    return hits
}

/** Every file below [dir] as a path relative to it (folders end with /), skipping hidden and bulky folders and unreadable ones. */
internal fun ToolBox.walkTree(dir: Path): List<String> {
    val out = ArrayList<String>()
    fun visit(folder: Path, depth: Int) {
        if (out.size > TREE_LIMIT) return
        val children = try { Files.newDirectoryStream(folder).use { it.toList() } } catch (e: Exception) { return }
        for (child in children.sortedBy { it.fileName.toString().lowercase() }) {
            val name = child.fileName.toString()
            val isDir = Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            out += dir.relativize(child).toString() + if (isDir) "/" else ""
            if (isDir && depth < TREE_DEPTH && name !in FIND_SKIP && name !in HIDDEN_SKIP) visit(child, depth + 1)
        }
    }
    visit(dir, 1)
    return out
}

/** For a relative path that exists nowhere: the places in the allowed folders where such a path does exist, so the model can retry with it. */
internal fun ToolBox.didYouMean(raw: String): String {
    val tail = raw.trim().trim('/').takeIf { it.isNotEmpty() && !it.startsWith("~") && !Path.of(it).isAbsolute } ?: return ""
    val last = tail.substringAfterLast('/').lowercase()
    val found = access.roots.flatMap { findByName(it, last) }.map { it.trimEnd('/') }.distinct()
    // the path as written ends the real one (a folder was left off the front), else the same file name in another folder (a folder was dropped from the middle)
    val matches = found.filter { it.lowercase().endsWith(tail.lowercase()) }
        .ifEmpty { found.filter { it.substringAfterLast('/').lowercase() == last } }
    return if (matches.isEmpty()) "" else " Did you mean: " + matches.take(5).joinToString(" or ") + "?"
}

internal fun ToolBox.requireExists(raw: String, file: Path) {
    require(Files.exists(file)) {
        "$raw does not exist" + (vault?.let { " (a relative path is looked up inside the vault, ${it.root})" }.orEmpty()) +
            ". Use an absolute path or ~/… for anything outside the vault." + didYouMean(raw)
    }
}

/** The search of the user's files in the chosen folders (code, configuration, text, documents), by words and meaning, with the line or page of each hit; see [ProjectSearch]. */
internal fun ToolBox.searchProjectTool(search: ProjectSearch) = ToolSpec(
    "search_files", "Search the user's FILES in the folders chosen for search (the working folder and the \"also search\" folders): CODE, configuration, text and DOCUMENTS (PDF, Word, HTML), by WORDS and by MEANING together, and get the best passages with the file path and where in it (a line for code, a page for a PDF). " +
        "Use it to find where something is implemented or defined, how a part works, or which document talks about a topic; then read_file the best hit (start_line near the line given). For an exact regular expression use grep_files. " +
        "mode: both, keywords (exact names, identifiers) or meaning (paraphrases; needs an embedding model); leave it out and the user's setting is used, normally both. The answer says which method really ran. Notes of the vault are searched with search_vault, not with this.",
    schema(
        "query" to ("string" to "What you are looking for, in a few words or a sentence"),
        "limit" to ("integer" to "How many hits (default ${HybridSearch.DEFAULT_LIMIT}, at most ${HybridSearch.MAX_LIMIT})"),
        "mode" to ("string" to "both, keywords or meaning (leave it out to use the user's setting)"),
        required = listOf("query"),
    ),
    ToolRisk.Read, { it.str("query") },
) { a -> search.answer(a.str("query"), a.int("limit") ?: HybridSearch.DEFAULT_LIMIT, RetrievalMode.of(a.str("mode"), searchMode)) }

/** Reads the file named by `path` (whole, or `start_line`..`end_line` numbered); what `read_file` and `cat` return. */
internal fun ToolBox.readFile(a: JsonObject): String {
    val file = access.resolve(a.str("path"), base)
    requireExists(a.str("path"), file)
    require(Files.isRegularFile(file)) { "${a.str("path")} is a folder, not a file. Use list_dir for folders." }
    val from = a.int("start_line")
    val to = a.int("end_line")
    return if (TextFiles.isPdf(file)) {
        require(Files.size(file) <= TextFiles.MAX_PDF_BYTES) { "The PDF is too large." }
        cut(TextFiles.load(file).text)
    } else if (from != null || to != null) {
        require(Files.size(file) <= TextFiles.MAX_FILE_BYTES) { "The file is larger than ${TextFiles.MAX_FILE_BYTES / (1024 * 1024)} MB." }
        val lines = Files.readAllLines(file)
        val first = (from ?: 1).coerceAtLeast(1)
        require(first <= lines.size) { "${a.str("path")} has only ${lines.size} lines." }
        val last = minOf(to ?: (first + RANGE_LINES - 1), first + RANGE_LINES - 1, lines.size)
        val width = last.toString().length
        lines.subList(first - 1, last).withIndex().joinToString("\n") { (i, l) -> "${(first + i).toString().padStart(width)} | $l" } +
            if (last < lines.size) "\n… (${lines.size - last} more lines; continue with start_line=${last + 1})" else "\n(end of file, ${lines.size} lines)"
    } else {
        require(Files.size(file) <= TextFiles.MAX_FILE_BYTES) { "The file is larger than ${TextFiles.MAX_FILE_BYTES / (1024 * 1024)} MB." }
        val text = TextFiles.load(file).text
        if (text.length > READ_LIMIT) {
            text.take(READ_LIMIT) + "\n… (cut after $READ_LIMIT characters; the file has more. Read the rest with start_line / end_line)"
        } else text
    }
}

internal fun ToolBox.fileTools(): List<ToolSpec> = listOf(
    ToolSpec(
        "list_dir", "List the files and folders in a directory. Folders end with /. With recursive=true it lists everything below it as a tree of paths, which is how to see a whole module or project.",
        schema(
            "path" to ("string" to "Directory: absolute, ~/…, or relative to $relativeTo"),
            "recursive" to ("boolean" to "true to include all subfolders (up to 10 levels, tooling and build folders skipped)"),
            required = listOf("path"),
        ),
        ToolRisk.Read, { listOf(it.str("path"), if (it.bool("recursive")) "(recursive)" else "").filter { s -> s.isNotEmpty() }.joinToString("  ") },
    ) { a ->
        val dir = access.resolve(a.str("path"), base)
        requireExists(a.str("path"), dir)
        require(Files.isDirectory(dir)) { "${a.str("path")} is a file, not a folder." }
        val names = if (a.bool("recursive")) walkTree(dir) else
            Files.list(dir).use { s -> s.map { (if (Files.isDirectory(it)) "${it.fileName}/" else it.fileName.toString()) }.sorted().toList() }
        val limit = if (a.bool("recursive")) TREE_LIMIT else LIST_LIMIT
        when {
            names.isEmpty() -> "(empty)"
            names.size > limit -> names.take(limit).joinToString("\n") + "\n… and ${names.size - limit} more; list a subfolder to see them"
            else -> names.joinToString("\n")
        }
    },
    ToolSpec(
        "find_files", "Find files and folders below a directory (up to 20 levels) by part of the name and/or a glob pattern such as **/*.kt or *Paywall*.{kt,java}.",
        schema(
            "name" to ("string" to "Part of the name, case-insensitive, for example Arcana"),
            "glob" to ("string" to "Glob pattern. Without a / it is matched against the file name, otherwise against the path inside the search folder, e.g. src/**/*.kt"),
            "path" to ("string" to "Directory to search in: absolute, ~/…, or relative to $relativeTo. Leave empty to search every folder you may use (the vault and the extra folders)"),
        ),
        ToolRisk.Read, { a -> listOf(a.str("name"), a.str("glob"), a.str("path")).filter { it.isNotBlank() }.joinToString("  ") },
    ) { a ->
        val needle = a.str("name").lowercase()
        val glob = a.str("glob")
        require(needle.isNotBlank() || glob.isNotBlank()) { "Give part of a name or a glob pattern to look for." }
        val raw = a.str("path")
        val dirs = searchRoots(raw)
        val accepts = if (glob.isBlank()) null else globMatcher(glob)
        val found = dirs.flatMap { dir ->
            val out = ArrayList<String>()
            scan(dir) { child, isDir ->
                if ((needle.isBlank() || child.fileName.toString().lowercase().contains(needle)) && (accepts == null || accepts(dir, child))) out += if (isDir) "$child/" else child.toString()
                out.size <= FIND_LIMIT
            }
            out
        }.distinct()
        when {
            found.isEmpty() -> "Nothing matched inside ${raw.ifBlank { dirs.joinToString(", ") }} (Library, build, media and tooling or secret folders like .git and .ssh are not searched). If you know the project folder, search in it with path."
            found.size > FIND_LIMIT -> found.take(FIND_LIMIT).joinToString("\n") + "\n… more matches; use a longer name, a narrower glob or a deeper folder"
            else -> found.joinToString("\n")
        }
    },
    ToolSpec(
        "grep_files", "Search INSIDE files for a regular expression, like grep: returns file:line: text for each match. Use it to find where a class, function, string or setting is used or defined.",
        schema(
            "pattern" to ("string" to "Regular expression (a plain word works too), case-insensitive. Escape special characters like ( or . with a backslash"),
            "path" to ("string" to "Directory to search in: absolute, ~/…, or relative to $relativeTo. Leave empty for every folder you may use"),
            "glob" to ("string" to "Only search files matching this glob, e.g. *.kt or src/**/*.java"),
            required = listOf("pattern"),
        ),
        ToolRisk.Read, { a -> listOf(a.str("pattern"), a.str("glob"), a.str("path")).filter { it.isNotBlank() }.joinToString("  ") },
    ) { a ->
        require(a.str("pattern").isNotEmpty()) { "Give a pattern to search for." }
        val regex = try { Regex(a.str("pattern"), RegexOption.IGNORE_CASE) } catch (e: Exception) { throw IllegalArgumentException("\"${a.str("pattern")}\" is not a valid regular expression (${e.message?.lineSequence()?.firstOrNull()}). Escape ( ) [ ] . * + ? with a backslash, or search for a simpler word.") }
        val raw = a.str("path")
        val dirs = searchRoots(raw)
        val hits = ArrayList<String>()
        for (dir in dirs) if (hits.size <= GREP_LIMIT) hits += grep(dir, regex, a.str("glob"), GREP_LIMIT - hits.size)
        when {
            hits.isEmpty() -> "No line matches \"${a.str("pattern")}\" inside ${raw.ifBlank { dirs.joinToString(", ") }}${if (a.str("glob").isBlank()) "" else " (files matching ${a.str("glob")})"}. Binary files, PDFs, hidden tooling folders, Library and build folders are not searched."
            hits.size > GREP_LIMIT -> hits.take(GREP_LIMIT).joinToString("\n") + "\n… more matches; narrow it with path or glob, or a more specific pattern"
            else -> hits.joinToString("\n")
        }
    },
    ToolSpec(
        "read_file", "Read a file as text: plain text, JSON, CSV, code, logs, Markdown, or the text layer of a PDF. For a long file read it in parts with start_line and end_line; the lines then come numbered.",
        schema(
            "path" to ("string" to "File: absolute, ~/…, or relative to $relativeTo"),
            "start_line" to ("integer" to "First line to read, counting from 1"),
            "end_line" to ("integer" to "Last line to read (at most $RANGE_LINES lines after start_line). Default: start_line + ${RANGE_LINES - 1}"),
            required = listOf("path"),
        ),
        ToolRisk.Read, { a -> a.str("path") + (if (a.int("start_line") != null || a.int("end_line") != null) "  lines ${a.int("start_line") ?: 1}-${a.int("end_line") ?: "…"}" else "") },
    ) { a -> readFile(a) },
    ToolSpec(
        "cat", "Print a text file with line numbers, like the shell's `cat -n`: the whole file when it is short, else the first part with a note on where to continue (use read_file with start_line / end_line for the rest). Same files and limits as read_file.",
        schema("path" to ("string" to "File: absolute, ~/…, or relative to $relativeTo"), required = listOf("path")),
        ToolRisk.Read, { it.str("path") },
    ) { a -> readFile(buildJsonObject { put("path", a.str("path")); put("start_line", 1) }) },
    ToolSpec(
        "write_file", "Create a text file, or replace an existing one completely. The old version of a replaced file is kept as a backup.",
        schema(
            "path" to ("string" to "File: absolute, ~/…, or relative to $relativeTo"),
            "content" to ("string" to "The complete new text of the file"),
            required = listOf("path", "content"),
        ),
        ToolRisk.Write, { it.str("path") },
        { a ->
            val exists = runCatching { Files.exists(access.resolve(a.str("path"), base)) }.getOrDefault(false)
            val head = (if (exists) "Replace " else "Create ") + a.str("path") + " (${a.str("content").length} characters)"
            val diff = if (exists) currentText(a.str("path"))?.let { Diff.unified(it, a.str("content")) } else null
            when {
                diff == null -> listOf(head) + a.str("content").lines().take(PREVIEW_LINES).map { "+ $it" }
                diff.isEmpty() -> listOf(head, "The file already has exactly this text; nothing changes.")
                else -> listOf(head) + diff.take(MAX_DIFF_LINES)  + (if (diff.size > MAX_DIFF_LINES) listOf("… ${diff.size - MAX_DIFF_LINES} more lines of the diff") else emptyList())
            }
        },
    ) { a ->
        val file = writable(a.str("path"))
        val existed = Files.exists(file)
        if (existed) backup(file)
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, a.str("content"))
        (if (existed) "Replaced " else "Created ") + "${a.str("path")} (${a.str("content").length} characters)" + if (existed) ". The old version is backed up." else ""
    },
    ToolSpec(
        "edit_file", "Change a text file by replacing one exact piece of text with another. The old text must occur exactly once.",
        schema(
            "path" to ("string" to "File: absolute, ~/…, or relative to $relativeTo"),
            "old" to ("string" to "The exact text to replace (must be unique in the file)"),
            "new" to ("string" to "The text to put in its place"),
            required = listOf("path", "old", "new"),
        ),
        ToolRisk.Write, { it.str("path") },
        { a ->
            val text = currentText(a.str("path"))
            val old = a.str("old")
            // the diff of the whole file when the piece is found once (what the edit would really do); the bare pieces otherwise
            val diff = if (text != null && old.isNotEmpty() && text.indexOf(old) >= 0 && text.indexOf(old) == text.lastIndexOf(old)) Diff.unified(text, text.replace(old, a.str("new"))) else null
            if (diff == null) listOf("Edit ${a.str("path")}") + old.lines().take(PREVIEW_LINES).map { "- $it" } + a.str("new").lines().take(PREVIEW_LINES).map { "+ $it" }
            else listOf("Edit ${a.str("path")}") + diff.take(MAX_DIFF_LINES) + (if (diff.size > MAX_DIFF_LINES) listOf("… ${diff.size - MAX_DIFF_LINES} more lines of the diff") else emptyList())
        },
    ) { a ->
        val file = writable(a.str("path"))
        requireExists(a.str("path"), file)
        require(Files.isRegularFile(file)) { "${a.str("path")} is a folder, not a file." }
        val text = Files.readString(file)
        val old = a.str("old")
        require(old.isNotEmpty()) { "The text to replace is empty." }
        val count = text.windowed(old.length).count { it == old }
        require(count == 1) { if (count == 0) "The text to replace was not found in ${a.str("path")}." else "The text to replace occurs $count times; give more context so it is unique." }
        backup(file)
        Files.writeString(file, text.replace(old, a.str("new")))
        "Edited ${a.str("path")}. The old version is backed up."
    },
    ToolSpec(
        "delete_path", "Delete a file or a whole folder. Nothing is erased for good: it is moved to a trash folder (in the vault's .trash for vault files) and the result says where, so it can be put back. The user must confirm every deletion.",
        schema("path" to ("string" to "File or folder: absolute, ~/…, or relative to $relativeTo"), required = listOf("path")),
        ToolRisk.Write, { it.str("path") },
        { a ->
            val target = runCatching { writable(a.str("path")) }.getOrNull()
            val what = when {
                target == null || !Files.exists(target) -> "(it does not exist)"
                Files.isDirectory(target) -> "folder with ${countEntries(target)} items"
                else -> "file, ${Files.size(target)} bytes"
            }
            listOf("- ${a.str("path")}  ($what)", "It is moved to the trash, not erased (${trashWhere(target ?: Path.of(a.str("path")))}).")
        },
        alwaysAsk = true, confirmHeading = "Delete this?",
    ) { a ->
        val target = writable(a.str("path"))
        requireExists(a.str("path"), target)
        requireNotRoot(a.str("path"), target)
        val trash = trashFor(target)
        Files.createDirectories(trash)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var dest = trash.resolve("$stamp-${target.fileName}")
        var n = 2
        while (Files.exists(dest)) dest = trash.resolve("$stamp-${target.fileName}.${n++}")
        moveTree(target, dest)
        "Moved ${a.str("path")} to the trash: $dest (move it back to restore it)."
    },
    ToolSpec(
        "move_path", "Move or rename a file or folder inside the allowed folders. It refuses to overwrite anything. The user must confirm every move. Links inside Obsidian notes that point to a moved note are not updated.",
        schema(
            "from" to ("string" to "Current path: absolute, ~/…, or relative to $relativeTo"),
            "to" to ("string" to "New path (including the new name), same forms"),
            required = listOf("from", "to"),
        ),
        ToolRisk.Write, { "${it.str("from")} → ${it.str("to")}" },
        { a -> listOf(a.str("from"), "→ ${a.str("to")}", "Nothing is overwritten; Obsidian links to a moved note are not updated.") },
        alwaysAsk = true, confirmHeading = "Move this?",
    ) { a ->
        val from = writable(a.str("from"))
        val to = writable(a.str("to"))
        requireExists(a.str("from"), from)
        requireNotRoot(a.str("from"), from)
        require(!Files.exists(to)) { "${a.str("to")} already exists; nothing was moved. Choose another name." }
        require(!(Files.isDirectory(from) && to.startsWith(from))) { "A folder cannot be moved into itself." }
        to.parent?.let { Files.createDirectories(it) }
        moveTree(from, to)
        "Moved ${a.str("from")} to ${a.str("to")}."
    },
)

/** The text of the file at [raw] for a preview: null when it is missing, a folder, too big or not text. */
internal fun ToolBox.currentText(raw: String): String? = runCatching {
    val file = access.resolve(raw, base)
    if (Files.isRegularFile(file) && Files.size(file) <= MAX_DIFF_BYTES) Files.readString(file) else null
}.getOrNull()

internal fun ToolBox.countEntries(dir: Path): Int = runCatching { Files.walk(dir).use { it.count().toInt() - 1 } }.getOrDefault(0)

/** The allowed roots themselves (the vault, an allowed folder) and the home folder are never deleted or moved. */
internal fun ToolBox.requireNotRoot(raw: String, path: Path) {
    val real = FileAccess.realOf(path)
    require(access.roots.none { FileAccess.realOf(it) == real } && real != FileAccess.realOf(Path.of(System.getProperty("user.home")))) {
        "$raw is an allowed folder itself (or the home folder); it is never deleted or moved. Name something inside it."
    }
}

/** Where [path] goes when deleted: the vault's own `.trash` (Obsidian's) for a vault file, else Arcana's trash next to the backups. */
internal fun ToolBox.trashFor(path: Path): Path {
    val root = vault?.root
    return if (root != null && FileAccess.realOf(path).startsWith(FileAccess.realOf(root))) root.resolve(".trash") else backups.resolveSibling("trash")
}

internal fun ToolBox.trashWhere(path: Path): String {
    val trash = trashFor(path)
    return if (trash.fileName.toString() == ".trash") ".trash in the vault" else trash.toString().replace(System.getProperty("user.home"), "~")
}

internal fun ToolBox.moveTree(from: Path, to: Path) {
    try {
        Files.move(from, to)
    } catch (e: java.io.IOException) {
        // another disk: copy everything, then remove the original
        Files.walk(from).use { s -> s.forEach { p -> val d = to.resolve(from.relativize(p).toString()); if (Files.isDirectory(p)) Files.createDirectories(d) else Files.copy(p, d) } }
        Files.walk(from).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}

/** [raw] inside the allowed folders and not in a folder that holds settings or history (`.git`, `.obsidian`). */
internal fun ToolBox.writable(raw: String): Path {
    val file = access.resolve(raw, base)
    require(file.none { it.toString() in PROTECTED }) { "$raw is in a protected folder (${PROTECTED.joinToString()})." }
    return file
}

internal fun ToolBox.backup(file: Path) {
    Files.createDirectories(backups)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    // two backups of one file within a second must not overwrite each other
    var target = backups.resolve("$stamp-${file.fileName}")
    var n = 2
    while (Files.exists(target)) target = backups.resolve("$stamp-${file.fileName}.${n++}")
    Files.copy(file, target)
}

internal fun ToolBox.cut(text: String) = if (text.length > READ_LIMIT) text.take(READ_LIMIT) + "\n… (cut after $READ_LIMIT characters)" else text
