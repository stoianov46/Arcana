package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The model asked for a tool: its name and the arguments it chose. */
@Serializable
data class ToolFunction(val name: String, val arguments: JsonObject = JsonObject(emptyMap()))

@Serializable
data class ToolCall(val function: ToolFunction) {
    val name: String get() = function.name
    val arguments: JsonObject get() = function.arguments
}

/** [Read] tools only look; [Write] tools change something and must be approved. */
enum class ToolRisk { Read, Write }

/** One tool the model may use: what it is called and takes, how it is shown, and what it does. */
class ToolSpec(
    val name: String,
    val description: String,
    /** The JSON schema of the arguments. */
    val parameters: JsonObject,
    val risk: ToolRisk,
    /** One short line saying what the call is about (a path, a query…). */
    val summary: (JsonObject) -> String,
    /** For an approval: the lines that show what would change. */
    val preview: (JsonObject) -> List<String> = { emptyList() },
    val run: (JsonObject) -> String,
)

/** Reading and writing is allowed only inside [roots]; anything else (and `..` or symlink tricks) is refused. */
class FileAccess(roots: List<Path>) {
    val roots: List<Path> = roots.map { it.toAbsolutePath().normalize() }.distinct()
    private val realRoots: List<Path> = this.roots.map { realOf(it) }

    /** [raw] (absolute, `~/…`, or relative to [base]) as a path inside the allowed folders. */
    fun resolve(raw: String, base: Path?): Path {
        val text = raw.trim()
        require(text.isNotEmpty()) { "The path is empty." }
        val expanded = when {
            text == "~" || text.startsWith("~/") -> Path.of(System.getProperty("user.home"), text.removePrefix("~").trimStart('/'))
            else -> Path.of(text)
        }
        val start = base ?: roots.firstOrNull() ?: throw IllegalArgumentException("No folder is allowed. Add one in Settings → Tools.")
        val path = (if (expanded.isAbsolute) expanded else start.resolve(expanded)).normalize()
        val real = realOf(path)
        require(realRoots.any { real.startsWith(it) }) {
            "$text is outside the folders Arcana may use (${roots.joinToString(", ")}). The user can add folders in Settings → Tools."
        }
        return path
    }

    companion object {
        /** The real location of [path]: symlinks resolved as far as it exists, the rest appended as is. */
        fun realOf(path: Path): Path {
            var existing = path
            val rest = ArrayDeque<Path>()
            while (!Files.exists(existing) && existing.parent != null) {
                rest.addFirst(existing.fileName)
                existing = existing.parent
            }
            var real = runCatching { existing.toRealPath() }.getOrDefault(existing)
            rest.forEach { real = real.resolve(it) }
            return real
        }
    }
}

/**
 * The tools the model can use in Chat: reading, searching and writing notes in the vault, and reading, listing and writing
 * files inside the allowed folders. A failed call returns "Error: …" as its result, so the model can react to it.
 */
class ToolBox(private val vault: Vault?, folders: List<Path>, private val backups: Path, private val outputFolder: String = "", private val model: String = "Arcana") {
    val access = FileAccess(listOfNotNull(vault?.root) + folders)

    val specs: List<ToolSpec> = buildList {
        if (vault != null) addAll(vaultTools(vault))
        addAll(fileTools())
    }

    fun find(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    /** The tools in the shape Ollama's `tools` field wants. */
    fun definitions(): JsonArray = JsonArray(specs.map { spec ->
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", spec.name)
                put("description", spec.description)
                put("parameters", spec.parameters)
            }
        }
    })

    /** Runs [call]; an unknown tool, bad arguments or a failure come back as text starting with "Error:". */
    fun execute(call: ToolCall): String {
        val spec = find(call.name) ?: return "Error: there is no tool called ${call.name}. Available: ${specs.joinToString { it.name }}."
        return try {
            spec.run(call.arguments)
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ----- vault -----

    private fun vaultTools(vault: Vault): List<ToolSpec> = listOf(
        ToolSpec(
            "search_notes", "Search the user's notes for a phrase. Returns the best matching notes with an excerpt each.",
            schema("query" to ("string" to "Words to look for"), "limit" to ("integer" to "How many notes (default 5, at most 20)"), required = listOf("query")),
            ToolRisk.Read, { it.str("query") },
        ) { a ->
            val hits = vault.search(a.str("query"), (a.int("limit") ?: 5).coerceIn(1, 20), 300)
            if (hits.isEmpty()) "No notes matched." else hits.joinToString("\n\n") { "${it.note.path}\n${it.excerpt}" }
        },
        ToolSpec(
            "list_notes", "List the notes in the vault, optionally only those inside one folder. Paths are relative to the vault.",
            schema("folder" to ("string" to "Folder inside the vault (default: the whole vault)")),
            ToolRisk.Read, { it.str("folder").ifEmpty { "(whole vault)" } },
        ) { a ->
            val folder = a.str("folder").trim('/')
            val notes = vault.notes().filter { folder.isEmpty() || it.path.startsWith("$folder/") }.map { it.path }.sorted()
            when {
                notes.isEmpty() -> "No notes found."
                notes.size > LIST_LIMIT -> notes.take(LIST_LIMIT).joinToString("\n") + "\n… and ${notes.size - LIST_LIMIT} more (name a folder to narrow it down)"
                else -> notes.joinToString("\n")
            }
        },
        ToolSpec(
            "read_note", "Read the full text of one note, by its path relative to the vault (for example Projects/Idea.md).",
            schema("path" to ("string" to "Path of the note inside the vault"), required = listOf("path")),
            ToolRisk.Read, { it.str("path") },
        ) { a -> cut(vault.read(notePath(a.str("path")))) },
        ToolSpec(
            "create_note", "Create a new Markdown note in the vault. It is saved in the given folder (default: the output folder).",
            schema(
                "title" to ("string" to "Note title; it becomes the file name"),
                "content" to ("string" to "The note's Markdown text"),
                "folder" to ("string" to "Folder inside the vault (optional)"),
                required = listOf("title", "content"),
            ),
            ToolRisk.Write, { "“${it.str("title")}”" + it.str("folder").let { f -> if (f.isEmpty()) "" else " in $f" } },
            { a -> listOf("New note “${a.str("title")}”${a.str("folder").let { if (it.isEmpty()) "" else " in $it" }}") + a.str("content").lines().take(PREVIEW_LINES).map { "+ $it" } },
        ) { a ->
            val note = vault.create(a.str("folder").trim('/').ifEmpty { outputFolder }, a.str("title"), a.str("content"), model)
            "Created ${note.path}"
        },
        ToolSpec(
            "append_to_note", "Add text to the end of an existing note, optionally under a new heading.",
            schema(
                "path" to ("string" to "Path of the note inside the vault"),
                "content" to ("string" to "The Markdown to add"),
                "heading" to ("string" to "A heading to put above it (optional)"),
                required = listOf("path", "content"),
            ),
            ToolRisk.Write, { it.str("path") },
            { a -> listOf("Add to ${a.str("path")}") + a.str("content").lines().take(PREVIEW_LINES).map { "+ $it" } },
        ) { a ->
            vault.append(notePath(a.str("path")), a.str("heading").ifBlank { null }, a.str("content"))
            "Added to ${a.str("path")}"
        },
    )

    private fun notePath(raw: String): String {
        val path = raw.trim().trim('/')
        require(path.isNotEmpty()) { "The path is empty." }
        return if (path.endsWith(".md")) path else "$path.md"
    }

    // ----- files -----

    private val base: Path? get() = vault?.root

    private fun fileTools(): List<ToolSpec> = listOf(
        ToolSpec(
            "list_dir", "List the files and folders in a directory. Folders end with /.",
            schema("path" to ("string" to "Directory: absolute, ~/…, or relative to the vault"), required = listOf("path")),
            ToolRisk.Read, { it.str("path") },
        ) { a ->
            val dir = access.resolve(a.str("path"), base)
            require(Files.isDirectory(dir)) { "${a.str("path")} is not a folder." }
            val names = Files.list(dir).use { s -> s.map { (if (Files.isDirectory(it)) "${it.fileName}/" else it.fileName.toString()) }.sorted().toList() }
            when {
                names.isEmpty() -> "(empty)"
                names.size > LIST_LIMIT -> names.take(LIST_LIMIT).joinToString("\n") + "\n… and ${names.size - LIST_LIMIT} more"
                else -> names.joinToString("\n")
            }
        },
        ToolSpec(
            "read_file", "Read a file as text: plain text, JSON, CSV, code, logs, Markdown, or the text layer of a PDF.",
            schema("path" to ("string" to "File: absolute, ~/…, or relative to the vault"), required = listOf("path")),
            ToolRisk.Read, { it.str("path") },
        ) { a ->
            val file = access.resolve(a.str("path"), base)
            require(Files.isRegularFile(file)) { "${a.str("path")} is not a file." }
            if (TextFiles.isPdf(file)) {
                require(Files.size(file) <= TextFiles.MAX_PDF_BYTES) { "The PDF is too large." }
                cut(TextFiles.load(file).text)
            } else {
                require(Files.size(file) <= TextFiles.MAX_FILE_BYTES) { "The file is larger than ${TextFiles.MAX_FILE_BYTES / (1024 * 1024)} MB." }
                cut(TextFiles.load(file).text)
            }
        },
        ToolSpec(
            "write_file", "Create a text file, or replace an existing one completely. The old version of a replaced file is kept as a backup.",
            schema(
                "path" to ("string" to "File: absolute, ~/…, or relative to the vault"),
                "content" to ("string" to "The complete new text of the file"),
                required = listOf("path", "content"),
            ),
            ToolRisk.Write, { it.str("path") },
            { a ->
                val exists = runCatching { Files.exists(access.resolve(a.str("path"), base)) }.getOrDefault(false)
                listOf((if (exists) "Replace " else "Create ") + a.str("path") + " (${a.str("content").length} characters)") +
                    a.str("content").lines().take(PREVIEW_LINES).map { "+ $it" }
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
                "path" to ("string" to "File: absolute, ~/…, or relative to the vault"),
                "old" to ("string" to "The exact text to replace (must be unique in the file)"),
                "new" to ("string" to "The text to put in its place"),
                required = listOf("path", "old", "new"),
            ),
            ToolRisk.Write, { it.str("path") },
            { a -> listOf("Edit ${a.str("path")}") + a.str("old").lines().take(PREVIEW_LINES).map { "- $it" } + a.str("new").lines().take(PREVIEW_LINES).map { "+ $it" } },
        ) { a ->
            val file = writable(a.str("path"))
            require(Files.isRegularFile(file)) { "${a.str("path")} is not a file." }
            val text = Files.readString(file)
            val old = a.str("old")
            require(old.isNotEmpty()) { "The text to replace is empty." }
            val count = text.windowed(old.length).count { it == old }
            require(count == 1) { if (count == 0) "The text to replace was not found in ${a.str("path")}." else "The text to replace occurs $count times; give more context so it is unique." }
            backup(file)
            Files.writeString(file, text.replace(old, a.str("new")))
            "Edited ${a.str("path")}. The old version is backed up."
        },
    )

    /** [raw] inside the allowed folders and not in a folder that holds settings or history (`.git`, `.obsidian`). */
    private fun writable(raw: String): Path {
        val file = access.resolve(raw, base)
        require(file.none { it.toString() in PROTECTED }) { "$raw is in a protected folder (${PROTECTED.joinToString()})." }
        return file
    }

    private fun backup(file: Path) {
        Files.createDirectories(backups)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        // two backups of one file within a second must not overwrite each other
        var target = backups.resolve("$stamp-${file.fileName}")
        var n = 2
        while (Files.exists(target)) target = backups.resolve("$stamp-${file.fileName}.${n++}")
        Files.copy(file, target)
    }

    private fun cut(text: String) = if (text.length > READ_LIMIT) text.take(READ_LIMIT) + "\n… (cut after $READ_LIMIT characters)" else text

    companion object {
        const val READ_LIMIT = 20_000
        const val LIST_LIMIT = 200
        const val PREVIEW_LINES = 12
        val PROTECTED = setOf(".git", ".obsidian")

        private fun schema(vararg props: Pair<String, Pair<String, String>>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                props.forEach { (name, typeAndDescription) ->
                    putJsonObject(name) {
                        put("type", typeAndDescription.first)
                        put("description", typeAndDescription.second)
                    }
                }
            }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    }
}

/** The word to put in the system prompt when tools are on, so the model knows what it can do and how to behave. */
const val TOOLS_PROMPT = " You have tools to search, read and write the user's notes and to read and write files in the folders they allowed. " +
    "Use a tool only when it helps. Do not guess a path: list or search first. Changes are shown to the user for approval; " +
    "after making one, say briefly what you did."
