package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** `search_files` looks only where the tools may read: the working folder, and search folders that lie inside an allowed one. */
class ProjectRootsTest {
    private fun app(config: AppConfig): AppContext {
        val dir = Files.createTempDirectory("arcana-roots")
        val store = ConfigStore(dir.resolve("config.json")).also { it.save(config) }
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), EventStore(dir.resolve("events.jsonl")))
    }

    @Test
    fun onlyTheWorkingFolderAndSearchFoldersInsideAllowedOnesAreSearched() {
        val project = Files.createTempDirectory("project")
        val allowed = Files.createTempDirectory("allowed")
        val inside = Files.createDirectories(allowed.resolve("lib"))
        val outside = Files.createTempDirectory("outside")
        val vault = Files.createTempDirectory("vault")
        val config = AppConfig(vaultPath = vault.toString(), projectFolder = project.toString(), toolFolders = allowed.toString(), searchIn = "vault; working; $inside; $outside; ${project.resolve("missing")}; $inside")
        val roots = app(config).chat.projectRoots(config)
        assertEquals(listOf(project, inside).map { it.toRealPath() }, roots.map { it.toRealPath() }, "the working folder first, then the allowed one; not the outside one, not a missing one, no repeats")
        assertEquals(emptyList(), app(AppConfig(vaultPath = vault.toString())).let { it.chat.projectRoots(AppConfig(vaultPath = vault.toString())) }, "no working folder and no search folders: nothing to search")
    }
}
