package com.arcana.brain.app

import com.arcana.brain.app.screens.ModelPickerState
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
import com.arcana.brain.core.ModelDetails
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import com.idea.ink.input.Key
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/model … --retry`: the new model gets your last message again, with the earlier attempt still in the chat; the `/model` list's keys. */
class ModelSwitchRetryTest {
    private fun fake(bodies: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun reply(e: com.sun.net.httpserver.HttpExchange, text: String) { val b = text.toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) } }
        server.createContext("/api/tags") { e -> reply(e, """{"models":[{"name":"small:4b"},{"name":"big:26b"}]}""") }
        server.createContext("/api/ps") { e -> reply(e, """{"models":[]}""") }
        server.createContext("/api/show") { e -> e.requestBody.readBytes(); reply(e, """{"capabilities":["completion"]}""") }
        server.createContext("/api/chat") { e ->
            val body = e.requestBody.readBytes().decodeToString()
            bodies += body
            val model = Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content
            reply(e, """{"message":{"role":"assistant","content":"Answer from $model."},"done":true,"prompt_eval_count":5,"eval_count":2}""" + "\n")
        }
        server.start()
        return server
    }

    private fun answered(app: AppContext, n: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (app.chat.messages.toList().count { it.role == "assistant" } < n && System.currentTimeMillis() < deadline) Thread.sleep(30)
        Thread.sleep(150)
    }

    @Test
    fun theNewModelGetsTheSameTaskWithTheEarlierAttemptInFrontOfIt() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fake(bodies)
        try {
            val dir = Files.createTempDirectory("arcana-switch")
            val store = ConfigStore(dir.resolve("config.json"))
            store.save(AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}", model = "small:4b", keepChats = false, toolsEnabled = false, memoryEnabled = false))
            val app = AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), EventStore(dir.resolve("events.jsonl")))
                .also { it.scope = CoroutineScope(Dispatchers.Default) }

            assertFalse(app.chat.retryLastTask(), "nothing to run again in an empty chat")
            app.chat.send("Write a haiku about tomatoes")
            answered(app, 1)

            // what switchModel does: the setting, then what Ollama says about the new model, then the task again
            app.update(app.config.copy(model = "big:26b"))
            runBlocking { app.refreshConnection() }
            assertTrue(app.chat.retryLastTask())
            answered(app, 2)

            assertEquals(listOf("small:4b", "big:26b"), bodies.map { Json.parseToJsonElement(it).jsonObject["model"]!!.jsonPrimitive.content })
            val sent = Json.parseToJsonElement(bodies[1]).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]!!.jsonPrimitive.content != "system" }
            assertEquals(listOf("user", "assistant", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
            assertTrue("haiku" in sent.last()["content"]!!.jsonPrimitive.content, "the same task, last")
            assertEquals("Answer from small:4b.", sent[1]["content"]!!.jsonPrimitive.content, "the earlier attempt is in front of it")
            val shown = app.chat.messages.toList()
            assertEquals(2, shown.count { it.role == "user" && "haiku" in it.content })
            assertEquals("Answer from big:26b.", shown.last().content)
            assertEquals(listOf("small:4b", "big:26b"), shown.filter { it.role == "assistant" }.map { it.model }, "each reply keeps the name of the model that wrote it")
        } finally { server.stop(0) }
    }

    @Test
    fun theListSwitchesOnEnterAndAlsoRunsTheTaskAgainOnR() {
        val models = listOf(ModelDetails("small:4b", 3_000_000_000, listOf("completion", "tools"), 32_768), ModelDetails("big:26b", 20_000_000_000, listOf("completion"), 131_072))
        val picks = mutableListOf<Pair<String, Boolean>>()
        var closed = 0
        val state = ModelPickerState()
        state.open(models, current = "small:4b", retry = false, ram = 32_000_000_000)
        assertEquals(0, state.picker.index, "the current model is highlighted")
        assertTrue(state.note(models[0], "small:4b").startsWith("current · "))
        assertTrue("too big" !in state.note(models[0], "small:4b"))

        state.handle(Key.Down, { m, r -> picks += m to r }) { closed++ }
        state.handle(Key.Enter, { m, r -> picks += m to r }) { closed++ }
        state.open(models, current = "big:26b", retry = false, ram = 32_000_000_000)
        state.handle(Key.Text('r'), { m, r -> picks += m to r }) { closed++ }
        state.open(models, current = "small:4b", retry = true, ram = 32_000_000_000)
        state.handle(Key.Enter, { m, r -> picks += m to r }) { closed++ }
        state.open(models, current = "small:4b", retry = false)
        state.handle(Key.Escape, { m, r -> picks += m to r }) { closed++ }

        assertEquals(listOf("big:26b" to false, "big:26b" to true, "small:4b" to true), picks)
        assertEquals(4, closed)
    }
}
