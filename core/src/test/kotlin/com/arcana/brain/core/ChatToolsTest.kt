package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatToolsTest {
    private fun names(defs: List<JsonObject>) = defs.map { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun eachChatToolIsOfferedExactlyWhenItWasBefore() {
        // the order and the conditions of the list the chat built by hand before the registry
        assertEquals(listOf(REQUEST_MODE, CHAT_STATS, CHAT_STATS_JSON, TODO), names(ChatTools.definitions(OfferState())))
        assertEquals(listOf(TURN_ON_TOOLS, REQUEST_MODE, CHAT_STATS, CHAT_STATS_JSON, TODO, CONSULT, REQUEST_ACCESS), names(ChatTools.definitions(OfferState(listOf("shell"), planMode = true, consult = true))))
        assertEquals(listOf(REQUEST_ACCESS, ASK_USER), names(ChatTools.withoutToolbox(canRequest = true)!!))
        assertNull(ChatTools.withoutToolbox(canRequest = false))
        assertEquals(setOf(TURN_ON_TOOLS, REQUEST_MODE, CHAT_STATS, CHAT_STATS_JSON, TODO, CONSULT, REQUEST_ACCESS, ASK_USER), ChatTools.names)
    }
}
