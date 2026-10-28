package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.core.Bootstrap
import com.arcana.brain.core.SetupItem
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import java.nio.file.Files
import java.nio.file.Path

private enum class SetupStep { Vault, Items, Done }

/**
 * Sets Arcana up for a vault, step by step: choose the vault folder, then tick what to create in it (the memory file, the skills folder with
 * its guide and starter skills, the output folder), then see what was made. Shown on the first start (no vault yet) and any time from the
 * palette ("Set up the vault"). It only creates what is missing; `s` on the first step skips it.
 */
@Composable
fun SetupScreen(app: AppContext) {
    val hasVault = remember { app.openVault().isSuccess }
    var step by remember { mutableStateOf(if (hasVault) SetupStep.Items else SetupStep.Vault) }
    var cursor by remember { mutableIntStateOf(0) }
    var ticked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val config = app.config

    fun items(): List<SetupItem> = app.openVault().getOrNull()?.let { Bootstrap.items(it, app.config) }.orEmpty()
    fun enterItems() {
        // what is missing starts ticked; what is there already cannot be ticked
        ticked = items().filter { !it.done }.map { it.id }.toSet()
        cursor = 0
        step = SetupStep.Items
    }
    fun close() {
        app.update(app.config.copy(setupDone = true))
        app.setupOpen = false
    }
    val browser = remember {
        val start = listOf("~/Documents/Obsidian", "~/Obsidian", "~/Documents").map { AppContext.expandHome(it) }.firstOrNull { Files.isDirectory(it) } ?: Path.of(System.getProperty("user.home"))
        DirectoryPickerState(
            start,
            onChoose = { dir ->
                app.update(app.config.copy(vaultPath = dir.toString()))
                if (app.openVault().isSuccess) { error = null; enterItems() } else error = "Cannot use that folder."
            },
            onCancel = {},
        )
    }

    FocusMode(true)
    when (step) {
        SetupStep.Vault -> Hints(*(if (browser.filtering) arrayOf("↑↓  move", "Enter  open", "Esc  clear filter") else arrayOf("Space  use this folder", "Enter  open", "←  up", "/  filter", "s  skip setup")))
        SetupStep.Items -> Hints("↑↓  move", "Space  tick", "Enter  create the ticked", "Esc  ${if (hasVault) "close" else "back"}")
        SetupStep.Done -> Hints("Enter  start")
    }
    KeyHandler { key ->
        when (step) {
            SetupStep.Vault -> when {
                !browser.filtering && key.char == 's' -> { close(); true }
                else -> browser.handle(key)
            }
            SetupStep.Items -> {
                val list = items()
                when {
                    key == Key.Up -> { cursor = (cursor - 1).coerceAtLeast(0); true }
                    key == Key.Down -> { cursor = (cursor + 1).coerceAtMost((list.size - 1).coerceAtLeast(0)); true }
                    key.char == ' ' -> {
                        list.getOrNull(cursor)?.takeIf { !it.done }?.let { ticked = if (it.id in ticked) ticked - it.id else ticked + it.id }
                        true
                    }
                    key == Key.Enter -> {
                        val vault = app.openVault().getOrNull()
                        if (vault == null) error = "Choose a vault first."
                        else runCatching { Bootstrap.create(vault, app.config, ticked) }
                            .onSuccess { report = it; error = null; app.brain.changed(); step = SetupStep.Done }
                            .onFailure { error = it.message ?: "Could not create that." }
                        true
                    }
                    key == Key.Escape -> { if (hasVault) close() else step = SetupStep.Vault; true }
                    else -> false
                }
            }
            SetupStep.Done -> if (key == Key.Enter || key == Key.Escape) { close(); true } else false
        }
    }

    Stack {
        when (step) {
            SetupStep.Vault -> {
                ScreenHeader("Welcome to Arcana", "Step 1 of 2: choose your Obsidian vault, the folder your notes live in")
                VGap()
                Panel("Choose a folder") {
                    DirectoryPicker(browser, rows = bodyRows(extra = 8))
                    error?.let { Warning(it) }
                }
                ActionBar(DirectoryPickerState.actions(browser) + ActionItem("s", "Skip setup"), style = ActionStyle.Button)
            }
            SetupStep.Items -> {
                ScreenHeader(if (hasVault) "Set up the vault" else "Welcome to Arcana", "${if (hasVault) "" else "Step 2 of 2: "}tick what to create in ${config.vaultPath}. Nothing that exists is touched")
                VGap()
                Panel("Create in your vault") {
                    items().forEachIndexed { i, item ->
                        val on = i == cursor
                        Row {
                            Label(if (on) "▸ " else "  ", color = Palette.Cta)
                            Label(if (item.done) "[✓] " else if (item.id in ticked) "[x] " else "[ ] ", color = if (item.done) Palette.Green else if (item.id in ticked) Palette.Cta else Palette.Muted)
                            Label(item.title, color = if (on) Palette.Text else Palette.FieldLabel, textStyle = if (on) TextStyle.Bold else TextStyle.Unspecified)
                            if (item.done) Label("  (already there)", color = Palette.Green)
                        }
                        Label("      ${item.detail}", color = Palette.Muted)
                    }
                    error?.let { Warning(it) }
                }
                ActionBar(listOf(ActionItem("Space", "Tick"), ActionItem("Enter", "Create the ticked", ticked.isNotEmpty(), Palette.Cta), ActionItem("Esc", if (hasVault) "Close" else "Back")), style = ActionStyle.Button)
            }
            SetupStep.Done -> {
                ScreenHeader(if (hasVault) "Set up the vault" else "You are set", "Arcana is ready")
                VGap()
                Panel("Done") {
                    if (report.isEmpty()) Hint("Nothing had to be created: everything was already there.")
                    else {
                        Label("Created:", color = Palette.Muted)
                        report.forEach { Label("  ✓ $it", color = Palette.Green) }
                    }
                    VGap()
                    Hint("Open Chat (1), press Enter and ask. /memory opens your memory file; /skills lists the skills. The Guide (9) explains the rest.")
                }
                ActionBar(listOf(ActionItem("Enter", "Start", accent = Palette.Cta)), style = ActionStyle.Button)
            }
        }

    }
}
