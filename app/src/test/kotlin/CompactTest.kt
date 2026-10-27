package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.Compaction
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.SessionStore
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** /compact against a fake Ollama: the summary lands at the cut, nothing is removed, and the model is then sent the summary instead of the old messages. */
class CompactTest {
    private fun app(server: HttpServer): AppContext {
        val dir = Files.createTempDirectory("arcana-compact")
        val config = ConfigStore(dir.resolve("config.json"))
        config.save(AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}", model = "m", keepChats = false))
        return AppContext(config, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), SessionStore(dir.resolve("sessions"))).also {
            it.scope = CoroutineScope(Dispatchers.Default)
        }
    }

    private fun fakeOllama(reply: String, bodies: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/chat") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            val bytes = ("""{"message":{"role":"assistant","content":"$reply"},"done":true,"prompt_eval_count":50,"eval_count":10}""" + "\n").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun compactInsertsASummaryAtTheCutAndKeepsEveryMessage() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fakeOllama("They asked about apples.", bodies)
        try {
            val chat = app(server).chat
            (1..4).forEach { chat.add(ChatMessage.user("question $it")); chat.add(ChatMessage.assistant("answer $it")) }
            val told = runBlocking { chat.compact() }
            assertTrue(told.startsWith("Compacted 4 messages"), told)
            assertEquals(9, chat.messages.size, "eight messages and the summary: nothing removed")
            assertTrue(Compaction.isSummary(chat.messages[4]) && "apples" in Compaction.summaryText(chat.messages[4]))
            assertEquals(listOf("question 3", "answer 3", "question 4", "answer 4"), chat.messages.drop(5).map { it.content })
            assertEquals(5, Compaction.forModel(chat.messages.toList()).size, "the model sees the summary and the last two turns")
            assertTrue("question 1" in bodies[0] && "question 2" in bodies[0] && "question 3" !in bodies[0], "only the older part was summarized")
            assertTrue(!chat.compacting)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun compactRefusesAShortChatAndAnEmptySummary() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fakeOllama("", bodies)
        try {
            val chat = app(server).chat
            chat.add(ChatMessage.user("hi")); chat.add(ChatMessage.assistant("hello"))
            assertTrue(assertFailsWith<IllegalStateException> { runBlocking { chat.compact() } }.message!!.contains("too short"))
            assertTrue(bodies.isEmpty(), "no request for a chat that is too short")
            (2..4).forEach { chat.add(ChatMessage.user("question $it")); chat.add(ChatMessage.assistant("answer $it")) }
            val before = chat.messages.size
            assertTrue(assertFailsWith<IllegalArgumentException> { runBlocking { chat.compact() } }.message!!.contains("empty summary"))
            assertEquals(before, chat.messages.size, "an empty summary changes nothing")
            assertTrue(!chat.compacting)
        } finally {
            server.stop(0)
        }
    }
}
