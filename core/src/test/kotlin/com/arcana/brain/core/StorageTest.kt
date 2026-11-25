package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Arcana's own files: the config round trip, the chat store, Storage (list, measure, clear). */
class StorageTest {
    @Test
    fun configRoundTrips() {
        val dir = Files.createTempDirectory("arcana")
        val store = ConfigStore(dir.resolve("nested/config.json"))
        assertEquals(AppConfig(), store.load())
        val changed = AppConfig(model = "x", vaultPath = "/v", temperature = 0.1)
        store.save(changed)
        assertEquals(changed, store.load())
    }

    @Test
    fun chatsAreSavedListedAndPruned() {
        val store = ChatStore(Files.createTempDirectory("chats").resolve("chats"))
        assertTrue(store.list().isEmpty())
        fun chat(id: String, at: Long, first: String) =
            StoredChat(id, ChatStore.titleOf(listOf(ChatMessage.user(first))), at, "gemma", listOf(ChatMessage.user(first), ChatMessage.assistant("ok")))
        store.save(chat("a", 1, "first chat\nmore"))
        store.save(chat("b", 3, "newest"))
        store.save(chat("c", 2, "middle"))
        assertEquals(listOf("b", "c", "a"), store.list().map { it.id })
        assertEquals("first chat", store.list().last().title)
        assertEquals("b", store.latest()?.id)
        store.prune(2)
        assertEquals(listOf("b", "c"), store.list().map { it.id })
        store.delete("b")
        assertEquals(listOf("c"), store.list().map { it.id })
        assertEquals("New chat", ChatStore.titleOf(emptyList()))
    }

    @Test
    fun storageListsMeasuresAndClearsArcanasOwnFiles() {
        val root = Files.createTempDirectory("arcana-storage")
        val vault = Files.createDirectories(root.resolve("vault"))
        val config = Files.createDirectories(root.resolve("cfg"))
        Files.createDirectories(config.resolve("backups/n")); Files.writeString(config.resolve("backups/n/a.bak"), "x".repeat(2048)); Files.writeString(config.resolve("backups/b.bak"), "y")
        Files.writeString(config.resolve("usage.jsonl"), "{}\n")
        Files.createDirectories(vault.resolve(".trash")); Files.writeString(vault.resolve(".trash/gone.md"), "old")
        val areas = Storage.areas(config, vault)
        assertEquals(listOf("backups", "trash", "vault-trash", "chats", "usage", "tool-usage", "events"), areas.map { it.id })
        assertEquals(listOf("backups", "trash", "chats", "usage", "tool-usage", "events"), Storage.areas(config).map { it.id }, "no vault, no vault trash")
        fun area(id: String) = areas.first { it.id == id }
        assertEquals(StorageUse(2, 2049), Storage.use(area("backups")))
        assertEquals(StorageUse(1, 3), Storage.use(area("usage")))
        assertEquals(StorageUse(0, 0), Storage.use(area("chats")), "a missing area is empty")
        // by age: the old file goes, the new one stays, and the folder it was in goes with it
        val old = System.currentTimeMillis() - 40 * 86_400_000L
        Files.setLastModifiedTime(config.resolve("backups/n/a.bak"), java.nio.file.attribute.FileTime.fromMillis(old))
        assertEquals(StorageUse(1, 2048), Storage.clearable(area("backups"), 30))
        assertEquals(1, Storage.clear(area("backups"), 30))
        assertTrue(!Files.exists(config.resolve("backups/n")) && Files.exists(config.resolve("backups/b.bak")) && Files.isDirectory(config.resolve("backups")))
        assertEquals(1, Storage.clear(area("backups")))
        assertTrue(Files.isDirectory(config.resolve("backups")) && Storage.use(area("backups")).files == 0)
        assertEquals(1, Storage.clear(area("usage")))
        assertTrue(!Files.exists(config.resolve("usage.jsonl")))
        assertEquals(1, Storage.clear(area("vault-trash")))
        assertEquals(0, Storage.clear(area("chats")))
        assertEquals("0 B", Storage.size(0)); assertEquals("12 KB", Storage.size(12 * 1024)); assertEquals("1.5 MB", Storage.size(1_572_864))
    }
}
