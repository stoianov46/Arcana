package com.arcana.brain.core

/** How a line of the start screen looks: done and fine, worth a look, switched off on purpose, or still being checked. */
enum class CheckState { Ok, Warn, Off, Pending }

/** One line of the start screen: `✓ Skills   8 · create-dev-research-report, ascii-bar-chart, …`. */
data class StartupCheck(val label: String, val value: String, val state: CheckState)

/**
 * What the start screen says Arcana loaded, worked out without the UI so it can be tested: the vault, the memory file, the skills, the tools and semantic search.
 * The model and Ollama are the app's own connection state and are added by the screen.
 */
object Startup {
    /** How many skill names the line spells out before `+ n more`. */
    const val SKILL_NAMES = 3

    fun vault(path: String, notes: Int?): StartupCheck =
        if (path.isBlank()) StartupCheck("Vault", "none chosen (Settings → Vault)", CheckState.Warn)
        else StartupCheck("Vault", path.replace(System.getProperty("user.home"), "~") + (notes?.let { " · $it notes" } ?: ""), CheckState.Ok)

    fun memory(enabled: Boolean, name: String, chars: Int, limit: Int): StartupCheck = when {
        !enabled -> StartupCheck("Memory", "off (Settings → Console)", CheckState.Off)
        chars == 0 -> StartupCheck("Memory", "$name is empty or missing (/memory opens it)", CheckState.Warn)
        chars > limit -> StartupCheck("Memory", "$name · ${UsageStats.compact(chars.toLong())} chars, only the first ${UsageStats.compact(limit.toLong())} are sent", CheckState.Warn)
        else -> StartupCheck("Memory", "$name · ${UsageStats.compact(chars.toLong())} chars, sent with every message", CheckState.Ok)
    }

    fun skills(enabled: Boolean, names: List<String>): StartupCheck = when {
        !enabled -> StartupCheck("Skills", "off (/skills)", CheckState.Off)
        names.isEmpty() -> StartupCheck("Skills", "none in the skills folder", CheckState.Off)
        else -> StartupCheck("Skills", "${names.size} · " + names.sorted().take(SKILL_NAMES).joinToString(", ") + if (names.size > SKILL_NAMES) ", + ${names.size - SKILL_NAMES} more" else "", CheckState.Ok)
    }

    /** `31 tools · 19 read, 12 change · shell off, web on`: what the model may use now; null specs = tools are off. */
    fun tools(specs: List<ToolSpec>?, switchedOff: List<String>, mode: String): StartupCheck {
        if (specs == null) return StartupCheck("Tools", "off: the model only talks (/tools)", CheckState.Off)
        val read = specs.count { it.risk == ToolRisk.Read }
        val change = specs.size - read
        val off = listOf("shell", "web").joinToString(", ") { "$it ${if (it in switchedOff) "off" else "on"}" }
        return StartupCheck("Tools", "${specs.size} · $read read, $change change · $off · mode $mode", CheckState.Ok)
    }

    /** Semantic search: the embedding model, or words only; [installed] null = not known yet (Ollama not asked). */
    fun search(embedModel: String, installed: Boolean?): StartupCheck = when {
        embedModel.isBlank() -> StartupCheck("Search", "by words (no embedding model: Settings → Search)", CheckState.Off)
        installed == null -> StartupCheck("Search", "words + meaning · $embedModel", CheckState.Pending)
        installed -> StartupCheck("Search", "words + meaning · $embedModel", CheckState.Ok)
        else -> StartupCheck("Search", "$embedModel is not installed: ollama pull $embedModel", CheckState.Warn)
    }
}
