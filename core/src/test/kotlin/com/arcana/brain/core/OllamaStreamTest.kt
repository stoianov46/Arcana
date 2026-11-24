package com.arcana.brain.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The streaming request to Ollama against a fake server: cancelling, stalls, time-outs, retries, a cut stream and `done_reason`. */
class OllamaStreamTest {
    private val servers = ArrayList<HttpServer>()
    private val savedIdle = OllamaClient.minIdleSeconds
    private val savedDelays = OllamaClient.retryDelaysMs

    @BeforeTest
    fun fast() { OllamaClient.retryDelaysMs = longArrayOf(10, 10) }

    @AfterTest
    fun restore() {
        servers.forEach { it.stop(0) }
        OllamaClient.minIdleSeconds = savedIdle
        OllamaClient.retryDelaysMs = savedDelays
    }

    /** A server whose `/api/chat` runs [handler]; `/api/ps` is not served, so the make-room step finds nothing to do. */
    private fun server(handler: (HttpExchange, Int) -> Unit): Pair<OllamaClient, AtomicInteger> {
        val calls = AtomicInteger()
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.executor = java.util.concurrent.Executors.newCachedThreadPool()
        s.createContext("/api/chat") { ex -> ex.requestBody.readBytes(); handler(ex, calls.incrementAndGet()) }
        s.start()
        servers += s
        return OllamaClient { AppConfig(ollamaUrl = "http://127.0.0.1:${s.address.port}", model = "m", timeoutSeconds = 2) } to calls
    }

    private fun HttpExchange.json(status: Int, body: String) {
        val b = body.toByteArray()
        sendResponseHeaders(status, b.size.toLong())
        responseBody.use { it.write(b) }
    }

    private fun line(content: String, done: Boolean = false, reason: String = "") =
        """{"message":{"role":"assistant","content":"$content"},"done":$done${if (reason.isEmpty()) "" else ""","done_reason":"$reason""""}${if (done) ""","prompt_eval_count":3,"eval_count":2""" else ""}}""" + "\n"

    @Test
    fun cancellingStopsAStreamingReplyAtOnceAndClosesTheConnection() {
        val closed = AtomicBoolean(false)
        val (client, _) = server { ex, _ ->
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            out.write(line("first").toByteArray()); out.flush()
            // a long silence, as while a model writes a tool call; the server notices when the client hangs up
            try { repeat(300) { Thread.sleep(100); out.write("\n".toByteArray()); out.flush() } } catch (e: java.io.IOException) { closed.set(true) }
        }
        runBlocking {
            val first = CountDownLatch(1)
            val job = CoroutineScope(Dispatchers.Default).launch { client.chat(listOf(ChatMessage.user("hi"))).collect { first.countDown() } }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            val t0 = System.currentTimeMillis()
            job.cancel(); job.join()
            assertTrue(System.currentTimeMillis() - t0 < 2_000, "the collector ends at once")
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!closed.get() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(closed.get(), "the connection is closed, so Ollama stops generating")
    }

    @Test
    fun aSilentStreamIsStoppedAsStalled() {
        OllamaClient.minIdleSeconds = 1
        val (client, _) = server { ex, _ ->
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            out.write(line("first").toByteArray()); out.flush()
            runCatching { Thread.sleep(10_000); out.close() }
        }
        val e = assertFailsWith<OllamaException> { runBlocking { withTimeout(8_000) { client.chat(listOf(ChatMessage.user("hi"))).toList() } } }
        assertTrue("stopped sending anything" in e.message.orEmpty(), e.message)
    }

    @Test
    fun noAnswerWithinTheTimeoutSaysSoAndIsNotRetried() {
        val (client, calls) = server { ex, _ -> runCatching { Thread.sleep(5_000); ex.json(200, line("late", true)) } }
        val e = assertFailsWith<OllamaException> { runBlocking { client.chat(listOf(ChatMessage.user("hi"))).toList() } }
        assertTrue("did not start answering within 2 s" in e.message.orEmpty() && "Cannot reach" !in e.message.orEmpty(), e.message)
        assertEquals(1, calls.get())
    }

    @Test
    fun aBusyOrRestartingOllamaIsTriedAgain() {
        val (client, calls) = server { ex, n ->
            when (n) {
                1 -> ex.json(503, """{"error":"server busy, please try again"}""")
                2 -> ex.json(500, """{"error":"llama runner process has terminated: signal: killed"}""")
                else -> ex.json(200, line("ok", true, "stop"))
            }
        }
        val chunks = runBlocking { client.chat(listOf(ChatMessage.user("hi"))).toList() }
        assertEquals("ok", chunks.joinToString("") { it.content })
        assertEquals(3, calls.get())
    }

    @Test
    fun aRealErrorIsNotRetriedAndReadsCleanly() {
        val (client, calls) = server { ex, _ -> ex.json(400, """{"error":"model \"m\" not found, try pulling it first\nsecond line"}""") }
        val e = assertFailsWith<OllamaException> { runBlocking { client.chat(listOf(ChatMessage.user("hi"))).toList() } }
        assertEquals("Ollama returned HTTP 400: model \"m\" not found, try pulling it first\nsecond line", e.message)
        assertEquals(1, calls.get())
        // an out-of-memory 500 is explained, not retried
        val (oom, oomCalls) = server { ex, _ -> ex.json(500, """{"error":"mlx runner failed: Insufficient Memory"}""") }
        assertTrue(assertFailsWith<OllamaException> { runBlocking { oom.chat(listOf(ChatMessage.user("hi"))).toList() } }.message!!.startsWith("Out of memory"))
        assertEquals(1, oomCalls.get())
    }

    @Test
    fun aStreamThatEndsWithoutDoneIsAnErrorAndDoneReasonIsRead() {
        val (cut, _) = server { ex, _ -> ex.json(200, line("half")) }
        val e = assertFailsWith<OllamaException> { runBlocking { cut.chat(listOf(ChatMessage.user("hi"))).toList() } }
        assertTrue("before it was finished" in e.message.orEmpty(), e.message)

        val (limited, _) = server { ex, _ -> ex.json(200, line("part") + line("", true, "length")) }
        val chunks = runBlocking { limited.chat(listOf(ChatMessage.user("hi"))).toList() }
        assertTrue(chunks.last().cutOff && !chunks.first().cutOff)

        val (garbled, _) = server { ex, _ -> ex.json(200, "not json\n") }
        assertTrue("cannot read" in assertFailsWith<OllamaException> { runBlocking { garbled.chat(listOf(ChatMessage.user("hi"))).toList() } }.message.orEmpty())
    }
}

/** What a request carries: only Ollama's fields, the thoughts of the turn under way, and messages frozen as they were first sent. */
class FrozenRequestTest {
    private fun wire(messages: List<ChatMessage>) = OllamaProtocol.chatRequest(AppConfig(), messages)["messages"] as kotlinx.serialization.json.JsonArray

    @Test
    fun theThoughtsOfTheTurnUnderWayGoBackAndNothingOfArcanasOwn() {
        val call = ToolCall(ToolFunction("read_note", kotlinx.serialization.json.buildJsonObject { put("path", kotlinx.serialization.json.JsonPrimitive("a")) }))
        val old = ChatMessage.assistantCalls("", listOf(call), thinking = "old plan")
        val messages = listOf(
            ChatMessage.user("first"), old, ChatMessage.toolResult("read_note", "x"), ChatMessage.assistant("done", sources = listOf("a.md"), thinking = "old answer thoughts"),
            ChatMessage.user("second").copy(sent = "second with excerpts"),
            ChatMessage.assistantCalls("", listOf(call), thinking = "p".repeat(5_000) + "THE PLAN"),
        )
        val sent = wire(messages).map { it as kotlinx.serialization.json.JsonObject }
        assertTrue("thinking" !in sent[1] && "thinking" !in sent[3], "earlier turns' thoughts stay home")
        val now = (sent[5]["thinking"] as kotlinx.serialization.json.JsonPrimitive).content
        assertTrue(now.endsWith("THE PLAN") && now.length == OllamaProtocol.SENT_THINKING, "the end of this turn's plan goes back, capped")
        val text = sent.toString()
        assertTrue("sources" !in text && "\"at\"" !in text && "\"sent\"" !in text && "stats" !in text, text)
        // the frozen text is what Prompts sends for that message, never Arcana's field itself
        val prompt = Prompts.chatMessages(AppConfig(), messages, null, emptyMap())
        assertEquals("second with excerpts", prompt.first { it.content.startsWith("second") }.content)
    }

    @Test
    fun aChangeIsToldOnceWithTheNextMessageAndTheSystemPromptStays() {
        val freeze = SystemFreeze()
        val a = Prompts.SystemParts("Today is Monday.", "tools: a", "- Answer briefly.", "")
        var built = 0
        val build = { p: Prompts.SystemParts -> built++; "SYSTEM " + p.memory }
        assertEquals("SYSTEM - Answer briefly." to "", freeze.take("k", a, commit = true, build))
        val b = a.copy(memory = "- Answer briefly.\n- Use metric units.", goal = "Plan the trip")
        // between the rounds of a turn nothing is handed out (and nothing is lost)
        assertEquals("SYSTEM - Answer briefly." to "", freeze.take("k", b, commit = false, build))
        val (system, note) = freeze.take("k", b, commit = true, build)
        assertEquals("SYSTEM - Answer briefly.", system)
        assertTrue("Added:" in note && "Use metric units" in note && "Answer briefly" !in note.substringAfter("Added:") && "goal of this chat is now: Plan the trip" in note, note)
        assertEquals("", freeze.take("k", b, commit = true, build).second, "told once")
        // another kind of chat (tools switched on, another model) builds it anew
        assertEquals("SYSTEM - Answer briefly.\n- Use metric units." to "", freeze.take("other", b, commit = true, build))
        assertEquals(2, built)
        freeze.reset()
        freeze.take("other", a, commit = true, build)
        assertEquals(3, built)
    }
}

/** Compaction reads everything, the estimates count what is really sent, verdicts come as JSON or text, and the judge's labels get their models back. */
class HelperRequestTest {
    @Test
    fun compactionPiecesCoverTheWholeChatWithTheArgumentsOfEveryCall() {
        val call = ToolCall(ToolFunction("read_note", kotlinx.serialization.json.buildJsonObject { put("path", kotlinx.serialization.json.JsonPrimitive("Projects/Plan.md")) }))
        val chat = (1..40).flatMap { i -> listOf(ChatMessage.user("question $i " + "x".repeat(2_000)), ChatMessage.assistantCalls("", listOf(call)), ChatMessage.toolResult("read_note", "r"), ChatMessage.assistant("answer $i")) }
        val pieces = Compaction.pieces(chat)
        assertTrue(pieces.size > 1 && pieces.all { it.length <= 30_000 })
        val all = pieces.joinToString("\n")
        assertTrue((1..40).all { "question $it " in all && "answer $it" in all }, "nothing in the middle is left out")
        assertTrue("(called read_note({\"path\":\"Projects/Plan.md\"}))" in all, "the paths a call used are kept")
        assertTrue(Compaction.pieces(listOf(ChatMessage.user("y".repeat(100_000)))).let { p -> p.size == 4 && p.all { it.length <= 30_000 } })
        val next = Compaction.pieceRequest("PART TWO", "summary so far")
        assertTrue("EARLIER SUMMARY" in next.last().content && "summary so far" in next.last().content && next.last().content.endsWith("PART TWO"))
        assertEquals("PART ONE", Compaction.pieceRequest("PART ONE", "").last().content)
    }

    @Test
    fun estimatesCountWhatIsSentAndNonAsciiText() {
        assertEquals(25, Compaction.tokens("a".repeat(100)))
        assertEquals(40, Compaction.tokens("я".repeat(100)), "Cyrillic takes more tokens per character")
        val frozen = ChatMessage.user("hi").copy(sent = "hi" + "e".repeat(4_000))
        assertTrue(Compaction.messageTokens(frozen) > 1_000, "a frozen message counts with its excerpts")
    }

    @Test
    fun verdictsAreReadFromJsonOrText() {
        assertEquals(Oracul.Control(false, "- fix the path"), Oracul.parseControl("""{"verdict":"REDO","notes":"- fix the path"}"""))
        assertEquals(Oracul.Control(true, ""), Oracul.parseControl("""{"verdict":"done","notes":""}"""))
        assertEquals(Oracul.GoalCheck(false, "tags missing"), Oracul.parseGoal("""{"verdict":"NOT MET","notes":"tags missing"}"""))
        assertEquals(Oracul.GoalCheck(true, ""), Oracul.parseGoal("VERDICT: MET"), "plain text still works")
        assertTrue(Oracul.parseControl("""{"verdict":"MAYBE"}""").done, "an unknown verdict falls back to the text rules")
        val request = OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("q")), format = Oracul.CONTROL_SCHEMA).toString()
        assertTrue("\"format\":{\"type\":\"object\"" in request && "\"enum\":[\"DONE\",\"REDO\"]" in request, request)
    }

    @Test
    fun theJudgeSeesNoNamesAndTheReaderSeesThem() {
        val others = listOf("big" to "Answer from big.", "other" to "Answer from other.")
        val request = Oracul.judgeRequest("Q?", "worker", "Worker's answer.", others, order = listOf(2, 0, 1)).last().content
        assertTrue(request.indexOf("Answer from other.") < request.indexOf("Worker's answer.") && request.indexOf("Worker's answer.") < request.indexOf("Answer from big."))
        assertTrue("worker" !in request.replace("Worker's", "") && "(big)" !in request && "ANSWER A:" in request && "ANSWER C:" in request)
        assertEquals("Answer B (worker) is best; ANSWER C (big) misses it. Answer A (other) is close.",
            Oracul.judgeLabels("Answer B is best; ANSWER C misses it. Answer A is close.", "worker", others, listOf(2, 0, 1)))
    }
}
