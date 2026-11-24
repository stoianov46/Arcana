package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSettingsTest {
    @Test
    fun theSettingsBecomeToolSettingsInOnePlace() {
        val project = Files.createTempDirectory("arcana-project")
        val config = AppConfig(toolFolders = " ~/Documents ; /tmp/x ;", toolMode = "plan", toolShell = true, toolWeb = false, outputFolder = "Research", model = "gemma4:12b", projectFolder = project.toString(), skillsEnabled = true)
        val s = ToolSettings.of(config, Path.of("/b"), ".arcana/skills", notesSearch = false)
        assertEquals(listOf(Path.of(System.getProperty("user.home"), "Documents"), Path.of("/tmp/x")), s.folders)
        assertTrue(s.readOnly, "plan mode only reads")
        assertTrue(s.shell && !s.web)
        assertEquals("Research", s.outputFolder); assertEquals("gemma4:12b", s.model); assertEquals(project, s.project)
        assertEquals(".arcana/skills", s.skillsFolder)
        assertTrue(!s.notesSearch)
        // skills off: no skills folder; a project folder that does not exist: none
        val off = ToolSettings.of(config.copy(skillsEnabled = false, projectFolder = "/no/such/folder"), Path.of("/b"), ".arcana/skills", true)
        assertEquals("", off.skillsFolder); assertNull(off.project)
    }

    @Test
    fun aToolBoxFromSettingsOffersTheSameToolsAsOneBuiltByHand() {
        val root = Files.createTempDirectory("arcana-ts")
        val vault = Vault(Files.createDirectories(root.resolve("v")))
        val settings = ToolSettings(backups = root.resolve("b"), shell = true, web = true, readOnly = false)
        val fromSettings = ToolBox(vault, settings).specs.map { it.name }
        val byHand = ToolBox(vault, emptyList(), root.resolve("b"), shell = true, web = true).specs.map { it.name }
        assertEquals(byHand, fromSettings)
    }
}
