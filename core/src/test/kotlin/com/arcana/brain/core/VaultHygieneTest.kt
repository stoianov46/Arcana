package com.arcana.brain.core

import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultHygieneTest {
    private val today = LocalDate.of(2026, 11, 3)
    private fun note(type: String? = "reference", extra: String = ""): String =
        "---\n" + (if (type != null) "type: $type\n" else "") + extra + "tags: [x]\n---\n\nbody\n"

    private fun kinds(vararg notes: Pair<String, String>, islands: Set<String> = emptySet()) =
        VaultHygiene.checkNotes(notes.toList(), islands, today).issues.map { it.kind to it.path }

    @Test
    fun aNoteWithoutATypeIsFlaggedButAThoughtFileIsNot() {
        val found = kinds("Research/A.md" to note(type = null), "Research/B.md" to note(), "Thoughts/thought-2026-11-02.md" to "- 09:00 hi\n", "Research/C.md" to "no properties at all")
        assertEquals(listOf(HygieneKind.MissingType to "Research/A.md", HygieneKind.MissingType to "Research/C.md"), found)
        // an empty type counts as missing
        assertEquals(1, kinds("A/X.md" to "---\ntype:\ntags: [x]\n---\nbody").count { it.first == HygieneKind.MissingType })
    }

    @Test
    fun aNameThatStartsWithASymbolOrEmojiIsFlaggedWithTheCleanName() {
        val report = VaultHygiene.checkNotes(listOf("Research/🐉 Qwen Reference.md" to note(), "Research/(Draft) Plan.md" to note(), "Research/[x] Ok.md" to note(), "Research/Plain.md" to note()), emptySet(), today)
        val issues = report.byKind.getValue(HygieneKind.SymbolName)
        assertEquals(listOf("Research/🐉 Qwen Reference.md"), issues.map { it.path })
        assertTrue("\"Qwen Reference\"" in issues.single().fix)
    }

    @Test
    fun genericNamesAreFlaggedButAnAreaIndexIsNot() {
        val found = kinds("A/Index.md" to note(), "A/Untitled 3.md" to note(), "A/README.md" to note(), "A/New note.md" to note(), "A/Notes.md" to note(), "A/Dev Index.md" to note(), "A/Reference.md" to note())
            .filter { it.first == HygieneKind.GenericName }.map { it.second }
        assertEquals(listOf("A/Index.md", "A/New note.md", "A/Notes.md", "A/README.md", "A/Untitled 3.md"), found)
    }

    @Test
    fun namesThatDifferOnlyByCaseOrEmojiAreDuplicatesOnBothSides() {
        val report = VaultHygiene.checkNotes(listOf("A/🧠 gpt-oss Reference.md" to note(), "B/GPT-OSS Reference.md" to note(), "C/Other.md" to note()), emptySet(), today)
        val dup = report.byKind.getValue(HygieneKind.DuplicateName)
        assertEquals(setOf("A/🧠 gpt-oss Reference.md", "B/GPT-OSS Reference.md"), dup.map { it.path }.toSet())
        assertTrue("B/GPT-OSS Reference.md" in dup.first { it.path.startsWith("A/") }.message)
        // the dated thought files differ in their digits, so they are not duplicates
        assertEquals(0, kinds("thought-2026-11-01.md" to "x", "thought-2026-11-02.md" to "x").count { it.first == HygieneKind.DuplicateName })
    }

    @Test
    fun onlyHomeMayStayInTheRootAndThoughtsBelongInTheThoughtsFolder() {
        val found = kinds("Home.md" to note("index"), "Thoughts/Thoughts Index.md" to note("index"), "Thoughts/thought-2026-11-02.md" to "x", "thought-2026-11-01.md" to "x", "Stray.md" to note(), "Research/Fine.md" to note())
            .filter { it.first == HygieneKind.LooseRoot }
        assertEquals(listOf(HygieneKind.LooseRoot to "Stray.md", HygieneKind.LooseRoot to "thought-2026-11-01.md"), found.sortedBy { it.second })
    }

    @Test
    fun aReportThatIsNotDoneIsFlaggedWithItsStatus() {
        val report = VaultHygiene.checkNotes(listOf(
            "R/a.md" to note("report", "status: done\nkind: digest\ndate: 2026-11-01\n"),
            "R/b.md" to note("report", "status: incomplete\nkind: digest\ndate: 2026-11-01\n"),
            "R/c.md" to note("report", "kind: digest\ndate: 2026-11-01\n"),
            "R/d.md" to note("reference", "status: incomplete\n"),
        ), emptySet(), today)
        val bad = report.byKind.getValue(HygieneKind.ReportNotDone)
        assertEquals(listOf("R/b.md", "R/c.md"), bad.map { it.path })
        assertTrue("\"incomplete\"" in bad[0].message && "\"missing\"" in bad[1].message)
    }

    @Test
    fun landscapeAndCompareReportsGoStaleAfter90DaysOthersAfter365() {
        fun r(kind: String, date: String) = note("report", "status: done\nkind: $kind\ndate: $date\n")
        val report = VaultHygiene.checkNotes(listOf(
            "R/land-old.md" to r("landscape", "2026-07-01"),      // 125 days
            "R/land-new.md" to r("landscape", "2026-09-10"),      // 54 days
            "R/cmp-edge.md" to r("compare", "2026-08-05"),        // 90 days: not yet stale
            "R/cmp-old.md" to r("compare", "2026-08-04"),         // 91 days
            "R/dig-old.md" to r("digest", "2026-01-01"),          // 306 days: a digest keeps a year
            "R/dig-ancient.md" to r("digest", "2025-10-01"),      // 398 days
            "R/nodate.md" to note("report", "status: done\nkind: digest\n"),
            "R/time.md" to r("landscape", "2026-07-01T19:11"),    // a time after the date is fine
        ), emptySet(), today)
        assertEquals(listOf("R/cmp-old.md", "R/dig-ancient.md", "R/land-old.md", "R/time.md"), report.byKind.getValue(HygieneKind.StaleReport).map { it.path })
        assertTrue("limit 90" in report.byKind.getValue(HygieneKind.StaleReport).first { it.path == "R/land-old.md" }.message)
    }

    @Test
    fun islandsComeFromTheIndexAndSkipThoughtFiles() {
        val found = kinds("A/Lonely.md" to note(), "A/Linked.md" to note(), "Thoughts/thought-2026-11-02.md" to "x", islands = setOf("A/Lonely.md", "Thoughts/thought-2026-11-02.md"))
            .filter { it.first == HygieneKind.Island }
        assertEquals(listOf(HygieneKind.Island to "A/Lonely.md"), found)
    }

    @Test
    fun theReportGroupsByKindCountsAndOrdersTheWorstFirst() {
        val report = VaultHygiene.checkNotes(listOf("A.md" to note(null), "B.md" to note(null), "Research/Index.md" to note()), emptySet(), today)
        assertEquals(2, report.count(HygieneKind.MissingType))
        assertEquals(1, report.count(HygieneKind.GenericName))
        assertEquals(0, report.count(HygieneKind.Island))
        assertEquals(HygieneKind.MissingType, report.worst.first())
        assertTrue(HygieneKind.Island !in report.worst)
        assertEquals(report.total, report.issues.size)
    }

    @Test
    fun countsReadAsEnglish() {
        assertEquals("1 stale report", HygieneKind.StaleReport.counted(1))
        assertEquals("0 stale reports", HygieneKind.StaleReport.counted(0))
        assertEquals("3 notes linked to nothing", HygieneKind.Island.counted(3))
    }

    @Test
    fun aCleanVaultHasNoIssuesAndARealVaultIsReadFromDisk() {
        val dir = Files.createTempDirectory("hygiene")
        Files.createDirectories(dir.resolve("Research"))
        Files.createDirectories(dir.resolve(".arcana"))
        Files.writeString(dir.resolve("Home.md"), "---\ntype: index\ntags: [index]\n---\n\n# Home\n- [[Dev Index]]\n")
        Files.writeString(dir.resolve("Research/Dev Index.md"), "---\ntype: index\ntags: [index]\n---\n\nBack to [[Home]]\n")
        Files.writeString(dir.resolve(".arcana/Hidden.md"), "no type but hidden")
        val vault = Vault(dir)
        assertEquals(0, VaultHygiene.check(vault, today).total)
        Files.writeString(dir.resolve("Stray.md"), "no properties")
        val report = VaultHygiene.check(vault, today, VaultIndex.build(vault))
        assertEquals(setOf(HygieneKind.MissingType, HygieneKind.LooseRoot, HygieneKind.Island), report.worst.toSet())
        assertTrue(report.issues.all { it.path == "Stray.md" })
    }
}
