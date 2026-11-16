package com.arcana.brain.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainStatsTest {
    private val today = LocalDate.of(2026, 11, 6) // a Friday
    private fun at(day: LocalDate) = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun note(path: String, day: LocalDate, words: Int = 100, tags: Set<String> = setOf("a"), links: Set<String> = emptySet()) = NoteInfo(path, at(day), words, tags, links, emptySet())
    private val health = BrainHealth(notes = 4, words = 400, links = 2, tags = 1, orphans = 0, untagged = 0, brokenNotes = 0, brokenLinks = 0, stale = 0, changedThisWeek = 2, connectedPercent = 100, taggedPercent = 100)
    private fun report(status: String, tokens: Long? = null) = ReportInfo("R/x.md", "x", "t", "research", status, "2026-11-01", "2026", "", "", "q", 5L, tokens = tokens)

    private fun compute(notes: List<NoteInfo> = emptyList(), work: List<WorkItem> = emptyList(), reports: List<ReportInfo> = emptyList(), usage: List<UsageRecord> = emptyList(), health: BrainHealth = this.health, hygiene: HygieneReport? = null, weeks: Int = 8) =
        BrainStats.compute(weeks, notes, health, reports, work, emptySet(), usage, hygiene, today)

    @Test
    fun theWindowIsWholeWeeksFromAMondayToTodayAndEveryDayIsCounted() {
        val s = compute(notes = listOf(note("A.md", today), note("B.md", today), note("C.md", today.minusDays(10))))
        assertEquals(8, s.weekly.size)
        assertEquals(LocalDate.of(2026, 9, 14), s.weekly.first().weekStart, "eight weeks back from the Monday of this week")
        assertEquals(today, s.days.last().day)
        assertEquals(2, s.days.last().changed)
        assertEquals(3, s.changedInWindow)
        assertEquals(today, s.busiestDay?.day)
        assertEquals(2, s.weekly.last().changed)
    }

    @Test
    fun aNoteOlderThanTheWindowIsNotInTheChartsButIsInTheFolders() {
        val s = compute(notes = listOf(note("Research/Old.md", today.minusWeeks(30)), note("Research/New.md", today), note("Home.md", today)))
        assertEquals(2, s.changedInWindow)
        assertEquals(listOf("Research" to 2, "(vault root)" to 1), s.folders.map { it.folder to it.notes })
    }

    @Test
    fun hubsAreTheMostLinkedToNotes() {
        val notes = listOf(note("Hub.md", today), note("A.md", today, links = setOf("Hub.md")), note("B.md", today, links = setOf("Hub.md", "A.md")), note("C.md", today, links = setOf("C.md")))
        assertEquals(listOf("Hub.md" to 2, "A.md" to 1), compute(notes = notes).hubs, "a note linking to itself does not count")
    }

    @Test
    fun tokensAreSummedPerWeek() {
        val usage = listOf(UsageRecord(at(today), "Chat", "m", 100, 50, 10), UsageRecord(at(today.minusDays(1)), "Chat", "m", 10, 5, 10), UsageRecord(at(today.minusWeeks(2)), "Chat", "m", 1, 1, 10))
        val s = compute(usage = usage)
        assertEquals(165L, s.tokensByWeek.last())
        assertEquals(2L, s.tokensByWeek[s.tokensByWeek.size - 3])
    }

    @Test
    fun reportsAreCountedByStatus() {
        val s = compute(reports = listOf(report("done", 100), report("done", 300), report("draft")))
        assertEquals(ReportStats(3, 2, 1, 200L), s.reports)
        assertEquals(66, s.reports.donePercent)
        assertEquals(100, compute().reports.donePercent, "no reports is not a problem")
    }

    @Test
    fun theWorkPipelineCountsEachKindByStateAndAgesTheOldestOpenOne() {
        val old = WorkItem("a", WorkKind.Research, "r1", "2026-10-30", at(LocalDate.of(2026, 10, 30)))
        val started = WorkItem("b", WorkKind.Research, "r2", "2026-11-01", at(LocalDate.of(2026, 11, 1)), runs = listOf("run-1"))
        val done = WorkItem("c", WorkKind.Todo, "t", "2026-11-02", at(LocalDate.of(2026, 11, 2)), closing = WorkClosing.Answered)
        val s = compute(work = listOf(old, started, done))
        val research = s.pipeline.first { it.kind == WorkKind.Research }
        assertEquals(1, research.open); assertEquals(1, research.inProgress); assertEquals(7L, research.oldestOpenDays)
        assertEquals(100, s.pipeline.first { it.kind == WorkKind.Todo }.donePercent)
        assertNull(s.pipeline.first { it.kind == WorkKind.Question }.oldestOpenDays)
    }

    @Test
    fun activityTrendComparesTheLastTwoWeeksWithTheWeeksBefore() {
        val busyBefore = (1..6).flatMap { w -> (0..3).map { d -> note("n$w$d.md", BrainStats.monday(today).minusWeeks(w + 1L).plusDays(d.toLong())) } }
        val s = compute(notes = busyBefore + note("now.md", today))
        assertNotNull(s.activityTrend)
        assertTrue(s.activityTrend!! < 0.5, "the last weeks are quiet against the busy weeks before: ${s.activityTrend}")
        assertTrue(s.bottlenecks.any { it.key == "activity" })
        assertNull(compute(weeks = 3).activityTrend, "too little history says nothing")
    }

    @Test
    fun bottlenecksAreRankedAndEachSaysWhereToGo() {
        val h = health.copy(brokenNotes = 1, brokenLinks = 3, untagged = 5)
        val s = compute(health = h, reports = listOf(report("draft"), report("draft")))
        val keys = s.bottlenecks.map { it.key }
        assertEquals(listOf("reports", "broken", "untagged"), keys, "highest score first: ${s.bottlenecks.map { it.key to it.score }}")
        assertEquals(BottleneckAction.Hygiene(HygieneKind.ReportNotDone), s.bottlenecks.first().action)
        assertEquals(BottleneckAction.Library("broken"), s.bottlenecks[1].action)
        assertEquals(BottleneckAction.Library("untagged"), s.bottlenecks.last().action)
    }

    @Test
    fun aCleanBrainSaysSoInsteadOfShowingNothing() {
        val s = compute()
        assertEquals(listOf("clean"), s.bottlenecks.map { it.key })
        assertTrue(s.bottlenecks.single().good)
    }

    @Test
    fun waitingWorkIsABottleneckAndTheOldestOneIsNamed() {
        val q = WorkItem("q", WorkKind.Question, "why", "2026-10-30", at(LocalDate.of(2026, 10, 30)))
        val s = compute(work = listOf(q))
        val b = s.bottlenecks.single { it.key == "questions" }
        assertEquals(BottleneckAction.Work, b.action)
        assertTrue("7 days" in b.detail, b.detail)
    }
}
