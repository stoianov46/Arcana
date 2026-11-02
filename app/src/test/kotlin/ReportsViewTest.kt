package com.arcana.brain.app

import com.arcana.brain.app.navigation.LibraryFilter
import com.arcana.brain.app.screens.defaultLibraryFilter
import com.arcana.brain.core.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Library's Reports view sits in the tab bar between Recent and Inbox, and Settings → Library → Opens on can name it. */
class ReportsViewTest {
    @Test
    fun theReportsViewIsOneOfTheLibraryViews() {
        val order = LibraryFilter.entries.map { it.title }
        assertEquals(listOf("All", "Recent", "Reports", "Inbox"), order.take(4))
        assertEquals(LibraryFilter.Reports, defaultLibraryFilter(AppConfig(libraryTab = "reports")))
        assertEquals(LibraryFilter.All, defaultLibraryFilter(AppConfig(libraryTab = "nonsense")))
    }
}
