package com.arcana.brain.core

import java.io.File
import kotlin.concurrent.thread

/** What Arcana can tell you with a sound while you are somewhere else. */
enum class ChatSoundEvent(val label: String) {
    /** The model waits for you: an approval or a question. */
    Attention("Needs you"),
    /** A reply (with all its tool steps) is finished. */
    Done("Reply finished"),
    /** A reply ended with an error. */
    Failed("Reply failed"),
}

/**
 * The sounds to choose from, as ids kept in the settings: `off`, `bell1`..`bell5` (terminal bells), `sys:Hero` (a macOS system sound) and
 * `file` (a sound file of your own). The same ids as in CosmoCLI.
 */
object SoundChoices {
    const val OFF = "off"
    const val FILE = "file"
    private const val BELL = "bell"
    private const val SYSTEM = "sys:"

    /** The macOS sounds in /System/Library/Sounds. */
    val system = listOf("Pop", "Hero", "Glass", "Submarine", "Basso", "Funk", "Ping", "Tink", "Purr", "Frog", "Bottle", "Blow", "Morse", "Sosumi")
    val ids: List<String> = listOf(OFF) + system.map { SYSTEM + it } + (1..5).map { BELL + it } + FILE

    /** The sound an event is suggested to have: its own, so they can be told apart by ear. */
    fun suggested(event: ChatSoundEvent): String = SYSTEM + when (event) {
        ChatSoundEvent.Attention -> "Ping"
        ChatSoundEvent.Done -> "Hero"
        ChatSoundEvent.Failed -> "Basso"
    }

    fun isValid(id: String) = id in ids

    fun label(id: String): String = when {
        id == OFF -> "Off"
        id == FILE -> "Your own file"
        id.startsWith(BELL) -> "Terminal bell × ${id.removePrefix(BELL)}"
        id.startsWith(SYSTEM) -> "System · ${id.removePrefix(SYSTEM)}"
        else -> id
    }

    /** Beeps of a bell choice, or null for any other. */
    fun beeps(id: String): Int? = if (id.startsWith(BELL)) id.removePrefix(BELL).toIntOrNull() else null

    /** Name of the macOS sound of a system choice, or null for any other. */
    fun systemName(id: String): String? = if (id.startsWith(SYSTEM)) id.removePrefix(SYSTEM) else null
}

/** Plays a chosen sound. Never blocks the caller and never throws: a missing player just means silence. */
fun interface SoundPlayer {
    fun play(choice: String, volume: Int, file: String, event: ChatSoundEvent?)
}

/**
 * Plays a [SoundChoices] id: the terminal bell, a macOS system sound or a file of the user's own through `afplay` (with the volume).
 * Where `afplay` is not available (not macOS) or the file is missing, it falls back to the bell, so a chosen sound is never silently lost.
 */
class CommandSoundPlayer(
    private val macOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true),
    private val ring: () -> Unit = { System.out.write(7); System.out.flush() },
    private val run: (List<String>) -> Unit = { cmd -> ProcessBuilder(cmd).redirectErrorStream(true).start().also { it.outputStream.close() } },
    private val exists: (String) -> Boolean = { File(it).exists() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    /** Runs the work off the caller's thread. */
    private val background: (() -> Unit) -> Unit = { work -> thread(isDaemon = true, name = "arcana-sound") { work() } },
    private val home: String = System.getProperty("user.home").orEmpty(),
) : SoundPlayer {
    override fun play(choice: String, volume: Int, file: String, event: ChatSoundEvent?) {
        if (choice == SoundChoices.OFF || !SoundChoices.isValid(choice)) return
        background {
            runCatching {
                val beeps = SoundChoices.beeps(choice)
                val path = when {
                    beeps != null -> null
                    choice == SoundChoices.FILE -> expand(file)
                    else -> SoundChoices.systemName(choice)?.let { systemFile(it) }
                }
                if (path != null && macOs && exists(path)) run(listOf("afplay", "-v", volumeOf(volume), path)) else bell(beeps ?: ((event?.ordinal ?: 0) + 1))
            }
        }
    }

    private fun expand(path: String) = if (path == "~" || path.startsWith("~/")) home + path.removePrefix("~") else path

    private fun bell(beeps: Int) {
        repeat(beeps) { i ->
            if (i > 0) pause(BELL_GAP_MILLIS)
            ring()
        }
    }

    companion object {
        private const val BELL_GAP_MILLIS = 180L

        fun systemFile(name: String) = "/System/Library/Sounds/$name.aiff"

        /** afplay's gain: 1.0 is full volume. */
        fun volumeOf(percent: Int) = "%.2f".format(java.util.Locale.ROOT, percent.coerceIn(0, 100) / 100.0)
    }
}

/** The one player the app uses, and what the settings screen calls to let you hear a choice. */
object Sounds {
    var player: SoundPlayer = CommandSoundPlayer()

    /** Plays the sound set for [event] by [config], when the settings say it may be heard now ([onChatScreen]: Chat is what is on screen). */
    fun notify(config: AppConfig, event: ChatSoundEvent, onChatScreen: Boolean) {
        if (config.soundWhere == "away" && onChatScreen) return
        val choice = when (event) {
            ChatSoundEvent.Attention -> config.soundAttention
            ChatSoundEvent.Done -> config.soundDone
            ChatSoundEvent.Failed -> config.soundFailed
        }
        player.play(choice, config.soundVolume, config.soundFile, event)
    }
}
