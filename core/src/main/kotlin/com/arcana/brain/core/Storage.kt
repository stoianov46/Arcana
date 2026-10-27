package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path

/** A place where Arcana keeps files of its own, and what it holds. */
data class StorageArea(
    val id: String,
    val title: String,
    val detail: String,
    val path: Path,
    /** Backups and trash can be pruned by age; the others are all or nothing. */
    val prunable: Boolean = false,
)

/** How much is in a [StorageArea]. */
data class StorageUse(val files: Int, val bytes: Long)

/**
 * Arcana's own files (settings are not among them): backups of files the model changed, the trash of files it deleted, chat history,
 * saved sessions and the usage logs. Settings → Storage lists them with their size and clears them on request.
 */
object Storage {
    const val BACKUPS = "backups"
    const val TRASH = "trash"
    const val CHATS = "chats"
    const val SESSIONS = "sessions"
    const val USAGE = "usage"
    const val TOOL_USAGE = "tool-usage"
    const val VAULT_TRASH = "vault-trash"

    /** The areas under [root] (the folder that holds `config.json`), and the vault's own `.trash` when a vault is given. */
    fun areas(root: Path, vaultRoot: Path? = null): List<StorageArea> = buildList {
        add(StorageArea(BACKUPS, "Backups", "the old version of every file the model changed", root.resolve("backups"), prunable = true))
        add(StorageArea(TRASH, "Trash (outside the vault)", "files the model deleted that were not in the vault", root.resolve("trash"), prunable = true))
        if (vaultRoot != null) add(StorageArea(VAULT_TRASH, "Vault trash (.trash)", "notes and files the model deleted from the vault (Obsidian's own .trash)", vaultRoot.resolve(".trash"), prunable = true))
        add(StorageArea(CHATS, "Chat history", "every saved chat", root.resolve("chats")))
        add(StorageArea(SESSIONS, "Saved sessions", "the activity logs of past sessions", root.resolve("sessions")))
        add(StorageArea(USAGE, "Usage history", "tokens and timing of every request (the Usage screen)", root.resolve("usage.jsonl")))
        add(StorageArea(TOOL_USAGE, "Tool usage history", "every tool call (the Usage screen's Tools page)", root.resolve("tool-usage.jsonl")))
    }

    fun use(area: StorageArea): StorageUse {
        if (!Files.exists(area.path)) return StorageUse(0, 0)
        if (Files.isRegularFile(area.path)) return StorageUse(1, runCatching { Files.size(area.path) }.getOrDefault(0))
        return runCatching {
            Files.walk(area.path).use { s ->
                val files = s.filter { Files.isRegularFile(it) }.toList()
                StorageUse(files.size, files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) })
            }
        }.getOrDefault(StorageUse(0, 0))
    }

    /** What clearing [area] would remove: everything, or (with [olderThanDays]) only files not changed for that long. */
    fun clearable(area: StorageArea, olderThanDays: Int = 0, now: Long = System.currentTimeMillis()): StorageUse {
        if (olderThanDays <= 0) return use(area)
        val limit = now - olderThanDays * 86_400_000L
        val old = oldFiles(area, limit)
        return StorageUse(old.size, old.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) })
    }

    /** Deletes the files of [area] (only those older than [olderThanDays] when it is above 0) and the folders left empty; returns how many files went. */
    fun clear(area: StorageArea, olderThanDays: Int = 0, now: Long = System.currentTimeMillis()): Int {
        if (!Files.exists(area.path)) return 0
        if (Files.isRegularFile(area.path)) { Files.delete(area.path); return 1 }
        val victims = if (olderThanDays <= 0) allFiles(area) else oldFiles(area, now - olderThanDays * 86_400_000L)
        victims.forEach { runCatching { Files.delete(it) } }
        // folders that are empty now go too (the area's own folder stays)
        runCatching {
            Files.walk(area.path).use { s -> s.sorted(Comparator.reverseOrder()).filter { it != area.path && Files.isDirectory(it) }.toList() }
                .forEach { dir -> runCatching { if (Files.list(dir).use { it.findAny().isEmpty }) Files.delete(dir) } }
        }
        return victims.size
    }

    private fun allFiles(area: StorageArea): List<Path> = runCatching { Files.walk(area.path).use { s -> s.filter { Files.isRegularFile(it) }.toList() } }.getOrDefault(emptyList())

    private fun oldFiles(area: StorageArea, limitMillis: Long): List<Path> =
        allFiles(area).filter { runCatching { Files.getLastModifiedTime(it).toMillis() < limitMillis }.getOrDefault(false) }

    /** `1.4 MB`, `12 KB`, `0 B`. */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
    }
}
