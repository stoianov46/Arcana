package com.arcana.brain.app.design

import com.arcana.brain.core.ToolRisk
import com.jakewharton.mosaic.ui.Color

/**
 * What an entry of Chat's "/" menu is, and so how it looks: colour, glyph and badge all say what picking it will do, so the kinds can be told
 * apart at a glance (colour alone is never the only signal: the glyph and the badge word carry the meaning in a monochrome or plain terminal).
 *
 * | kind | colour | meaning |
 * |---|---|---|
 * | Command | cyan (`Cta`) | an Arcana action (`/new`, `/clear`) |
 * | Skill | purple | attaches a skill from the vault |
 * | Read | green | a tool that only looks (runs at once) |
 * | Write | orange | a tool that changes notes or files (asks first) |
 * | Shell | red | runs a command on the computer (asks every time) |
 * | Danger | red | deletes or moves (`delete_path`, `move_path`: ask every time, even in Auto mode) |
 * | Web | blue | reaches the internet (asks every time) |
 * | Ask | muted | talks to you (a question, a request) |
 */
enum class SlashKind(val glyph: String, val badge: String?) {
    Command("", null),
    Skill("◆ ", "SKILL"),
    Read("◎ ", "READ"),
    Write("✎ ", "WRITE"),
    Shell("▶ ", "SHELL"),
    Danger("✕ ", "DELETE/MOVE"),
    Web("↗ ", "WEB"),
    Ask("? ", "ASK");

    val color: Color
        get() = when (this) {
            Command -> Palette.Cta
            Skill -> Palette.Purple
            Read -> Palette.Green
            Write -> Palette.Orange
            Shell, Danger -> Palette.Red
            Web -> Palette.Blue
            Ask -> Palette.Muted
        }

    companion object {
        /** The kind of a tool: what it can do to the user's notes, files, computer or privacy. */
        fun forTool(name: String, risk: ToolRisk): SlashKind = when {
            name == "ask_user" || name == "request_access" -> Ask
            name == "delete_path" || name == "move_path" -> Danger
            risk == ToolRisk.Write -> Write
            risk == ToolRisk.Shell -> Shell
            risk == ToolRisk.Web -> Web
            else -> Read
        }
    }
}
