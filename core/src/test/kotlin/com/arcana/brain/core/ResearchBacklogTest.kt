package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchBacklogTest {
    private val day = LocalDate.of(2026, 11, 3)

    private fun vault(): Pair<Path, Vault> = Files.createTempDirectory("backlog").let { it to Vault(it) }

    private fun note(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun report(status: String, question: String?) =
        "---\ntype: report\ntopic: dev-research\nkind: research\ndate: 2026-11-04\nstatus: $status\n" + (question?.let { "question: \"$it\"\n" } ?: "") + "---\n\n# R\n"

    @Test
    fun onlyAPrefixedThoughtIsAQuestion() {
        assertEquals("what is the outbox pattern", ResearchBacklog.capturedQuestion("? what is the outbox pattern"))
        assertEquals("what is the outbox pattern", ResearchBacklog.capturedQuestion("  ?   what is the outbox pattern  "))
        assertEquals("Compare KMP and Flutter", ResearchBacklog.capturedQuestion("Research: Compare KMP and Flutter"))
        assertEquals("x", ResearchBacklog.capturedQuestion("RESEARCH: x"))
        assertNull(ResearchBacklog.capturedQuestion("buy milk"))
        assertNull(ResearchBacklog.capturedQuestion("?"))
        assertNull(ResearchBacklog.capturedQuestion("? "))
        assertNull(ResearchBacklog.capturedQuestion("?what"), "a question mark glued to the word is not the prefix")
        assertNull(ResearchBacklog.capturedQuestion("research:"))
        assertNull(ResearchBacklog.capturedQuestion("researching is fun"))
    }

    @Test
    fun theNoteIsCreatedOnFirstUseWithItsProperties() {
        val (root, vault) = vault()
        assertTrue(ResearchBacklog.add(vault, "what is the outbox pattern", day))
        val text = Files.readString(root.resolve(ResearchBacklog.PATH))
        assertTrue(text.startsWith("---\ntype: backlog\ntags: [index, research]\n---\n"))
        assertTrue("# Research Backlog" in text && "- [ ] what is the outbox pattern (2026-11-03)" in text)
        assertTrue(text.trimEnd().endsWith("Back to [[Home]]"))
        assertEquals(listOf("what is the outbox pattern"), ResearchBacklog.open(vault).map { it.question })
        assertEquals("2026-11-03", ResearchBacklog.open(vault)[0].date)
    }

    @Test
    fun newQuestionsGoAboveTheFooterAndNoQuestionIsAddedTwice() {
        val (root, vault) = vault()
        ResearchBacklog.add(vault, "first one", day)
        assertTrue(ResearchBacklog.add(vault, "second   one ", day.plusDays(1)))
        assertFalse(ResearchBacklog.add(vault, "FIRST ONE?", day.plusDays(2)), "same text apart from case and the question mark")
        assertFalse(ResearchBacklog.add(vault, "   ", day))
        val text = Files.readString(root.resolve(ResearchBacklog.PATH))
        assertEquals(listOf("first one", "second one"), ResearchBacklog.open(vault).map { it.question })
        assertTrue(text.indexOf("- [ ] second one") < text.indexOf("Back to [[Home]]"))
        assertEquals(1, Regex("Back to \\[\\[Home]]").findAll(text).count())
        assertEquals(2, ResearchBacklog.items(vault).size)
    }

    @Test
    fun aDoneQuestionIsNotAddedAgainEither() {
        val (_, vault) = vault()
        ResearchBacklog.add(vault, "what is CQRS", day)
        ResearchBacklog.tick(vault, ResearchBacklog.open(vault)[0])
        assertFalse(ResearchBacklog.add(vault, "what is cqrs", day.plusDays(1)))
        assertTrue(ResearchBacklog.open(vault).isEmpty())
    }

    @Test
    fun tickingTurnsTheBoxAndLinksTheReport() {
        val (root, vault) = vault()
        ResearchBacklog.add(vault, "a", day); ResearchBacklog.add(vault, "b", day)
        val b = ResearchBacklog.open(vault)[1]
        assertTrue(ResearchBacklog.tick(vault, b, "[[2026-11-04 B report]]"))
        val text = Files.readString(root.resolve(ResearchBacklog.PATH))
        assertTrue("- [ ] a (2026-11-03)" in text && "- [x] b (2026-11-03) -> [[2026-11-04 B report]]" in text)
        val done = ResearchBacklog.items(vault).single { it.done }
        assertEquals("[[2026-11-04 B report]]", done.link)
        assertEquals(listOf("a"), ResearchBacklog.open(vault).map { it.question })
        assertFalse(ResearchBacklog.tick(vault, b), "already ticked")
        assertTrue(ResearchBacklog.tick(vault, ResearchBacklog.open(vault)[0]), "without a link")
        assertTrue("- [x] a (2026-11-03)\n" in Files.readString(root.resolve(ResearchBacklog.PATH)))
    }

    @Test
    fun aStaleLineIndexStillFindsTheQuestion() {
        val (root, vault) = vault()
        ResearchBacklog.add(vault, "a", day); ResearchBacklog.add(vault, "b", day)
        val b = ResearchBacklog.open(vault)[1]
        val file = root.resolve(ResearchBacklog.PATH)
        Files.writeString(file, "one more line above\n\n" + Files.readString(file)) // the user edited the note meanwhile: every index moved
        assertTrue(ResearchBacklog.tick(vault, b))
        assertEquals(listOf("a"), ResearchBacklog.open(vault).map { it.question })
        assertTrue(ResearchBacklog.tick(vault, BacklogItem(0, "gone", "2026-11-03", false, null)).not(), "a question that is not there")
    }

    @Test
    fun aDoneReportWithTheSameQuestionTicksTheItem() {
        val (root, vault) = vault()
        ResearchBacklog.add(vault, "What is the outbox pattern?", day)
        ResearchBacklog.add(vault, "How does Navigation 3 work", day)
        ResearchBacklog.add(vault, "unanswered one", day)
        note(root, "Research/Dev/Reports/2026-11-04 Outbox.md", report("done", "what is the outbox pattern"))
        note(root, "Research/Dev/Reports/2026-11-04 Nav.md", report("incomplete", "How does Navigation 3 work")) // not done: stays open
        note(root, "Research/Dev/Reports/2026-11-04 No question.md", report("done", null))
        assertEquals(1, ResearchBacklog.reconcile(vault))
        assertEquals(listOf("How does Navigation 3 work", "unanswered one"), ResearchBacklog.open(vault).map { it.question })
        val ticked = ResearchBacklog.items(vault).single { it.done }
        assertEquals("What is the outbox pattern?", ticked.question)
        assertEquals("[[2026-11-04 Outbox]]", ticked.link)
        assertEquals(0, ResearchBacklog.reconcile(vault), "nothing more to tick")
    }

    @Test
    fun reconcileWithoutANoteOrReportsDoesNothing() {
        val (_, vault) = vault()
        assertEquals(0, ResearchBacklog.reconcile(vault))
        assertTrue(ResearchBacklog.open(vault).isEmpty() && ResearchBacklog.items(vault).isEmpty())
        ResearchBacklog.add(vault, "q", day)
        assertEquals(0, ResearchBacklog.reconcile(vault))
    }

    @Test
    fun theNewestDoneReportIsLinked() {
        val (root, vault) = vault()
        ResearchBacklog.add(vault, "same question", day)
        note(root, "Research/Dev/Reports/2026-11-04 Old.md", report("done", "same question").replace("2026-11-04", "2026-11-04"))
        note(root, "Research/Dev/Reports/2026-11-09 New.md", report("done", "Same Question").replace("2026-11-04", "2026-11-09"))
        ResearchBacklog.reconcile(vault)
        assertEquals("[[2026-11-09 New]]", ResearchBacklog.items(vault).single().link)
    }
}
