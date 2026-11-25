package com.arcana.brain.core

/** Who makes a tool call: the chat model (the worker) or the Oracul, which never gets more power than a read without asking. */
enum class Actor { Worker, Oracul }

/** The settings that decide whether a call asks: the tool mode and the *without asking* switches of Settings → Tools. */
data class ApprovalSettings(val mode: String = "ask", val autoRead: Boolean = true, val autoShell: Boolean = false, val autoSearch: Boolean = false) {
    companion object {
        fun of(config: AppConfig) = ApprovalSettings(config.toolMode, config.toolAutoRead, config.toolAutoShell, config.toolAutoSearch)
    }
}

/**
 * Whether a tool call asks the user first, and how the question is titled: one rule for the chat model and the Oracul (proposal 0007, phase 5). The refusals that come
 * before it (a blocked command, a private address, a folder that is not allowed) and the dialogs themselves stay with the chat.
 */
object ApprovalPolicy {
    /** The web tools that *Search and fetch without asking* can let through; `http_request` always asks (its body and headers can carry data out). */
    val AUTO_WEB = setOf("web_search", "fetch_url")

    /**
     * True when [spec] must ask before it runs. [allowedAlways]: the user said "always" for this tool in this session; [tainted]: a web page is in this turn and the
     * call changes, runs or reaches something new (it asks even in Auto mode); [command]: a shell command's text (a safe one runs with *Read without asking*).
     */
    fun mustAsk(spec: ToolSpec, actor: Actor, settings: ApprovalSettings, allowedAlways: Boolean = false, tainted: Boolean = false, command: String = ""): Boolean {
        // the Oracul reads like the worker; changes, commands and the web always ask, in every mode
        if (actor == Actor.Oracul) return if (spec.risk == ToolRisk.Read) !settings.autoRead else true
        if (tainted) return true
        if (allowedAlways) return false
        return when (spec.risk) {
            ToolRisk.Write -> spec.alwaysAsk || settings.mode != "auto" // deleting and moving ask even in Auto mode
            ToolRisk.Read -> !settings.autoRead
            ToolRisk.Shell -> !settings.autoShell && !(settings.autoRead && ShellPolicy.isSafe(command)) // each command is looked at, unless auto-run is on
            ToolRisk.Web -> !(spec.name in AUTO_WEB && settings.autoSearch)
        }
    }

    /** The title of the question: a command, a web search, request or page, the tool's own (delete, move, remember), a read or a change. */
    fun heading(spec: ToolSpec): String = when (spec.risk) {
        ToolRisk.Shell -> "Run this command?"
        ToolRisk.Web -> when (spec.name) { "web_search" -> "Search the web?"; "http_request" -> "Send this HTTP request?"; else -> "Fetch this web page?" }
        else -> spec.confirmHeading ?: if (spec.risk == ToolRisk.Read) "Allow this read?" else "Allow this change?"
    }

    /** Whether the question may offer "always this session": never for commands, the web, the tools that always ask, or a call made after a web page. */
    fun offersAlways(spec: ToolSpec, tainted: Boolean): Boolean = spec.risk != ToolRisk.Shell && spec.risk != ToolRisk.Web && !spec.alwaysAsk && !tainted
}
