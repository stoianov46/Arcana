package com.arcana.brain.app

import com.arcana.brain.app.screens.entriesOf
import com.arcana.brain.app.screens.messageLines
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
import com.arcana.brain.core.SessionStore
import com.arcana.brain.core.ToolCall
import com.arcana.brain.core.ToolFunction
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Select mode walks entries; the lines drawn for a message must carry exactly the entry numbers its entriesOf says, or the cursor lights up the wrong rows. */
class SelectEntriesTest {
    private fun app(): AppContext {
        val dir = Files.createTempDirectory("arcana-select")
        val store = ConfigStore(dir.resolve("config.json")).also { it.save(AppConfig()) }
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), SessionStore(dir.resolve("sessions")), EventStore(dir.resolve("events.jsonl")))
    }

    private fun call(name: String, key: String, value: String) = ToolCall(ToolFunction(name, buildJsonObject { put(key, value) }))

    @Test
    fun everyToolCallAndResultIsItsOwnEntryAndNoHeaderBorrowsTheNextOne() {
        val app = app()
        val messages = listOf(
            ChatMessage.user("Do it"),
            ChatMessage.assistantCalls("", listOf(call("read_note", "path", "a.md")), null),          // a reply that only calls a tool
            ChatMessage.toolResult("read_note", "text of a"),
            ChatMessage.assistantCalls("", listOf(call("run_command", "command", "ls"), call("list_dir", "path", ".")), null),
            ChatMessage.toolResult("run_command", "exit code 0\nfiles"),
            ChatMessage.toolResult("list_dir", "a.md"),
            ChatMessage.assistant("First paragraph.\n\nSecond paragraph."),
        )
        var next = 0
        val byEntry = HashMap<Int, MutableList<String>>()
        messages.forEach { m ->
            val lines = messageLines(app, m, next, 100)
            val count = entriesOf(m).size
            val own = lines.map { it.entry }.filter { it >= 0 }.toSet()
            assertTrue(own.all { it in next until next + count }, "${m.role}: lines may only use this message's own entry numbers ($own, ${next until next + count})")
            assertEquals((next until next + count).toSet(), own, "${m.role}: every entry of the message is drawn")
            lines.filter { it.entry >= 0 }.forEach { byEntry.getOrPut(it.entry) { mutableListOf() } += it.text }
            next += count
        }
        assertEquals(9, next, "1 user + 1 call + 1 result + 2 calls + 2 results + 2 paragraphs")
        assertTrue(byEntry.values.none { rows -> rows.count { it.contains("◆") || it.contains("gemma") } > 1 }, "one entry never holds two reply headers: $byEntry")
        assertEquals("read_note  a.md", entriesOf(messages[1]).single())
        assertTrue(entriesOf(messages[2]).single().contains("text of a"), "a tool result is copied with its text")
    }
}
