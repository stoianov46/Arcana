package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.OllamaClient
import com.arcana.brain.core.Requirement
import com.arcana.brain.core.SkillRun
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the real tool loop against a fake Ollama server: the completion gate must not let a skill run end with a required step missing. */
class SkillGateTest {
    private fun fakeOllama(replies: List<String>, bodies: MutableList<String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/chat") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            val bytes = (replies[minOf(bodies.size - 1, replies.size - 1)] + "\n").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun reply(content: String, toolCall: String? = null): String {
        val calls = if (toolCall == null) "" else ""","tool_calls":[{"function":{"name":"$toolCall","arguments":{"title":"T","content":"x"}}}]"""
        return """{"message":{"role":"assistant","content":"$content"$calls},"done":true,"prompt_eval_count":10,"eval_count":5}"""
    }

    private fun runLoop(server: HttpServer, run: SkillRun?, recorded: MutableList<String>): Pair<Generation, () -> String?> {
        var completed: String? = null
        val client = OllamaClient { AppConfig(ollamaUrl = "http://127.0.0.1:${server.address.port}", model = "m") }
        val gen = Generation(
            scope = CoroutineScope(Dispatchers.Default), client = client,
            onComplete = { text, _ -> completed = text },
            onToolRound = { text, calls, _ ->
                recorded += "round:" + calls.joinToString { it.name }
                val results = calls.map { call -> run?.observe(call.name, call.arguments.toString(), "Created x"); ChatMessage.toolResult(call.name, "Created x") }
                listOf(ChatMessage.assistantCalls(text, calls)) + results
            },
            gate = { run?.nudge() },
        )
        gen.start(tools = JsonArray(listOf(JsonObject(emptyMap()))), maxSteps = 5) { listOf(ChatMessage.user("do it")) }
        val deadline = System.currentTimeMillis() + 20_000
        while (gen.state == GenState.Running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return gen to { completed }
    }

    @Test
    fun aSkillRunWithAMissingStepSendsTheModelBackInsteadOfFinishing() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fakeOllama(listOf(reply("All done, nothing more to do."), reply("", toolCall = "create_note"), reply("Finished for real.")), bodies)
        try {
            val run = SkillRun("demo", listOf(Requirement("create_note", "", "", 1, "step 6: create the report note")))
            val recorded = CopyOnWriteArrayList<String>()
            val (gen, completed) = runLoop(server, run, recorded)
            assertEquals(GenState.Done, gen.state)
            assertEquals("Finished for real.", completed(), "the first 'All done' was not accepted as the end")
            assertEquals(3, bodies.size)
            assertTrue("STOP: you have not finished the skill" !in bodies[0])
            assertTrue("STOP: you have not finished the skill" in bodies[1] && "step 6: create the report note" in bodies[1], "the model is told which step is missing: ${bodies[1].takeLast(400)}")
            assertEquals(1, Regex("STOP: you have not finished").findAll(bodies[2]).count(), "the earlier STOP stays in the history; once the step is done no new one is added")
            assertEquals(listOf("round:", "round:create_note"), recorded.toList(), "the premature reply stays in the chat, then the real tool round")
            assertTrue(run.finished && run.done == 1 && run.skippedCount == 0)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun thinkingIsKeptApartFromTheAnswerAndNeverSentBack() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/chat") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            val bytes = ("""{"message":{"role":"assistant","content":"","thinking":"Let me "},"done":false}""" + "\n" +
                """{"message":{"role":"assistant","content":"","thinking":"think."},"done":false}""" + "\n" +
                """{"message":{"role":"assistant","content":"Answer."},"done":true,"prompt_eval_count":1,"eval_count":1}""" + "\n").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val (gen, completed) = runLoop(server, null, CopyOnWriteArrayList())
            assertEquals("Answer.", completed())
            assertEquals("Let me think.", gen.takeThinking())
            assertEquals("", gen.takeThinking(), "reading it clears it")
            val request = com.arcana.brain.core.OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.assistant("Answer.", thinking = "secret thoughts")))
            assertTrue("secret thoughts" !in request.toString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anEmptyReplyIsAskedAgainOnceBeforeItIsAnError() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fakeOllama(listOf(reply(""), reply("Here is the summary.")), bodies)
        try {
            val (gen, completed) = runLoop(server, null, CopyOnWriteArrayList())
            assertEquals(GenState.Done, gen.state)
            assertEquals("Here is the summary.", completed())
            assertTrue("Your last reply was empty" in bodies[1] && "Your last reply was empty" !in bodies[0])
        } finally {
            server.stop(0)
        }
        val again = CopyOnWriteArrayList<String>()
        val stubborn = fakeOllama(listOf(reply("")), again)
        try {
            val (gen, _) = runLoop(stubborn, null, CopyOnWriteArrayList())
            assertEquals(GenState.Failed, gen.state)
            assertEquals("The model returned an empty response.", gen.error)
            assertEquals(2, again.size, "one retry, then the error")
        } finally {
            stubborn.stop(0)
        }
    }

    @Test
    fun aModelThatKeepsIgnoringAStepIsEventuallyLetGoAndTheStepIsMarkedSkipped() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fakeOllama(listOf(reply("I am done.")), bodies) // always the same plain reply, never the tool call
        try {
            val run = SkillRun("demo", listOf(Requirement("create_note", "", "", 1, "step 6: create the report note")))
            val (gen, completed) = runLoop(server, run, CopyOnWriteArrayList())
            assertEquals(GenState.Done, gen.state, "the run must end: the gate gives up after ${SkillRun.MAX_STRIKES} strikes")
            assertEquals("I am done.", completed())
            assertEquals(SkillRun.MAX_STRIKES + 1, bodies.size, "the original request plus one per strike")
            assertTrue(run.finished && run.skippedCount == 1 && run.done == 0)
            assertTrue(run.summary()!!.contains("not done: step 6: create the report note"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun withoutASkillRunTheReplyEndsTheTurnAsBefore() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = fakeOllama(listOf(reply("Plain answer.")), bodies)
        try {
            val (gen, completed) = runLoop(server, null, CopyOnWriteArrayList())
            assertEquals(GenState.Done, gen.state)
            assertEquals("Plain answer.", completed())
            assertEquals(1, bodies.size)
        } finally {
            server.stop(0)
        }
    }
}
