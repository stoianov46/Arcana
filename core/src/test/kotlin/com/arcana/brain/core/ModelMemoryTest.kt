package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Memory and loaded models: token room, the Mac GPU limit, what is unloaded to make room, vm_stat, ModelFit and its advice, a model on the CPU, the embedding model in the bottom bar and the waiting line (ModelStatus). */
class ModelMemoryTest {
    @Test
    fun tokenRoomFollowsMemoryAndModel() {
        val body = """{"model_info":{"general.architecture":"llama","llama.block_count":32,"llama.attention.head_count":32,"llama.attention.head_count_kv":8,"llama.embedding_length":4096}}"""
        val kv = OllamaProtocol.parseKvBytesPerToken(body)
        assertEquals(2L * 32 * 8 * 128 * 2, kv)
        val gb = 1_000_000_000L
        val big = ModelFit.tokenRoom(64 * gb, 5 * gb, kv)!!
        assertTrue(big > ModelFit.tokenRoom(16 * gb, 5 * gb, kv)!!)
        assertEquals(0L, ModelFit.tokenRoom(8 * gb, 9 * gb, kv))
        assertNull(ModelFit.tokenRoom(16 * gb, 5 * gb, 0))
        // a GPU limit below the rule of thumb leaves less room
        assertTrue(ModelFit.tokenRoom(32 * gb, 5 * gb, kv, 20 * gb)!! < ModelFit.tokenRoom(32 * gb, 5 * gb, kv)!!)
    }

    @Test
    fun gpuLimitFollowsMacDefaultsAndSysctl() {
        val gib = 1L shl 30
        assertEquals(GpuMemory.Cap(32 * gib * 2 / 3, false), GpuMemory.cap(32 * gib, 0))
        assertEquals(GpuMemory.Cap(64 * gib * 3 / 4, false), GpuMemory.cap(64 * gib, 0))
        assertEquals(GpuMemory.Cap(26624L shl 20, true), GpuMemory.cap(32 * gib, 26624))
        assertNull(GpuMemory.cap(32 * gib, null))
        // a fifth (at least 6 GB) is left for macOS
        assertEquals(32 * 1024L * 4 / 5, GpuMemory.suggestedMb(32 * gib))
        assertEquals(128 * 1024L * 4 / 5, GpuMemory.suggestedMb(128 * gib))
        assertEquals(32 * gib, ModelFit.usable(64 * gib, GpuMemory.Cap(32 * gib, true)))
        assertEquals(ModelFit.budget(64 * gib), ModelFit.usable(64 * gib, null))
    }

    @Test
    fun makingRoomUnloadsOnlyWhatMustGo() {
        val gb = 1_000_000_000L
        val worker = LoadedModel("qwen3.6:35b-a3b", 23 * gb, 23 * gb, 32768)
        val embed = LoadedModel("nomic-embed-text:latest", gb / 2, gb / 2, 2048)
        // both fit: nothing is unloaded (the Oracul and the worker may stay loaded together)
        assertEquals(emptyList<String>(), ModelFit.toUnload("gemma4:26b", 10 * gb, 0, listOf(embed), 21 * gb))
        // the big one has to go, the small embedding model can stay
        assertEquals(listOf(worker.name), ModelFit.toUnload("gemma4:31b", 19 * gb, 8192, listOf(worker, embed), 21 * gb))
        // already loaded with this context: nothing to do; with another one Ollama reloads it, so the others are weighed again
        assertEquals(emptyList<String>(), ModelFit.toUnload("qwen3.6:35b-a3b", 30 * gb, 32768, listOf(worker, embed), 21 * gb))
        assertEquals(listOf(embed.name), ModelFit.toUnload("qwen3.6:35b-a3b", 20_800_000_000, 8192, listOf(worker, embed), 21 * gb))
        // unknown size: nothing is guessed
        assertEquals(emptyList<String>(), ModelFit.toUnload("x", 0, 0, listOf(worker), 21 * gb))
        assertEquals(19 * gb + 1000L * 8192, ModelFit.loadedNeed(19 * gb, 1000, 8192))
        assertEquals(ModelFit.need(19 * gb), ModelFit.loadedNeed(19 * gb, 0, 8192))
    }

    @Test
    fun usedMemoryIsReadFromVmStat() {
        val text = "Mach Virtual Memory Statistics: (page size of 16384 bytes)\nPages free:   100.\nPages active:   1000.\nPages wired down:   500.\nPages occupied by compressor:   250.\n"
        assertEquals(1750L * 16384, SystemMemory.usedFromVmStat(text))
        assertNull(SystemMemory.usedFromVmStat("nothing"))
    }

    @Test
    fun theEmbeddingModelIsNamedOnlyWhenItIsLoadedNextToTheChatModel() {
        val loaded = listOf(LoadedModel("gemma4:26b", 16_000_000_000, 16_000_000_000, 32_000), LoadedModel("nomic-embed-text:latest", 600_000_000, 600_000_000, 2048))
        assertEquals("nomic-embed-text:latest", ModelStatus.embedLoaded(loaded, "nomic-embed-text", "gemma4:26b")?.name)
        assertEquals(null, ModelStatus.embedLoaded(loaded.take(1), "nomic-embed-text", "gemma4:26b"))
        assertEquals(null, ModelStatus.embedLoaded(loaded, "", "gemma4:26b"))
        assertEquals(null, ModelStatus.embedLoaded(loaded, "gemma4:26b", "gemma4:26b"), "the chat model itself is not a second model")
        assertEquals(null, ModelStatus.embedLoaded(null, "nomic-embed-text", "gemma4:26b"))
    }

    @Test
    fun modelFitWarnsAboutMemoryAndSuggestsAnOraculAndAJudge() {
        val gb = 1_000_000_000L
        val ram = 32 * gb // budget 22.4 GB
        assertEquals(ModelFit.Fit.Both, ModelFit.fit(6 * gb, 6 * gb, ram))
        assertEquals(ModelFit.Fit.Alone, ModelFit.fit(17 * gb, 6 * gb, ram), "20.4 GB alone fits, with a 7.2 GB worker it does not")
        assertEquals(ModelFit.Fit.TooBig, ModelFit.fit(20 * gb, 6 * gb, ram))
        val tools = listOf("completion", "tools")
        val models = listOf(
            ModelDetails("worker", 6 * gb, tools, 0), ModelDetails("small", 3 * gb, tools, 0), ModelDetails("big", 17 * gb, tools, 0),
            ModelDetails("huge", 40 * gb, tools, 0), ModelDetails("embed", 1 * gb, listOf("embedding"), 0), ModelDetails("notools", 18 * gb, listOf("completion"), 0),
        )
        val suggestion = ModelFit.suggest(models, "worker", ram)
        assertEquals("big", suggestion.oracul, "the largest that fits at all, never the worker, an embedding model or one that cannot use tools")
        assertEquals("small", suggestion.judge, "the largest that stays loaded together with the worker")
        assertEquals("notools", ModelFit.suggest(models, "worker", ram, oraculTools = false).oracul)
        assertEquals(ModelFit.Suggestion(null, null), ModelFit.suggest(models, "worker", 0), "unknown memory: no suggestion")
        assertEquals(null, ModelFit.suggest(models, "worker", 8 * gb).oracul.takeIf { it == "huge" })
    }

    @Test
    fun `the memory advice says what goes wrong and what to choose instead`() {
        val gb = 1_000_000_000L
        val ram = 32 * gb
        val sizes = mapOf("worker" to 6 * gb, "small" to 3 * gb, "big" to 17 * gb, "huge" to 40 * gb)
        val huge = ModelFit.advice("huge", ModelFit.Role.Oracul, sizes, "worker", ram)!!
        assertTrue(huge.startsWith("⚠ huge is too big") && "Better: big" in huge, huge)
        val big = ModelFit.advice("big", ModelFit.Role.Oracul, sizes, "worker", ram)!!
        assertTrue(!big.startsWith("⚠") && "swaps" in big, "fits only alone: a note, not a warning")
        assertEquals(null, ModelFit.advice("small", ModelFit.Role.Judge, sizes, "worker", ram), "fits with the worker: nothing to say")
        assertTrue("To keep both loaded: small" in ModelFit.advice("big", ModelFit.Role.Judge, sizes, "worker", ram)!!, "the judge should stay loaded with the worker")
        assertEquals(null, ModelFit.advice("huge", ModelFit.Role.Oracul, sizes, "worker", 0), "unknown memory: no advice")
        assertEquals(null, ModelFit.advice("unknown", ModelFit.Role.Oracul, sizes, "worker", ram), "a model we have no size for: no advice")
        assertTrue("Install a smaller model" in ModelFit.advice("huge", ModelFit.Role.Oracul, mapOf("huge" to 40 * gb), "worker", ram)!!)
        assertTrue(ModelFit.advice("huge", ModelFit.Role.Main, sizes, "huge", ram)!!.contains("Better: big"))
    }

    @Test
    fun aModelThatDoesNotFitInGraphicsMemoryIsReportedAsRunningOnTheCpu() {
        val body = """{"models":[
            {"name":"big:latest","size":38654705664,"size_vram":25000000000,"context_length":131072},
            {"name":"small:latest","size":8000000000,"size_vram":8000000000},
            {"name":"edge:latest","size":10000000000,"size_vram":9500000000}]}"""
        val offloads = OllamaProtocol.parseOffloads(body)
        assertEquals(listOf("big:latest"), offloads.map { it.model }, "a model fully on the GPU, or with under 10 % on the CPU, is not reported")
        assertEquals(35, offloads.single().cpuPercent)
        assertEquals(listOf("big:latest", "edge:latest"), OllamaProtocol.parseOffloads(body, minPercent = 1).map { it.model }, "with a lower threshold the 5 % model is listed too, the biggest share first")
        assertEquals(emptyList(), OllamaProtocol.parseOffloads("""{"models":[]}"""))
        assertEquals(emptyList(), OllamaProtocol.parseOffloads("not json"))
    }

    @Test
    fun theWaitingLineSaysWhyTheModelHasNotAnsweredYet() {
        fun m(name: String, size: Long = 10_000_000_000, vram: Long = 10_000_000_000) = LoadedModel(name, size, vram, 32768)
        assertEquals("loading gemma4:e4b into memory", ModelStatus.describe("gemma4:e4b", emptyList(), 0, null))
        assertTrue("mistral-small3.2:latest is loaded and may be unloaded first" in ModelStatus.describe("gemma4:e4b", listOf(m("mistral-small3.2:latest")), 0, null))
        assertEquals("loaded, reading about 12k tokens of conversation", ModelStatus.describe("gemma4:e4b", listOf(m("gemma4:e4b")), 12_000, null))
        assertEquals("loaded, reading about 12k tokens of conversation", ModelStatus.describe("mistral-small3.2", listOf(m("mistral-small3.2:latest")), 12_000, null).replace("12.0k", "12k"), "a name without :latest matches")
        val slow = ModelStatus.describe("big", listOf(m("big", 36_000_000_000, 23_000_000_000)), 0, 88)
        assertTrue("loaded, reading the prompt" in slow && "36% of it runs on the CPU" in slow && "memory is tight (swap 88% full)" in slow, slow)
        assertTrue("does not answer" in ModelStatus.describe("x", null, 0, null))
        assertEquals(88, SystemMemory.parseSwap("total = 12288.00M  used = 10872.44M  free = 1415.56M  (encrypted)"))
        assertEquals(null, SystemMemory.parseSwap("nonsense"))
        val body = """{"models":[{"name":"a:latest","size":38654705664,"size_vram":25000000000,"context_length":131072}]}"""
        assertEquals(LoadedModel("a:latest", 38654705664, 25000000000, 131072), OllamaProtocol.parseLoaded(body).single())
        assertEquals(35, OllamaProtocol.parseLoaded(body).single().cpuPercent)
    }
}
