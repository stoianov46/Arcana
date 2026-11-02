package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R7 and R8: what Arcana writes into a finished report (tokens, work time, model), the rating, and a report that a newer one replaced. */
class ReportLifecycleTest {
    private val today = LocalDate.of(2026, 11, 3)

    private fun vaultWith(path: String, text: String): Pair<Vault, Path> {
        val root = Files.createTempDirectory("lifecycle")
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return Vault(root) to file
    }

    private val report = "---\ntype: report\ntopic: dev-research\nkind: research\ndate: 2026-11-01\nyear: 2026\nstatus: done\nsummary: \"S\"\n---\n\n# R\n\nbody\n"

    @Test
    fun workTimeReadsLikeTheElapsedTimeTool() {
        assertEquals("0 s", ReportMeta.workTime(-5))
        assertEquals("45 s", ReportMeta.workTime(45_000))
        assertEquals("5 min 12 s", ReportMeta.workTime(312_000))
        assertEquals("1 h 2 min 3 s", ReportMeta.workTime(3_723_000))
    }

    @Test
    fun stampWritesTokensWorkTimeAndModelIntoAReport() {
        val (vault, file) = vaultWith("Research/Dev/Reports/2026-11-01 R.md", report)
        val written = ReportMeta.stamp(vault, "Research/Dev/Reports/2026-11-01 R.md", 12_345, 312_000, "qwen3.5:9b")
        assertEquals(listOf("tokens", "work_time", "model"), written)
        val info = Reports.info(vault, "Research/Dev/Reports/2026-11-01 R.md", today)!!
        assertEquals(12_345L, info.tokens); assertEquals("5 min 12 s", info.workTime); assertEquals("qwen3.5:9b", info.model)
        // the rest of the note is untouched
        assertTrue(Files.readString(file).endsWith("# R\n\nbody\n"))
    }

    @Test
    fun stampNeverChangesAValueThatIsThereAndTwiceChangesNothing() {
        val (vault, file) = vaultWith("R/a.md", report.replace("status: done\n", "status: done\nmodel: gemma4:12b\ntokens: 99\n"))
        assertEquals(listOf("work_time"), ReportMeta.stamp(vault, "R/a.md", 5_000, 60_000, "qwen3.5:9b"))
        val once = Files.readString(file)
        assertEquals(emptyList(), ReportMeta.stamp(vault, "R/a.md", 5_000, 90_000, "qwen3.5:9b"))
        assertEquals(once, Files.readString(file))
        val info = Reports.info(vault, "R/a.md", today)!!
        assertEquals(99L, info.tokens); assertEquals("gemma4:12b", info.model); assertEquals("1 min 0 s", info.workTime)
    }

    @Test
    fun stampSkipsWhatIsNotAReportAndNumbersThatWereNotRecorded() {
        val (vault, file) = vaultWith("N/plain.md", "---\ntype: reference\n---\n\ntext\n")
        assertEquals(emptyList(), ReportMeta.stamp(vault, "N/plain.md", 100, 1000, "m"))
        Files.writeString(vault.root.resolve("N/none.md"), "no properties\n")
        assertEquals(emptyList(), ReportMeta.stamp(vault, "N/none.md", 100, 1000, "m"))
        assertEquals(emptyList(), ReportMeta.stamp(vault, "N/missing.md", 100, 1000, "m"))
        assertEquals("---\ntype: reference\n---\n\ntext\n", Files.readString(file))
        // nothing recorded: no zero tokens
        Files.writeString(vault.root.resolve("N/r.md"), report)
        assertEquals(listOf("work_time", "model"), ReportMeta.stamp(vault, "N/r.md", 0, 1000, "m"))
    }

    @Test
    fun ratingTokensAndSupersededByAreReadFromTheProperties() {
        val extra = "rating: 4\ntokens: 20500\nwork_time: 3 min 2 s\nmodel: qwen3.5:9b\n"
        val (vault, _) = vaultWith("R/a.md", report.replace("summary: \"S\"\n", "summary: \"S\"\n$extra"))
        val info = Reports.info(vault, "R/a.md", today)!!
        assertEquals(4, info.rating); assertEquals(20_500L, info.tokens); assertEquals("3 min 2 s", info.workTime)
        // a rating outside 1 to 5, or not a number, is no rating
        for (bad in listOf("rating: 9", "rating: 0", "rating: great", "rating: ")) {
            val (v, _) = vaultWith("R/b.md", report.replace("summary: \"S\"\n", "summary: \"S\"\n$bad\n"))
            assertNull(Reports.info(v, "R/b.md", today)!!.rating, bad)
        }
        val (vault2, _) = vaultWith("R/c.md", report.replace("status: done\n", "status: superseded\nsuperseded_by: \"[[2026-11-03 New]]\"\n"))
        val old = Reports.info(vault2, "R/c.md", today)!!
        assertEquals("2026-11-03 New", old.supersededBy); assertTrue(Reports.isSuperseded(old))
    }

    @Test
    fun aSupersededReportIsNeitherUnfinishedNorStale() {
        fun info(status: String, kind: String = "landscape", date: String = "2025-01-01") =
            ReportInfo("R/x.md", "x", "t", kind, status, date, date.take(4), "", "", "", java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(date), today))
        assertTrue(Reports.isStale(info("done")))
        assertFalse(Reports.isStale(info("superseded")))
        assertTrue(Reports.isIncomplete(info("incomplete")))
        assertFalse(Reports.isIncomplete(info("superseded")))
    }

    @Test
    fun theHygieneChecksSkipSupersededReportsAndSayHowToRefreshAStaleOne() {
        fun note(status: String) = "---\ntype: report\nstatus: $status\nkind: landscape\ndate: 2025-01-01\ntags: [x]\n---\n\nbody\n"
        val found = VaultHygiene.checkNotes(listOf("R/old.md" to note("superseded"), "R/stale.md" to note("done")), emptySet(), today)
        assertEquals(emptyList(), found.issues.filter { it.path == "R/old.md" && (it.kind == HygieneKind.ReportNotDone || it.kind == HygieneKind.StaleReport) })
        val stale = found.issues.single { it.path == "R/stale.md" && it.kind == HygieneKind.StaleReport }
        assertTrue("press f" in stale.fix && "Reports view" in stale.fix, stale.fix)
    }
}
