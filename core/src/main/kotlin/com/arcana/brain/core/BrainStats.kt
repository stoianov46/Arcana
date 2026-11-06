package com.arcana.brain.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How many notes changed on one day. */
data class DayPoint(val day: LocalDate, val changed: Int)

/** How many notes changed in the week that starts on [weekStart] (a Monday). */
data class WeekPoint(val weekStart: LocalDate, val changed: Int)

/** One top-level folder of the vault: how many notes and words it holds. */
data class FolderShare(val folder: String, val notes: Int, val words: Long)

/** Where the items of one kind stand. [oldestOpenDays] is the age of the oldest item nobody has started (null when none). */
data class KindPipeline(val kind: WorkKind, val open: Int, val inProgress: Int, val done: Int, val abandoned: Int, val oldestOpenDays: Long?) {
    val total: Int get() = open + inProgress + done + abandoned
    val donePercent: Int get() = if (total - abandoned <= 0) 0 else done * 100 / (total - abandoned)
}

/** The generated reports of the vault. */
data class ReportStats(val total: Int, val done: Int, val notDone: Int, val averageTokens: Long?) {
    val donePercent: Int get() = if (total == 0) 100 else done * 100 / total
}

/** Where a bottleneck sends you when you act on it. */
sealed interface BottleneckAction {
    data class Hygiene(val kind: HygieneKind?) : BottleneckAction
    data class Library(val filter: String) : BottleneckAction
    data object Work : BottleneckAction
    data object Inbox : BottleneckAction
    data object None : BottleneckAction
}

/**
 * Something that holds the brain back, with how much ([score], 0..100: the higher the sooner it deserves attention), what it is and what to do.
 * [good] marks a thing that is fine, shown as a tick so a clean area is visible too.
 */
data class Bottleneck(val key: String, val score: Int, val title: String, val detail: String, val action: BottleneckAction, val good: Boolean = false)

/** Everything the Brain tabs draw, computed once from the vault, its reports, the work log and the usage records. */
data class BrainSnapshot(
    val weeks: Int,
    val days: List<DayPoint>,
    val weekly: List<WeekPoint>,
    val tokensByWeek: List<Long>,
    val health: BrainHealth,
    val reports: ReportStats,
    val folders: List<FolderShare>,
    val pipeline: List<KindPipeline>,
    val bottlenecks: List<Bottleneck>,
    /** The most linked-to notes: the hubs of the vault. */
    val hubs: List<Pair<String, Int>>,
    val busiestDay: DayPoint?,
    val changedInWindow: Int,
    /** The last two weeks against the average of the weeks before: 0.5 is half as active; null with fewer than 4 weeks of history. */
    val activityTrend: Double?,
)

/**
 * The numbers behind the Brain tabs (proposals: the Brain redesign). A pure function of what it is given, so it is tested without a vault: [notes] from the
 * vault index, [reports] and [work] (with [doneNotes], the titles of finished reports and answers), [usage] and the [hygiene] check, and [today]. Nothing here
 * reads a file or a clock.
 */
object BrainStats {
    private val zone: ZoneId = ZoneId.systemDefault()

    fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun monday(day: LocalDate): LocalDate = day.with(DayOfWeek.MONDAY)

    fun compute(
        weeks: Int,
        notes: List<NoteInfo>,
        health: BrainHealth,
        reports: List<ReportInfo>,
        work: List<WorkItem>,
        doneNotes: Set<String>,
        usage: List<UsageRecord>,
        hygiene: HygieneReport?,
        inboxFolder: String,
        today: LocalDate,
    ): BrainSnapshot {
        val span = weeks.coerceIn(1, 52)
        val firstMonday = monday(today).minusWeeks((span - 1).toLong())
        val changedPerDay = notes.groupingBy { dayOf(it.modified) }.eachCount()
        val days = generateSequence(firstMonday) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { DayPoint(it, changedPerDay[it] ?: 0) }.toList()
        val weekly = (0 until span).map { w ->
            val start = firstMonday.plusWeeks(w.toLong())
            WeekPoint(start, days.filter { it.day >= start && it.day < start.plusWeeks(1) }.sumOf { it.changed })
        }
        val tokensByWeek = (0 until span).map { w ->
            val start = firstMonday.plusWeeks(w.toLong())
            usage.filter { dayOf(it.time).let { d -> d >= start && d < start.plusWeeks(1) } }.sumOf { it.totalTokens.toLong() }
        }
        val folders = notes.groupBy { it.path.substringBefore('/', "(vault root)").ifEmpty { "(vault root)" } }
            .map { (f, ns) -> FolderShare(f, ns.size, ns.sumOf { it.words.toLong() }) }
            .sortedWith(compareByDescending<FolderShare> { it.notes }.thenBy { it.folder.lowercase() })
        val reportStats = ReportStats(
            total = reports.size,
            done = reports.count { it.status.equals("done", ignoreCase = true) },
            notDone = reports.count { !it.status.equals("done", ignoreCase = true) },
            averageTokens = reports.mapNotNull { it.tokens }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        )
        val pipeline = listOf(WorkKind.Question, WorkKind.Research, WorkKind.Todo).map { kind -> pipelineOf(kind, work, doneNotes, today) }
        val hubs = notes.map { n -> n.path to notes.count { m -> n.path in m.links && m.path != n.path } }.filter { it.second > 0 }
            .sortedWith(compareBy({ -it.second }, { it.first })).take(5)
        val recent = weekly.takeLast(2).sumOf { it.changed }
        val before = weekly.dropLast(2)
        val trend: Double? = if (weekly.size < 4) null else before.map { it.changed }.average().let { avg -> if (avg <= 0.0) null else recent / 2.0 / avg }
        return BrainSnapshot(
            weeks = span, days = days, weekly = weekly, tokensByWeek = tokensByWeek, health = health, reports = reportStats, folders = folders,
            pipeline = pipeline, hubs = hubs, busiestDay = days.maxByOrNull { it.changed }?.takeIf { it.changed > 0 },
            changedInWindow = days.sumOf { it.changed }, activityTrend = trend,
            bottlenecks = bottlenecks(health, reportStats, pipeline, hygiene, trend, inboxFolder),
        )
    }

    private fun pipelineOf(kind: WorkKind, work: List<WorkItem>, doneNotes: Set<String>, today: LocalDate): KindPipeline {
        val of = work.filter { it.kind == kind }
        val states = of.map { it to it.state(doneNotes) }
        val open = states.filter { it.second == WorkState.Open }
        return KindPipeline(
            kind = kind,
            open = open.size,
            inProgress = states.count { it.second == WorkState.InProgress },
            done = states.count { it.second == WorkState.Answered },
            abandoned = states.count { it.second == WorkState.Abandoned },
            oldestOpenDays = open.minOfOrNull { it.first.capturedAt }?.let { java.time.temporal.ChronoUnit.DAYS.between(dayOf(it), today).coerceAtLeast(0) },
        )
    }

    /** The bottlenecks, the most pressing first; a clean area becomes a `good` entry so it shows as done. */
    internal fun bottlenecks(health: BrainHealth, reports: ReportStats, pipeline: List<KindPipeline>, hygiene: HygieneReport?, trend: Double?, inboxFolder: String): List<Bottleneck> {
        val out = ArrayList<Bottleneck>()
        fun add(key: String, count: Int, score: Int, title: String, detail: String, action: BottleneckAction) {
            if (count > 0) out += Bottleneck(key, score.coerceIn(1, 100), title, detail, action)
        }
        add("inbox", health.inbox, 55 + health.inbox * 3, "${health.inbox} ${if (health.inbox == 1) "note" else "notes"} waiting in the inbox", "process them with AI", BottleneckAction.Inbox)
        add("reports", reports.notDone, 60 + reports.notDone * 4, "${reports.notDone} ${if (reports.notDone == 1) "report" else "reports"} not finished", "a report that is not done answers nothing", BottleneckAction.Hygiene(HygieneKind.ReportNotDone))
        val research = pipeline.first { it.kind == WorkKind.Research }
        add("research", research.open, 40 + research.open * 3 + (research.oldestOpenDays ?: 0L).toInt().coerceAtMost(20), "${research.open} research ${if (research.open == 1) "question" else "questions"} never started", research.oldestOpenDays?.let { "the oldest waits $it ${if (it == 1L) "day" else "days"}" } ?: "", BottleneckAction.Work)
        val question = pipeline.first { it.kind == WorkKind.Question }
        add("questions", question.open, 35 + question.open * 3 + (question.oldestOpenDays ?: 0L).toInt().coerceAtMost(20), "${question.open} open ${if (question.open == 1) "question" else "questions"}", question.oldestOpenDays?.let { "the oldest waits $it ${if (it == 1L) "day" else "days"}" } ?: "", BottleneckAction.Work)
        val todo = pipeline.first { it.kind == WorkKind.Todo }
        add("todos", todo.open, 30 + todo.open * 2 + (todo.oldestOpenDays ?: 0L).toInt().coerceAtMost(20), "${todo.open} open ${if (todo.open == 1) "todo" else "todos"}", todo.oldestOpenDays?.let { "the oldest waits $it ${if (it == 1L) "day" else "days"}" } ?: "", BottleneckAction.Work)
        val stuck = pipeline.sumOf { it.inProgress }
        add("in-progress", stuck, 35 + stuck * 3, "$stuck ${if (stuck == 1) "item" else "items"} in progress with no finished answer", "finish them or give them up", BottleneckAction.Work)
        add("broken", health.brokenNotes, 45 + health.brokenNotes * 3, "${health.brokenNotes} notes with broken links (${health.brokenLinks} in all)", "links that point at nothing", BottleneckAction.Library("broken"))
        add("orphans", health.orphans, 25 + health.orphans * 2, "${health.orphans} ${if (health.orphans == 1) "note" else "notes"} linked to nothing", "islands: link them or fold them in", BottleneckAction.Library("orphans"))
        add("untagged", health.untagged, 20 + health.untagged * 2, "${health.untagged} ${if (health.untagged == 1) "note" else "notes"} without tags", "tags are how you find them later", BottleneckAction.Library("untagged"))
        add("stale", health.stale, 15 + health.stale, "${health.stale} ${if (health.stale == 1) "note" else "notes"} untouched for a long time", "still true? archive or refresh", BottleneckAction.Library("stale"))
        hygiene?.let { h ->
            for (kind in listOf(HygieneKind.StaleReport, HygieneKind.MissingType, HygieneKind.LooseRoot, HygieneKind.DuplicateName)) {
                add("hygiene-${kind.name}", h.count(kind), 18 + h.count(kind) * 2, kind.counted(h.count(kind)), "see them in vault hygiene", BottleneckAction.Hygiene(kind))
            }
        }
        if (trend != null && trend < 0.5) out += Bottleneck("activity", 30, "activity is down: ${(trend * 100).toInt()}% of your usual weekly pace", "the last two weeks against the weeks before", BottleneckAction.None)
        val sorted = out.sortedWith(compareBy({ -it.score }, { it.key }))
        return if (sorted.isEmpty()) listOf(Bottleneck("clean", 0, "Nothing is holding the brain back", "no unfinished reports, no waiting work, no broken links", BottleneckAction.None, good = true)) else sorted
    }
}
