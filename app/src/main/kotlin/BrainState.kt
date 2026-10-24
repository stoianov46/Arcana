package com.arcana.brain.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.core.VaultIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Arcana knows about the vault as a whole: the index of every note with its links and tags. Building it reads every
 * note, so it happens in the background; screens show the last result and a "reading…" hint while a new one is built.
 */
class BrainState(private val app: AppContext) {
    var index by mutableStateOf<VaultIndex?>(null)
        private set
    var reading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Bumped whenever something may have changed in the vault, so the index is read again. */
    var revision by mutableIntStateOf(0)
        private set

    fun changed() {
        revision++
    }

    suspend fun refresh() {
        val vault = app.openVault().getOrElse {
            index = null
            error = it.message
            return
        }
        reading = true
        try {
            index = withContext(Dispatchers.IO) { VaultIndex.build(vault) }
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            reading = false
        }
    }
}
