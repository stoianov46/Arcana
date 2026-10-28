package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.Oracul
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Oracul against a fake Ollama that answers differently for each model: the right model is asked, and the result lands in the chat as a block of its own. */
class OraculTest {
    private class Fake(val server: HttpServer, val asked: MutableList<Pair<String, String>>, var toolCapable: Boolean = true, var script: (String, String) -> String? = { _, _ -> null })

    private fun fake(): Fake {
        val asked = CopyOnWriteArrayList<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val fake = Fake(server, asked)
        server.createContext("/api/show") { exchange ->
            exchange.requestBody.readBytes()
            val bytes = (if (fake.toolCapable) """{"capabilities":["completion","tools"]}""" else """{"capabilities":["completion"]}""").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/tags") { exchange ->
            val bytes = """{"models":[{"name":"worker"},{"name":"big"},{"name":"judge"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/chat") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val model = Regex("\"model\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            asked += model to body
            // a scripted reply (a whole NDJSON line) wins; otherwise the usual text per model
            val line = fake.script(model, body) ?: run {
                val reply = when (model) { "big" -> "The answer misses the edge case."; "judge" -> "Answer 2 is better. Best answer: use the edge case."; else -> "Another answer." }
                """{"message":{"role":"assistant","content":"$reply"},"done":true,"prompt_eval_count":20,"eval_count":8}"""
            }
            val bytes = (line + "\n").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return fake
    }

    private fun call(name: String, args: String) = """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"$name","arguments":$args}}]},"done":true,"prompt_eval_count":10,"eval_count":3}"""

    private fun app(f: Fake, config: AppConfig.() -> AppConfig = { this }): AppContext {
        val dir = Files.createTempDirectory("arcana-oracul")
        val store = ConfigStore(dir.resolve("config.json"))
        store.save(AppConfig(ollamaUrl = "http://127.0.0.1:${f.server.address.port}", model = "worker", keepChats = false).config())
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), SessionStore(dir.resolve("sessions")))
            .also { it.scope = CoroutineScope(Dispatchers.Default) }
    }

    @Test
    fun aMessageQueuedWhileTheModelWorksIsSentAfterItsReply() {
        val f = fake()
        try {
            val chat = app(f).chat
            chat.send("first")
            chat.enqueue("second")
            assertEquals(listOf("second"), chat.queued.toList())
            val deadline = System.currentTimeMillis() + 10_000
            while (chat.messages.count { it.role == "assistant" } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(listOf("first", "second"), chat.messages.filter { it.role == "user" }.map { it.content })
            assertEquals(2, chat.messages.count { it.role == "assistant" })
            assertTrue(chat.queued.isEmpty())
        } finally { f.server.stop(0) }
    }

    @Test
    fun theThoughtsOfAReasoningModelAreFollowedLiveAndKeptWithTheAnswer() {
        val f = fake()
        try {
            f.script = { model, _ ->
                if (model != "worker") null else listOf(
                    """{"message":{"role":"assistant","content":"","thinking":"let me "},"done":false}""",
                    """{"message":{"role":"assistant","content":"","thinking":"ponder this"},"done":false}""",
                    """{"message":{"role":"assistant","content":"Done."},"done":true,"prompt_eval_count":5,"eval_count":4}""",
                ).joinToString("\n")
            }
            val chat = app(f).chat
            chat.send("think about it")
            val deadline = System.currentTimeMillis() + 10_000
            while (chat.messages.none { it.role == "assistant" } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals("let me ponder this", chat.gen.liveThinking, "the pane has the whole round's thoughts, also the chunks the throttle skipped")
            assertEquals("let me ponder this", chat.messages.last { it.role == "assistant" }.thinking)
        } finally { f.server.stop(0) }
    }

    @Test
    fun aQueuedMessageCanBeDismissedNewestFirst() {
        val f = fake()
        try {
            val chat = app(f).chat
            assertTrue(!chat.dismissQueued(), "nothing queued, nothing to remove")
            chat.enqueue("one"); chat.enqueue("two")
            assertTrue(chat.dismissQueued())
            assertEquals(listOf("one"), chat.queued.toList())
            assertTrue(chat.dismissQueued() && !chat.dismissQueued())
            assertEquals("", chat.takeQueued())
        } finally { f.server.stop(0) }
    }

    @Test
    fun cancellingGivesTheQueueBack() {
        val f = fake()
        try {
            val chat = app(f).chat
            chat.enqueue("one"); chat.enqueue("two")
            assertEquals("one\n\ntwo", chat.takeQueued())
            assertTrue(chat.queued.isEmpty())
        } finally { f.server.stop(0) }
    }

    @Test
    fun aSecondOpinionAsksTheOraculModelAndAddsItAsABlock() {
        val f = fake()
        try {
            val app = app(f) { copy(oraculModel = "big") }
            val chat = app.chat
            chat.add(ChatMessage.user("Is 7 prime?")); chat.add(ChatMessage.assistant("Yes."))
            val told = runBlocking { chat.secondOpinion() }
            assertEquals("Second opinion from big added.", told)
            assertEquals(listOf("big"), f.asked.map { it.first }, "only the Oracul model was asked, not the worker")
            assertTrue("Is 7 prime?" in f.asked[0].second && "Yes." in f.asked[0].second, "it saw the question and the answer")
            val block = chat.messages.last()
            assertTrue(Oracul.isOpinion(block) && "misses the edge case" in block.content && "big" in Oracul.title(block))
            assertEquals(3, chat.messages.size)
            assertEquals(listOf("Oracul" to "big"), app.usage.map { it.feature to it.model }, "its tokens are recorded under Oracul, with the model that spent them")
            assertEquals(null, chat.consulting)
        } finally { f.server.stop(0) }
    }

    @Test
    fun aComparisonAsksTheOraculThenTheJudgeAndKeepsBothAnswers() {
        val f = fake()
        try {
            val app = app(f) { copy(oraculModel = "big", judgeModel = "judge") }
            val chat = app.chat
            chat.add(ChatMessage.user("Is 7 prime?")); chat.add(ChatMessage.assistant("Yes."))
            val told = runBlocking { chat.compare() }
            assertTrue("judged by judge" in told, told)
            assertEquals(listOf("Oracul" to "big", "Judge" to "judge"), app.usage.map { it.feature to it.model }, "the judge is stored under its own name, so its calls can be counted")
            assertEquals(listOf("big", "judge"), f.asked.map { it.first })
            assertTrue("Yes." in f.asked[1].second && "Another answer" !in f.asked[1].second.substringBefore("ANSWER 2") && "misses the edge case" in f.asked[1].second, "the judge got both answers")
            val block = chat.messages.last().content
            assertTrue(Oracul.isOpinion(chat.messages.last()) && "Yes." in block && "misses the edge case" in block && "Best answer: use the edge case." in block, block)
        } finally { f.server.stop(0) }
    }

    @Test
    fun reviseNeedsAnOpenOpinionAndAsksTheWorkerToUseIt() {
        val f = fake()
        try {
            val app = app(f) { copy(oraculModel = "big") }
            val chat = app.chat
            chat.add(ChatMessage.user("Is 7 prime?")); chat.add(ChatMessage.assistant("Yes."))
            assertTrue(assertFailsWith<IllegalStateException> { chat.revise() }.message!!.contains("no second opinion"))
            runBlocking { chat.secondOpinion() }
            chat.revise()
            assertEquals(Oracul.REVISE_PROMPT, chat.messages.last().content, "the worker is asked to revise, in the user's name")
            assertTrue(!Oracul.hasOpenOpinion(chat.messages.toList()), "the opinion is answered once the revision is asked")
            val deadline = System.currentTimeMillis() + 10_000
            while (f.asked.none { it.first == "worker" } && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val request = f.asked.first { it.first == "worker" }.second
            assertTrue("misses the edge case" in request && "revise your last answer" in request, "the worker saw the opinion and the request")
        } finally { f.server.stop(0) }
    }

    @Test
    fun withoutAModelOrAnAnswerNothingIsAskedAndNothingChanges() {
        val f = fake()
        try {
            val off = app(f).chat
            off.add(ChatMessage.user("q")); off.add(ChatMessage.assistant("a"))
            assertTrue(assertFailsWith<IllegalStateException> { runBlocking { off.secondOpinion() } }.message!!.contains("Choose an Oracul model"))
            val empty = app(f) { copy(oraculModel = "big") }.chat
            assertTrue(assertFailsWith<IllegalStateException> { runBlocking { empty.secondOpinion() } }.message!!.contains("no answer to review"))
            assertTrue(f.asked.isEmpty() && off.messages.size == 2)
        } finally { f.server.stop(0) }
    }

    @Test
    fun theOraculChecksWithReadToolsAndNamesWhatItUsed() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-oracul-vault").resolve("v"))
            Files.writeString(vault.resolve("facts.md"), "seven is prime")
            f.script = { model, body -> if (model == "big" && "tool_calls" !in body && "tools" in body) call("read_note", """{"path":"facts.md"}""") else null }
            val app = app(f) { copy(oraculModel = "big", toolsEnabled = true, toolAutoRead = true, vaultPath = vault.toString()) }
            val chat = app.chat
            chat.add(ChatMessage.user("Is 7 prime?")); chat.add(ChatMessage.assistant("Yes."))
            runBlocking { chat.secondOpinion() }
            assertEquals(listOf("big", "big"), f.asked.map { it.first }, "one round for the tool call, one for the opinion")
            assertTrue("seven is prime" in f.asked[1].second, "the result of the tool went back to the Oracul")
            assertTrue(f.asked[0].second.contains("\"tools\"") && "CHECK the answer" in f.asked[0].second, "it was given tools and told to check with them")
            val block = chat.messages.last().content
            assertTrue("misses the edge case" in block && block.trimEnd().endsWith("_Checked with: read_note_"), block)
            assertEquals(listOf("read_note"), app.toolUsage.map { it.tool }, "its tool calls are recorded like the worker's")
        } finally { f.server.stop(0) }
    }

    @Test
    fun anIdenticalReadInTheSameTurnIsNotRunAgain() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-repeat-vault").resolve("v"))
            Files.writeString(vault.resolve("facts.md"), "seven is prime")
            f.script = { model, body ->
                val tools = Regex("\"role\":\"tool\"").findAll(body).count()
                if (model == "worker" && "\"tools\"" in body && tools < 2) call("read_note", """{"path":"facts.md"}""") else null
            }
            val app = app(f) { copy(toolsEnabled = true, toolAutoRead = true, vaultPath = vault.toString()) }
            val chat = app.chat
            chat.send("what do the facts say?")
            val deadline = System.currentTimeMillis() + 10_000
            while (chat.messages.none { it.role == "assistant" && it.toolCalls.isEmpty() } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val results = chat.messages.filter { it.role == "tool" }
            assertEquals(2, results.size)
            assertTrue("seven is prime" in results[0].content)
            assertTrue("already made this exact call" in results[1].content && "seven is prime" in results[1].content, results[1].content)
            assertEquals(1, app.toolUsage.size, "the repeat was not run, so it is not counted as a tool use")
        } finally { f.server.stop(0) }
    }

    @Test
    fun aRepeatedWaitAndAFailedReadAreNeverAnsweredFromMemory() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-wait-vault").resolve("v"))
            f.script = { model, body ->
                val tools = Regex("\"role\":\"tool\"").findAll(body).count()
                if (model == "worker" && "\"tools\"" in body && tools < 2) call("wait", """{"seconds":1}""") else null
            }
            val app = app(f) { copy(toolsEnabled = true, toolAutoRead = true, vaultPath = vault.toString()) }
            val chat = app.chat
            chat.send("wait twice")
            val deadline = System.currentTimeMillis() + 15_000
            while (chat.messages.none { it.role == "assistant" && it.toolCalls.isEmpty() } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val results = chat.messages.filter { it.role == "tool" }
            assertEquals(2, results.size)
            assertTrue(results.all { "already made" !in it.content && it.content.startsWith("Waited") }, results.map { it.content }.toString())
            assertEquals(2, app.toolUsage.size, "both waits really ran")
        } finally { f.server.stop(0) }
    }

    @Test
    fun theModelCanKeepATaskListWithTheTodoTool() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-todo-vault").resolve("v"))
            f.script = { model, body ->
                if (model == "worker" && "\"tools\"" in body && "\"role\":\"tool\"" !in body) call("todo", """{"items":"[x] read\n[~] write\n[ ] save"}""") else null
            }
            val app = app(f) { copy(toolsEnabled = true, toolAutoRead = true, vaultPath = vault.toString()) }
            val chat = app.chat
            chat.send("plan the job")
            val deadline = System.currentTimeMillis() + 10_000
            while (chat.messages.none { it.role == "assistant" && it.toolCalls.isEmpty() } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val result = chat.messages.first { it.role == "tool" }
            assertEquals("todo", result.toolName)
            assertTrue("1 of 3 done" in result.content && "Carry on with: write" in result.content, result.content)
            assertEquals(listOf('x', '~', ' '), chat.todos.map { it.state })
            assertTrue(f.asked.first().second.contains("\"name\":\"todo\""), "the tool is offered to the model")
        } finally { f.server.stop(0) }
    }

    @Test
    fun aChangeByTheOraculAlwaysAsksEvenInAutoModeAndADenialChangesNothing() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-oracul-vault").resolve("v"))
            f.script = { model, body -> if (model == "big" && "tool_calls" !in body) call("write_file", """{"path":"out.txt","content":"hello"}""") else null }
            // Auto mode: the worker's own changes would run without asking; the Oracul's must not
            val app = app(f) { copy(oraculModel = "big", toolsEnabled = true, toolMode = "auto", toolAutoRead = true, vaultPath = vault.toString()) }
            val chat = app.chat
            chat.add(ChatMessage.user("Write it")); chat.add(ChatMessage.assistant("Done."))
            val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { runCatching { chat.secondOpinion() } }
            val deadline = System.currentTimeMillis() + 10_000
            while (chat.approval == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val asked = chat.approval
            assertTrue(asked != null, "the dialog appeared although the mode is Auto")
            assertTrue(asked!!.heading.startsWith("Ψ Oracul ·") && "big (Oracul)" in asked.title && asked.always.not(), "marked as the Oracul's, never 'always'")
            assertTrue(!Files.exists(vault.resolve("out.txt")), "nothing is written before the answer")
            chat.answer(ChatSession.ApprovalChoice.Deny)
            runBlocking { job.join() }
            assertTrue(!Files.exists(vault.resolve("out.txt")), "a denial changes nothing")
            assertEquals(listOf("denied"), app.toolUsage.map { it.outcome })
            assertTrue(f.asked.last().second.contains("The user denied this action"), "the Oracul was told")
        } finally { f.server.stop(0) }
    }

    @Test
    fun anOraculModelWithoutToolsOrWithTheSettingOffOnlyTalks() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-oracul-vault").resolve("v"))
            f.toolCapable = false
            val noCapability = app(f) { copy(oraculModel = "big", toolsEnabled = true, vaultPath = vault.toString()) }.chat
            noCapability.add(ChatMessage.user("q")); noCapability.add(ChatMessage.assistant("a"))
            runBlocking { noCapability.secondOpinion() }
            assertTrue("\"tools\"" !in f.asked.last().second, "a model that cannot call tools is not offered any")
            f.toolCapable = true
            val settingOff = app(f) { copy(oraculModel = "big", toolsEnabled = true, oraculTools = false, vaultPath = vault.toString()) }.chat
            settingOff.add(ChatMessage.user("q")); settingOff.add(ChatMessage.assistant("a"))
            runBlocking { settingOff.secondOpinion() }
            assertTrue("\"tools\"" !in f.asked.last().second, "the setting turns tools off for the Oracul")
            val toolsOff = app(f) { copy(oraculModel = "big", toolsEnabled = false, vaultPath = vault.toString()) }.chat
            toolsOff.add(ChatMessage.user("q")); toolsOff.add(ChatMessage.assistant("a"))
            runBlocking { toolsOff.secondOpinion() }
            assertTrue("\"tools\"" !in f.asked.last().second, "with tools off in Chat the Oracul has none either")
        } finally { f.server.stop(0) }
    }

    @Test
    fun theOraculTakesOverAnUnansweredTurnWithTheSameHistoryAndOnlyThenWritesTheReply() {
        val f = fake()
        try {
            val chat = app(f) { copy(oraculModel = "big") }.chat
            assertFailsWith<IllegalStateException> { runBlocking { chat.takeOver() } } // nothing to take over yet
            chat.add(ChatMessage.user("Is 7 prime?"))
            val told = runBlocking { chat.takeOver() }
            assertEquals("big took over and answered.", told)
            assertEquals(listOf("big"), f.asked.map { it.first }, "only the Oracul model was asked")
            assertTrue("Is 7 prime?" in f.asked[0].second)
            val reply = chat.messages.last()
            assertEquals("assistant", reply.role)
            assertTrue("big took over" in reply.content && "misses the edge case" in reply.content)
            assertEquals(null, chat.consulting)
            // an answered turn is not taken over again
            assertFailsWith<IllegalStateException> { runBlocking { chat.takeOver() } }
            val none = app(f).chat
            none.add(ChatMessage.user("q"))
            assertFailsWith<IllegalStateException> { runBlocking { none.takeOver() } } // no Oracul model chosen
        } finally { f.server.stop(0) }
    }

    @Test
    fun aSkillThatNamesAWorkerAndAReviewerRunsWithThemAndOnlyForThatMessage() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-roles").resolve("v"))
            Files.createDirectories(vault.resolve("Skills/demo"))
            Files.writeString(vault.resolve("Skills/demo/demo.skill.md"), "---\nname: demo\ndescription: Demo\nworker: big\nreviewer: judge\n---\nDo the demo.\n")
            Files.writeString(vault.resolve("Skills/missing.skill.md"), "x")
            val chat = app(f) { copy(vaultPath = vault.toString(), toolsEnabled = false) }.chat
            fun wait() { val end = System.currentTimeMillis() + 10_000; while ((chat.gen.state == GenState.Running || chat.consulting != null) && System.currentTimeMillis() < end) Thread.sleep(20) }
            chat.attachments.notes += "Skills/demo/demo.skill.md"
            chat.send("run it")
            wait(); Thread.sleep(300); wait()
            assertEquals(listOf("big", "judge"), f.asked.map { it.first }, "the skill's worker answered, then its reviewer reviewed")
            assertTrue(chat.messages.any { Oracul.isOpinion(it) && "judge" in Oracul.title(it) })
            chat.send("and now a plain question")
            wait(); Thread.sleep(300)
            assertEquals("worker", f.asked.last().first, "the next message goes back to the chat model")
            assertEquals(3, f.asked.size, "and no review without a skill")
        } finally { f.server.stop(0) }
    }

    @Test
    fun theOraculAndTheJudgeCanLiveOnAnotherOllamaServer() {
        val main = fake(); val other = fake()
        try {
            val app = app(main) { copy(oraculModel = "big", judgeModel = "judge", oraculUrl = "http://127.0.0.1:${other.server.address.port}/") }
            val chat = app.chat
            chat.add(ChatMessage.user("q")); chat.add(ChatMessage.assistant("a"))
            runBlocking { chat.secondOpinion(); chat.compare() }
            assertEquals(emptyList(), main.asked.map { it.first }, "the main server is never asked")
            assertEquals(listOf("big", "big", "judge"), other.asked.map { it.first })
            assertEquals("http://x:1", Oracul.serverConfig(AppConfig(oraculUrl = " http://x:1/ "), "m").ollamaUrl)
            assertEquals(AppConfig().ollamaUrl, Oracul.serverConfig(AppConfig(), "m").ollamaUrl)
        } finally { main.server.stop(0); other.server.stop(0) }
    }

    @Test
    fun aComparisonCanHaveMoreThanTwoAnswersAndSkipsAnExtraModelThatCannotAnswer() {
        val f = fake()
        try {
            f.script = { model, _ -> if (model == "mute") """{"message":{"role":"assistant","content":""},"done":true}""" else null }
            val app = app(f) { copy(oraculModel = "big", oraculExtra = " other , mute, big", judgeModel = "judge") }
            assertEquals(listOf("big", "other", "mute"), Oracul.models(app.config))
            val chat = app.chat
            chat.add(ChatMessage.user("Is 7 prime?")); chat.add(ChatMessage.assistant("Yes."))
            val told = runBlocking { chat.compare() }
            assertTrue("big, other against worker" in told && "Left out: mute" in told, told)
            assertEquals(listOf("big", "other", "mute", "judge"), f.asked.map { it.first })
            val judged = f.asked.last().second
            assertTrue("ANSWER 1 (worker)" in judged && "ANSWER 2 (big)" in judged && "ANSWER 3 (other)" in judged && "ANSWER 4" !in judged, "the judge saw every answer that exists")
            val block = chat.messages.last().content
            assertTrue("## other" in block && "## mute" !in block && "Left out: mute" in block, block)
            // the first Oracul model must answer: otherwise nothing is added
            f.script = { model, _ -> if (model == "big") """{"message":{"role":"assistant","content":""},"done":true}""" else null }
            val before = chat.messages.size
            assertFailsWith<IllegalStateException> { runBlocking { chat.compare() } }
            assertEquals(before, chat.messages.size)
        } finally { f.server.stop(0) }
    }

    private fun skillVault(properties: String): java.nio.file.Path {
        val vault = Files.createDirectories(Files.createTempDirectory("arcana-roles").resolve("v"))
        Files.createDirectories(vault.resolve("Skills/demo"))
        Files.writeString(vault.resolve("Skills/demo/demo.skill.md"), "---\nname: demo\ndescription: Demo\n$properties---\nDo the demo.\n")
        return vault
    }

    private fun runSkill(f: Fake, vault: java.nio.file.Path, config: AppConfig.() -> AppConfig): ChatSession {
        val chat = app(f) { copy(vaultPath = vault.toString(), toolsEnabled = false).config() }.chat
        chat.attachments.notes += "Skills/demo/demo.skill.md"
        chat.send("run it")
        val end = System.currentTimeMillis() + 10_000
        while ((chat.gen.state == GenState.Running || chat.consulting != null) && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(300)
        return chat
    }

    @Test
    fun aPlannerWritesAPlanThatTheWorkerSeesAndItIsNoReview() {
        val f = fake()
        try {
            val chat = runSkill(f, skillVault("planner: judge\nworker: big\n")) { this }
            assertEquals(listOf("judge", "big"), f.asked.map { it.first }, "the planner first, then the skill's worker")
            val plan = chat.messages.first { Oracul.isPlan(it) }
            assertTrue(Oracul.isOpinion(plan) && !Oracul.isReview(plan) && "judge" in Oracul.title(plan))
            assertTrue("A planner (another model) wrote this plan" in f.asked[1].second, "the worker was given the plan")
            assertTrue("Do the demo" in f.asked[0].second, "the planner read the skill's instructions")
            assertTrue(!Oracul.hasOpenOpinion(chat.messages.toList()), "a plan does not offer /revise")
        } finally { f.server.stop(0) }
    }

    @Test
    fun theRoleTableInTheSettingsFillsInWhatASkillDoesNotNameAndTheSkillWins() {
        val f = fake()
        try {
            runSkill(f, skillVault("")) { copy(roleWorker = "big", roleReviewer = "judge", rolePlanner = "") }
            assertEquals(listOf("big", "judge"), f.asked.map { it.first }, "the table's worker answered, then its reviewer")
            f.asked.clear()
            runSkill(f, skillVault("worker: judge\n")) { copy(roleWorker = "big") }
            assertEquals(listOf("judge"), f.asked.map { it.first }, "the skill's own worker wins over the table")
            f.asked.clear()
            val noSkill = app(f) { copy(roleWorker = "big", roleReviewer = "judge", toolsEnabled = false) }.chat
            noSkill.send("plain question")
            Thread.sleep(500)
            assertEquals(listOf("worker"), f.asked.map { it.first }, "outside a skill the table is not used")
        } finally { f.server.stop(0) }
    }

    @Test
    fun reviseTakesAnExtraNoteAndCompareCanUseOneModelForOneCall() {
        val f = fake()
        try {
            val app = app(f) { copy(oraculModel = "big", oraculExtra = "other") }
            val chat = app.chat
            chat.add(ChatMessage.user("Is 7 prime?")); chat.add(ChatMessage.assistant("Yes."))
            runBlocking { chat.compare("judge") }
            assertEquals(listOf("judge", "big"), f.asked.map { it.first }, "the named model answered instead of the configured ones; the judge is still the configured one")
            chat.add(Oracul.opinionMessage("big", "wrong"))
            chat.revise("focus on the edge case")
            val end = System.currentTimeMillis() + 10_000
            while (f.asked.none { it.first == "worker" } && System.currentTimeMillis() < end) Thread.sleep(20)
            assertTrue(f.asked.any { it.first == "worker" && "The user adds: focus on the edge case" in it.second })
        } finally { f.server.stop(0) }
    }

    @Test
    fun theOraculStepsIntoAWorkerThatKeepsFailingAndItsAdviceJoinsTheLoop() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-stuck").resolve("v"))
            // the worker keeps reading a note that does not exist, until it is given advice
            f.script = { model, body ->
                if (model == "worker") {
                    if ("which keep failing" in body) """{"message":{"role":"assistant","content":"Found it: wrong name."},"done":true,"prompt_eval_count":10,"eval_count":3}"""
                    else call("read_note", """{"path":"nope-${f.asked.size}"}""")
                } else null
            }
            val app = app(f) { copy(oraculModel = "big", oraculTools = false, toolsEnabled = true, toolAutoRead = true, toolSteps = 10, vaultPath = vault.toString()) }
            val chat = app.chat
            chat.send("fix the bug")
            val end = System.currentTimeMillis() + 15_000
            while ((chat.gen.state == GenState.Running || chat.consulting != null) && System.currentTimeMillis() < end) Thread.sleep(20)
            Thread.sleep(300)
            assertEquals(listOf("worker", "worker", "worker", "worker", "big", "worker"), f.asked.map { it.first }, "after the 4th failed call the Oracul read the attempts, then the worker went on")
            val advice = chat.messages.first { Oracul.isReview(it) }
            assertTrue("misses the edge case" in advice.content && "keep failing" in advice.content, advice.content)
            assertTrue("CALL read_note" in f.asked[4].second && "(FAILED)" in f.asked[4].second && "fix the bug" in f.asked[4].second, "the Oracul saw the task and the failed attempts")
            assertEquals("Found it: wrong name.", chat.messages.last { it.role == "assistant" }.content)
            assertEquals(6, f.asked.size, "no further review after the finished reply: once per turn is enough")
            assertEquals("Oracul" to "big", app.usage.last { it.feature == "Oracul" }.let { it.feature to it.model })
        } finally { f.server.stop(0) }
    }

    @Test
    fun noAdviceWithoutAnOraculModelOrWhenTheAutomaticSettingIsOff() {
        val f = fake()
        try {
            val vault = Files.createDirectories(Files.createTempDirectory("arcana-stuck2").resolve("v"))
            f.script = { model, body -> if (model == "worker" && "keep failing" !in body && f.asked.count { it.first == "worker" } < 5) call("read_note", """{"path":"nope-${f.asked.size}"}""") else null }
            for (config in listOf<AppConfig.() -> AppConfig>({ copy(oraculModel = "") }, { copy(oraculModel = "big", oraculAuto = "off") })) {
                f.asked.clear()
                val chat = app(f) { copy(oraculTools = false, toolsEnabled = true, toolAutoRead = true, toolSteps = 10, vaultPath = vault.toString()).config() }.chat
                chat.send("fix it")
                val end = System.currentTimeMillis() + 15_000
                while (chat.gen.state == GenState.Running && System.currentTimeMillis() < end) Thread.sleep(20)
                Thread.sleep(200)
                assertTrue(f.asked.none { it.first == "big" }, "the Oracul was not asked")
            }
        } finally { f.server.stop(0) }
    }
}
