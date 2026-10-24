package com.arcana.brain.app

import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.SessionStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSessionTest {
    private fun store() = SessionStore(Files.createTempDirectory("sessions").resolve("sessions"))

    @Test
    fun aFreshSessionHasNothingToSaveAndWritesNothing() {
        val store = store()
        val session = AppSession(store)
        assertFalse(session.needsSaving)
        assertFalse(session.hasActivity)
        assertTrue(store.list().isEmpty(), "an empty session leaves no file behind")
    }

    @Test
    fun everyEventIsWrittenAsItHappensAndNeedsSavingUntilSaved() {
        val store = store()
        val session = AppSession(store)
        session.log(SessionKind.Asked, "What is RAG?\nreally")
        session.log(SessionKind.Created, "Summary", "Arcana/Summary.md")
        assertTrue(session.needsSaving)
        assertEquals(2, session.unsavedCount)
        val onDisk = store.list().single()
        assertEquals(2, onDisk.events.size, "persisted at once, so a crash loses nothing")
        assertEquals("What is RAG? really", onDisk.events.first().text, "line breaks are flattened")
        session.markSaved("Arcana/Sessions/Session.md")
        assertFalse(session.needsSaving, "a saved session is not asked about again")
        session.log(SessionKind.Task, "Improve Trip.md")
        assertTrue(session.needsSaving, "new activity after a save needs saving again")
        assertEquals(1, session.unsavedCount)
        assertEquals("Arcana/Sessions/Session.md", store.list().single().savedAs)
    }

    @Test
    fun startingANewSessionClosesTheOldOneAndBeginsEmpty() {
        val store = store()
        val session = AppSession(store)
        val first = session.id
        session.log(SessionKind.Captured, "a thought")
        session.startNew()
        assertNotEquals(first, session.id)
        assertTrue(session.events.isEmpty())
        assertFalse(session.needsSaving)
        assertNull(session.savedAs)
        val closed = store.list().single { it.id == first }
        assertTrue(closed.ended != null, "the old session is marked as ended")
        assertEquals(1, closed.events.size)
    }

    @Test
    fun theSessionBeforeIsRememberedForTheDashboard() {
        val store = store()
        val earlier = AppSession(store)
        earlier.log(SessionKind.Asked, "q")
        earlier.markSaved("Sessions/x.md")
        earlier.finish()
        val next = AppSession(store)
        assertEquals("Sessions/x.md", next.previous?.savedAs)
        assertEquals(1, next.previous?.events?.size)
    }
}
