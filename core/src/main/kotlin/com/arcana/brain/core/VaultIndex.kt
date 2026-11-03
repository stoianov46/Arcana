package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What Arcana knows about one note: its size, tags and where it links to (as vault paths of notes that exist). */
data class NoteInfo(
    val path: String,
    val modified: Long,
    val words: Int,
    val tags: Set<String>,
    val links: Set<String>,
    /** Link targets that match no note. */
    val unresolved: Set<String>,
) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".md")
    val folder: String get() = path.substringBeforeLast('/', "")
}

/**
 * A map of the whole vault: every note with its tags and links, and from them the things a second brain needs to know about
 * itself: what links to what, which notes are islands, which have no tags, which have not been touched in a long time.
 */
class VaultIndex(val notes: List<NoteInfo>) {
    private val byPath = notes.associateBy { it.path }

    /** For each note, the notes that link to it. */
    val backlinks: Map<String, List<NoteInfo>> = buildMap<String, MutableList<NoteInfo>> {
        for (n in notes) for (target in n.links) if (target != n.path) getOrPut(target) { mutableListOf() } += n
    }

    fun note(path: String): NoteInfo? = byPath[path]

    fun backlinksOf(path: String): List<NoteInfo> = backlinks[path].orEmpty().sortedBy { it.title.lowercase() }

    fun linksOf(path: String): List<NoteInfo> = byPath[path]?.links.orEmpty().mapNotNull { byPath[it] }.sortedBy { it.title.lowercase() }

    /** Notes with no links in and none out: islands that are not connected to anything. */
    val orphans: List<NoteInfo> get() = notes.filter { it.links.none { l -> l != it.path } && backlinks[it.path].isNullOrEmpty() }

    val untagged: List<NoteInfo> get() = notes.filter { it.tags.isEmpty() }

    /** Notes not touched for [days] days. */
    fun stale(days: Int, now: Long = System.currentTimeMillis()): List<NoteInfo> =
        notes.filter { (now - it.modified) > days * 86_400_000L }

    fun inFolder(folder: String): List<NoteInfo> {
        val prefix = folder.trim('/').let { if (it.isEmpty()) return emptyList() else "$it/" }
        return notes.filter { it.path.startsWith(prefix, ignoreCase = true) }
    }

    /** Every tag with the number of notes that carry it, the most used first. */
    val tagCounts: List<Pair<String, Int>>
        get() = notes.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key to it.value }

    val totalWords: Long get() = notes.sumOf { it.words.toLong() }
    val totalLinks: Int get() = notes.sumOf { it.links.size }
    val deadLinks: Int get() = notes.sumOf { it.unresolved.size }

    /** Notes created or changed in the last [days] days. */
    fun touchedSince(days: Int, now: Long = System.currentTimeMillis()): List<NoteInfo> =
        notes.filter { (now - it.modified) <= days * 86_400_000L }.sortedByDescending { it.modified }

    /** Notes that share tags or links with [path], the closest first: where to look for things that belong together. */
    fun related(path: String, limit: Int = 8): List<NoteInfo> {
        val me = byPath[path] ?: return emptyList()
        val neighbours = me.links + backlinks[path].orEmpty().map { it.path }
        return notes.asSequence().filter { it.path != path }.map { other ->
            val sharedTags = other.tags.intersect(me.tags).size
            val sharedLinks = (other.links + backlinks[other.path].orEmpty().map { it.path }).intersect(neighbours).size
            val direct = if (other.path in neighbours) 3 else 0
            other to (sharedTags * 2 + sharedLinks * 2 + direct)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }.toList()
    }

    /** The vault's vital signs and its to-do list: what is waiting, disconnected, untagged, broken or forgotten. */
    fun health(inboxFolder: String, staleDays: Int, now: Long = System.currentTimeMillis()): BrainHealth {
        val n = notes.size
        fun percent(part: Int) = if (n == 0) 100 else ((n - part) * 100.0 / n).toInt()
        val orphanCount = orphans.size
        val untaggedCount = untagged.size
        return BrainHealth(
            notes = n,
            words = totalWords,
            links = totalLinks,
            tags = tagCounts.size,
            inbox = inFolder(inboxFolder).size,
            orphans = orphanCount,
            untagged = untaggedCount,
            brokenNotes = notes.count { it.unresolved.isNotEmpty() },
            brokenLinks = deadLinks,
            stale = stale(staleDays, now).size,
            changedThisWeek = touchedSince(7, now).size,
            connectedPercent = percent(orphanCount),
            taggedPercent = percent(untaggedCount),
        )
    }

    companion object {
        private val Wikilink = Regex("""!?\[\[([^\]\n]+?)]]""")
        private val InlineTag = Regex("""(?:^|[\s(])#([\p{L}\p{N}_][\p{L}\p{N}_/-]*)""")
        private val FencedCode = Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL)
        private val InlineCode = Regex("""`[^`\n]*`""")
        /** A file name's end: a dot and one to five letters or digits, no space (`photo.png`, but not the `.6 35B-A3B…` inside `Qwen3.6 35B-A3B…`). */
        private val FileExtension = Regex("""\.[A-Za-z0-9]{1,5}$""")

        /** Scans the vault once. Reading every note is the cost; the result is a plain value that screens can share. */
        fun build(vault: Vault): VaultIndex {
            val files = vault.notes()
            val texts = files.associate { it.path to (runCatching { vault.read(it.path) }.getOrDefault("")) }
            val byTitle = files.groupBy { it.title.lowercase() }
            val byPathLower = files.associateBy { it.path.removeSuffix(".md").lowercase() }
            fun resolve(raw: String): String? {
                var target = raw.substringBefore('|').substringBefore('#').trim()
                if (target.isEmpty()) return null
                target = target.removeSuffix(".md").removeSuffix(".MD").trim('/')
                byPathLower[target.lowercase()]?.let { return it.path }
                byTitle[target.substringAfterLast('/').lowercase()]?.minByOrNull { it.path.length }?.let { return it.path }
                // no note of that name: `[[photo.png]]` is a picture or file (a dot and a short extension without spaces), `[[Missing note]]` is a broken link
                return if (FileExtension.containsMatchIn(target)) "" else null
            }
            return VaultIndex(
                files.map { f ->
                    val text = texts.getValue(f.path)
                    val (front, body) = Vault.splitFrontmatter(text)
                    // links and tags inside code do not count, as in Obsidian
                    val clean = body.replace(FencedCode, " ").replace(InlineCode, " ")
                    val links = mutableSetOf<String>()
                    val unresolved = mutableSetOf<String>()
                    for (m in Wikilink.findAll(clean)) {
                        when (val r = resolve(m.groupValues[1])) {
                            null -> unresolved += m.groupValues[1].substringBefore('|').substringBefore('#').trim()
                            "" -> Unit
                            else -> links += r
                        }
                    }
                    val tags = frontmatterTags(front) + InlineTag.findAll(clean).map { it.groupValues[1] }.filter { t -> t.any { c -> !c.isDigit() } }
                    NoteInfo(f.path, f.modified, body.split(Regex("\\s+")).count { it.isNotBlank() }, tags.toSet(), links, unresolved)
                },
            )
        }

        /** A frontmatter line that starts the tags: `tags:` or `tag:`, in any case. */
        fun isTagKey(line: String): Boolean = TagKey.containsMatchIn(line)
        private val TagKey = Regex("""^\s*tags?\s*:""", RegexOption.IGNORE_CASE)

        /** A tag as Obsidian spells it: no `#`, no quotes or `[[ ]]`, no spaces (they become `-`). */
        fun cleanTag(raw: String): String = raw.trim().trim('"', '\'').removePrefix("[[").removeSuffix("]]").trim().removePrefix("#").trim().replace(Regex("\\s+"), "-")

        /**
         * The tags listed in a frontmatter block, however they are written: `tags: [a, b]`, `tags: a, b`, `tags: a b`, `tags: #a #b`, a `- a`
         * list, and the same under `tag:` or `Tags:`. Several such keys all count.
         */
        fun frontmatterTags(front: String): List<String> {
            val lines = front.lines()
            val out = ArrayList<String>()
            lines.forEachIndexed { i, line ->
                if (!isTagKey(line)) return@forEachIndexed
                val inline = line.substringAfter(':').trim().trim('[', ']')
                if (inline.isNotEmpty()) out += inline.replace("[[", "").replace("]]", "").split(Regex("[,\\s]+"))
                else out += lines.drop(i + 1).takeWhile { it.trimStart().startsWith("-") && it.trim() != "---" }.map { it.trimStart().removePrefix("-") }
            }
            return out.map { cleanTag(it) }.filter { it.isNotEmpty() }
        }
    }
}

/** Where today's daily note lives: Obsidian's own Daily notes settings when the vault has them, else defaults. */
data class DailyNotes(val folder: String, val format: String) {
    /** The vault path of the daily note for [date]. */
    fun pathFor(date: LocalDate): String {
        val name = date.format(DateTimeFormatter.ofPattern(javaPattern(format)))
        return (if (folder.isBlank()) "" else folder.trim('/') + "/") + "$name.md"
    }

    companion object {
        /** Reads `.obsidian/daily-notes.json` (the Daily notes core plugin); falls back to [folder] and [format] when it is missing. */
        fun detect(vault: Vault, folder: String = "", format: String = "YYYY-MM-DD"): DailyNotes {
            val file = vault.root.resolve(".obsidian/daily-notes.json")
            val text = if (Files.isRegularFile(file)) runCatching { Files.readString(file) }.getOrNull() else null
            fun field(name: String) = text?.let { Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }
            return DailyNotes(field("folder") ?: folder, field("format") ?: format)
        }

        /** Moment.js date patterns (what Obsidian uses) as Java ones: `YYYY-MM-DD` becomes `yyyy-MM-dd`. */
        fun javaPattern(moment: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < moment.length) {
                val rest = moment.substring(i)
                when {
                    rest.startsWith("[") && rest.contains(']') -> {
                        val end = rest.indexOf(']')
                        out.append('\'').append(rest.substring(1, end)).append('\'')
                        i += end + 1
                    }
                    rest.startsWith("YYYY") -> { out.append("yyyy"); i += 4 }
                    rest.startsWith("YY") -> { out.append("yy"); i += 2 }
                    rest.startsWith("dddd") -> { out.append("EEEE"); i += 4 }
                    rest.startsWith("ddd") -> { out.append("EEE"); i += 3 }
                    rest.startsWith("DD") -> { out.append("dd"); i += 2 }
                    rest.startsWith("D") -> { out.append("d"); i += 1 }
                    rest.first().isLetter() && rest.first() !in "MHhmsaAEyd" -> { out.append('\'').append(rest.first()).append('\''); i += 1 }
                    else -> { out.append(rest.first()); i += 1 }
                }
            }
            return out.toString()
        }
    }
}

/** Instant capture: a thought goes to the end of today's daily note, stamped with the time, and the note is created if needed. */
fun Vault.capture(text: String, daily: DailyNotes, now: LocalDateTime = LocalDateTime.now()): String {
    val path = daily.pathFor(now.toLocalDate())
    val line = "- ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} ${text.trim()}"
    val file = root.resolve(path)
    if (!Files.exists(file)) {
        Files.createDirectories(file.parent)
        Files.writeString(file, line + "\n")
        listThoughtDay(file)
    } else {
        val existing = Files.readString(file)
        Files.writeString(file, existing + (if (existing.endsWith("\n") || existing.isEmpty()) "" else "\n") + line + "\n")
    }
    return path
}

/** Where the days of the daily notes are listed: a note next to them, so no day is left without a link to it (it would be an island in the graph). */
const val THOUGHTS_INDEX = "Thoughts Index"

/** Lists the thought note at vault path [note] (a thought saved as a note of its own in the thoughts folder) in the Thoughts Index beside it. Best effort. */
fun Vault.listThought(note: String) = listThoughtDay(root.resolve(note))

/** Adds the new daily note [file] to the index note beside it as `- [[name]]`, creating the index when it is missing; a day already listed is not added twice. Best effort: a failure never loses the thought. */
private fun listThoughtDay(file: java.nio.file.Path) {
    runCatching {
        val name = file.fileName.toString().removeSuffix(".md")
        val index = file.resolveSibling("$THOUGHTS_INDEX.md")
        val link = "- [[$name]]"
        if (!Files.exists(index)) {
            Files.writeString(index, "---\ntags: [index, thoughts]\ntype: index\n---\n\n# $THOUGHTS_INDEX\n\nEverything Capture wrote: one note per day (a line per thought) and thoughts saved as notes of their own. Arcana adds each here when it starts it.\n\n$link\n\nBack to [[Home]]\n")
            return@runCatching
        }
        val text = Files.readString(index)
        if (link in text) return@runCatching
        // new days go above the "Back to" line, in order of time
        val at = text.lastIndexOf("\nBack to ").takeIf { it >= 0 } ?: text.length
        Files.writeString(index, text.substring(0, at).trimEnd('\n') + "\n$link\n" + text.substring(at))
    }
}

/** How the vault is doing, in numbers. */
data class BrainHealth(
    val notes: Int,
    val words: Long,
    val links: Int,
    val tags: Int,
    /** Notes waiting in the inbox folder. */
    val inbox: Int,
    /** Notes with no links in or out. */
    val orphans: Int,
    val untagged: Int,
    /** Notes with at least one link that points at nothing, and how many such links there are. */
    val brokenNotes: Int,
    val brokenLinks: Int,
    val stale: Int,
    val changedThisWeek: Int,
    /** Share of notes that are connected to something, and that carry a tag (100 for an empty vault). */
    val connectedPercent: Int,
    val taggedPercent: Int,
)
