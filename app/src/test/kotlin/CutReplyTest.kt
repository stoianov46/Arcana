package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.OllamaClient
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A reply Ollama ended at a limit (`done_reason: length`) is kept but says it was cut off; one that only thought until the limit is an error, not retried. */
class CutReplyTest {
    private fun run(stream: String): Pair<Generation, String?> {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        var requests = 0
        server.createContext("/api/chat") { ex ->
            ex.requestBody.readBytes(); requests++
            val b = stream.toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            var completed: String? = null
            val gen = Generation(CoroutineScope(Dispatchers.Default), OllamaClient { AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}", model = "m") }, onComplete = { t, _ -> completed = t })
            gen.start { listOf(ChatMessage.user("hi")) }
            val deadline = System.currentTimeMillis() + 10_000
            while (gen.state == GenState.Running && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(1, requests)
            return gen to completed
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aCutOffReplySaysSo() {
        val (gen, completed) = run("""{"message":{"role":"assistant","content":"The first half"},"done":false}""" + "\n" + """{"message":{"role":"assistant","content":""},"done":true,"done_reason":"length","prompt_eval_count":3,"eval_count":2}""" + "\n")
        assertEquals(GenState.Done, gen.state)
        assertTrue(completed!!.startsWith("The first half") && completed.endsWith(CUT_NOTE), completed)
    }

    @Test
    fun aReplyThatOnlyThoughtUntilTheLimitIsAnError() {
        val (gen, completed) = run("""{"message":{"role":"assistant","content":"","thinking":"hmm"},"done":false}""" + "\n" + """{"message":{"role":"assistant","content":""},"done":true,"done_reason":"length"}""" + "\n")
        assertEquals(GenState.Failed, gen.state)
        assertEquals(CUT_EMPTY, gen.error)
        assertEquals(null, completed)
    }

    @Test
    fun aFinishedReplyHasNoNote() {
        val (gen, completed) = run("""{"message":{"role":"assistant","content":"All done."},"done":true,"done_reason":"stop","prompt_eval_count":3,"eval_count":2}""" + "\n")
        assertEquals(GenState.Done, gen.state)
        assertEquals("All done.", completed)
    }
}
