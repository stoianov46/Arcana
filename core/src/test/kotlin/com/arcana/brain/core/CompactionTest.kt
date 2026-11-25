package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compaction: where the cut goes, what stays, and how many latest turns fit in the share of the window. */
class CompactionTest {
    @Test
    fun compactionPicksTheCutKeepsTheLastTurnsAndHidesTheRestOnlyFromTheModel() {
        fun u(n: Int) = ChatMessage.user("question $n")
        fun a(n: Int) = ChatMessage.assistant("answer $n")
        val chat = listOf(u(1), a(1), u(2), a(2), u(3), a(3), u(4), a(4))
        assertEquals(4, Compaction.cutPoint(chat), "the last two user turns stay")
        assertEquals(null, Compaction.cutPoint(listOf(u(1), a(1), u(2), a(2))), "too little to summarize")
        val longTurn = listOf(u(1)) + (1..8).map { a(it) }
        assertEquals(8, Compaction.cutPoint(longTurn), "one long turn is cut between its rounds, the latest round stays")
        assertEquals(chat, Compaction.forModel(chat), "no summary yet: the model sees everything")
        val summary = Compaction.summaryMessage("they asked four things")
        assertTrue(Compaction.isSummary(summary) && Compaction.summaryText(summary) == "they asked four things" && !Compaction.isSummary(u(1)))
        val compacted = chat.subList(0, 4) + summary + chat.subList(4, 8)
        assertEquals(listOf(summary) + chat.subList(4, 8), Compaction.forModel(compacted))
        assertEquals(8, compacted.size - 1, "nothing was removed from the chat")
        // a second compaction later chains onto the first summary
        val more = compacted + listOf(u(5), a(5), u(6), a(6), u(7), a(7))
        val cut = Compaction.cutPoint(more)!!
        assertEquals("question 6", more[cut].content)
        val part = Compaction.toSummarize(more, cut)
        assertTrue(Compaction.isSummary(part.first()) && part.size == cut - 4, "from the first summary up to the cut")
        val text = Compaction.transcript(listOf(summary, u(5), ChatMessage.toolResult("read_file", "x".repeat(900)), a(5)))
        assertTrue("EARLIER SUMMARY:\nthey asked four things" in text && "USER:\nquestion 5" in text && "(900 characters in all)" in text && "ASSISTANT:\nanswer 5" in text, text)
        assertTrue(Compaction.transcript(listOf(ChatMessage.user("y".repeat(100_000)))).length < 31_000)
    }

    @Test
    fun compactingKeepsAsManyLatestTurnsAsFitInTheShareOfTheWindow() {
        // ten turns of about 100 tokens each (a short question and a 400-character answer)
        val chat = (1..10).flatMap { listOf(ChatMessage.user("q$it"), ChatMessage.assistant("a".repeat(400))) }
        assertEquals(16, Compaction.cutPoint(chat), "without a share: the last two turns stay")
        assertEquals(14, Compaction.cutPoint(chat, keepTokens = 350), "350 tokens hold three turns of about 102")
        assertEquals(10, Compaction.cutPoint(chat, keepTokens = 600), "600 tokens hold five turns")
        assertEquals(18, Compaction.cutPoint(chat, keepTokens = 10), "the latest turn always stays, even when it alone is too big")
        assertEquals(null, Compaction.cutPoint(chat, keepTokens = 1_000_000), "everything fits: nothing to summarize")
        assertEquals(30, AppConfig().compactKeepPercent)
    }
}
