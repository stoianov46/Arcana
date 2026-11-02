package com.arcana.brain.app

import com.arcana.brain.app.screens.refreshMessage
import com.arcana.brain.app.screens.reportMeta
import com.arcana.brain.core.NoteInfo
import com.arcana.brain.core.ReportInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a row of the Library's Reports view says on the right, and what the refresh key puts in the message field. */
class ReportRowTest {
    private fun report(status: String = "done", ageDays: Long? = 3, rating: Int? = null, tokens: Long? = null, by: String = "", question: String = "Which model?") =
        ReportInfo("R/2026-11-01 Compare.md", "2026-11-01 Compare", "llm-compare", "compare", status, "2026-11-01", "2026", "", "create-llm-compare-report", question, ageDays,
            rating = rating, tokens = tokens, supersededBy = by)

    private val note = NoteInfo("R/2026-11-01 Compare.md", System.currentTimeMillis(), 10, emptySet(), emptySet(), emptySet())

    @Test
    fun theRowShowsRatingAndTokensBeforeTheState() {
        assertEquals("★4 · 20.5k tok · just now", reportMeta(report(rating = 4, tokens = 20_500), note))
        assertEquals("*4 · 20.5k tok · just now", reportMeta(report(rating = 4, tokens = 20_500), note, plain = true))
        assertEquals("just now", reportMeta(report(), note))
        assertEquals("★5 · incomplete", reportMeta(report(status = "incomplete", rating = 5), note))
        assertEquals("1.2k tok · stale, 155 d", reportMeta(report(ageDays = 155, tokens = 1_200).copy(kind = "landscape"), note))
    }

    @Test
    fun aSupersededReportSaysItWasReplaced() {
        assertEquals("replaced by 2026-11-03 New", reportMeta(report(status = "superseded", by = "2026-11-03 New"), note))
        assertEquals("replaced", reportMeta(report(status = "superseded"), note))
    }

    @Test
    fun refreshNamesTheOldReportAndKeepsTheQuestion() {
        assertEquals("Refresh [[2026-11-01 Compare]]: Which model?", refreshMessage(report(), refresh = true))
        assertEquals("Which model?", refreshMessage(report(), refresh = false))
        // a report that never recorded a question still gives a usable message
        assertEquals("Refresh [[2026-11-01 Compare]]", refreshMessage(report(question = ""), refresh = true))
    }
}
