package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportsTest {
    private val today = LocalDate.of(2026, 11, 3)

    private fun note(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun report(kind: String, date: String, status: String = "done", extra: String = "") =
        "---\ntype: report\ntopic: t-$kind\nkind: $kind\ndate: $date\nyear: ${date.take(4)}\nstatus: $status\nsummary: \"Summary of $kind\"\nskill: \"[[create-$kind-report.skill]]\"\n$extra---\n\n# R\n\n## TL;DR\n- x\n"

    @Test
    fun findsEveryReportNewestFirstAndReadsItsProperties() {
        val root = Files.createTempDirectory("reports")
        note(root, "Research/LLM/Reports/2026-11-01 Compare.md", report("compare", "2026-11-01", extra = "question: \"Which model?\"\n"))
        note(root, "Research/Dev/Reports/2026-10-20 Research.md", report("research", "2026-10-20", status = "incomplete"))
        note(root, "Research/DeFi/Reports/2026-09-01 Pool.md", report("pool", "2026-09-01", extra = "pair: \"eth/usd\"\n"))
        note(root, "Research/Dev/Plain note.md", "---\ntype: reference\n---\n\ntext\n")
        note(root, "No properties.md", "just text\n")
        note(root, ".arcana/skills/x/Hidden.md", report("digest", "2026-11-02"))
        val all = Reports.scan(Vault(root), today)
        assertEquals(listOf("2026-11-01 Compare", "2026-10-20 Research", "2026-09-01 Pool"), all.map { it.title })
        val first = all[0]
        assertEquals("compare", first.kind); assertEquals("t-compare", first.topic); assertEquals("done", first.status)
        assertEquals("2026-11-01", first.date); assertEquals("2026", first.year); assertEquals("Summary of compare", first.summary)
        assertEquals("create-compare-report", first.skill); assertEquals("Which model?", first.question); assertEquals(2L, first.ageDays)
        assertEquals("eth/usd", all[2].question)
        assertTrue(Reports.isIncomplete(all[1])); assertFalse(Reports.isIncomplete(first))
    }

    @Test
    fun agedKindsAreStaleSooner() {
        fun info(kind: String, days: Long?) = ReportInfo("p.md", "p", "", kind, "done", "", "", "", "", "", days)
        assertFalse(Reports.isStale(info("landscape", 90))); assertTrue(Reports.isStale(info("landscape", 91)))
        assertTrue(Reports.isStale(info("compare", 120)))
        assertFalse(Reports.isStale(info("digest", 120))); assertFalse(Reports.isStale(info("research", 365)))
        assertTrue(Reports.isStale(info("digest", 366)))
        assertFalse(Reports.isStale(info("landscape", null)), "no readable date: never stale")
    }

    @Test
    fun toleratesMissingKeysBadDatesAndBrokenProperties() {
        val root = Files.createTempDirectory("reports")
        note(root, "A/Bare.md", "---\ntype: report\n---\n\nbody\n")
        note(root, "A/Bad date.md", "---\ntype: report\ndate: someday\nstatus: done\n---\n\nbody\n")
        note(root, "A/Unclosed.md", "---\ntype: report\ndate: 2026-11-01\nbody without a closing line\n")
        note(root, "A/Empty.md", "")
        val all = Reports.scan(Vault(root), today)
        assertEquals(setOf("Bare", "Bad date"), all.map { it.title }.toSet())
        val bare = all.first { it.title == "Bare" }
        assertEquals("", bare.kind); assertEquals("", bare.date); assertNull(bare.ageDays); assertTrue(Reports.isIncomplete(bare))
        assertNull(all.first { it.title == "Bad date" }.ageDays)
    }

    @Test
    fun groupsByTheDomainFolder() {
        assertEquals("LLM", Reports.domainOf("Research/LLM/Reports/a.md"))
        assertEquals("Dev", Reports.domainOf("Research/Dev/Reports/a.md"))
        assertEquals("Elsewhere", Reports.domainOf("Elsewhere/a.md"))
        assertEquals("(vault)", Reports.domainOf("a.md"))
        fun r(path: String) = ReportInfo(path, path, "", "", "", "", "", "", "", "", null)
        val groups = Reports.groupByDomain(listOf(r("Research/LLM/Reports/a.md"), r("Research/Dev/Reports/b.md"), r("Research/LLM/Reports/c.md")))
        assertEquals(listOf("Dev", "LLM"), groups.keys.toList())
        assertEquals(2, groups.getValue("LLM").size)
    }

    @Test
    fun aReportNameLosesItsDateAtTheEndOrAtTheStart() {
        assertEquals("Jetpack Navigation V3", Reports.nameWithoutDate("Jetpack Navigation V3 2026-11-03", "2026-11-03"))
        assertEquals("Jetpack Navigation V3", Reports.nameWithoutDate("Jetpack Navigation V3 2026-11-03 2", "2026-11-03"), "a taken name has a number after the date")
        assertEquals("Jetpack Navigation V3", Reports.nameWithoutDate("2026-11-03 Jetpack Navigation V3", "2026-11-03"), "an older report has the date first")
        assertEquals("Plan for 2026", Reports.nameWithoutDate("Plan for 2026 2026-11-03", "2026-11-03"))
        assertEquals("2026-11-03", Reports.nameWithoutDate("2026-11-03", "2026-11-03"), "a name that is only a date stays")
    }
}
