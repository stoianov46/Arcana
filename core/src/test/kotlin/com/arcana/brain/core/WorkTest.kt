package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkTest {
    private fun cap(id: String, at: Long = 1, text: String = "q $id") = WorkEvent.Captured("cap-$id", id, at, "question", text, "2026-11-06")

    // ---- the fold ----

    @Test
    fun foldIsTheSameWhenEventsAreReadTwiceOrInAnotherOrder() {
        val events = listOf(
            cap("a", 1), cap("b", 2),
            WorkEvent.RunLinked("r1", "a", 10, "run-1"),
            WorkEvent.ArtifactLinked("l1", "a", 11, "Report A", "report"),
            WorkEvent.Edited("e1", "b", 12, "q b, reworded"),
            WorkEvent.Closed("c1", "b", 13, "abandoned"),
        )
        val expected = WorkItems.fold(events)
        assertEquals(expected, WorkItems.fold(events + events))
        assertEquals(expected, WorkItems.fold(events.reversed()))
        assertEquals(expected, WorkItems.fold(events.shuffled(java.util.Random(7))))
    }

    @Test
    fun stateIsDerivedNotStored() {
        val open = WorkItems.fold(listOf(cap("a"))).single()
        assertEquals(WorkState.Open, open.state())

        val running = WorkItems.fold(listOf(cap("a"), WorkEvent.RunLinked("r", "a", 5, "run-1"))).single()
        assertEquals(WorkState.InProgress, running.state())

        val reported = WorkItems.fold(listOf(cap("a"), WorkEvent.ArtifactLinked("l", "a", 5, "Report A", "report"))).single()
        assertEquals(WorkState.InProgress, reported.state(), "a linked report that is not done does not answer the item")
        assertEquals(WorkState.Answered, reported.state(setOf("Report A")), "a linked report that is done does")
        assertEquals(WorkState.InProgress, reported.state(setOf("Another")))

        val draft = WorkItems.fold(listOf(cap("a"), WorkEvent.ArtifactLinked("l", "a", 5, "Notes", "draft"))).single()
        assertEquals(WorkState.InProgress, draft.state(setOf("Notes")), "only a report answers")
    }

    @Test
    fun severalReportsMayAnswerOneItemAndALaterRunNeverReopensTheFirst() {
        val events = listOf(
            cap("a"),
            WorkEvent.ArtifactLinked("l1", "a", 5, "Quick report", "report"),
            WorkEvent.RunLinked("r2", "a", 20, "run-2"),
            WorkEvent.ArtifactLinked("l2", "a", 21, "Deep report", "report"),
        )
        val item = WorkItems.fold(events).single()
        assertEquals(2, item.artifacts.size)
        assertEquals(WorkState.Answered, item.state(setOf("Quick report")))
        assertEquals(WorkState.Answered, item.state(setOf("Quick report", "Deep report")))
    }

    @Test
    fun closeAndReopen() {
        val closed = WorkItems.fold(listOf(cap("a"), WorkEvent.Closed("c", "a", 5, "answered"))).single()
        assertEquals(WorkState.Answered, closed.state())
        val reopened = WorkItems.fold(listOf(cap("a"), WorkEvent.Closed("c", "a", 5, "answered"), WorkEvent.Reopened("o", "a", 6))).single()
        assertEquals(WorkState.Open, reopened.state())
        val abandoned = WorkItems.fold(listOf(cap("a"), WorkEvent.Closed("c", "a", 5, "abandoned"))).single()
        assertEquals(WorkState.Abandoned, abandoned.state())
    }

    @Test
    fun anEventForAnItemNeverCapturedOrWithAnUnknownReasonChangesNothing() {
        assertTrue(WorkItems.fold(listOf(WorkEvent.RunLinked("r", "ghost", 1, "run-1"))).isEmpty())
        val item = WorkItems.fold(listOf(cap("a"), WorkEvent.Closed("c", "a", 5, "who-knows"))).single()
        assertEquals(WorkState.Open, item.state())
    }

    @Test
    fun aliasMakesAnEventFindTheItemByEitherId() {
        val item = WorkItems.fold(listOf(cap("legacy-1"), WorkEvent.Aliased("al", "legacy-1", 2, "q-0611-1650"), WorkEvent.RunLinked("r", "q-0611-1650", 3, "run-1"))).single()
        assertEquals("legacy-1", item.id)
        assertEquals(listOf("run-1"), item.runs)
        assertTrue(item.isKnownAs("q-0611-1650"))
    }

    // ---- the log file ----

    private fun log(dir: Path = Files.createTempDirectory("arcana-work")) = JsonlWorkLog(dir.resolve("work/question.jsonl")) to dir.resolve("work/question.jsonl")

    @Test
    fun appendIsIdempotentById() {
        val (log, _) = log()
        assertTrue(log.append(cap("a")))
        assertFalse(log.append(cap("a")), "the same event id is written once")
        assertEquals(1, log.events().size)
    }

    @Test
    fun theLogSurvivesANewProcess() {
        val (log, path) = log()
        log.append(cap("a")); log.append(WorkEvent.RunLinked("r", "a", 3, "run-1"))
        val again = JsonlWorkLog(path)
        assertEquals(listOf("run-1"), again.items().single().runs)
        assertFalse(again.append(cap("a")), "ids are known after a restart")
    }

    @Test
    fun aWriteCutShortDoesNotSwallowTheNextEventAndBadLinesAreSkipped() {
        val (log, path) = log()
        log.append(cap("a"))
        Files.writeString(path, Files.readString(path) + """{"type":"captured","id":"cap-b","item":"b","at":2,"kind":"ques""", java.nio.file.StandardOpenOption.APPEND) // torn, no newline
        assertTrue(log.append(cap("c")), "the next event starts on its own line")
        Files.writeString(path, Files.readString(path) + """{"type":"from-the-future","id":"x","item":"a","at":9}""" + "\nnot json at all\n")
        val events = JsonlWorkLog(path).also { it.events() }
        assertEquals(listOf("a", "c"), events.items().map { it.id })
        assertEquals(3, events.unreadable, "the torn line, the unknown type and the garbage line are counted, not fatal")
    }

    // ---- the migration of the backlog note ----

    private fun vaultWith(note: String): Vault {
        val dir = Files.createTempDirectory("arcana-vault")
        Files.createDirectories(dir.resolve("Research"))
        Files.writeString(dir.resolve(ResearchBacklog.PATH), note)
        return Vault(dir)
    }

    private val note = """
        ---
        type: backlog
        ---

        # Research Backlog

        - [ ] test capture (2026-11-03)
        - [x] create transparent theme (2026-11-03) → done 2026-11-05: Ghost and friends in `Theme.kt`
        - [x] is logging a fix for the cache problem (2026-11-04) -> [[Why Qwen Re-Reads 2026-11-04]]
        - [x] Investigate Android CLI usage (2026-11-04) -> [[Android CLI Usage 2026-11-05]] ^q-0411-2336
        - [ ] add a splash screen (2026-11-05) ^q-0511-0005
        - [x] research web browser tool (2026-11-06) -> [[Web Search Tool 2026-11-06]] ^q-0611-1650

        Back to [[Home]]
    """.trimIndent() + "\n"

    @Test
    fun migrationMirrorsEveryLineOfTheNoteAndWritesNothingTheSecondTime() {
        val vault = vaultWith(note)
        val store = WorkStore(vault)
        val first = WorkMigration.sync(vault, store, now = 1_000)
        assertTrue(first > 0)
        val items = store.log(WorkKind.Question).items()
        // the line with a free-text tail after its date is not a backlog line (the reader never saw it either): six lines, five read
        assertEquals(listOf("test capture", "is logging a fix for the cache problem", "Investigate Android CLI usage", "add a splash screen", "research web browser tool"), items.map { it.text })
        assertEquals(listOf("q-0411-2336", "q-0511-0005", "q-0611-1650"), items.map { it.id }.filter { it.startsWith("q-") })
        assertTrue(items.first().id.startsWith("legacy-"), "a line without a marker gets an id made from its text and date")

        assertEquals(WorkState.Open, items.first { it.text == "test capture" }.state())
        assertEquals(WorkState.Answered, items.first { it.id == "q-0611-1650" }.state())
        assertEquals(listOf(WorkArtifact("Web Search Tool 2026-11-06", WorkRole.Report)), items.first { it.id == "q-0611-1650" }.artifacts)
        assertEquals(WorkState.Open, items.first { it.id == "q-0511-0005" }.state())

        assertEquals(0, WorkMigration.sync(vault, store, now = 2_000), "a second run writes nothing")
        assertEquals(0, WorkMigration.sync(vault, WorkStore(vault), now = 3_000), "nor does a run in a new process")
        assertEquals(emptyList(), WorkMigration.discrepancies(vault, store))
    }

    @Test
    fun aTickedLineClosesTheItemAndAnUntickReopensIt() {
        val vault = vaultWith(note)
        val store = WorkStore(vault)
        WorkMigration.sync(vault, store, now = 1_000)
        val splash = ResearchBacklog.items(vault).first { it.id == "q-0511-0005" }
        assertTrue(ResearchBacklog.tick(vault, splash, "Splash Screen Report"))

        assertTrue(WorkMigration.discrepancies(vault, store).any { it.startsWith("ticked in the note") })
        assertTrue(WorkMigration.sync(vault, store, now = 2_000) > 0)
        val ticked = store.log(WorkKind.Question).items().first { it.id == "q-0511-0005" }
        assertEquals(WorkState.Answered, ticked.state())
        assertEquals("Splash Screen Report", ticked.artifacts.single().note)

        // unticked by hand in the note
        val file = vault.root.resolve(ResearchBacklog.PATH)
        Files.writeString(file, Files.readString(file).replace("- [x] add a splash screen", "- [ ] add a splash screen"))
        WorkMigration.sync(vault, store, now = 3_000)
        val unticked = store.log(WorkKind.Question).items().first { it.id == "q-0511-0005" }
        assertEquals(null, unticked.closing)
        assertEquals(WorkState.InProgress, unticked.state(), "reopened, but the report it was linked to stays linked (and is not known to be done)")
        assertEquals(0, WorkMigration.sync(vault, store, now = 4_000))
    }

    @Test
    fun aMarkerGivenLaterIsTheSameItemNotANewOne() {
        val vault = vaultWith(note)
        val store = WorkStore(vault)
        WorkMigration.sync(vault, store, now = 1_000)
        val before = store.log(WorkKind.Question).items().size
        val first = ResearchBacklog.items(vault).first { it.question == "test capture" }
        val marker = ResearchBacklog.ensureId(vault, first)!!

        WorkMigration.sync(vault, store, now = 2_000)
        val items = store.log(WorkKind.Question).items()
        assertEquals(before, items.size, "no second item")
        val same = items.first { it.text == "test capture" }
        assertTrue(same.isKnownAs(marker))
        assertTrue(same.id.startsWith("legacy-"), "the id never changes; the marker is an alias")
        assertEquals(0, WorkMigration.sync(vault, store, now = 3_000))
    }

    @Test
    fun aReworkedQuestionIsAnEditAndADeletedLineIsReportedNotErased() {
        val vault = vaultWith(note)
        val store = WorkStore(vault)
        WorkMigration.sync(vault, store, now = 1_000)
        val file = vault.root.resolve(ResearchBacklog.PATH)
        Files.writeString(file, Files.readString(file).replace("add a splash screen", "add a splash screen with the loaded tools"))
        WorkMigration.sync(vault, store, now = 2_000)
        assertEquals("add a splash screen with the loaded tools", store.log(WorkKind.Question).items().first { it.id == "q-0511-0005" }.text)

        Files.writeString(file, Files.readString(file).lines().filterNot { "web browser tool" in it }.joinToString("\n"))
        WorkMigration.sync(vault, store, now = 3_000)
        assertTrue(store.log(WorkKind.Question).items().any { it.id == "q-0611-1650" }, "the log keeps what the note lost")
        assertEquals(listOf("gone from the note: research web browser tool"), WorkMigration.discrepancies(vault, store))
    }

    @Test
    fun noNoteMeansNothingToMirror() {
        val dir = Files.createTempDirectory("arcana-vault")
        val vault = Vault(dir)
        assertEquals(0, WorkMigration.sync(vault, WorkStore(vault)))
        assertFalse(Files.exists(dir.resolve(".arcana/work")), "nothing is created for nothing")
    }
}
