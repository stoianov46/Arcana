package com.arcana.brain.app

import com.arcana.brain.app.screens.entriesOf
import com.arcana.brain.app.screens.messageLines
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
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
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), EventStore(dir.resolve("events.jsonl")))
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

    @Test
    fun anOraculToolCallIsOneEntryWithTheCallAndHowItEnded() {
        val app = app()
        val steps = listOf(
            com.arcana.brain.core.OraculSteps.message("qwen3.8:27b", "web_search", "qwen3.5 moe", "ok", "5 results for qwen3.5 moe\nmore"),
            com.arcana.brain.core.OraculSteps.message("qwen3.8:27b", "web_search", "again", "error", "Error: DuckDuckGo is limiting requests"),
            com.arcana.brain.core.OraculSteps.message("qwen3.8:27b", "write_note", "Notes/x.md", "denied", "The user denied this action."),
        )
        var next = 0
        steps.forEach { m ->
            val lines = messageLines(app, m, next, 100)
            assertEquals(1, entriesOf(m).size)
            assertEquals(setOf(next), lines.map { it.entry }.filter { it >= 0 }.toSet(), "the call and its outcome are one entry")
            next++
        }
        val shown = steps.map { messageLines(app, it, 0, 100).joinToString("\n") { l -> l.text } }
        assertTrue("Ψ qwen3.8:27b  web_search  qwen3.5 moe" in shown[0] && "✓ 5 results for qwen3.5 moe" in shown[0], shown[0])
        assertTrue("✕ Error: DuckDuckGo is limiting requests" in shown[1], shown[1])
        assertTrue("you did not allow it" in shown[2], shown[2])
        assertTrue(entriesOf(steps[1]).single().startsWith("Ψ qwen3.8:27b used web_search again: failed"), entriesOf(steps[1]).single())
    }

    @Test
    fun theContextMeterEstimatesAtOnceAfterACompactionAndGoesBackToTheRealCountsWithTheNextReply() {
        val stats = com.arcana.brain.core.ReplyStats(promptTokens = 99_000, completionTokens = 300, totalMillis = 1000)
        val before = mutableListOf(
            ChatMessage.user("a".repeat(4_000)), ChatMessage.assistant("b".repeat(4_000)),
            ChatMessage.user("c".repeat(4_000)), ChatMessage.assistant("d".repeat(4_000), stats = stats),
        )
        assertEquals((99_300 to false), com.arcana.brain.app.screens.contextTokens(before))
        // compaction inserts the summary BEFORE the latest turn, so the last reply (with its old counts) still comes after it
        val after = before.toMutableList().also {
            it.add(2, ChatMessage.user("${com.arcana.brain.core.Compaction.MARK}\n\n" + "s".repeat(400)).copy(contextAt = 5))
        }
        val (tokens, estimated) = com.arcana.brain.app.screens.contextTokens(after)!!
        assertTrue(estimated && tokens < 5_000, "after a compaction it is an estimate of what is sent now, not the old 99k: $tokens")
        // the next reply was made with the summary: its counts are real again
        val next = after + ChatMessage.user("more") + ChatMessage.assistant("ok", stats = com.arcana.brain.core.ReplyStats(7_000, 100, 500))
        assertEquals((7_100 to false), com.arcana.brain.app.screens.contextTokens(next))
        // a summary from before this field existed keeps the old rule (the summary must come after the reply)
        assertEquals((99_300 to false), com.arcana.brain.app.screens.contextTokens(after.map { if (com.arcana.brain.core.Compaction.isSummary(it)) it.copy(contextAt = 0) else it }))
    }
}
