package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The model's task list (Todos) and the artifact list (Artifacts). */
class TodosAndArtifactsTest {
    @Test
    fun theModelsTaskListIsParsedShownAndKeptInTheChat() {
        val items = Todos.parse("[x] read the note\n- [~] write the summary\n3. [ ] save it\nplain line\n\n(x) odd mark")
        assertEquals(listOf('x', '~', ' ', ' ', 'x'), items.map { it.state })
        assertEquals("write the summary", items[1].text)
        assertEquals("plain line", items[3].text)
        val said = Todos.describe(items)
        assertTrue(said.startsWith("1. [x] read the note") && "2 of 5 done" in said && "Carry on with: write the summary" in said, said)
        assertTrue("Every item is done" in Todos.describe(Todos.parse("[x] a\n[x] b")))
        assertTrue("only one should be in progress" in Todos.describe(Todos.parse("[~] a\n[~] b")))
        assertEquals("Todo list cleared.", Todos.describe(emptyList()))
        assertEquals(Todos.MAX_ITEMS, Todos.parse((1..50).joinToString("\n") { "[ ] item $it" }).size)
        // the list in the chat is what the LAST todo call said; a later empty call clears it
        fun call(list: String) = ChatMessage.assistantCalls("", listOf(ToolCall(ToolFunction(TODO, kotlinx.serialization.json.buildJsonObject { put("items", kotlinx.serialization.json.JsonPrimitive(list)) }))))
        val chat = listOf(ChatMessage.user("go"), call("[ ] a\n[ ] b"), ChatMessage.toolResult(TODO, "ok"), call("[x] a\n[~] b"))
        assertEquals(listOf('x', '~'), Todos.last(chat).map { it.state })
        assertEquals(emptyList(), Todos.last(chat + call("")))
        assertEquals(emptyList(), Todos.last(listOf(ChatMessage.user("hi"))))
    }

    @Test
    fun theArtifactListHasOneEntryPerFileAndOpensOnlyVaultNotesInTheLibrary() {
        val dir = java.nio.file.Files.createTempDirectory("vault").toRealPath()
        java.nio.file.Files.createDirectories(dir.resolve("Notes"))
        java.nio.file.Files.writeString(dir.resolve("Notes/Plan.md"), "x")
        java.nio.file.Files.writeString(dir.resolve("Notes/data.csv"), "x")
        java.nio.file.Files.createDirectories(dir.resolve(".hidden"))
        java.nio.file.Files.writeString(dir.resolve(".hidden/a.md"), "x")
        val outside = java.nio.file.Files.createTempFile("out", ".md")
        val list = listOf(Artifact("Notes/Plan.md", "created", "create_note", 1), Artifact("Notes/data.csv", "created", "write_file", 2), Artifact("Notes/Plan.md", "edited", "edit_file", 3))
        val entries = Artifacts.entries(list)
        assertEquals(listOf("Notes/Plan.md", "Notes/data.csv"), entries.map { it.artifact.path }, "newest first, one per path")
        assertEquals("edited", entries[0].artifact.action)
        assertEquals(2, entries[0].times)
        assertEquals("Notes/Plan.md", Artifacts.libraryNote(entries[0].artifact, dir))
        assertEquals("Notes/Plan.md", Artifacts.libraryNote(Artifact(dir.resolve("Notes/Plan.md").toString(), "created", "write_file", 1), dir), "an absolute path inside the vault")
        assertEquals(null, Artifacts.libraryNote(entries[1].artifact, dir), "not Markdown")
        assertEquals(null, Artifacts.libraryNote(Artifact("Notes/Plan.md", "deleted", "delete_path", 1), dir))
        assertEquals(null, Artifacts.libraryNote(Artifact("Notes/Gone.md", "created", "create_note", 1), dir), "not there any more")
        assertEquals(null, Artifacts.libraryNote(Artifact(outside.toString(), "created", "write_file", 1), dir), "outside the vault")
        assertEquals(null, Artifacts.libraryNote(Artifact(".hidden/a.md", "created", "write_file", 1), dir), "hidden folders are not in the Library")
    }

    @Test
    fun onlyToolCallsThatLeaveAFileOrNoteBehindAreArtifacts() {
        fun of(tool: String, args: Map<String, String>, result: String) = Artifacts.of(tool, { args[it].orEmpty() }, result, 1L)
        assertEquals(Artifact("Notes/Plan.md", "created", "create_note", 1L), of("create_note", mapOf("title" to "Plan", "folder" to "Notes"), "Created Notes/Plan.md"))
        assertEquals("replaced", of("write_file", mapOf("path" to "a.txt"), "Replaced a.txt (12 characters). The old")?.action)
        assertEquals("created", of("write_file", mapOf("path" to "a.txt"), "Created a.txt (12 characters)")?.action)
        assertEquals("edited", of("edit_file", mapOf("path" to "a.txt"), "Edited a.txt")?.action)
        assertEquals("b.md", of("move_path", mapOf("from" to "a.md", "to" to "b.md"), "Moved")?.path, "a move is the new path")
        assertEquals("deleted", of("delete_path", mapOf("path" to "a.md"), "Moved to trash")?.action)
        assertEquals(null, of("read_note", mapOf("path" to "a.md"), "text"), "a read leaves nothing")
        assertEquals(null, of("run_command", mapOf("command" to "ls"), "files"), "a command is not an artifact")
        assertEquals(null, of("edit_file", mapOf("path" to "a.txt"), "Error: old text not found"), "a failed call leaves nothing")
        assertEquals("✦ 2 changed", Artifacts.pill(listOf(Artifact("a", "created", "t", 1), Artifact("a", "edited", "t", 2), Artifact("b", "created", "t", 3)), false), "one path counts once")
        assertEquals(null, Artifacts.pill(emptyList(), false))
    }
}
