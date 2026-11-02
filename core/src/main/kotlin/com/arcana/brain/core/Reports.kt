package com.arcana.brain.core

import java.nio.file.Files
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One report written by a research skill: a note whose properties say `type: report` (see the vault note `Report Standard`).
 * Every field is read from the properties and may be empty; nothing is guessed.
 *
 * @property path vault-relative path of the note
 * @property title the file name without `.md`
 * @property topic which skill's output it is (`dev-research`, `llm-compare`…)
 * @property kind the report kind (`digest`, `landscape`, `compare`, `pool`, `forecast`, `research`)
 * @property status `done` or `incomplete` (empty when the note does not say)
 * @property date `YYYY-MM-DD` when the report was made, as written ("" when missing)
 * @property summary the one-sentence summary property
 * @property skill the skill's name taken from the `skill:` property (`[[create-dev-research-report.skill]]` gives `create-dev-research-report`)
 * @property question what was asked (`question`, else `pair`), to run the skill again
 * @property ageDays days between [date] and the day of the scan; null when the date is missing or not a date
 */
data class ReportInfo(
    val path: String,
    val title: String,
    val topic: String,
    val kind: String,
    val status: String,
    val date: String,
    val year: String,
    val summary: String,
    val skill: String,
    val question: String,
    val ageDays: Long?,
)

/** Finds the reports in a vault and says which have aged. Pure reading: it never writes. */
object Reports {
    /** After how many days a report of a kind counts as stale: what is written about changes fast for these kinds (new models appear every month). */
    val STALE_AFTER_DAYS: Map<String, Int> = mapOf("landscape" to 90, "compare" to 90)

    /** The limit for every other kind (a digest, a research report, a pool or forecast snapshot is looked up long after, but a year is the longest it is trusted). */
    const val DEFAULT_STALE_DAYS = 365

    /** How much of a note is read to find its properties: they sit at the top. */
    private const val HEAD_CHARS = 6000

    /** Every note of [vault] (hidden folders are not part of it) with `type: report`, newest date first, then by title. */
    fun scan(vault: Vault, today: LocalDate = LocalDate.now()): List<ReportInfo> =
        vault.notes().mapNotNull { note -> runCatching { info(vault, note.path, today) }.getOrNull() }
            .sortedWith(compareByDescending<ReportInfo> { it.date }.thenBy { it.title.lowercase() })

    /** The report at [path], or null when it is not one (no properties, or `type` is not `report`). */
    internal fun info(vault: Vault, path: String, today: LocalDate): ReportInfo? {
        val head = Files.newBufferedReader(vault.root.resolve(path)).use { r ->
            val buffer = CharArray(HEAD_CHARS)
            val n = r.read(buffer)
            if (n <= 0) "" else String(buffer, 0, n)
        }
        if (!head.startsWith("---\n")) return null
        val block = Vault.splitFrontmatter(head).first // empty when there is no closing line within the part read
        if (block.isEmpty()) return null
        val props = Vault.parseProperties(Vault.propertyText(block))
        fun value(key: String): String = props[key]?.firstOrNull()?.substringAfter(':', "")?.trim()?.trim('"', '\'')?.trim().orEmpty()
        if (value("type") != "report") return null
        val date = value("date")
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date.take(10)), today) }.getOrNull()
        return ReportInfo(
            path = path,
            title = path.substringAfterLast('/').removeSuffix(".md"),
            topic = value("topic"),
            kind = value("kind"),
            status = value("status"),
            date = date.take(10),
            year = value("year").ifEmpty { date.take(4) },
            summary = value("summary"),
            skill = value("skill").removePrefix("[[").removeSuffix("]]").substringBefore('|').removeSuffix(".skill").trim(),
            question = value("question").ifEmpty { value("pair") },
            ageDays = age,
        )
    }

    /** True when the report is older than its kind's limit ([STALE_AFTER_DAYS], else [DEFAULT_STALE_DAYS]). A report without a readable date is never stale. */
    fun isStale(info: ReportInfo): Boolean {
        val age = info.ageDays ?: return false
        return age > (STALE_AFTER_DAYS[info.kind] ?: DEFAULT_STALE_DAYS)
    }

    /** True when the report is not finished: its status is `incomplete` (or the note does not say `done`). */
    fun isIncomplete(info: ReportInfo): Boolean = info.status != "done"

    /** `Research/LLM/Reports/x.md` gives `LLM`; a report anywhere else gives its folder's name, and one in the vault root `(vault)`. */
    fun domainOf(path: String): String {
        val parts = path.split('/').dropLast(1)
        val at = parts.indexOf("Reports")
        return when {
            at >= 1 -> parts[at - 1]
            parts.isNotEmpty() -> parts.last()
            else -> "(vault)"
        }
    }

    /** The reports grouped by domain (see [domainOf]), domains A to Z, each group keeping the order given. */
    fun groupByDomain(reports: List<ReportInfo>): Map<String, List<ReportInfo>> =
        reports.groupBy { domainOf(it.path) }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
}
