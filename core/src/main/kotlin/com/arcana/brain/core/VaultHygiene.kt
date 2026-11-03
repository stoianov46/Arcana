package com.arcana.brain.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What kind of problem a note has. The rules mirror the vault note `Research/Where Things Go.md` ("Names and properties"). */
enum class HygieneKind(val label: String, val one: String, val icon: String) {
    MissingType("notes without a type property", "note without a type property", "◇"),
    SymbolName("names that start with a symbol or emoji", "name that starts with a symbol or emoji", "☺"),
    GenericName("generic names", "generic name", "≈"),
    DuplicateName("duplicate names", "duplicate name", "⧉"),
    LooseRoot("loose notes in the vault root", "loose note in the vault root", "⌂"),
    ReportNotDone("reports not finished", "report not finished", "◔"),
    StaleReport("stale reports", "stale report", "◷"),
    Island("notes linked to nothing", "note linked to nothing", "⊘"),
    ;

    /** `1 stale report`, `3 stale reports`. */
    fun counted(n: Int): String = "$n ${if (n == 1) one else label}"
}

/** One problem: the note, what is wrong, and what to do about it ([fix] is written so it can be handed to a model as an instruction). */
data class HygieneIssue(val kind: HygieneKind, val path: String, val message: String, val fix: String) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".md")
}

/** All the problems found, by kind. */
data class HygieneReport(val issues: List<HygieneIssue>) {
    val byKind: Map<HygieneKind, List<HygieneIssue>> = HygieneKind.entries.associateWith { k -> issues.filter { it.kind == k } }
    val total: Int get() = issues.size
    fun count(kind: HygieneKind): Int = byKind.getValue(kind).size

    /** The kinds with problems, the most first (ties keep the order of [HygieneKind]). */
    val worst: List<HygieneKind> get() = HygieneKind.entries.filter { count(it) > 0 }.sortedByDescending { count(it) }
}

/**
 * Checks the vault for the habits that keep it navigable: every note has a `type`, names are plain and unique, nothing loose in the root, generated
 * reports are finished and fresh, and no note is an island. Each check is a plain function of a note's path and text, so it is cheap to test; reading the
 * notes is the only cost (once, off the UI thread).
 */
object VaultHygiene {
    /** A report older than this many days is stale; landscape and comparison reports age fastest. Anything else uses [DEFAULT_STALE_DAYS]. */
    val STALE_DAYS: Map<String, Int> = mapOf("landscape" to 90, "compare" to 90)
    const val DEFAULT_STALE_DAYS = 365

    private val GenericNames = Regex("""(index|notes?|untitled( \d+)?|readme|new note( \d+)?)""", RegexOption.IGNORE_CASE)
    private val SymbolStart = Regex("""^[^\p{L}\p{N}(\[]""")
    private val RootAllowed = setOf("home")
    private val Key = Regex("""^([A-Za-z][\w-]*):\s*(.*)$""")

    /** Reads every note of [vault] once and checks it. [index] (the Brain's) tells which notes are islands; it is built here when not given. */
    fun check(vault: Vault, today: LocalDate = LocalDate.now(), index: VaultIndex? = null): HygieneReport {
        val notes = vault.notes().map { it.path to runCatching { vault.read(it.path) }.getOrDefault("") }
        val orphans = (index ?: VaultIndex.build(vault)).orphans.map { it.path }.toSet()
        return checkNotes(notes, orphans, today)
    }

    /** The checks on notes already read: (vault path, text) pairs, and the paths that are islands. Hidden folders are not in [notes] to begin with. */
    fun checkNotes(notes: List<Pair<String, String>>, islands: Set<String> = emptySet(), today: LocalDate = LocalDate.now()): HygieneReport {
        val out = ArrayList<HygieneIssue>()
        val byName = notes.groupBy { plainKey(stem(it.first)) }
        for ((path, text) in notes) {
            val name = stem(path)
            val thought = name.startsWith("thought-")
            val props = properties(text)

            // 1. every note has a type (a thought file is a log and has none)
            if (!thought && props["type"].isNullOrBlank()) {
                out += HygieneIssue(HygieneKind.MissingType, path, "has no `type` property",
                    "add `type:` to its properties (index, reference, analysis, comparison, checklist, template, article, benchmark-prompt, guide, report or thought) and `tags:`")
            }
            // 2. plain names: nothing in front of the first letter
            if (SymbolStart.containsMatchIn(name)) {
                val clean = name.replace(Regex("""^[^\p{L}\p{N}(\[]+\s*"""), "")
                out += HygieneIssue(HygieneKind.SymbolName, path, "the name starts with a symbol or emoji (\"${name.take(2).trim()}\")",
                    "rename it to \"$clean\" and update the links to it")
            }
            // 3. no generic names
            if (GenericNames.matches(name)) {
                out += HygieneIssue(HygieneKind.GenericName, path, "the name \"$name\" is too generic",
                    "rename it after its subject, for example \"<Area> $name\", and update the links to it")
            }
            // 4. names are unique, ignoring case and symbols (Obsidian links by name)
            val same = byName[plainKey(name)].orEmpty().filter { it.first != path }
            if (same.isNotEmpty()) {
                out += HygieneIssue(HygieneKind.DuplicateName, path, "same name as ${same.joinToString(", ") { it.first }}",
                    "merge the notes (keep the newer, move the older to the trash) or give each a distinct name")
            }
            // 5. the root holds only Home; thought files and their index live in Thoughts/
            if ('/' !in path && name.lowercase() !in RootAllowed) {
                out += HygieneIssue(HygieneKind.LooseRoot, path, "sits in the vault root",
                    if (thought || name.equals("Thoughts Index", true)) "move it into the Thoughts folder" else "move it into the folder of its subject (Research/<Domain>/..., Projects/..., Explore/, Thoughts/)")
            }
            if (props["type"] == "report") {
                // 6. a report is finished when its status says done
                val status = props["status"].orEmpty()
                // a report that a newer one replaced (status superseded) is kept as history: it is neither unfinished nor stale
                val superseded = status == Reports.SUPERSEDED
                if (status != "done" && !superseded) {
                    out += HygieneIssue(HygieneKind.ReportNotDone, path, "report status is \"${status.ifEmpty { "missing" }}\"",
                        "finish it with its skill (the report check must say REPORT_VALID=yes, then set status: done) or delete it")
                }
                // 7. and fresh: landscape and comparison reports age fast
                val kind = props["kind"].orEmpty()
                val date = runCatching { LocalDate.parse(props["date"].orEmpty().take(10)) }.getOrNull()
                if (date != null && !superseded) {
                    val limit = STALE_DAYS[kind] ?: DEFAULT_STALE_DAYS
                    val age = ChronoUnit.DAYS.between(date, today)
                    if (age > limit) {
                        out += HygieneIssue(HygieneKind.StaleReport, path, "report from $date is $age days old (limit $limit for ${kind.ifEmpty { "this" }} reports)",
                            "refresh it: open the Library, switch to the Reports view (←/→), select it and press f; the new report replaces it and this one is marked superseded")
                    }
                }
            }
            // 8. every note is linked to something (a thought file is listed in the Thoughts Index)
            if (path in islands && !thought) {
                out += HygieneIssue(HygieneKind.Island, path, "no note links to it and it links to no note",
                    "link it from the index of its area (or from Home), or delete it if it is not needed")
            }
        }
        return HygieneReport(out.sortedWith(compareBy({ it.kind.ordinal }, { it.path.lowercase() })))
    }

    private fun stem(path: String) = path.substringAfterLast('/').removeSuffix(".md")

    /** A name for comparing: letters and digits only, lower case, so "🐍 Phi Reference" and "phi reference" are the same name. */
    private fun plainKey(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /** The simple `key: value` lines of a note's frontmatter (lists and nested values are not needed here). */
    internal fun properties(text: String): Map<String, String> {
        val (front, _) = Vault.splitFrontmatter(text)
        if (front.isEmpty()) return emptyMap()
        return front.lineSequence().drop(1).takeWhile { it.trim() != "---" }
            .mapNotNull { Key.find(it)?.destructured?.let { (k, v) -> k to v.trim().removeSurrounding("\"").removeSurrounding("'") } }
            .toMap()
    }
}
