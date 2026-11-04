package com.arcana.brain.app

import com.arcana.brain.app.screens.ThinkLevels
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reasoning level and the keep-alive in real requests, against a fake Ollama that lists its levels the way `/api/show` does: a level picked for one message is sent with
 * that message only, the Settings value with the next; keep_alive goes with every request to the chat model and with no request to another one.
 */
class ThinkAndKeepAliveTest {
    private class Fake(val server: HttpServer, val bodies: MutableList<String>)

    private fun fake(showJson: String): Fake {
        val bodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/show") { e -> e.requestBody.readBytes(); val b = showJson.toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) } }
        server.createContext("/api/tags") { e -> val b = """{"models":[{"name":"qwen"},{"name":"other"}]}""".toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) } }
        server.createContext("/api/ps") { e -> val b = """{"models":[]}""".toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) } }
        server.createContext("/api/chat") { e ->
            bodies += e.requestBody.readBytes().decodeToString()
            val b = ("""{"message":{"role":"assistant","content":"ok"},"done":true,"prompt_eval_count":5,"eval_count":2}""" + "\n").toByteArray()
            e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) }
        }
        server.start()
        return Fake(server, bodies)
    }

    private fun app(f: Fake, config: AppConfig.() -> AppConfig): AppContext {
        val dir = Files.createTempDirectory("arcana-think")
        val store = ConfigStore(dir.resolve("config.json"))
        store.save(AppConfig(ollamaUrl = "http://127.0.0.1:${f.server.address.port}", model = "qwen", keepChats = false).config())
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), SessionStore(dir.resolve("sessions")), EventStore(dir.resolve("events.jsonl")))
            .also { it.scope = CoroutineScope(Dispatchers.Default) }
    }

    private fun waitFor(f: Fake, count: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (f.bodies.size < count && System.currentTimeMillis() < deadline) Thread.sleep(30)
        Thread.sleep(200) // let the reply finish and be added
    }

    private val qwen38 = """{"capabilities":["completion","tools","thinking"],"thinking":{"values":[false,"low","medium","xhigh"],"default":"medium"},"parameters":"temperature 1\ntop_k 20\ntop_p 0.95","model_info":{"qwen3_5.context_length":262144}}"""

    @Test
    fun aLevelPickedForOneMessageIsSentWithThatMessageOnlyAndTheModelsOwnListFeedsTheChoices() {
        val f = fake(qwen38)
        try {
            val app = app(f) { copy(thinking = "off", keepAlive = "30m") }
            runBlocking { app.refreshConnection() }
            assertEquals(listOf("default", "off", "low", "medium", "xhigh"), ThinkLevels.options, "the choices are what Ollama listed (no invented 'high'), with false as off")
            assertEquals("temperature" to "1", app.modelParameters.entries.first().toPair(), "the model's own sampling defaults are read")
            val chat = app.chat
            chat.thinkNext = "xhigh"
            chat.send("hard one")
            waitFor(f, 1)
            assertTrue("\"think\":\"xhigh\"" in f.bodies[0], f.bodies[0])
            assertTrue("\"keep_alive\":\"30m\"" in f.bodies[0], "keep_alive goes with the chat model's request")
            assertEquals("xhigh", chat.messages.first { it.role == "user" }.think, "the level is kept on the message")
            assertEquals("", chat.thinkNext, "and the pick is used up")
            assertTrue("\"think\":\"xhigh\"" !in f.bodies[0].substringAfter("\"messages\""), "the field never goes to the model inside a message")
            chat.send("easy one")
            waitFor(f, 2)
            assertTrue("\"think\":false" in f.bodies[1], "the next message is back to the Settings value (off): ${f.bodies[1]}")
        } finally { f.server.stop(0) }
    }

    @Test
    fun aLevelTheModelDoesNotListIsNotKeptOrSent() {
        val f = fake(qwen38)
        try {
            val app = app(f) { copy(thinking = "default") }
            runBlocking { app.refreshConnection() }
            app.chat.thinkNext = "high" // qwen3.8 has no "high": that is what a gpt-oss level would look like after a model change
            app.chat.send("hi")
            waitFor(f, 1)
            assertTrue("\"think\"" !in f.bodies[0], f.bodies[0])
            assertEquals("", app.chat.messages.first { it.role == "user" }.think)
        } finally { f.server.stop(0) }
    }

    @Test
    fun keepAliveIsSentOnlyToTheChatModelAndOnlyWhenSet() {
        val cfg = AppConfig(model = "qwen", keepAlive = "-1", oraculModel = "other")
        assertEquals("-1", cfg.keepAliveValue().toString(), "-1 is a number for Ollama")
        assertEquals("\"1h\"", cfg.copy(keepAlive = "1h").keepAliveValue().toString())
        assertEquals(null, cfg.copy(keepAlive = "").keepAliveValue())
        assertEquals(null, cfg.copy(keepAlive = "soon").keepAliveValue(), "anything that is not a time is not sent")
        assertEquals("-1", com.arcana.brain.core.Oracul.serverConfig(cfg.copy(oraculModel = "qwen"), "qwen").keepAliveValue().toString(), "a helper that is the chat model keeps the pin: its request would reset the timer")
        assertEquals(null, com.arcana.brain.core.Oracul.serverConfig(cfg, "other").keepAliveValue(), "another model is not pinned")
        assertEquals(null, com.arcana.brain.core.Oracul.serverConfig(cfg.copy(oraculUrl = "http://big:11434"), "qwen").keepAliveValue(), "another server is not told")
    }
}
