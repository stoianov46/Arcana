package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
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

/** A `/vault` answer: the notes are found by the method the setting names, the model is sent the matching passage, and the answer's Sources line says which method ran. */
class VaultAnswersTest {
    /** [toolRounds]: the replies to give first, as whole lines (a scripted tool call), before the usual "Answer.". */
    private fun fake(bodies: MutableList<String>, toolRounds: List<String> = emptyList()): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun reply(e: com.sun.net.httpserver.HttpExchange, text: String) { val b = text.toByteArray(); e.sendResponseHeaders(200, b.size.toLong()); e.responseBody.use { it.write(b) } }
        server.createContext("/api/tags") { e -> reply(e, """{"models":[{"name":"qwen"}]}""") }
        server.createContext("/api/ps") { e -> reply(e, """{"models":[]}""") }
        server.createContext("/api/show") { e -> e.requestBody.readBytes(); reply(e, """{"capabilities":["completion","tools"]}""") }
        server.createContext("/api/chat") { e ->
            bodies += e.requestBody.readBytes().decodeToString()
            val scripted = toolRounds.getOrNull(bodies.size - 1)
            reply(e, (scripted ?: """{"message":{"role":"assistant","content":"Answer."},"done":true,"prompt_eval_count":5,"eval_count":2}""") + "\n")
        }
        server.start()
        return server
    }

    private fun app(server: HttpServer, vault: java.nio.file.Path, mode: String, tools: Boolean = false): AppContext {
        val dir = Files.createTempDirectory("arcana-vaultanswers")
        val store = ConfigStore(dir.resolve("config.json"))
        store.save(AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}", model = "qwen", keepChats = false, vaultPath = vault.toString(), chatUseVault = true, searchBy = mode, toolsEnabled = tools, toolAutoRead = true))
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), EventStore(dir.resolve("events.jsonl")))
            .also { it.scope = CoroutineScope(Dispatchers.Default) }
    }

    private fun waitForAnswer(app: AppContext) {
        val deadline = System.currentTimeMillis() + 15_000
        while (app.chat.messages.toList().none { it.role == "assistant" } && System.currentTimeMillis() < deadline) Thread.sleep(30)
        Thread.sleep(150)
    }

    private fun vault(): java.nio.file.Path = Files.createTempDirectory("vault").also {
        Files.writeString(it.resolve("Models.md"), "# Models\n\n" + "Filler about nothing. ".repeat(80) + "\n\n## Levels\n\nqwen3.8:27b offers the thinking levels low, medium and xhigh.\n")
        Files.writeString(it.resolve("Other.md"), "# Other\n\nSomething unrelated about gardens.\n")
    }

    @Test
    fun theAnswerIsSentTheMatchingPassageAndNamesTheMethodThatFoundIt() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fake(bodies)
        try {
            val app = app(server, vault(), mode = "both")
            app.chat.send("which levels does qwen3.8:27b offer?")
            waitForAnswer(app)
            val answer = app.chat.messages.toList().last { it.role == "assistant" }
            assertEquals(listOf("Models.md"), answer.sources, "found by the identifier, which a word search over whole notes would also find, but now by passage")
            assertEquals("keywords", answer.sourcesBy, "no embedding model is set up: words carried on alone, and the answer says so")
            assertTrue("xhigh" in bodies.first() && "Levels" in bodies.first(), "the model is sent the passage that matched, not the start of the note")
        } finally { server.stop(0) }
    }

    @Test
    fun meaningOnlyWithoutAModelFindsNothingAndSaysNothingWasFoundByIt() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fake(bodies)
        try {
            val app = app(server, vault(), mode = "meaning")
            app.chat.send("which levels does qwen3.8:27b offer?")
            waitForAnswer(app)
            val answer = app.chat.messages.toList().last { it.role == "assistant" }
            assertTrue(answer.sources.isEmpty() && answer.sourcesBy.isEmpty(), "no notes, so no Sources line")
            assertTrue("No matching notes were found" in bodies.first(), "the model is told so")
        } finally { server.stop(0) }
    }

    @Test
    fun theAnswerListsEveryNoteItIsBuiltFromWithHowItGotThereAndTheToolsUsed() {
        val bodies = CopyOnWriteArrayList<String>()
        val call = """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"read_note","arguments":{"path":"Other.md"}}}]},"done":true,"prompt_eval_count":5,"eval_count":2}"""
        val server = fake(bodies, listOf(call))
        try {
            val app = app(server, vault(), mode = "keywords", tools = true)
            runBlocking { app.refreshConnection() }
            app.chat.send("which levels does qwen3.8:27b offer?")
            waitForAnswer(app)
            val deadline = System.currentTimeMillis() + 15_000
            while (app.chat.messages.toList().count { it.role == "assistant" && it.toolCalls.isEmpty() } < 1 && System.currentTimeMillis() < deadline) Thread.sleep(30)
            val answer = app.chat.messages.toList().last { it.role == "assistant" && it.toolCalls.isEmpty() }
            assertEquals(listOf("Other.md", "Models.md"), answer.sources, "the note the model opened first, then the one sent as context")
            assertEquals("read", answer.sourcesInfo[0], "opened by the model, not part of the context")
            assertTrue(answer.sourcesInfo[1].startsWith("context · keywords · words "), answer.sourcesInfo[1])
            assertEquals(listOf("read_note"), answer.toolsUsed)
            assertEquals("keywords", answer.sourcesBy)
            // the screen shows one note per line and the tools used
            val shown = com.arcana.brain.app.screens.sourceLines(answer, false, 100).map { it.text }
            assertEquals(3, shown.size, shown.toString())
            assertTrue(shown[0].startsWith("Sources (2)") && "vault search: keywords" in shown[0] && "Other" in shown[1] && "read" in shown[1] && "Models" in shown[2] && "context" in shown[2], shown.toString())
        } finally { server.stop(0) }
    }
}
