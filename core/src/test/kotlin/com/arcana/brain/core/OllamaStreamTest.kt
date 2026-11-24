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
