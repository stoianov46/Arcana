package com.arcana.brain.app.screens

import com.arcana.brain.core.AppConfig

/**
 * The window's dock layout: the conversation is the main part and panels dock around it, as in an IDE. For now a panel docks on the LEFT or the RIGHT, as a full-height column;
 * strips above or below the conversation (the thinking pane's top and bottom) are separate. Each side holds ONE panel: a second panel can take a side only after the first is switched off.
 * New panels join by getting a [PaneId], a placement in [AppConfig] and a line in [Docks.placement]/[Docks.enabled]; the layout in Chat draws whatever the docks hold.
 */
internal enum class PaneId(val title: String) {
    /** The first in this order wins a side when a hand-edited config puts two panels on it. */
    Thinking("thinking pane"),
    Status("status pane"),
}

internal object Docks {
    const val LEFT = "left"
    const val RIGHT = "right"
    val SIDES = listOf(LEFT, RIGHT)

    fun placement(c: AppConfig, id: PaneId): String = when (id) {
        PaneId.Thinking -> c.thinkingPanePlacement
        PaneId.Status -> c.statusPanePlacement
    }

    fun enabled(c: AppConfig, id: PaneId): Boolean = when (id) {
        PaneId.Thinking -> c.thinkingPane
        PaneId.Status -> c.statusPane
    }

    /** The panel that is switched on and sits on [side], leaving out [except] (the panel asking). */
    fun occupant(c: AppConfig, side: String, except: PaneId? = null): PaneId? =
        PaneId.entries.firstOrNull { it != except && enabled(c, it) && placement(c, it) == side }

    /** Why [id] may not be switched on (or moved) to [side], or null when the side is free: the answer for a toast or a settings error. */
    fun refusal(c: AppConfig, id: PaneId, side: String = placement(c, id)): String? {
        if (side !in SIDES) return null
        val other = occupant(c, side, id) ?: return null
        return "The $side side already holds the ${other.title}: switch it off first, or put this one on the other side."
    }

    /** The side [id] really takes while drawing: its own, unless it is off, not a side panel, or an earlier panel already holds that side. Null = no column. */
    fun sideOf(c: AppConfig, id: PaneId): String? {
        if (!enabled(c, id)) return null
        val side = placement(c, id)
        if (side !in SIDES) return null
        val winner = PaneId.entries.first { enabled(c, it) && placement(c, it) == side }
        return if (winner == id) side else null
    }
}
