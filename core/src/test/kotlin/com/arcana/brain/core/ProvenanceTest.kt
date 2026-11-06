package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProvenanceTest {
    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall(ToolFunction(name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) })))

    private class Setup {
        val root = Files.createTempDirectory("arcana-prov")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val vault = Vault(vaultDir)
        val provenance = Provenance(vault)
        val origin = Origin("run-test1", "chat-test1")
        fun box(origin: Origin? = this.origin, provenance: Provenance? = this.provenance) =
            ToolBox(vault, emptyList(), root.resolve("backups"), outputFolder = "Research", origin = origin, provenance = provenance)
    }

    @Test
    fun runIdsAreSortableByTimeAndDifferWithinOneMillisecond() {
        val a = Origin.newRun("c", now = 1_000, random = java.util.Random(1))
        val b = Origin.newRun("c", now = 1_000, random = java.util.Random(2))
        val later = Origin.newRun("c", now = 2_000_000_000_000, random = java.util.Random(1))
        assertTrue(a.run.startsWith("run-"))
        assertNotEquals(a.run, b.run)
        assertTrue(a.run < later.run, "a later run sorts after an earlier one")
    }

    @Test
    fun aNoteCreatedByARunCarriesItsOriginAndTheRunKnowsTheNote() {
        val s = Setup()
        s.provenance.started(s.origin, "research the web browser tool", "qwen", now = 100)
        val result = s.box().execute(call("create_note", "title" to "Browser Report", "content" to "# Browser Report\n\ntext"))
        assertTrue(result.startsWith("Created Research/Browser Report"), result)

        val text = Files.readString(s.vaultDir.resolve("Research/Browser Report.md"))
        assertTrue("origin: run-test1" in text && "chat: chat-test1" in text, text)
        assertEquals("Research/Browser Report.md", s.provenance.notesOf("run-test1").single().note)
        assertEquals(NoteAction.Created, s.provenance.notesOf("run-test1").single().action)
        assertEquals("run-test1", s.provenance.runOf("Research/Browser Report.md")?.run)
        assertEquals("research the web browser tool", s.provenance.runs().single().request)
    }

    @Test
    fun appendAndSetPropertiesAreRecordedAsChangesNotAsCreation() {
        val s = Setup()
        s.provenance.started(s.origin, "x", "m", now = 1)
        val box = s.box()
        box.execute(call("create_note", "title" to "R", "content" to "body"))
        box.execute(call("append_to_note", "path" to "Research/R.md", "heading" to "More", "content" to "text"))
        box.execute(call("set_properties", "path" to "Research/R.md", "properties" to "status: done"))
        val actions = s.provenance.notesOf("run-test1").map { it.action }
        assertEquals(listOf(NoteAction.Created, NoteAction.Appended, NoteAction.Properties), actions)
        // the same run doing the same to the same note again is one record
        box.execute(call("append_to_note", "path" to "Research/R.md", "heading" to "Again", "content" to "text"))
        assertEquals(3, s.provenance.notesOf("run-test1").size)
        assertEquals("run-test1", s.provenance.runOf("Research/R.md")?.run, "only the creating run owns the note")
    }

    @Test
    fun withoutAnOriginNothingChangesAndNothingIsRecorded() {
        val s = Setup()
        s.box(origin = null).execute(call("create_note", "title" to "Plain", "content" to "body"))
        val text = Files.readString(s.vaultDir.resolve("Research/Plain.md"))
        assertTrue("origin:" !in text && "chat:" !in text, "a note made without a run has no origin: $text")
        assertNull(s.provenance.runOf("Research/Plain.md"))
        assertTrue(s.provenance.runs().isEmpty())
        // and the tools work without any record at all
        s.box(provenance = null).execute(call("create_note", "title" to "Other", "content" to "body"))
        assertTrue(Files.exists(s.vaultDir.resolve("Research/Other.md")))
    }

    @Test
    fun anOriginInTheGivenPropertiesOfAnoteDoesNotOverrideTheStamp() {
        val s = Setup()
        // a model that writes its own `origin:` in the properties block wins, like every other property (documented in Vault.create)
        s.vault.create("", "T", "---\norigin: by-hand\n---\ntext", "m", s.origin)
        val text = Files.readString(s.vaultDir.resolve("T.md"))
        assertTrue("origin: by-hand" in text && "origin: run-test1" !in text, text)
        assertTrue("chat: chat-test1" in text)
    }

    @Test
    fun aRunLogSurvivesAProcessAndAnUnknownNoteIsUnknown() {
        val s = Setup()
        s.provenance.started(s.origin, "q", "m", now = 5)
        s.provenance.touched(s.origin, "A.md", NoteAction.Created, now = 6)
        val again = Provenance(s.vault)
        assertEquals("run-test1", again.runOf("A.md")?.run)
        assertNull(again.runOf("never-written-by-a-run.md"))
        assertEquals(emptyList(), again.notesOf("run-unknown"))
    }

    @Test
    fun theSameRunStartedTwiceIsOneRun() {
        val s = Setup()
        s.provenance.started(s.origin, "q", "m", now = 5)
        s.provenance.started(s.origin, "q again", "m", now = 9)
        assertEquals(1, s.provenance.runs().size)
        assertEquals("q", s.provenance.runs().single().request)
    }
}
