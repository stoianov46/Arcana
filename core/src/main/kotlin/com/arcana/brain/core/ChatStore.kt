package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One saved conversation: what was said, with which model, and when it was last touched. */
@Serializable
data class StoredChat(
    val id: String,
    val title: String,
    val updated: Long,
    val model: String,
    val messages: List<ChatMessage>,
)

/** Chats are kept as one JSON file each in a `chats` folder next to the settings. */
class ChatStore(val dir: Path = defaultDir()) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Every saved chat, the most recently used first. */
    fun list(): List<StoredChat> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files ->
            files.filter { it.extension == "json" }.toList()
        }.mapNotNull { file ->
            runCatching { json.decodeFromString<StoredChat>(Files.readString(file)) }.getOrNull()
        }.sortedByDescending { it.updated }
    }

    fun latest(): StoredChat? = list().firstOrNull()

    fun save(chat: StoredChat) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${chat.id}.json"), json.encodeToString(StoredChat.serializer(), chat))
    }

    fun delete(id: String) {
        Files.deleteIfExists(dir.resolve("$id.json"))
    }

    /** Keeps the [keep] most recently used chats and deletes the rest. */
    fun prune(keep: Int) {
        list().drop(keep.coerceAtLeast(1)).forEach { delete(it.id) }
    }

    companion object {
        fun defaultDir(): Path = ConfigStore.defaultPath().resolveSibling("chats")

        /** The title of a chat: the first line of the first thing the user said. */
        fun titleOf(messages: List<ChatMessage>): String =
            messages.firstOrNull { it.role == "user" }?.content?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60)
                ?: "New chat"
    }
}
