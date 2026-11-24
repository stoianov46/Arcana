package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject

/**
 * Where the chat stands when it offers its tools: what is switched off and could be asked for, plan mode, whether the flow's `consult` is on, and whether the toolbox
 * exists at all (tools on) or only asking for tools is possible.
 */
data class OfferState(val switchedOff: List<String> = emptyList(), val planMode: Boolean = false, val consult: Boolean = false)

/** A tool the chat answers itself instead of the [ToolBox]: its [name] and the definition it is offered with, or null when [offered] says it is not offered now. */
class PseudoTool(val name: String, val offered: (OfferState) -> JsonObject?)

/**
 * The one list of the tools the chat answers itself (proposal 0007, phase 4): what the model is offered beside the toolbox, in a fixed order, and the names the
 * chat must handle. `ask_user` is among the names but is offered by the toolbox (it is one of its specs, so plan mode keeps it). The handlers live in the chat
 * session, which holds the state they need; a test checks that every name here has one.
 */
object PseudoTools {
    val all: List<PseudoTool> = listOf(
        PseudoTool(TURN_ON_TOOLS) { s -> if (s.switchedOff.isEmpty()) null else turnOnToolsDefinition(s.switchedOff) },
        PseudoTool(REQUEST_MODE) { REQUEST_MODE_DEFINITION },
        PseudoTool(CHAT_STATS) { CHAT_STATS_DEFINITION },
        PseudoTool(CHAT_STATS_JSON) { CHAT_STATS_JSON_DEFINITION },
        PseudoTool(TODO) { TODO_DEFINITION },
        PseudoTool(CONSULT) { s -> if (s.consult) CONSULT_DEFINITION else null },
        PseudoTool(REQUEST_ACCESS) { s -> if (s.planMode) REQUEST_ACCESS_DEFINITION else null },
    )

    /** Every name the chat answers itself, `ask_user` included. */
    val names: Set<String> = all.map { it.name }.toSet() + ASK_USER

    /** The definitions offered next to the toolbox's own, in order. */
    fun definitions(state: OfferState): List<JsonObject> = all.mapNotNull { it.offered(state) }

    /** With tools off: only asking for them, and asking the user (when the model can use tools at all), else nothing. */
    fun withoutToolbox(canRequest: Boolean): List<JsonObject>? = if (canRequest) listOf(REQUEST_ACCESS_DEFINITION, ASK_USER_DEFINITION) else null
}
