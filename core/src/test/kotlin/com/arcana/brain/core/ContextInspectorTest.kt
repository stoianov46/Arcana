package com.arcana.brain.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextInspectorTest {
    private fun tool(name: String) = buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", name); put("description", "does $name") } }

    @Test
    fun aRequestIsSplitIntoItsPartsWithTheMemoryInsideTheSystemPrompt() {
        val memory = "Always answer in English."
        val system = ChatMessage.system("You are Arcana. Today is Monday.\n\nThe user's memory file:\n$memory")
        val typed = "what is the outbox pattern"
        val sent = "Vault excerpts:\n\n<note path=\"A.md\">\nx\n</note>\n\nQuestion: $typed"
        val request = listOf(system, ChatMessage.user("hi"), ChatMessage.assistant("hello"), ChatMessage("user", sent))
        val snap = ContextInspector.inspect(request, JsonArray(listOf(tool("read_note"), tool("search_vault"))), "qwen", 32_000, memory = memory, typed = typed)
        val titles = snap.parts.map { it.title }
        assertEquals(listOf("System prompt", "Memory file (ARCANA.md)", "Instructions and the date", "Tool definitions", "Earlier conversation", "The message being answered", "What you typed", "Added by Arcana"), titles)
        assertEquals(memory, snap.parts[1].text)
        assertEquals(2, snap.toolCount)
        assertEquals("vault excerpts (1 note)", snap.parts.last().detail)
        assertEquals("2 messages: 1 yours, 1 replies, 0 tool results", snap.parts.first { it.title == "Earlier conversation" }.detail)
        // the pieces of a part add up to it, and nothing was sent before: nothing is cached
        assertEquals(snap.parts[0].tokens, snap.parts[1].tokens + snap.parts[2].tokens)
        assertEquals(0, snap.cachedTokens)
        assertTrue(snap.parts.none { it.reused })
        assertTrue(snap.total > 0 && snap.share > 0.0)
    }

    @Test
    fun whatMatchesTheRequestBeforeIsMarkedAsReusedByOllama() {
        val first = listOf(ChatMessage.system("sys"), ChatMessage.user("one"), ChatMessage.assistant("two"))
        val second = first + ChatMessage.user("three")
        assertEquals(3, ContextInspector.sharedPrefix(second, first))
        val snap = ContextInspector.inspect(second, null, "m", 0, previous = first)
        assertTrue(snap.parts.first { it.title == "System prompt" }.reused)
        assertTrue(snap.parts.first { it.title == "Earlier conversation" }.reused)
        assertTrue(!snap.parts.first { it.title == "The message being answered" }.reused, "the new message is read anew")
        assertTrue(snap.cachedTokens in 1 until snap.total)
        // a changed system prompt makes Ollama read everything again
        assertEquals(0, ContextInspector.sharedPrefix(listOf(ChatMessage.system("other")) + second.drop(1), first))
    }

    @Test
    fun aSummaryAndTheToolRoundsOfThisTurnAreTheirOwnParts() {
        val call = ToolCall(ToolFunction("read_note", buildJsonObject { put("path", JsonPrimitive("A.md")) }))
        val request = listOf(
            ChatMessage.system("sys"), Compaction.summaryMessage("we talked about A"), ChatMessage.user("read A"),
            ChatMessage.assistantCalls("", listOf(call)), ChatMessage.toolResult("read_note", "text of A"),
        )
        val snap = ContextInspector.inspect(request, null, "m", 0)
        val turn = snap.parts.first { it.title == "This turn so far" }
        assertEquals("1 tool call and 1 results since your message", turn.detail)
        assertTrue("→ read_note" in turn.text && "TOOL RESULT (read_note)" in turn.text, turn.text)
        assertTrue(snap.parts.any { it.title == "Summary of the earlier chat" })
        assertTrue(snap.parts.none { it.title == "Earlier conversation" }, "nothing between the summary and the message")
    }

    @Test
    fun theBarShowsAShareAndAlwaysOneCellForSomething() {
        assertEquals("▰▰▰▰▱▱▱▱", ContextInspector.bar(50, 100, 8))
        assertEquals("▰▱▱▱▱▱▱▱", ContextInspector.bar(1, 1000, 8))
        assertEquals("##......", ContextInspector.bar(25, 100, 8, plain = true))
        assertEquals("", ContextInspector.bar(5, 0, 8))
    }
}
