package com.arcana.brain.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.FileAttachment
import com.arcana.brain.core.ImageAttachment
import com.arcana.brain.core.Prompts
import com.arcana.brain.core.ReplyStats
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.StoredChat
import com.arcana.brain.core.Vault

/**
 * The conversation on the Chat screen. It lives here, not in the screen, so it survives switching to another screen, and
 * so does the reply being written: it keeps streaming while you look at something else. With [AppConfig.keepChats] on it
 * is saved after every message, so the last chat is back when Arcana starts again.
 *
 * A message can carry pictures and notes from the vault (their text is read when the message is sent), and with
 * [AppConfig.chatUseVault] on, the vault is searched for each question and the answer cites the notes it used.
 */
class ChatSession(private val app: AppContext, private val store: ChatStore) {
    val messages = mutableStateListOf<ChatMessage>()

    /** What is typed in the message field but not sent yet: kept too, so leaving the screen does not lose it. */
    val input = TextFieldState(multiline = true)
    var id by mutableStateOf(newId())
        private set

    /** Pictures, files and notes chosen for the next message; they go out with it and are cleared. */
    val attachments = app.attachments()

    /** How many messages were already saved to the vault as a note; anything after that is unsaved. */
    var savedMessages by mutableIntStateOf(0)

    /** Messages that exist only in the chat history, not in a vault note. */
    val unsaved: Boolean get() = messages.size > savedMessages

    private var pendingSources: List<String> = emptyList()

    /** The reply being written. It lives as long as the app does. */
    val gen: Generation by lazy {
        Generation(
            app.scope, app.client,
            onBusy = { busy -> app.busy = (app.busy + if (busy) 1 else -1).coerceAtLeast(0) },
            onUsage = { chunk -> app.recordUsage("Chat", chunk) },
            onComplete = { text, stats -> finishReply(text, stats) },
        )
    }

    init {
        if (app.config.keepChats) store.latest()?.let { open(it) }
    }

    val title: String get() = ChatStore.titleOf(messages)

    fun add(message: ChatMessage) {
        messages += message
        persist()
    }

    /** Sends [text] with the attached pictures and notes; the reply streams into [gen]. */
    fun send(text: String) {
        val sent = attachments.drain()
        val body = text.ifEmpty {
            if (sent.pictures.isEmpty() && sent.files.isNotEmpty()) "Explain the attached file${if (sent.files.size == 1) "" else "s"}."
            else "Describe the attached image${if (sent.pictures.size == 1) "" else "s"}."
        }
        add(ChatMessage.user(body, sent.pictures, sent.notes, sent.files))
        app.session.log(SessionKind.Asked, body)
        val history = messages.toList()
        gen.start {
            val config = app.config
            val vault = app.openVault().getOrNull()
            val hits = if (config.chatUseVault && vault != null) vault.search(body, config.contextNotes, config.contextCharsPerNote) else null
            pendingSources = hits?.map { it.note.path }.orEmpty()
            Prompts.chatMessages(config, history, hits, noteTexts(vault, history.takeLast(config.chatHistory)))
        }
    }

    /** The text of every note attached to [window], read from the vault now (so a note that changed is seen as it is). */
    private fun noteTexts(vault: Vault?, window: List<ChatMessage>): Map<String, String> {
        if (vault == null) return emptyMap()
        return window.flatMap { it.noteNames }.distinct().mapNotNull { path ->
            runCatching { path to Vault.splitFrontmatter(vault.read(path)).second.take(MAX_NOTE_CHARS) }.getOrNull()
        }.toMap()
    }

    private fun finishReply(text: String, stats: ReplyStats?) {
        add(ChatMessage.assistant(text, stats, pendingSources))
        pendingSources = emptyList()
        gen.consume()
    }

    /** Starts an empty conversation. The old one stays saved and can be opened again from the history. */
    fun startNew() {
        messages.clear()
        input.clear()
        attachments.clear()
        savedMessages = 0
        id = newId()
    }

    fun open(chat: StoredChat) {
        messages.clear()
        messages += chat.messages
        attachments.clear()
        id = chat.id
        // A chat brought back from the history is not nagged about: it was already kept, and the user chose to open it.
        savedMessages = messages.size
    }

    /** Erases this conversation (also from disk) and starts an empty one. */
    fun deleteCurrent() {
        store.delete(id)
        startNew()
    }

    fun history(): List<StoredChat> = if (app.config.keepChats) store.list() else emptyList()

    private fun persist() {
        if (!app.config.keepChats || messages.isEmpty()) return
        runCatching {
            // pictures are sent from memory; only their names are kept on disk
            store.save(StoredChat(id, title, System.currentTimeMillis(), app.config.model, messages.map { it.withoutImageData() }))
            store.prune(app.config.chatsKept)
        }
    }

    private fun newId() = System.currentTimeMillis().toString(36) + (0..99999).random().toString(36)

    private companion object {
        const val MAX_NOTE_CHARS = 60_000
    }
}
