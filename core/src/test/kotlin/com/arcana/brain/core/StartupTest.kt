package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupTest {
    @Test
    fun theStartScreenLinesSayWhatIsLoadedAndWhatIsOff() {
        assertEquals(CheckState.Warn, Startup.vault("", null).state)
        assertTrue(Startup.vault("/x/v", 12).value.endsWith("· 12 notes"))
        assertEquals(CheckState.Off, Startup.memory(false, "ARCANA.md", 10, 8000).state)
        assertEquals(CheckState.Warn, Startup.memory(true, "ARCANA.md", 0, 8000).state)
        assertTrue("only the first 8k" in Startup.memory(true, "ARCANA.md", 9000, 8000).value)
        assertEquals("5 · a, b, c, + 2 more", Startup.skills(true, listOf("e", "d", "c", "b", "a")).value)
        assertEquals(CheckState.Off, Startup.skills(true, emptyList()).state)
        assertEquals(CheckState.Off, Startup.tools(null, emptyList(), "ask").state)
        assertEquals(CheckState.Pending, Startup.search("nomic-embed-text", null).state)
        assertEquals(CheckState.Warn, Startup.search("nomic-embed-text", false).state)
        assertEquals(CheckState.Off, Startup.search("", null).state)
    }

    @Test
    fun theToolsLineCountsWhatReadsAndWhatChanges() {
        val root = Files.createTempDirectory("arcana-startup")
        val box = ToolBox(Vault(Files.createDirectories(root.resolve("v"))), emptyList(), root.resolve("b"))
        val line = Startup.tools(box.specs, box.switchedOff, "ask").value
        val read = box.specs.count { it.risk == ToolRisk.Read }
        assertTrue(line.startsWith("${box.specs.size} · $read read, ${box.specs.size - read} change · shell off, web off · mode ask"), line)
    }
}
