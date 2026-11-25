package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalPolicyTest {
    private fun spec(name: String, risk: ToolRisk, alwaysAsk: Boolean = false, heading: String? = null) =
        ToolSpec(name, "", JsonObject(emptyMap()), risk, { "" }, alwaysAsk = alwaysAsk, confirmHeading = heading) { "" }

    private val read = spec("read_note", ToolRisk.Read)
    private val write = spec("create_note", ToolRisk.Write)
    private val delete = spec("delete_path", ToolRisk.Write, alwaysAsk = true, heading = "Delete this?")
    private val shell = spec("run_command", ToolRisk.Shell)
    private val search = spec("web_search", ToolRisk.Web)
    private val http = spec("http_request", ToolRisk.Web)

    @Test
    fun theWorkerAsksByRiskAndMode() {
        val ask = ApprovalSettings(mode = "ask")
        val auto = ApprovalSettings(mode = "auto")
        assertFalse(ApprovalPolicy.mustAsk(read, Actor.Worker, ask), "reads run without asking by default")
        assertTrue(ApprovalPolicy.mustAsk(read, Actor.Worker, ask.copy(autoRead = false)))
        assertTrue(ApprovalPolicy.mustAsk(write, Actor.Worker, ask)); assertFalse(ApprovalPolicy.mustAsk(write, Actor.Worker, auto))
        assertTrue(ApprovalPolicy.mustAsk(delete, Actor.Worker, auto), "deleting asks even in Auto mode")
        assertTrue(ApprovalPolicy.mustAsk(shell, Actor.Worker, auto, command = "rm -rf build"))
        assertFalse(ApprovalPolicy.mustAsk(shell, Actor.Worker, ask, command = "git status"), "a safe command (git status) runs with Read without asking")
        assertFalse(ApprovalPolicy.mustAsk(shell, Actor.Worker, ask.copy(autoShell = true), command = "make"))
        assertTrue(ApprovalPolicy.mustAsk(search, Actor.Worker, auto)); assertFalse(ApprovalPolicy.mustAsk(search, Actor.Worker, ask.copy(autoSearch = true)))
        assertTrue(ApprovalPolicy.mustAsk(http, Actor.Worker, ask.copy(autoSearch = true)), "http_request always asks")
        // "always this session" lets a change through; a web page in the turn asks again even then
        assertFalse(ApprovalPolicy.mustAsk(write, Actor.Worker, ask, allowedAlways = true))
        assertTrue(ApprovalPolicy.mustAsk(write, Actor.Worker, auto, allowedAlways = true, tainted = true))
    }

    @Test
    fun theOraculOnlyReadsWithoutAsking() {
        val auto = ApprovalSettings(mode = "auto", autoShell = true, autoSearch = true)
        assertFalse(ApprovalPolicy.mustAsk(read, Actor.Oracul, auto))
        assertTrue(ApprovalPolicy.mustAsk(read, Actor.Oracul, auto.copy(autoRead = false)))
        listOf(write, delete, shell, search, http).forEach { assertTrue(ApprovalPolicy.mustAsk(it, Actor.Oracul, auto), it.name) }
    }

    @Test
    fun theQuestionIsTitledByWhatTheCallDoes() {
        assertEquals("Run this command?", ApprovalPolicy.heading(shell))
        assertEquals("Search the web?", ApprovalPolicy.heading(search)); assertEquals("Send this HTTP request?", ApprovalPolicy.heading(http))
        assertEquals("Fetch this web page?", ApprovalPolicy.heading(spec("fetch_url", ToolRisk.Web)))
        assertEquals("Delete this?", ApprovalPolicy.heading(delete))
        assertEquals("Allow this read?", ApprovalPolicy.heading(read)); assertEquals("Allow this change?", ApprovalPolicy.heading(write))
        assertTrue(ApprovalPolicy.offersAlways(write, tainted = false))
        assertFalse(ApprovalPolicy.offersAlways(delete, false) || ApprovalPolicy.offersAlways(shell, false) || ApprovalPolicy.offersAlways(write, tainted = true))
    }
}
