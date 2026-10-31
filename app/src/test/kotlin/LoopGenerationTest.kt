package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.LoopGuard
import com.arcana.brain.core.OllamaClient
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The loop guard in the real [Generation]: a round whose text goes in circles is stopped, asked again once, and never ends as a wall of repeated text. */
class LoopGenerationTest {
    private val paragraph = "Wait, I should double check the folder name once more before I answer, because the earlier listing might be stale. "

    /** The first [loopingRequests] requests stream a looping thought without ever finishing; later ones answer. */
    private fun fake(loopingRequests: Int, bodies: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/chat") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            val looping = bodies.size <= loopingRequests
            exchange.sendResponseHeaders(200, 0)
            runCatching {
                exchange.responseBody.use { out ->
                    if (looping) repeat(200) { out.write("""{"message":{"role":"assistant","content":"","thinking":"$paragraph"},"done":false}""".plus("\n").toByteArray()); out.flush() }
                    else out.write("""{"message":{"role":"assistant","content":"Fixed answer."},"done":true,"prompt_eval_count":1,"eval_count":1}""".plus("\n").toByteArray())
                }
            }
        }
        server.start()
        return server
    }

    private fun run(server: HttpServer, loops: MutableList<String>, repeats: Int = 5): Triple<Generation, () -> String?, () -> Long> {
        var completed: String? = null
        val client = OllamaClient { AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}", model = "m") }
        val gen = Generation(
            scope = CoroutineScope(Dispatchers.Default), client = client,
            onComplete = { text, _ -> completed = text },
            loopRepeats = { repeats }, onLoop = { loops += it },
        )
        val started = System.currentTimeMillis()
        gen.start { listOf(ChatMessage.user("do it")) }
        val deadline = System.currentTimeMillis() + 20_000
        while (gen.state == GenState.Running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return Triple(gen, { completed }, { System.currentTimeMillis() - started })
    }

    @Test
    fun aLoopingThoughtIsStoppedAndAskedAgainWithAPenaltyAndANudge() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fake(loopingRequests = 1, bodies = bodies)
        try {
            val loops = CopyOnWriteArrayList<String>()
            val (gen, completed, _) = run(server, loops)
            assertEquals(GenState.Done, gen.state)
            assertEquals("Fixed answer.", completed())
            assertEquals(listOf("text"), loops.toList())
            assertEquals(2, bodies.size)
            assertTrue("repeat_penalty" !in bodies[0])
            assertTrue("\"repeat_penalty\":1.2" in bodies[1], "the retry asks for a higher repeat penalty: ${bodies[1].takeLast(200)}")
            assertTrue(LoopGuard.TEXT_NUDGE.take(40) in bodies[1], "the model is told what happened")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aSecondLoopEndsInAnErrorThatSaysSo() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fake(loopingRequests = 5, bodies = bodies)
        try {
            val loops = CopyOnWriteArrayList<String>()
            val (gen, completed, _) = run(server, loops)
            assertEquals(GenState.Failed, gen.state)
            assertTrue(gen.error.orEmpty().contains("repeating"), gen.error)
            assertEquals(null, completed())
            assertEquals(2, bodies.size, "one retry, not more")
            assertEquals(2, loops.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun withTheGuardOffNothingIsStopped() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fake(loopingRequests = 0, bodies = bodies)
        try {
            val loops = CopyOnWriteArrayList<String>()
            val (gen, completed, _) = run(server, loops, repeats = 0)
            assertEquals("Fixed answer.", completed())
            assertEquals(GenState.Done, gen.state)
            assertTrue(loops.isEmpty())
        } finally {
            server.stop(0)
        }
    }
}
