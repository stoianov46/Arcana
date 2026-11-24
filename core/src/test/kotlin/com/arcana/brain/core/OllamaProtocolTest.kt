package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ollama protocol: stream chunks, token counts and reply stats, tool calls in the request, the model list and model info, /api/ps, thinking, context length, tool calls written as text, out-of-memory errors. */
class OllamaProtocolTest {
    @Test
    fun parsesContentChunk() {
        val chunk = OllamaProtocol.parseChunk("""{"model":"g","message":{"role":"assistant","content":"Hi"},"done":false}""")
        assertEquals(ChatChunk(content = "Hi"), chunk)
    }

    @Test
    fun parsesThinkingChunkAndDone() {
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":"","thinking":"hmm"},"done":true}""")
        assertEquals(ChatChunk(thinking = "hmm", done = true), chunk)
    }

    @Test
    fun errorChunkThrows() {
        assertFailsWith<OllamaException> { OllamaProtocol.parseChunk("""{"error":"model not found"}""") }
    }

    @Test
    fun parsesModelList() {
        assertEquals(listOf("a:1", "b:2"), OllamaProtocol.parseModels("""{"models":[{"name":"b:2"},{"name":"a:1"}]}"""))
    }

    @Test
    fun parsesTokenCountsFromTheFinalChunk() {
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":26,"eval_count":298,"total_duration":5043500000}""")
        assertEquals(ChatChunk(done = true, promptTokens = 26, completionTokens = 298, totalMillis = 5043), chunk)
    }

    @Test
    fun outOfMemoryErrorsAreExplained() {
        val metal = "mlx runner failed: panic: mlx: [METAL] Command buffer execution failed: Insufficient Memory (00000008:kIOGPUCommandBufferCallbackErrorOutOfMemory). at /Users/runner/work/ollama/ollama/build/darwin-sources/_deps/mlx-c-src/mlx/c/transforms.cpp:73"
        assertTrue(OllamaProtocol.isOutOfMemory(metal))
        assertTrue(OllamaProtocol.isOutOfMemory("model requires more system memory (24.1 GiB) than is available (18.2 GiB)"))
        assertFalse(OllamaProtocol.isOutOfMemory("error parsing tool call"))
        val gib = 1L shl 30
        val text = OllamaProtocol.outOfMemoryMessage(metal, "qwen3.6:35b-a3b", 32768, 23_000_000_000, 100_000, 32 * gib, GpuMemory.cap(32 * gib, 0))
        assertTrue(text.startsWith("Out of memory: qwen3.6:35b-a3b with a 32k context needs about 26 GB"), text)
        assertTrue("GPU may use" in text && "macOS default" in text && "smaller Context window" in text && "sudo sysctl iogpu.wired_limit_mb=26214" in text, text)
        assertTrue("(Ollama: mlx runner failed" in text && text.length < 600, text)
        // the cost of a token unknown (an MLX build): the figure says it is the model alone
        val alone = OllamaProtocol.outOfMemoryMessage(metal, "inkling-2-mini:latest", 131072, 20_188_097_680, 0, 32 * gib, GpuMemory.cap(32 * gib, 0))
        assertTrue("needs about 24 GB for the model alone, before the cache for its context" in alone, alone)
        // ...which a loaded model reveals: 24.1 GB loaded with 128k, 20.2 GB of weights → about 30 KB a token
        assertEquals(29_807L, ModelFit.observedKvPerToken(24_095_000_000, 20_188_097_680, 131072))
        assertEquals(0L, ModelFit.observedKvPerToken(19_000_000_000, 20_188_097_680, 131072))
        // the limit already raised: no command suggested
        assertFalse("sudo" in OllamaProtocol.outOfMemoryMessage(metal, "m", 0, 0, 0, 32 * gib, GpuMemory.cap(32 * gib, 28000)))
        // a remote server: no local limit claimed
        assertFalse("GPU may use" in OllamaProtocol.outOfMemoryMessage(metal, "m", 0, 0, 0, 0, null))
    }

    @Test
    fun replyStatsComeFromTheFinalChunkAndAreStoredWithTheMessage() {
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":66,"eval_count":300,"total_duration":6000000000,"eval_duration":4000000000}""")
        val stats = chunk.toStats()!!
        assertEquals(ReplyStats(66, 300, 6000, 4000), stats)
        assertEquals(75.0, stats.tokensPerSecond, "speed is measured over the generating time")
        assertEquals(6.0, stats.seconds)
        assertNull(ChatChunk(content = "hi").toStats())
        assertEquals(50.0, ReplyStats(1, 300, 6000).tokensPerSecond, "without the generating time the whole time is used")
        val dir = Files.createTempDirectory("chats").resolve("chats")
        val store = ChatStore(dir)
        store.save(StoredChat("a", "t", 1, "m", listOf(ChatMessage.user("q"), ChatMessage.assistant("a", stats))))
        assertEquals(stats, store.latest()!!.messages.last().stats)
    }

    @Test
    fun toolCallsTravelInTheOllamaFormat() {
        val box = ToolBox(null, listOf(Files.createTempDirectory("arcana-fmt")), Files.createTempDirectory("arcana-bk"))
        val request = OllamaProtocol.chatRequest(
            AppConfig(),
            listOf(ChatMessage.assistantCalls("", listOf(call("read_file", "path" to "a.txt"))), ChatMessage.toolResult("read_file", "hi")),
            tools = box.definitions(),
        ).toString()
        assertTrue("\"tool_calls\"" in request && "\"tool_name\":\"read_file\"" in request && "\"type\":\"function\"" in request)
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"search_notes","arguments":{"query":"llm"}}}]},"done":true}""")
        assertEquals("search_notes", chunk.toolCalls.single().name)
        assertEquals("llm", (chunk.toolCalls.single().arguments["query"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun theLoadedContextIsReadFromOllamasProcessList() {
        val body = """{"models":[{"name":"qwen3.5:9b","model":"qwen3.5:9b","size":1,"context_length":4096},{"name":"gemma4:12b","model":"gemma4:12b","context_length":32768}]}"""
        assertEquals(4096, OllamaProtocol.parseLoadedContext(body, "qwen3.5:9b"))
        assertEquals(32768, OllamaProtocol.parseLoadedContext(body, "gemma4:12b"))
        assertEquals(0, OllamaProtocol.parseLoadedContext(body, "other"))
        assertEquals(0, OllamaProtocol.parseLoadedContext("""{"models":[]}""", "qwen3.5:9b"))
        assertEquals(0, OllamaProtocol.parseLoadedContext("""{"models":[{"name":"m"}]}""", "m"), "an older Ollama without context_length")
        assertEquals(0, OllamaProtocol.parseLoadedContext("not json", "m"))
    }

    @Test
    fun thinkingValuesAreReadFromShowAndSentAsThinkOnlyWhenAsked() {
        val binary = """{"capabilities":["thinking"],"thinking":{"values":[false,true],"default":true}}"""
        assertEquals(listOf("false", "true") to "true", OllamaProtocol.parseThinking(binary))
        assertEquals(listOf("low", "medium", "high") to "medium", OllamaProtocol.parseThinking("""{"thinking":{"values":["low","medium","high"],"default":"medium"}}"""))
        assertEquals(emptyList<String>() to "", OllamaProtocol.parseThinking("""{"capabilities":["completion"]}"""))
        assertEquals(null, OllamaProtocol.thinkValue("default"))
        assertEquals("false", OllamaProtocol.thinkValue("off")?.toString())
        assertEquals("true", OllamaProtocol.thinkValue("on")?.toString())
        assertEquals("\"low\"", OllamaProtocol.thinkValue("low")?.toString())
        val messages = listOf(ChatMessage.user("hi"))
        assertTrue("think" !in OllamaProtocol.chatRequest(AppConfig(), messages))
        assertEquals("false", OllamaProtocol.chatRequest(AppConfig(), messages, think = OllamaProtocol.thinkValue("off"))["think"].toString())
    }

    @Test
    fun contextLengthIsReadFromModelInfoAndAskedForWhenSet() {
        val body = """{"capabilities":["tools"],"model_info":{"general.architecture":"gemma4","gemma4.context_length":131072,"gemma4.embedding_length":2560}}"""
        assertEquals(131072, OllamaProtocol.parseContextLength(body))
        assertEquals(0, OllamaProtocol.parseContextLength("""{"capabilities":[]}"""))
        val withCtx = OllamaProtocol.chatRequest(AppConfig(contextTokens = 16384), listOf(ChatMessage.user("hi")))
        assertEquals(16384, ((withCtx["options"] as kotlinx.serialization.json.JsonObject)["num_ctx"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertTrue("num_ctx" !in (OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("hi")))["options"] as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun theModelListSaysWhatEachModelCanDo() {
        val tags = """{"models":[{"name":"qwen3:30b","size":19000000000},{"name":"gemma4:latest","size":9500000000},{"name":"tiny","size":400000000}]}"""
        assertEquals(listOf("qwen3:30b" to 19_000_000_000L, "gemma4:latest" to 9_500_000_000L, "tiny" to 400_000_000L), OllamaProtocol.parseModelSizes(tags))
        assertEquals("19 GB · dense · tools · thinking", ModelDetails("qwen3:30b", 19_000_000_000, listOf("completion", "tools", "thinking"), 40960, dense = true).summary())
        assertEquals("9.5 GB · dense · tools · vision", ModelDetails("g", 9_500_000_000, listOf("tools", "vision"), 0, dense = true).summary())
        assertEquals("1.2 GB · no tools", ModelDetails("old", 1_200_000_000, listOf("completion"), 0).summary())
        assertEquals("19.8 GB · tools", ModelDetails("mlx", 19_800_000_000, listOf("tools"), 262144).summary(), "an MLX build says nothing about experts: no architecture is shown")
        val moe = """{"tensors":[{"name":"draft.model.layers.0.mlp.gate_proj.weight","shape":[8,8]},{"name":"model.language_model.layers.0.experts.down_proj.weight","shape":[128,2816,704]},{"name":"model.language_model.layers.0.mlp.gate_proj.weight","shape":[2112,2816]}]}"""
        assertEquals(128 to false, OllamaProtocol.parseTensorLayout(moe))
        assertEquals(0 to true, OllamaProtocol.parseTensorLayout("""{"tensors":[{"name":"language_model.model.layers.0.mlp.gate_proj.weight","shape":[17408,5120]}]}"""))
        assertEquals(0 to false, OllamaProtocol.parseTensorLayout("""{"tensors":[]}"""))
        // the Oracul's tool calls are lines for the reader only: kept in the chat, never in a request
        val step = OraculSteps.message("qwen3.8:27b", "web_search", "qwen3.5 moe", OraculSteps.ERROR, "Error: DuckDuckGo is limiting requests\nTry later")
        val parsed = OraculSteps.parse(step)!!
        assertEquals(listOf("qwen3.8:27b", "web_search", "qwen3.5 moe", "error", "Error: DuckDuckGo is limiting requests"), listOf(parsed.model, parsed.tool, parsed.summary, parsed.status, parsed.outcome))
        assertTrue(step.displayOnly && !ChatMessage.user("x").displayOnly && OraculSteps.parse(ChatMessage.user("x")) == null)
        val history = listOf(ChatMessage.user("q"), step, ChatMessage.assistant("a"))
        assertEquals(listOf("user", "assistant"), Compaction.forModel(history).map { it.role })
        val wire = OllamaProtocol.chatRequest(AppConfig(), history).toString()
        assertTrue("oracul_step" !in wire && "DuckDuckGo" !in wire, wire)
        assertEquals(0, Oracul.turnFacts(history, 0).toolErrors, "a step is not a failed tool call of the worker")
        // the model's own sampling defaults and when Ollama unloads it
        assertEquals(mapOf("temperature" to "1", "top_k" to "20", "top_p" to "0.95"), OllamaProtocol.parseParameters("""{"parameters":"temperature                    1\ntop_k                          20\ntop_p                          0.95\nstop                           \"<|eot_id|>\""}"""))
        assertEquals(emptyMap(), OllamaProtocol.parseParameters("""{"capabilities":[]}"""))
        val ps = OllamaProtocol.parseLoaded("""{"models":[{"name":"qwen3.8:27b","size":30257852136,"size_vram":30257852136,"context_length":131072,"expires_at":"2026-11-04T19:53:13.417599+03:00"}]}""").single()
        assertEquals(java.time.OffsetDateTime.parse("2026-11-04T19:53:13.417599+03:00").toInstant().toEpochMilli(), ps.expiresAtMillis)
        // the model is told which reasoning levels IT offers and how the user picks one
        val note = Prompts.effortNote("qwen3.8:27b", listOf("false", "low", "medium", "xhigh"), "medium")
        assertTrue("qwen3.8:27b offers: off, low, medium, xhigh (default: medium)" in note && "Ctrl+G" in note && "/effort" in note && "You cannot change it yourself" in note, note)
        assertTrue("only on and off" in Prompts.effortNote("gemma4:26b", listOf("false", "true"), "true") && "offers: off, on (default: on)" in Prompts.effortNote("gemma4:26b", listOf("false", "true"), "true"))
        assertEquals("", Prompts.effortNote("llama3.1:8b", emptyList(), ""), "a model without thinking is told nothing")
        val cfg = AppConfig()
        assertTrue("REASONING EFFORT" in Prompts.chatMessages(cfg, listOf(ChatMessage.user("hi")), null, emptyMap(), effortNote = note).first().content)
        assertTrue("REASONING EFFORT" !in Prompts.chatMessages(cfg, listOf(ChatMessage.user("hi")), null, emptyMap()).first().content)
        assertEquals("400 MB", ModelDetails("x", 400_000_000, emptyList(), 0).summary())
        assertEquals("22.6 GB · MoE · tools · vision · thinking", ModelDetails("m", 22_600_000_000, listOf("tools", "vision", "thinking"), 262144, experts = 128).summary())
    }

    @Test
    fun aToolCallWrittenAsTextIsFoundOnlyAtTheEndOfAKnownTool() {
        val tools = setOf("fetch_url", "read_file", "grep_files", "ask_user")
        val screenshot = "I will use the fetch_url tool to read the content of the provided link.\n\nfetch_url(url=\"https://www.youtube.com/watch?v=9UMxZofMNbA\")"
        val (calls, rest) = TextToolCalls.extract(screenshot, tools)
        assertEquals(1, calls.size)
        assertEquals("fetch_url", calls[0].name)
        assertEquals("https://www.youtube.com/watch?v=9UMxZofMNbA", (calls[0].arguments["url"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("I will use the fetch_url tool to read the content of the provided link.", rest)
        // several calls, single quotes, numbers, booleans and arrays, inside a code fence
        val many = TextToolCalls.extract("Let me look.\n```tool_code\nread_file(path='a (1).txt', start_line=3, end_line=40)\ngrep_files(pattern=\"x\\\"y\")\nask_user(question=\"Pick\", options=[\"a\", \"b\"], multiple=true)\n```", tools)
        assertEquals(listOf("read_file", "grep_files", "ask_user"), many.first.map { it.name })
        assertEquals("a (1).txt", (many.first[0].arguments["path"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("3", (many.first[0].arguments["start_line"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("x\"y", (many.first[1].arguments["pattern"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(2, (many.first[2].arguments["options"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("Let me look.", many.second)
        // the JSON form
        val json = TextToolCalls.extract("Reading it.\n\n```json\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"x.md\"}}\n```", tools)
        assertEquals("read_file", json.first.single().name)
        assertEquals("Reading it.", json.second)
        assertEquals("read_file", TextToolCalls.extract("""{"name":"read_file","parameters":{"path":"y"}}""", tools).first.single().name)
        // never: an unknown tool, an explanation in the middle of an answer, a call that is not at the end, broken syntax
        assertTrue(TextToolCalls.extract("delete_everything(path=\"/\")", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("To read a page you can write fetch_url(url=\"https://a.b\") and I will do it.", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=\"https://a.b\")\nand then I explain what it does.", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=\"https://a.b\"", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=https://a.b)", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=\"x\")", emptySet()).first.isEmpty())
    }
}
