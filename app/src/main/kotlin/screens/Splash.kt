package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.Connection
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.core.CheckState
import com.arcana.brain.core.ModelStatus
import com.arcana.brain.core.Startup
import com.arcana.brain.core.StartupCheck
import com.arcana.brain.core.Vault
import com.idea.ink.theme.*
import com.idea.ink.ui.*
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long the start screen stays once everything is checked, and the longest it waits for Ollama before it closes anyway. */
private const val SPLASH_HOLD_MILLIS = 1_500L
private const val SPLASH_MAX_MILLIS = 6_000L

/**
 * The start screen: what Arcana loaded, one line each (Ollama and the model, the vault, the memory file, the skills, the tools, semantic search), filled in as each is
 * checked. It closes by itself a moment after the last check, or at once on any key (the key is not passed on). Settings → Appearance → Start screen turns it off.
 */
@Composable
fun SplashScreen(app: AppContext) {
    var checks by remember { mutableStateOf<List<StartupCheck>?>(null) }
    fun close() { app.splashOpen = false }
    LaunchedEffect(Unit) {
        val config = app.config
        checks = withContext(Dispatchers.IO) {
            val vault = app.openVault().getOrNull()
            val box = runCatching { app.chat.toolboxFor(config) }.getOrNull()
            val skills = if (config.skillsEnabled) (box?.skills ?: vault?.let { com.arcana.brain.core.Skills.discoverAll(it, app.skillsFolder(config)) }).orEmpty().map { it.name } else emptyList()
            listOf(
                Startup.vault(config.vaultPath, vault?.let { runCatching { it.notes().size }.getOrNull() }),
                Startup.memory(config.memoryEnabled, vault?.memoryName() ?: "ARCANA.md", vault?.memoryLength() ?: 0, Vault.MEMORY_LIMIT),
                Startup.skills(config.skillsEnabled, skills),
                Startup.tools(box?.specs, box?.switchedOff.orEmpty(), config.toolMode),
            )
        }
    }
    val connection = app.connection
    // close a moment after the last check; never wait longer than the cap for a slow Ollama
    LaunchedEffect(checks != null && connection !is Connection.Checking) {
        if (checks != null && connection !is Connection.Checking) { delay(SPLASH_HOLD_MILLIS); close() }
    }
    LaunchedEffect(Unit) { delay(SPLASH_MAX_MILLIS); close() }
    KeyHandler { close(); true }
    Hints("any key  continue")

    val config = app.config
    val plain = config.plainGlyphs
    val model = when (connection) {
        Connection.Checking -> StartupCheck("Model", "${config.model} · asking Ollama at ${config.ollamaUrl}…", CheckState.Pending)
        is Connection.Online -> StartupCheck("Model", "${config.model} · Ollama at ${config.ollamaUrl}" + listOfNotNull("tools".takeIf { app.tools == true }, "thinking".takeIf { app.thinking == true }, "vision".takeIf { app.vision == true }).joinToString("") { " · $it" }, CheckState.Ok)
        is Connection.ModelMissing -> StartupCheck("Model", "${config.model} is not installed: ollama pull ${config.model}", CheckState.Warn)
        is Connection.Offline -> StartupCheck("Model", "Ollama does not answer at ${config.ollamaUrl}: start it with ollama serve", CheckState.Warn)
    }
    val installed = (connection as? Connection.Online)?.models?.let { models -> config.embedModel.isNotBlank() && models.any { ModelStatus.sameModel(it, config.embedModel) } }
    val lines = listOf(model) + (checks ?: listOf(StartupCheck("Vault", "reading…", CheckState.Pending))) + Startup.search(config.embedModel, if (connection is Connection.Checking) null else installed ?: false.takeIf { config.embedModel.isNotBlank() })

    Stack {
        VGap()
        Label(if (plain) "Arcana" else "◆ Arcana", color = Palette.Accent, textStyle = TextStyle.Bold)
        Label("the research system of your second brain", color = Palette.Muted)
        VGap()
        Panel(if (checks == null || connection is Connection.Checking) "Loading…" else "Ready", accent = if (lines.any { it.state == CheckState.Warn }) Palette.Orange else Palette.Green) {
            lines.forEach { c ->
                val (mark, color) = when (c.state) {
                    CheckState.Ok -> (if (plain) "ok" else "✓") to Palette.Green
                    CheckState.Warn -> (if (plain) "!" else "⚠") to Palette.Orange
                    CheckState.Off -> (if (plain) "-" else "○") to Palette.Dim
                    CheckState.Pending -> (if (plain) ".." else "◌") to Palette.Muted
                }
                Row {
                    Label("${mark.padEnd(2)} ", color = color)
                    Label(c.label.padEnd(8), color = Palette.Muted)
                    Label(c.value, color = if (c.state == CheckState.Off) Palette.Dim else Palette.Text)
                }
            }
        }
        Hint("Any key to continue · Settings → Appearance → Start screen turns this off")
    }
}
