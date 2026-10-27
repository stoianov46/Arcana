package com.arcana.brain.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.arcana.brain.app.design.Palette
import com.arcana.brain.app.design.Themes
import com.arcana.brain.app.navigation.Navigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.app.navigation.Shell
import com.arcana.brain.app.screens.ChatScreen
import com.arcana.brain.app.screens.ChangelogScreen
import com.arcana.brain.app.screens.GuideScreen
import com.arcana.brain.app.screens.HomeScreen
import com.arcana.brain.app.screens.InboxScreen
import com.arcana.brain.app.screens.LibraryScreen
import com.arcana.brain.app.screens.NoteComposerScreen
import com.arcana.brain.app.screens.SessionScreen
import com.arcana.brain.app.screens.SettingsScreen
import com.arcana.brain.app.screens.SetupScreen
import com.arcana.brain.app.screens.UsageScreen
import com.jakewharton.mosaic.runMosaic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // `arcana --init <vault>` and `--help` need no screen
    Cli.run(args)?.let { exitProcess(it) }
    val quit = CompletableDeferred<Unit>()
    // Mosaic renders inline; start from a clean screen so the frame never scrolls and old output does not show through.
    print("\u001b[2J\u001b[H")
    runBlocking {
        val ui = launch { runMosaic { ArcanaApp(onQuit = { quit.complete(Unit) }) } }
        // Quitting cancels Mosaic from outside, which restores the terminal. It does not depend on the terminal reporting
        // Ctrl+C as an exact key event (some add modifier bits, which Mosaic's own Ctrl+C check would not match).
        launch {
            quit.await()
            ui.cancel()
        }
        ui.join()
        coroutineContext.cancelChildren()
    }
    // Mosaic reads the keyboard on a native thread. Halting skips the JVM's orderly shutdown, which could otherwise race
    // with that thread (a rare crash on quit). Nothing is left to flush: settings are written synchronously.
    System.out.flush()
    Runtime.getRuntime().halt(0)
}

@Composable
private fun ArcanaApp(onQuit: () -> Unit) {
    val app = remember { AppContext() }
    // Replies are generated in the app's own scope, so they keep streaming while another screen is showing.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    remember { app.scope = scope }
    val navigator = remember { Navigator() }

    LaunchedEffect(app.config.theme) { Palette.use(Themes.byId(app.config.theme)) }
    LaunchedEffect(app.config.ollamaUrl, app.config.model) { app.refreshConnection() }
    // The map of the vault is read in the background, and again whenever something may have changed in it.
    LaunchedEffect(app.config.vaultPath, app.brain.revision) { app.brain.refresh() }

    Shell(app, navigator, onQuit = onQuit) { screen ->
        // A new session rebuilds every screen from scratch (their local state, drafts, filters).
        androidx.compose.runtime.key(app.revision) {
        if (app.setupOpen) SetupScreen(app) else when (screen) {
            Screen.Home -> HomeScreen(app)
            Screen.Chat -> ChatScreen(app)
            Screen.Library -> LibraryScreen(app)
            Screen.Inbox -> InboxScreen(app)
            Screen.Session -> SessionScreen(app)
            Screen.Compose -> NoteComposerScreen(app)
            Screen.Usage -> UsageScreen(app)
            Screen.Settings -> SettingsScreen(app)
            Screen.Guide -> GuideScreen(app)
            Screen.Changelog -> ChangelogScreen(app)
        }
        }
    }
}
