package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.arcana.brain.app.AppContext

/**
 * What a screen remembers while another tab is open. The shell draws only the current screen, so a screen's `remember` state is thrown away when you leave
 * (the Library forgot its selection and scroll, the Brain its selected line). State that should survive is created through [keep] instead: the first call with a
 * key makes the object, every later call (the next visit) gets the same one. The objects are the screens' own state holders (`PickerState`, `OutputState`,
 * a `MutableState`), so nothing is copied or saved; it lives as long as the app runs. Put what the state belongs to in the key (the vault path), so a changed
 * vault starts clean.
 */
class ScreenMemory {
    private val kept = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(key: String, init: () -> T): T = kept.getOrPut(key, init) as T
}

/** [ScreenMemory.keep] for a composable: the screen's state holder for [key], the same object on every visit of the screen. */
@Composable
fun <T : Any> rememberKept(app: AppContext, key: String, init: () -> T): T = remember(app, key) { app.screenMemory.keep(key, init) }
