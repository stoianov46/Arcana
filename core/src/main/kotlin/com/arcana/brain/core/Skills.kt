package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

/**
 * One skill: a runbook in the vault that a model follows step by step, with the helper files it needs. It lives in its own folder,
 * `<skills folder>/<name>/`, and its entry note has the folder's name and the double extension [Skills.ENTRY_SUFFIX]:
 * `<skills folder>/<name>/<name>.skill.md`. It stays a Markdown note (Obsidian only opens, links and shows properties of those), the
 * `.skill` part tells a skill from the documentation and scripts beside it, and the name is unique (not `SKILL.md`) so `[[links]]` and the graph work.
 *
 * @property path the entry note, relative to the vault
 * @property folder the skill's folder, relative to the vault
 * @property files everything else in the folder (notes, scripts, reports), relative to [folder]
 * @property problems what is wrong with it (empty when it is fine); a skill with problems is still listed so the user can fix it
 */
class Skill(
    val name: String,
    val description: String,
    val version: String,
    val path: String,
    val folder: String,
    val files: List<String>,
    val chars: Int,
    val problems: List<String>,
)

object Skills {
    const val DEFAULT_FOLDER = "Skills"

    /** A skill run takes many tool rounds (the forecast skill about 16), so a message with a skill attached gets at least this many. */
    const val RUN_STEPS = 40

    /** The tool rounds one question may take: [base] (the user's setting), raised to [RUN_STEPS] when the latest message of [history] carries a skill. */
    fun stepsFor(history: List<ChatMessage>, base: Int): Int =
        if (history.lastOrNull { it.role == "user" }?.noteNames?.any { isEntry(it) } == true) maxOf(base, RUN_STEPS) else base

    /** What the entry note's file name ends with: `coingecko-forecast.skill.md`. */
    const val ENTRY_SUFFIX = ".skill.md"

    /** `read_note` and `use_skill` cut a text at 20,000 characters, so an entry note must stay clearly below it. */
    const val MAX_CHARS = 18_000

    /** What the model gets when it loads a skill at most; the same cut as `read_note`. */
    const val LOAD_LIMIT = 20_000

    private const val FILE_LIMIT = 60
    private const val PER_FOLDER = 8
    private val nameRule = Regex("[a-z0-9][a-z0-9-]*")

    /** Every skill folder below [folder]; the folders without an entry note of the same name are not skills and are skipped. */
    fun discover(vault: Vault, folder: String): List<Skill> {
        val base = folder.trim('/').ifBlank { return emptyList() }
        val dir = vault.root.resolve(base).normalize()
        if (!dir.startsWith(vault.root) || !Files.isDirectory(dir)) return emptyList()
        val subs = try { Files.newDirectoryStream(dir).use { s -> s.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.sortedBy { it.fileName.toString() } } } catch (e: Exception) { return emptyList() }
        return subs.mapNotNull { sub ->
            val dirName = sub.fileName.toString()
            val entry = sub.resolve("$dirName$ENTRY_SUFFIX")
            if (!Files.isRegularFile(entry)) return@mapNotNull null
            val text = runCatching { Files.readString(entry) }.getOrDefault("")
            val meta = frontmatter(text)
            val problems = ArrayList<String>()
            val name = meta["name"]?.takeIf { it.isNotBlank() } ?: dirName
            if (name != dirName) problems += "name \"$name\" differs from the folder name \"$dirName\""
            if (!nameRule.matches(dirName)) problems += "the folder name should be lower-case words joined by - (for example coingecko-forecast)"
            val description = meta["description"].orEmpty().trim()
            if (description.isEmpty()) problems += "no description: add `description:` to the properties so the model knows when to use it"
            parseRequirements(text).second.forEach { problems += "requires: cannot read the line \"$it\" (write: tool ~ text in the call => text in the result x3 | label)" }
            if (text.length > MAX_CHARS) problems += "the entry note has ${text.length} characters; keep it under $MAX_CHARS or the model will not read the end (move detail into a reference/ note)"
            Skill(
                name = dirName, description = description, version = meta["version"].orEmpty().trim(),
                path = entry.relativeTo(vault.root).toString().replace('\\', '/'), folder = sub.relativeTo(vault.root).toString().replace('\\', '/'),
                files = filesOf(sub, dirName), chars = text.length, problems = problems,
            )
        }
    }

    /** True when the note at [path] (vault-relative) is a skill's entry note. */
    fun isEntry(path: String): Boolean = path.endsWith(ENTRY_SUFFIX)

    /** The skill name of an entry note path (`Skills/x/x.skill.md` → `x`). */
    fun nameOfEntry(path: String): String = path.substringAfterLast('/').removeSuffix(ENTRY_SUFFIX)

    /** What an attached skill looks like in the message field and in the conversation. */
    fun chipLabel(path: String): String? = if (isEntry(path)) "◆ skill ${nameOfEntry(path)}" else null

    /** The loaded text of the skill whose entry note is [path] (what `use_skill` returns), or null when [path] is not a skill entry or cannot be read. */
    fun loadEntry(vault: Vault, path: String): String? {
        if (!isEntry(path)) return null
        val skillFolder = path.substringBeforeLast('/', "")
        val skills = discover(vault, skillFolder.substringBeforeLast('/', ""))
        return skills.firstOrNull { it.path == path }?.let { runCatching { load(vault, it) }.getOrNull() }
    }

    /**
     * The steps a skill says must happen before a run may end (`requires:` in its properties, one quoted `- "…"` item per step) and the
     * lines that could not be read. An item is `tool`, then optionally `~ text` (the call's arguments must contain it), `=> text` (the
     * result must contain it), `x3` (it must happen 3 times) and `| label` (what to tell the model when it is missing), for example
     * `run_command ~ check => REPORT_COMPLETE=yes | step 11: the check must say yes`.
     */
    fun parseRequirements(entryText: String): Pair<List<Requirement>, List<String>> {
        val block = Vault.splitFrontmatter(entryText).first
        if (block.isEmpty()) return emptyList<Requirement>() to emptyList()
        val lines = Vault.parseProperties(Vault.propertyText(block))["requires"].orEmpty().drop(1)
        val ok = ArrayList<Requirement>()
        val bad = ArrayList<String>()
        for (raw in lines) {
            val item = raw.trim().removePrefix("-").trim().removeSurrounding("\"").removeSurrounding("'").trim()
            if (item.isEmpty()) continue
            Requirement.parse(item)?.let { ok += it } ?: run { bad += item }
        }
        return ok to bad
    }

    /** The required steps of the skill whose entry note is [path] (empty when it declares none or is not a skill). */
    fun requirementsOf(vault: Vault, path: String): List<Requirement> =
        if (!isEntry(path)) emptyList() else runCatching { parseRequirements(Files.readString(vault.root.resolve(path))).first }.getOrDefault(emptyList())

    /** A skill by name (case-insensitive, with or without a leading slash). */
    fun find(skills: List<Skill>, name: String): Skill? = name.trim().removePrefix("/").lowercase().let { n -> skills.firstOrNull { it.name == n } }

    /** The lines for the system prompt: one per skill with its description, so the model can pick the right one. */
    fun catalog(skills: List<Skill>): String = skills.joinToString("\n") { "- ${it.name}: ${it.description.ifEmpty { "(no description)" }.replace('\n', ' ').take(240)}" }

    /** What `use_skill` returns: where the skill lives, its files, and the entry note's text (without the properties block). */
    fun load(vault: Vault, skill: Skill): String {
        val body = Vault.splitFrontmatter(Files.readString(vault.root.resolve(skill.path))).second.trim()
        val abs = vault.root.resolve(skill.folder)
        val files = if (skill.files.isEmpty()) "(none)" else skill.files.joinToString("\n") { "- ${skill.folder}/$it" }
        val head = "SKILL ${skill.name}${if (skill.version.isEmpty()) "" else " v${skill.version}"}\nFolder: $abs\nFiles in the skill (read a note with read_note, run a script with run_command using its full path):\n$files\n\n--- The skill's instructions: follow them step by step ---\n"
        val text = head + body
        return if (text.length <= LOAD_LIMIT) text else text.take(LOAD_LIMIT) + "\n… (cut at $LOAD_LIMIT characters; the rest of the skill is in ${skill.path})"
    }

    /** A one-line summary for lists and toasts. */
    fun summary(skill: Skill): String = "${skill.name}${if (skill.version.isEmpty()) "" else " v${skill.version}"}${if (skill.problems.isEmpty()) "" else " (needs attention: ${skill.problems.first()})"}"

    /** `key: value` lines of the properties block; quotes around a value are removed. Lists and nested values are kept as written. */
    internal fun frontmatter(text: String): Map<String, String> {
        val block = Vault.splitFrontmatter(text).first
        if (block.isEmpty()) return emptyMap()
        return block.lineSequence().drop(1).takeWhile { it.trim() != "---" }
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0 || line.first().isWhitespace()) null else line.substring(0, i).trim() to line.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            }.toMap()
    }

    /** The files of a skill's folder, at most [PER_FOLDER] per subfolder (a `reports/` folder only grows) and [FILE_LIMIT] in all. */
    private fun filesOf(dir: Path, entryName: String): List<String> {
        val all = ArrayList<String>()
        try {
            Files.walk(dir, 4).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().let { n -> !n.startsWith(".") && n != "$entryName$ENTRY_SUFFIX" } && dir.relativize(it).none { p -> p.toString().startsWith(".") } }
                    .sorted().limit(500).forEach { all += dir.relativize(it).toString().replace('\\', '/') }
            }
        } catch (e: Exception) { /* an unreadable folder just shows fewer files */ }
        val out = ArrayList<String>()
        for ((group, names) in all.groupBy { if ('/' in it) it.substringBefore('/') + "/" else "" }) {
            out += names.take(PER_FOLDER)
            if (names.size > PER_FOLDER) out += "$group… ${names.size - PER_FOLDER} more (list the folder to see them)"
        }
        return out.take(FILE_LIMIT)
    }
}

/** One step a skill requires: a call of [tool] whose arguments contain [contains] and whose result contains [expect] (both optional), [count] times. */
class Requirement(val tool: String, val contains: String, val expect: String, val count: Int, val label: String) {
    /** What to tell the model when the step is missing. */
    fun describe(): String {
        val call = buildString {
            append("a call of `$tool`")
            if (contains.isNotEmpty()) append(if (tool == "run_command") " whose command contains `$contains`" else " whose arguments contain `$contains`")
            if (count > 1) append(" ($count times)")
            if (expect.isNotEmpty()) append(" whose result contains `$expect` (if it does not, do what the result says and run it again)")
        }
        return if (label.isEmpty()) call else "$label: $call"
    }

    companion object {
        private val TOOL = Regex("[a-z_]+")

        /** `tool [~ text] [=> text] [x3] [| label]`, or null when the line cannot be read. */
        fun parse(item: String): Requirement? {
            val label = item.substringAfter('|', "").trim()
            var left = item.substringBefore('|').trim()
            val expect = if ("=>" in left) left.substringAfter("=>").trim() else ""
            left = left.substringBefore("=>").trim()
            var count = 1
            Regex("""\s+x(\d+)$""").find(left)?.let { count = it.groupValues[1].toInt(); left = left.substring(0, it.range.first).trim() }
            val tool = left.substringBefore('~').trim()
            val contains = if ('~' in left) left.substringAfter('~').trim() else ""
            if (!TOOL.matches(tool) || count < 1 || ('~' in left && contains.isEmpty()) || ("=>" in item.substringBefore('|') && expect.isEmpty())) return null
            return Requirement(tool, contains, expect, count, label)
        }
    }
}

/**
 * The progress of one skill run: Arcana tells it every tool call and result ([observe]); when the model tries to finish with a step still
 * missing, [nudge] gives the hidden message that sends it back (the first missing step, in the skill's order). A step the model ignores
 * [MAX_STRIKES] times is given up on ("skipped") so a stuck run still ends, and [summary] says what was left undone.
 */
class SkillRun(val name: String, val requirements: List<Requirement>) {
    private val counts = IntArray(requirements.size)
    private val strikes = IntArray(requirements.size)
    private val skipped = BooleanArray(requirements.size)

    val total: Int get() = requirements.size
    val done: Int get() = requirements.indices.count { satisfied(it) }
    val skippedCount: Int get() = skipped.count { it }

    /** Every step is done or given up on: the run may end. */
    val finished: Boolean get() = requirements.indices.all { satisfied(it) || skipped[it] }

    private fun satisfied(i: Int) = counts[i] >= requirements[i].count

    /** A tool call (its arguments as JSON text) and its result: counts for every step it matches. */
    fun observe(tool: String, arguments: String, result: String) {
        for ((i, r) in requirements.withIndex()) {
            if (satisfied(i) || tool != r.tool) continue
            if (r.contains.isNotEmpty() && !arguments.contains(r.contains, ignoreCase = true)) continue
            if (r.expect.isNotEmpty() && !result.contains(r.expect)) continue
            counts[i]++
        }
    }

    /** The message to send instead of letting the model finish, or null when the run may end. Each call counts one strike against the step it names. */
    fun nudge(): String? {
        val i = requirements.indices.firstOrNull { !satisfied(it) && !skipped[it] } ?: return null
        strikes[i]++
        if (strikes[i] > MAX_STRIKES) {
            skipped[i] = true
            return nudge()
        }
        return "STOP: you have not finished the skill \"$name\" ($done of $total required steps are done). This required step is still missing: ${requirements[i].describe()}. " +
            "Do it NOW with a tool call. Do not write a final answer, do not ask the user, and do not skip it."
    }

    /** What was left undone, for the user; null when everything was done. */
    fun summary(): String? {
        val left = requirements.indices.filter { !satisfied(it) }
        if (left.isEmpty()) return null
        return "Skill $name: $done of $total required steps done; not done: ${left.joinToString("; ") { requirements[it].label.ifEmpty { requirements[it].tool } }}."
    }

    companion object {
        /** How many times the model may ignore the same missing step before Arcana gives up on it. */
        const val MAX_STRIKES = 3
    }
}
