package com.arcana.brain.app

import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.EventStore
import com.arcana.brain.core.ResearchBacklog
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Capture keeps every thought in the thought file; one that starts with `? ` or `research: ` is also a question in the research backlog. */
class ResearchBacklogCaptureTest {
    private fun app(vault: Path): AppContext {
        val dir = Files.createTempDirectory("arcana-backlog")
        val store = ConfigStore(dir.resolve("config.json")).also { it.save(AppConfig(vaultPath = vault.toString())) }
        return AppContext(store, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), EventStore(dir.resolve("events.jsonl")))
    }

    @Test
    fun aQuestionThoughtIsAlsoInTheBacklogAndAnOrdinaryOneIsNot() {
        val vault = Files.createTempDirectory("vault")
        val app = app(vault)
        val noteWithQuestion = app.capture("? what is the outbox pattern").getOrThrow()
        app.capture("buy milk").getOrThrow()
        app.capture("Research: Navigation 3 on KMP").getOrThrow()
        app.capture("? What is the outbox pattern?").getOrThrow() // the same question again: not added twice
        val thoughts = Files.readString(vault.resolve(noteWithQuestion))
        assertTrue("? what is the outbox pattern" in thoughts && "buy milk" in thoughts, "the thought itself is saved as always, prefix and all")
        val open = ResearchBacklog.open(app.openVault().getOrThrow())
        assertEquals(listOf("what is the outbox pattern", "Navigation 3 on KMP"), open.map { it.question })
    }

    @Test
    fun noBacklogNoteWithoutAQuestion() {
        val vault = Files.createTempDirectory("vault")
        app(vault).capture("just a thought").getOrThrow()
        assertTrue(!Files.exists(vault.resolve(ResearchBacklog.PATH)))
    }
}
