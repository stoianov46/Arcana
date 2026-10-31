package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopGuardTest {
    private val paragraph = "Wait, I should check whether the folder Research/Models still exists before I move anything, because the earlier listing may be stale. "

    @Test
    fun aRepeatedParagraphIsALoop() {
        assertTrue(LoopGuard.repeating(paragraph.repeat(5), 5))
    }

    @Test
    fun fewerRepeatsThanTheLimitAreFine() {
        assertFalse(LoopGuard.repeating(paragraph.repeat(4), 5))
        assertTrue(LoopGuard.repeating(paragraph.repeat(4), 4))
    }

    @Test
    fun aLoopStillShowsAfterLongNormalText() {
        val long = (1..400).joinToString(" ") { "word$it unique$it" }
        assertTrue(LoopGuard.repeating(long + " " + paragraph.repeat(5), 5))
    }

    @Test
    fun ordinaryTextIsNotALoop() {
        val text = (1..300).joinToString(". ") { "Sentence number $it says something different about topic ${it * 7}" }
        assertFalse(LoopGuard.repeating(text, 3))
    }

    @Test
    fun offWhenTheLimitIsBelowTwo() {
        assertFalse(LoopGuard.repeating(paragraph.repeat(20), 0))
        assertFalse(LoopGuard.repeating(paragraph.repeat(20), 1))
    }

    @Test
    fun aTableRuleOrADashLineNeverCounts() {
        assertFalse(LoopGuard.repeating("-".repeat(2000), 3))
        assertFalse(LoopGuard.repeating("| --- | --- | --- | --- |\n".repeat(60), 3))
    }

    @Test
    fun anOpenCodeFenceIsNotChecked() {
        val code = "```kotlin\n" + "val a = listOf(1, 2, 3).map { it * 2 }.filter { it > 2 }.sum() // same line\n".repeat(10)
        assertFalse(LoopGuard.repeating(code, 3))
    }

    @Test
    fun theRepeatedTextIsCutAfterItsFirstRound() {
        val cut = LoopGuard.withoutLoop("Intro. " + paragraph.repeat(6))
        assertTrue(cut.startsWith("Intro."))
        assertEquals(1, Regex("Research/Models").findAll(cut).count())
    }

    @Test
    fun repeatedCallsWarnThenHalt() {
        val calls = LoopGuard.Calls { 3 }
        assertEquals(LoopGuard.Step.None, calls.note("list_dir /a"))
        // the 1st and 2nd repeat are still only counted; the 3rd warns
        assertEquals(LoopGuard.Step.None, calls.note("list_dir /a"))
        assertEquals(LoopGuard.Step.None, calls.note("list_dir /a"))
        assertEquals(LoopGuard.Step.Warn, calls.note("list_dir /a"))
        assertFalse(calls.halted)
        repeat(2) { assertEquals(LoopGuard.Step.None, calls.note("list_dir /a")) }
        assertEquals(LoopGuard.Step.Halt, calls.note("list_dir /a"))
        assertTrue(calls.halted)
    }

    @Test
    fun differentCallsAreNotRepeats() {
        val calls = LoopGuard.Calls { 2 }
        repeat(30) { assertEquals(LoopGuard.Step.None, calls.note("read_file /f$it")) }
    }

    @Test
    fun aChangeMakesAnEarlierCallNewAgain() {
        val calls = LoopGuard.Calls { 2 }
        calls.note("read_file /a")
        calls.note("read_file /a") // 1st repeat
        calls.forget()
        assertEquals(LoopGuard.Step.None, calls.note("read_file /a")) // new again, not a repeat
    }

    @Test
    fun zeroSwitchesTheCallCheckOff() {
        val calls = LoopGuard.Calls { 0 }
        repeat(20) { assertEquals(LoopGuard.Step.None, calls.note("x")) }
        assertFalse(calls.halted)
    }

    @Test
    fun resetStartsAFreshQuestion() {
        val calls = LoopGuard.Calls { 1 }
        calls.note("x"); calls.note("x"); calls.note("x")
        assertTrue(calls.halted)
        calls.reset()
        assertFalse(calls.halted)
    }

    @Test
    fun theRetryAsksForARepeatPenalty() {
        assertFalse("repeat_penalty" in OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("hi"))).toString())
        assertTrue("\"repeat_penalty\":1.2" in OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("hi")), repeatPenalty = 1.2).toString())
    }
}
