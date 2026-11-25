package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
import com.arcana.brain.core.Oracul
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the model is sent never changes behind it: a question keeps the vault excerpts it was sent with, the system prompt stays the same through the chat, and a change
 * (the memory file) is told with the next message instead. That is what lets Ollama reuse what it already read.
 */
class FrozenContextTest {
    private fun fake(bodies: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun reply(e: com.sun.net.httpserver.HttpExchange, text: String) { val b = text.toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) } }
        server.createContext("/api/tags") { e -> reply(e, """{"models":[{"name":"qwen"}]}""") }
        server.createContext("/api/ps") { e -> reply(e, """{"models":[]}""") }
        server.createContext("/api/show") { e -> e.requestBody.readBytes(); reply(e, """{"capabilities":["completion"]}""") }
        server.createContext("/api/chat") { e ->
            bodies += e.requestBody.readBytes().decodeToString()
            reply(e, """{"message":{"role":"assistant","content":"Answer ${bodies.size}."},"done":true,"prompt_eval_count":5,"eval_count":2}""" + "\n")
        }
        server.start()
        return server
    }

    private fun messages(body: String) = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.text() = this["content"]!!.jsonPrimitive.content
    private fun JsonObject.role() = this["role"]!!.jsonPrimitive.content

    private fun answered(app: AppContext, n: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (app.chat.messages.toList().count { it.role == "assistant" } < n && System.currentTimeMillis() < deadline) Thread.sleep(30)
        Thread.sleep(150)
    }

    @Test
    fun earlierMessagesAndTheSystemPromptStayAsTheyWereSent() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fake(bodies)
        val vault = Files.createTempDirectory("vault-frozen")
        Files.writeString(vault.resolve("Models.md"), "# Models\n\nqwen3.8:27b offers the thinking levels low, medium and xhigh.\n")
        Files.writeString(vault.resolve("Gardens.md"), "# Gardens\n\nTomatoes like sun and regular watering.\n")
        Files.createDirectories(vault.resolve(".arcana"))
        Files.writeString(vault.resolve(".arcana/ARCANA.md"), "# Memory\n\n- Answer briefly.\n")
        try {
            val dir = Files.createTempDirectory("arcana-frozen")
            val store = ConfigStore(dir.resolve("config.json"))
            store.save(AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}", model = "qwen", keepChats = false, vaultPath = vault.toString(), chatUseVault = true, searchBy = "keywords", toolsEnabled = false, memoryEnabled = true))
            val app = AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), EventStore(dir.resolve("events.jsonl")))
                .also { it.scope = CoroutineScope(Dispatchers.Default) }

            app.chat.send("which thinking levels does qwen3.8:27b offer?")
            answered(app, 1)
            app.chat.send("how much sun do tomatoes like?")
            answered(app, 2)
            val (first, second) = bodies.take(2).map(::messages)
            assertTrue("xhigh" in first.last().text())
            assertEquals(first.first(), second.first(), "the system prompt is the same")
            assertEquals(first[1], second[1], "the first question goes again exactly as it was sent, excerpts included")
            assertTrue("Tomatoes" in second.last().text() && "Tomatoes" !in second[1].text())

            // the memory file changes: the system prompt still does not, the next message says what changed
            Files.writeString(vault.resolve(".arcana/ARCANA.md"), "# Memory\n\n- Answer briefly.\n- Use metric units.\n")
            app.chat.send("and how often to water them?")
            answered(app, 3)
            val third = messages(bodies[2])
            assertEquals(first.first(), third.first(), "still the same system prompt")
            assertTrue("Use metric units" !in third.first().text())
            assertTrue("memory file (ARCANA.md) changed" in third.last().text() && "Use metric units" in third.last().text(), third.last().text())
            assertEquals(second.drop(1), third.drop(1).take(second.size - 1), "everything sent before is sent again unchanged")

            // Arcana's own follow-up is not a question: no new search, the excerpts stay with the question they belong to
            app.chat.send(Oracul.REVISE_PROMPT)
            answered(app, 4)
            val fourth = messages(bodies[3])
            assertTrue(fourth.last().role() == "user" && !fourth.last().text().startsWith("Vault excerpts"), fourth.last().text())
            // what the user wrote is still what the chat shows
            assertEquals("which thinking levels does qwen3.8:27b offer?", app.chat.messages.first { it.role == "user" }.content)
        } finally { server.stop(0) }
    }
}
