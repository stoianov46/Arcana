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

// The tools on the vault's notes (search, read, create, properties, tags, memory, vault_info, vault_health) and how a model's note path is understood. Split out of Tools.kt (proposal 0007, phase 3): extension functions of ToolBox, which offers them in `specs`.

// ----- vault -----

internal fun ToolBox.vaultTools(vault: Vault): List<ToolSpec> = listOf(
    ToolSpec(
        "search_notes", "Search the user's notes for words. Finds notes that contain ANY of the words (3+ letters, common words are skipped; a short term like \"AI\" is searched as written) in the title or text and returns the best matches with an excerpt each. For an exact phrase or a regular expression use grep_files. Words only: search_vault (words AND meaning together) is usually the better first search.",
        schema("query" to ("string" to "Words to look for"), "limit" to ("integer" to "How many notes (default 5, at most 20)"), required = listOf("query")),
        ToolRisk.Read, { it.str("query") },
    ) { a ->
        val query = a.str("query")
        if (query.isBlank()) return@ToolSpec "Error: query is empty. Give the words to look for."
        val hits = vault.search(query, (a.int("limit") ?: 5).coerceIn(1, 20), 300, literalFallback = true)
        if (hits.isNotEmpty()) hits.joinToString("\n\n") { "${it.note.path}\n${it.excerpt}" }
        else {
            // say what was searched and how big the vault is, so a miss is not mistaken for a broken tool
            val words = Vault.tokenize(query)
            "No notes matched ${if (words.isEmpty()) "\"${query.trim()}\"" else words.joinToString(" ") { "\"$it\"" }} (the search is not broken: the vault has ${vault.notes().size} notes). " +
                "It looks for any of the words (3+ letters, common words like \"the\" are skipped) in titles and text, case-insensitive. Try other or fewer words, a part of a title, or grep_files for an exact text."
        }
    },
    ToolSpec(
        "list_notes", "List the notes in the vault, optionally only those inside one folder. Paths are relative to $relativeTo.",
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
        "read_note", "Read the full text of one note, by its path relative to $relativeTo (for example Projects/Idea.md).",
        schema("path" to ("string" to "Path of the note inside the vault"), required = listOf("path")),
        ToolRisk.Read, { it.str("path") },
    ) { a ->
        val path = notePath(a.str("path"))
        val file = vault.root.resolve(path).normalize()
        require(file.startsWith(vault.root) && Files.isRegularFile(file)) { "$path does not exist in the vault, ${vault.root}." + didYouMean(path).ifEmpty { " Find the exact path with find_files or search_notes." } }
        val asked = a.str("path").trim().trim('/').let { if (it.endsWith(".md")) it else "$it.md" }
        (if (asked != path) "(found at $path)\n" else "") + cut(vault.read(path))
    },
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
        val note = vault.create(a.str("folder").trim('/').ifEmpty { outputFolder }, a.str("title"), a.str("content"), model, origin)
        origin?.let { provenance?.touched(it, note.path, NoteAction.Created) }
        "Created ${note.path}"
    },
    ToolSpec(
        "set_properties", "Set properties (the frontmatter block at the top) of an existing note, one `key: value` per line. A key that exists is replaced, a new key is added, everything else stays. Use it instead of editing the note's text to change properties such as status or tags.",
        schema(
            "path" to ("string" to "Path of the note inside the vault"),
            "properties" to ("string" to "One key and value per line, for example: status: done (a list: tags: [a, b])"),
            required = listOf("path", "properties"),
        ),
        ToolRisk.Write, { it.str("path") },
        { a -> listOf("Set properties of ${a.str("path")}") + a.str("properties").lines().filter { it.isNotBlank() }.take(PREVIEW_LINES).map { "+ $it" } },
    ) { a ->
        val path = notePath(a.str("path"))
        val keys = vault.setProperties(path, a.str("properties"))
        origin?.let { provenance?.touched(it, path, NoteAction.Properties) }
        "Set ${keys.joinToString()} in ${a.str("path")}"
    },
    ToolSpec(
        "add_tags", "Add tags to an existing note (in its properties). The tags it already has stay, so this is safer than set_properties for tags. Reuse the tags the vault already has (see list_tags) before inventing new ones.",
        schema(
            "path" to ("string" to "Path of the note inside the vault"),
            "tags" to ("string" to "The tags, separated by commas or spaces, with or without #, for example: project, ai"),
            required = listOf("path", "tags"),
        ),
        ToolRisk.Write, { it.str("path") },
        { a -> listOf("Add tags to ${a.str("path")}") + tagList(a.str("tags")).map { "+ #$it" } },
    ) { a ->
        val tags = vault.addTags(notePath(a.str("path")), tagList(a.str("tags")))
        "Tags of ${a.str("path")} are now: ${tags.joinToString { "#$it" }}"
    },
    ToolSpec(
        "list_tags", "List the tags used in the vault with how many notes have each, the most used first. Look here before tagging, to reuse a tag instead of inventing a new one.",
        schema("limit" to ("integer" to "How many tags (default 50, at most 200)")),
        ToolRisk.Read, { "" },
    ) { a ->
        val counts = VaultIndex.build(vault).tagCounts
        if (counts.isEmpty()) "The vault has no tags yet."
        else "${counts.size} tags:\n" + counts.take((a.int("limit") ?: 50).coerceIn(1, 200)).joinToString("\n") { (tag, n) -> "#$tag  ($n)" }
    },
    ToolSpec(
        VAULT_INFO, "Where the vault and Arcana's own files are: the vault's absolute path and size, the folder new notes go to, the skills folder, the memory file, the research backlog, the todo and question notes, the project folder and every allowed folder. Call it when you need an absolute path (for run_command, a script, a link outside Obsidian) instead of guessing one.",
        schema(), ToolRisk.Read, { "" },
    ) { _ -> vaultInfo(vault) },
    ToolSpec(
        VAULT_HEALTH, "Check the health of the vault, the same checks as the Brain screen's Vault hygiene, plus dead links: notes without a type property, names that start with an emoji or symbol, generic names (Index, Untitled), duplicate names, loose notes in the vault root, reports not finished, stale reports, notes linked to nothing, and links to notes that do not exist. Each problem comes with the note's path and how to fix it. Read-only: it changes nothing.",
        schema(
            "kind" to ("string" to "Only one kind: ${HygieneKind.entries.joinToString { it.name.lowercase() }} or dead_links (default: all)"),
            "limit" to ("integer" to "How many notes to list per kind (default 10, at most 50)"),
        ),
        ToolRisk.Read, { it.str("kind").ifEmpty { "all checks" } },
    ) { a -> vaultHealth(vault, a.str("kind").trim().lowercase(), (a.int("limit") ?: 10).coerceIn(1, 50)) },
    ToolSpec(
        "remember", "Add one line to the user's memory file (ARCANA.md): a lasting fact or preference that every future chat should know. Use it only when the user asks you to remember something. The user confirms each line. A line that names a note must use its exact path (find_files); an unknown path is refused, and a fact that is already there is not added twice.",
        schema("text" to ("string" to "One short sentence to remember"), required = listOf("text")),
        ToolRisk.Write, { it.str("text") },
        { a -> listOf("Remember in ARCANA.md (read in every chat):", "+ ${a.str("text").trim()}") },
        alwaysAsk = true, confirmHeading = "Remember this?",
    ) { a ->
        require(a.str("text").isNotBlank()) { "Say what to remember." }
        vault.remember(a.str("text"))?.let { "Already remembered: “$it”. Nothing was added." } ?: "Remembered in ARCANA.md."
    },
    ToolSpec(
        "append_to_note", "Add text to the end of an existing note, optionally under a new heading.",
        schema(
            "path" to ("string" to "Path of the note inside the vault"),
            "content" to ("string" to "The Markdown to add"),
            "heading" to ("string" to "A heading to put above it (optional; leave empty when content already starts with its own heading)"),
            required = listOf("path", "content"),
        ),
        ToolRisk.Write, { it.str("path") },
        { a -> listOf("Add to ${a.str("path")}") + a.str("content").lines().take(PREVIEW_LINES).map { "+ $it" } },
    ) { a ->
        val path = notePath(a.str("path"))
        vault.append(path, a.str("heading").ifBlank { null }, a.str("content"))
        origin?.let { provenance?.touched(it, path, NoteAction.Appended) }
        "Added to ${a.str("path")}"
    },
)

/**
 * What `vault_info` answers: absolute paths, so a script or a shell command gets them right, and whether each place exists yet (a backlog is made by the first question).
 * Plain `key: value` lines, one per place, easy for a small model to read and to quote.
 */
internal fun ToolBox.vaultInfo(vault: Vault): String {
    val root = vault.root
    fun place(label: String, path: Path) = "$label: $path" + if (Files.exists(path)) "" else " (not created yet)"
    val notes = vault.notes()
    return buildList {
        add("Vault: $root (${notes.size} notes)")
        add(place("New notes go to", if (outputFolder.isBlank()) root else root.resolve(outputFolder)))
        if (skillsFolder.isNotBlank()) add(place("Skills folder", root.resolve(skillsFolder)) + " (${skills.size} skill${if (skills.size == 1) "" else "s"})")
        add(place("Memory file", vault.memoryFile()))
        add(place("Research backlog", root.resolve(ResearchBacklog.PATH)))
        WorkNotes.paths.forEach { (kind, path) -> add(place("${kind.title} note", root.resolve(path))) }
        project?.let { add("Project folder: $it") }
        val extra = access.roots.filter { it != root && it != project?.toAbsolutePath()?.normalize() }
        if (extra.isNotEmpty()) add("Other allowed folders: ${extra.joinToString(", ")}")
        add("A relative path in the note tools means inside the vault.")
    }.joinToString("\n")
}

/**
 * What `vault_health` answers: the vault in figures, then each kind of problem with its count and up to [limit] notes, each with its fix (the hygiene checks of the Brain
 * screen, [VaultHygiene], and the dead links of [VaultIndex]); [kind] blank = every kind, else one kind's name or `dead_links`.
 */
internal fun ToolBox.vaultHealth(vault: Vault, kind: String, limit: Int): String {
    val kinds = HygieneKind.entries.associateBy { it.name.lowercase() }
    require(kind.isEmpty() || kind in kinds || kind == "dead_links") { "Unknown kind \"$kind\". Use one of: ${kinds.keys.joinToString()}, dead_links, or leave it empty for all." }
    val index = VaultIndex.build(vault)
    val report = VaultHygiene.check(vault, index = index)
    val dead = index.notes.flatMap { n -> n.unresolved.sorted().map { n.path to it } }
    return buildString {
        appendLine("Vault health: ${index.notes.size} notes, ${index.totalWords} words, ${index.totalLinks} links. Problems: ${report.total + dead.size}.")
        for (k in HygieneKind.entries) {
            if (kind.isNotEmpty() && kind != k.name.lowercase()) continue
            val issues = report.byKind.getValue(k)
            appendLine()
            appendLine("${k.icon} ${k.counted(issues.size)} (kind: ${k.name.lowercase()})")
            issues.take(limit).forEach { appendLine("- ${it.path}: ${it.message}. Fix: ${it.fix}") }
            if (issues.size > limit) appendLine("… and ${issues.size - limit} more (ask for this kind alone with a higher limit)")
        }
        if (kind.isEmpty() || kind == "dead_links") {
            appendLine()
            appendLine("✕ ${dead.size} dead link${if (dead.size == 1) "" else "s"} (kind: dead_links)")
            dead.take(limit).forEach { (from, to) -> appendLine("- $from links to [[$to]], which matches no note. Fix: link the right note (find it with search_notes) or remove the link") }
            if (dead.size > limit) appendLine("… and ${dead.size - limit} more")
        }
    }.trimEnd()
}

/**
 * The vault-relative path of a note from what a model wrote. Models usually know a note by its title or wikilink (that is how
 * Obsidian names notes), not by its folder and emoji prefix, so this accepts `[[Title]]`, an absolute path inside the vault, and a
 * bare name: when the exact path is missing, the one note with that name (case and leading emoji ignored) anywhere in the vault is used.
 */
internal fun ToolBox.notePath(raw: String): String {
    var path = raw.trim().removeSurrounding("[[", "]]").substringBefore('|').trim()
    val root = vault?.root
    if (root != null && path.startsWith("/")) {
        val absolute = Path.of(path).normalize()
        if (absolute.startsWith(root)) path = root.relativize(absolute).toString().replace('\\', '/')
    }
    path = path.trim('/')
    require(path.isNotEmpty()) { "The path is empty." }
    if (!path.endsWith(".md")) path = "$path.md"
    if (vault == null || Files.isRegularFile(vault.root.resolve(path))) return path
    fun key(name: String) = name.substringAfterLast('/').removeSuffix(".md").dropWhile { !it.isLetterOrDigit() }.trim().lowercase()
    val wanted = key(path)
    val same = vault.notes().map { it.path }.filter { key(it) == wanted }
    return when {
        same.size == 1 -> same.first()
        same.size > 1 -> throw IllegalArgumentException("$path is ambiguous, several notes have that name: ${same.take(5).joinToString(" or ")}. Use the full path.")
        else -> path
    }
}

/** The vault search that names its method: words, meaning, or both fused (the default and the best first choice); see [HybridSearch]. */
internal fun ToolBox.searchVaultTool(vault: Vault) = ToolSpec(
    "search_vault", "Search the user's notes by WORDS and by MEANING together (the best first search for almost any question about the notes): it finds passages that contain the words, and passages that say the same thing in other words, and ranks them together. " +
        "Describe what you look for in a few words or a sentence. mode: both, keywords (exact words, names, identifiers only) or meaning (paraphrases only); leave it out and the user's setting is used, normally both. The answer says which method found each note and which really ran (meaning needs an embedding model; without one it is keywords only). Read a hit with read_note.",
    schema(
        "query" to ("string" to "What you are looking for"),
        "limit" to ("integer" to "How many notes (default ${HybridSearch.DEFAULT_LIMIT}, at most ${HybridSearch.MAX_LIMIT})"),
        "mode" to ("string" to "both, keywords or meaning (leave it out to use the user's setting)"),
        required = listOf("query"),
    ),
    ToolRisk.Read, { it.str("query") },
) { a -> HybridSearch(LexicalIndex.forVault(vault), semantic?.index, searchTuning).answer(a.str("query"), a.int("limit") ?: HybridSearch.DEFAULT_LIMIT, RetrievalMode.of(a.str("mode"), searchMode)) }

internal fun ToolBox.semanticTool(search: SemanticSearch?) = ToolSpec(
    "semantic_search", "Find the user's notes by MEANING, not by words: describe what you are looking for in a sentence and get the best matching notes with the matching heading, an excerpt and a score (0 to 1; above about 0.6 is a close match). " +
        "Prefer it over search_notes for questions of meaning (\"find notes about how we decided on the database\"); use search_notes or grep_files for an exact word, name or phrase. Read a hit with read_note." +
        (if (search == null) " NOT SET UP YET: no embedding model is installed, so the first call asks the user once to download a small one (they may decline: you then get an error, and search_notes is the fallback)." else ""),
    schema("query" to ("string" to "What you are looking for, as a short sentence or a few words"), "limit" to ("integer" to "How many notes (default ${SemanticSearch.DEFAULT_LIMIT}, at most ${SemanticSearch.MAX_LIMIT})"), required = listOf("query")),
    ToolRisk.Read, { it.str("query") },
) { a -> search?.answer(a.str("query"), a.int("limit") ?: SemanticSearch.DEFAULT_LIMIT) ?: "Error: semantic search is not set up (no embedding model). Use search_notes or grep_files with a few likely words instead." }

/** `project, #ai data` as the tags `project`, `ai`, `data`. */
internal fun ToolBox.tagList(text: String): List<String> = text.split(Regex("[,\\s]+")).map { VaultIndex.cleanTag(it) }.filter { it.isNotEmpty() }
