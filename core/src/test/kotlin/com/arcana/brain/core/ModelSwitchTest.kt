package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `/model`: what it was typed with, which installed model a name means, and which message is the last task to send again. */
class ModelSwitchTest {
    private val installed = listOf("inkling-2-mini:26b", "qwen3:30b", "qwen3:8b", "gemma4:26b", "nomic-embed-text:latest")

    @Test
    fun theRetryWordIsTakenOutOfTheNameWhereverItIs() {
        assertEquals(ModelSwitch.Request("", false), ModelSwitch.parse(""))
        assertEquals(ModelSwitch.Request("qwen3:30b", false), ModelSwitch.parse("  qwen3:30b "))
        assertEquals(ModelSwitch.Request("qwen3:30b", true), ModelSwitch.parse("qwen3:30b --retry"))
        assertEquals(ModelSwitch.Request("qwen3:30b", true), ModelSwitch.parse("--retry qwen3:30b"))
        assertEquals(ModelSwitch.Request("qwen3:30b", true), ModelSwitch.parse("qwen3:30b again"))
        assertEquals(ModelSwitch.Request("", true), ModelSwitch.parse("-r"))
    }

    @Test
    fun aNameIsFoundExactlyThenWithoutLatestThenByItsStartThenByAPart() {
        assertEquals(ModelSwitch.Match.Found("qwen3:8b"), ModelSwitch.resolve("qwen3:8b", installed))
        assertEquals(ModelSwitch.Match.Found("nomic-embed-text:latest"), ModelSwitch.resolve("nomic-embed-text", installed), "a name without a tag means :latest")
        assertEquals(ModelSwitch.Match.Found("qwen3:30b"), ModelSwitch.resolve("QWEN3:30B", installed), "case does not matter")
        assertEquals(ModelSwitch.Match.Found("inkling-2-mini:26b"), ModelSwitch.resolve("inkling", installed), "the only model that starts with it")
        assertEquals(ModelSwitch.Match.Found("gemma4:26b"), ModelSwitch.resolve("gemma", installed))
        assertEquals(ModelSwitch.Match.Found("inkling-2-mini:26b"), ModelSwitch.resolve("mini", installed), "the only model that contains it")
        assertEquals(ModelSwitch.Match.Ambiguous("qwen3", listOf("qwen3:30b", "qwen3:8b")), ModelSwitch.resolve("qwen3", installed))
        assertEquals(ModelSwitch.Match.Ambiguous("26b", listOf("inkling-2-mini:26b", "gemma4:26b")), ModelSwitch.resolve("26b", installed))
        assertEquals(ModelSwitch.Match.Missing("llama3"), ModelSwitch.resolve("llama3", installed))
    }

    @Test
    fun theLastTaskIsYourLastMessageNotASummaryOrAnOraculMessage() {
        val task = ChatMessage.user("Summarize the Inbox notes", notes = listOf("Inbox/a.md")).copy(think = "xhigh", sent = "Summarize… (excerpts)")
        val messages = listOf(
            ChatMessage.user("first question"),
            ChatMessage.assistant("first answer"),
            task,
            ChatMessage.assistantCalls("", listOf(ToolCall(ToolFunction("read_note", kotlinx.serialization.json.buildJsonObject {})))),
            ChatMessage.toolResult("read_note", "text"),
            ChatMessage.assistant("a weak summary"),
            ChatMessage.user("${Oracul.MARK}\n\nThe summary misses two notes."),
            Compaction.summaryMessage("older part"),
        )
        assertEquals(task, ModelSwitch.lastTask(messages))
        assertNull(ModelSwitch.lastTask(listOf(ChatMessage.assistant("hello"))))

        val again = ModelSwitch.again(task, now = 42)
        assertEquals("Summarize the Inbox notes", again.content)
        assertEquals(listOf("Inbox/a.md"), again.noteNames)
        assertEquals("", again.think, "the level picked for that one message is not carried over")
        assertEquals("", again.sent, "the excerpts are read again for the new model")
        assertEquals(42L, again.at)
        assertTrue(again.role == "user")
    }
}
