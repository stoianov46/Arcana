package com.arcana.brain.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.core.SessionEvent
import com.arcana.brain.core.SessionStore
import com.arcana.brain.core.StoredSession

/**
 * What you do between opening Arcana and closing it (or starting a new session): every question, AI task and change to
 * your vault is logged here, and written to disk as it happens, so nothing is lost if the terminal is closed.
 *
 * A session can be turned into a note in the vault (a summary of what was done and learned), saved by hand at any time,
 * and a new session can start without restarting the app.
 */
class AppSession(private val store: SessionStore, private val keep: Int = 100) {
    /** The session before this one, if any: shown on the dashboard as "last time". */
    val previous: StoredSession? = runCatching { store.list().firstOrNull() }.getOrNull()

    var id by mutableStateOf(newId())
        private set
    var started by mutableLongStateOf(System.currentTimeMillis())
        private set
    val events = mutableStateListOf<SessionEvent>()

    /** The vault note this session was last saved to; null until it has been. */
    var savedAs by mutableStateOf<String?>(null)
        private set

    /** How many events the saved note already covers. */
    private var savedCount by mutableStateOf(0)

    val hasActivity: Boolean get() = events.isNotEmpty()

    /** There is activity that no saved note covers yet. */
    val needsSaving: Boolean get() = events.size > savedCount

    val unsavedCount: Int get() = (events.size - savedCount).coerceAtLeast(0)

    fun log(kind: String, text: String, path: String? = null) {
        events += SessionEvent(System.currentTimeMillis(), kind, text.replace('\n', ' ').take(200), path)
        persist()
    }

    fun markSaved(path: String) {
        savedAs = path
        savedCount = events.size
        persist()
    }

    fun snapshot(ended: Long? = null) = StoredSession(id, started, ended, savedAs, events.toList())

    /** The kind of thing the user did, newest last: what a summary is written from. */
    val activity: List<SessionEvent> get() = events.toList()

    /** Closes this session and begins a fresh one; the closed one stays on disk. */
    fun startNew() {
        persist(ended = System.currentTimeMillis())
        id = newId()
        started = System.currentTimeMillis()
        events.clear()
        savedAs = null
        savedCount = 0
    }

    /** Called when Arcana exits. */
    fun finish() = persist(ended = System.currentTimeMillis())

    private fun persist(ended: Long? = null) {
        if (events.isEmpty()) return
        runCatching {
            store.save(snapshot(ended))
            store.prune(keep)
        }
    }

    private fun newId() = System.currentTimeMillis().toString(36) + (0..9999).random().toString(36)
}
