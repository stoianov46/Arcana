package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppConfig(
    val ollamaUrl: String = "http://localhost:11434",
    val model: String = "gemma4:latest",
    val theme: String = "dark",
    val vaultPath: String = System.getenv("ARCANA_VAULT").orEmpty(),
    val outputFolder: String = "Arcana",
    val temperature: Double = 0.7,
    val timeoutSeconds: Int = 300,
    val contextNotes: Int = 5,
    val contextCharsPerNote: Int = 4000,
    val systemPrompt: String = "You are Arcana, a concise assistant that helps the user work with their Obsidian notes. " +
        "Answer in the language of the user's request. Use Markdown.",
    /** Interface density: "compact", "normal" or "spacious" (a terminal sets its own font size, so this is layout density). */
    val uiScale: String = "normal",
    /** Sidebar screens by name, in the user's order; the first one is where Arcana opens. Empty = the built-in order. */
    val tabOrder: List<String> = emptyList(),
    /** The sidebar folded to icons (Ctrl+B toggles it from anywhere). */
    val sidebarCollapsed: Boolean = false,
    /** Where the navigation sits: "left" sidebar, "right" sidebar, or a "top" bar. */
    val navPosition: String = "left",
    /** The sidebar logo's colours sweep while the model is generating. */
    val animateLogo: Boolean = true,
    /** Plain words and ASCII instead of decorative glyphs and frames, for screen readers. */
    val plainGlyphs: Boolean = false,
    /** Render Markdown in output (headings, bullets, code); off shows the model's text as written. */
    val renderMarkdown: Boolean = true,
    /** Show "Gemma is thinking…" while a reasoning model works before its first words. */
    val showThinking: Boolean = true,
    /** How many of the latest chat messages are sent to the model as context. */
    val chatHistory: Int = 20,
    /** Chats are saved to disk and the last one is reopened when Arcana starts. */
    /** The folder where new, unprocessed notes land; Arcana counts what is waiting there. */
    val inboxFolder: String = "Inbox",
    /** Daily notes: used only when the vault has no Obsidian Daily notes settings of its own. */
    val dailyFolder: String = "",
    val dailyFormat: String = "YYYY-MM-DD",
    /** A note counts as stale after this many days without a change. */
    val staleDays: Int = 90,
    val keepChats: Boolean = true,
    /** Chat searches the vault for each question and answers from the notes it finds, citing them. */
    val chatUseVault: Boolean = false,
    /** Warn before leaving a chat that has not been saved to the vault, with the chance to save it. */
    val confirmLeaveChat: Boolean = true,
    /** The folder the image picker was last left in (empty = start at the home folder). */
    val lastPickerDir: String = "",
    /** How many chats are kept; the least recently used are deleted. */
    val chatsKept: Int = 50,
    /** What select mode copies: "markdown" (as written) or "plain" (Markdown markers removed). */
    val copyAs: String = "markdown",
    val summaryPrompt: String = "Summarize the following note. Start with a one-sentence TL;DR, " +
        "then list the key points as bullets, then any action items.",
)

class ConfigStore(val path: Path = defaultPath()) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): AppConfig {
        if (!Files.exists(path)) return AppConfig()
        return runCatching { json.decodeFromString<AppConfig>(Files.readString(path)) }
            .getOrElse { AppConfig() }
    }

    fun save(config: AppConfig) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(AppConfig.serializer(), config))
    }

    companion object {
        fun defaultPath(): Path {
            System.getenv("ARCANA_CONFIG")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            return Path.of(System.getProperty("user.home"), ".config", "arcana", "config.json")
        }
    }
}
