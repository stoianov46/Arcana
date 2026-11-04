package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
import com.arcana.brain.core.SessionStore
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The model calls `semantic_search` with no embedding model installed: the user is asked once, the model is downloaded through /api/pull and selected, and the same call then answers; saying no gives the model an error and downloads nothing. */
class SemanticSetupTest {
    private class Fake(val server: HttpServer, val calls: MutableList<String>, var installed: Boolean = false, var chats: Int = 0)

    private fun fake(): Fake {
        val calls = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val f = Fake(server, calls)
        fun reply(e: com.sun.net.httpserver.HttpExchange, text: String) { val b = text.toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) } }
        server.createContext("/api/tags") { e -> reply(e, if (f.installed) """{"models":[{"name":"qwen"},{"name":"nomic-embed-text:latest"}]}""" else """{"models":[{"name":"qwen"}]}""") }
        server.createContext("/api/ps") { e -> reply(e, """{"models":[]}""") }
        server.createContext("/api/show") { e -> e.requestBody.readBytes(); reply(e, """{"capabilities":["completion","tools"]}""") }
        server.createContext("/api/pull") { e ->
            calls += "pull"; e.requestBody.readBytes()
            f.installed = true
            reply(e, listOf("""{"status":"pulling manifest"}""", """{"status":"pulling aa","total":200000000,"completed":100000000}""", """{"status":"success"}""").joinToString("\n"))
        }
        server.createContext("/api/embed") { e ->
            calls += "embed"
            val n = kotlinx.serialization.json.Json.parseToJsonElement(e.requestBody.readBytes().decodeToString()).let { (it as kotlinx.serialization.json.JsonObject)["input"] as kotlinx.serialization.json.JsonArray }.size
            reply(e, """{"embeddings":[${(1..n).joinToString(",") { "[1.0,0.0]" }}]}""")
        }
        // the model asks for semantic_search on the first round, then answers with what the tool said
        server.createContext("/api/chat") { e ->
            val body = e.requestBody.readBytes().decodeToString()
            calls += "chat"
            val round = f.chats++
            reply(e, if (round == 0) """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"semantic_search","arguments":{"query":"databases"}}}]},"done":true,"prompt_eval_count":10,"eval_count":5}"""
                else """{"message":{"role":"assistant","content":"Found: ${if ("Note.md" in body) "Note.md" else "nothing"}"},"done":true,"prompt_eval_count":10,"eval_count":5}""")
        }
        server.start()
        return f
    }

    private fun app(f: Fake, vault: java.nio.file.Path): AppContext {
        val dir = Files.createTempDirectory("arcana-semantic")
        val store = ConfigStore(dir.resolve("config.json"))
        store.save(AppConfig(ollamaUrl = "http://127.0.0.1:${f.server.address.port}", model = "qwen", keepChats = false, vaultPath = vault.toString(), toolsEnabled = true, toolAutoRead = true))
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), SessionStore(dir.resolve("sessions")), EventStore(dir.resolve("events.jsonl")))
            .also { it.scope = CoroutineScope(Dispatchers.Default) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertTrue(condition(), "timed out waiting for $what")
    }

    @Test
    fun theFirstSemanticSearchAsksOnceDownloadsTheModelAndThenAnswers() {
        val f = fake()
        try {
            val vault = Files.createTempDirectory("vault").also { Files.writeString(it.resolve("Note.md"), "# Note\nSome text about databases.\n") }
            val app = app(f, vault)
            runBlocking { app.refreshConnection() }
            assertTrue(!app.chat.semanticOn(), "off before: no embedding model is set")
            app.chat.send("find my notes about databases")
            waitFor("the question") { app.chat.approval != null }
            assertEquals("Download a model?", app.chat.approval!!.heading)
            assertTrue(app.chat.approval!!.lines.any { "nomic-embed-text" in it && "270 MB" in it }, "the dialog names the model and its size")
            assertTrue("pull" !in f.calls, "nothing is downloaded before the answer")
            app.chat.answer(ChatSession.ApprovalChoice.Allow)
            waitFor("the answer") { app.chat.messages.count { it.role == "assistant" && it.toolCalls.isEmpty() } >= 1 }
            assertEquals("nomic-embed-text", app.config.embedModel, "the model is selected")
            assertTrue(app.chat.semanticOn())
            assertEquals(1, f.calls.count { it == "pull" })
            val result = app.chat.messages.first { it.role == "tool" }.content
            assertTrue(!result.startsWith("Error") && "Note.md" in result, "the same call answered after the setup: $result")
            assertNull(app.chat.indexing)
        } finally { f.server.stop(0) }
    }

    @Test
    fun sayingNoGivesTheModelAnErrorAndDownloadsNothing() {
        val f = fake()
        try {
            val vault = Files.createTempDirectory("vault2").also { Files.writeString(it.resolve("Note.md"), "text") }
            val app = app(f, vault)
            runBlocking { app.refreshConnection() }
            app.chat.send("find my notes about databases")
            waitFor("the question") { app.chat.approval != null }
            app.chat.answer(ChatSession.ApprovalChoice.Deny)
            waitFor("the answer") { app.chat.messages.count { it.role == "assistant" && it.toolCalls.isEmpty() } >= 1 }
            assertTrue("pull" !in f.calls && app.config.embedModel.isEmpty(), "no download, no setting")
            val result = app.chat.messages.first { it.role == "tool" }.content
            assertTrue(result.startsWith("Error: the user declined") && "search_notes" in result, result)
        } finally { f.server.stop(0) }
    }
}
