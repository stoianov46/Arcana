package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamplingTest {
    private val hi = listOf(ChatMessage.user("hi"))
    private fun options(c: AppConfig, penalty: Double? = null) = OllamaProtocol.chatRequest(c, hi, repeatPenalty = penalty)["options"].toString()
    private val qwen = mapOf("temperature" to "1", "top_k" to "20", "top_p" to "0.95", "presence_penalty" to "1.5")

    @Test
    fun `model mode sends no sampling option at all, whatever the values say`() {
        val c = AppConfig(temperature = 0.2, topP = 0.5, topK = 10)
        assertEquals("{}", options(c), "the model's own defaults apply")
        assertEquals("""{"num_ctx":8192}""", options(c.copy(contextTokens = 8192)))
    }

    @Test
    fun `custom mode sends the temperature and only the options that have a value`() {
        val c = AppConfig(samplingMode = Sampling.CUSTOM, temperature = 1.0, topK = 20, presencePenalty = 1.5)
        val o = options(c)
        assertTrue("\"temperature\":1.0" in o && "\"top_k\":20" in o && "\"presence_penalty\":1.5" in o, o)
        assertFalse("top_p" in o || "min_p" in o || "repeat_penalty" in o, o)
        assertTrue("\"top_k\":20" in o && "\"top_k\":20.0" !in o, "top_k is a whole number")
    }

    @Test
    fun `the retry after a loop still raises the repeat penalty, in either mode`() {
        assertTrue("\"repeat_penalty\":1.2" in options(AppConfig(), 1.2))
        assertTrue("\"repeat_penalty\":1.3" in options(AppConfig(samplingMode = Sampling.CUSTOM, repeatPenalty = 1.1), 1.3))
    }

    @Test
    fun `another model than the chat model never gets the custom sampling`() {
        val c = AppConfig(model = "a", samplingMode = Sampling.CUSTOM, topK = 20, keepAlive = "30m")
        assertEquals(Sampling.CUSTOM, c.forModel("a").samplingMode)
        assertEquals(Sampling.MODEL, c.forModel("b").samplingMode)
        assertEquals("{}", options(c.forModel("b")))
    }

    @Test
    fun `typed values are checked against the range, blank means the model's own`() {
        val p = Sampling.param("top_p")
        assertEquals(0.95, Sampling.parse("0.95", p))
        assertEquals(0.95, Sampling.parse("0,95", p), "a comma is accepted")
        assertNull(Sampling.parse("  ", p))
        assertFailsWith<IllegalArgumentException> { Sampling.parse("1.5", p) }
        assertFailsWith<IllegalArgumentException> { Sampling.parse("abc", p) }
        assertFailsWith<IllegalArgumentException> { Sampling.parse("", Sampling.param("temperature")) }
        assertFailsWith<IllegalArgumentException> { Sampling.parse("2.5", Sampling.param("top_k")) }
    }

    @Test
    fun `stepping a blank option starts at the model's own value, and below the minimum it goes blank again`() {
        val k = Sampling.param("top_k")
        val c = Sampling.step(AppConfig(), k, 1, qwen)
        assertEquals(20, c.topK, "starts from the model's own 20")
        assertEquals(25, Sampling.step(c, k, 1, qwen).topK)
        assertEquals(40, Sampling.step(AppConfig(), k, 1, emptyMap()).topK, "no model value: the option's own start")
        assertNull(Sampling.step(AppConfig(topK = 1), k, -1, qwen).topK)
        val t = Sampling.param("temperature")
        assertEquals(0.8, Sampling.step(AppConfig(temperature = 0.7), t, 1, qwen).temperature)
        assertEquals(0.0, Sampling.step(AppConfig(temperature = 0.0), t, -1, qwen).temperature, "the temperature never goes blank")
        assertEquals(0.3, Sampling.step(AppConfig(minP = 0.29), Sampling.param("min_p"), 1, qwen).minP)
    }

    @Test
    fun `effective shows the model's own values and marks what Settings overrides`() {
        val model = Sampling.effective(AppConfig(temperature = 0.2), qwen)
        assertEquals(listOf("temperature", "top_p", "top_k", "presence_penalty"), model.map { it.param.key })
        assertTrue(model.none { it.fromSettings }, "model mode: nothing is overridden")
        assertEquals(1.0, model.first().value)
        val custom = Sampling.effective(AppConfig(samplingMode = Sampling.CUSTOM, temperature = 0.6, topK = 40), qwen)
        assertEquals(listOf(0.6 to true, 0.95 to false, 40.0 to true, 1.5 to false), custom.map { it.value to it.fromSettings })
        assertTrue(Sampling.effective(AppConfig(), emptyMap()).isEmpty(), "a model that lists none and nothing set: nothing to show")
    }

    @Test
    fun `the formatted numbers are short`() {
        assertEquals("0.95", Sampling.param("top_p").format(0.95))
        assertEquals("1", Sampling.param("temperature").format(1.0))
        assertEquals("0", Sampling.param("min_p").format(0.0))
        assertEquals("20", Sampling.param("top_k").format(20.0))
    }
}
