package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PseudoToolsTest {
    private fun names(defs: List<JsonObject>) = defs.map { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun eachPseudoToolIsOfferedExactlyWhenItWasBefore() {
        // the order and the conditions of the list the chat built by hand before the registry
        assertEquals(listOf(REQUEST_MODE, CHAT_STATS, CHAT_STATS_JSON, TODO), names(PseudoTools.definitions(OfferState())))
        assertEquals(listOf(TURN_ON_TOOLS, REQUEST_MODE, CHAT_STATS, CHAT_STATS_JSON, TODO, CONSULT, REQUEST_ACCESS), names(PseudoTools.definitions(OfferState(listOf("shell"), planMode = true, consult = true))))
        assertEquals(listOf(REQUEST_ACCESS, ASK_USER), names(PseudoTools.withoutToolbox(canRequest = true)!!))
        assertNull(PseudoTools.withoutToolbox(canRequest = false))
        assertEquals(setOf(TURN_ON_TOOLS, REQUEST_MODE, CHAT_STATS, CHAT_STATS_JSON, TODO, CONSULT, REQUEST_ACCESS, ASK_USER), PseudoTools.names)
    }
}
