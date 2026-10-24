package com.arcana.brain.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import com.arcana.brain.app.input.Key
import com.arcana.brain.core.FileAttachment
import com.arcana.brain.core.ImageAttachment
import com.arcana.brain.core.ImageFiles
import com.arcana.brain.core.TextFiles
import java.nio.file.Files
import java.nio.file.Path

/** What was attached to a message when it was sent. */
data class SentAttachments(
    val pictures: List<ImageAttachment>,
    val picturePaths: List<Path>,
    val files: List<FileAttachment>,
    val notes: List<String>,
    /** Files that were only picked, not read (see [Attachments.forModel]). */
    val plain: List<Path>,
) {
    val isEmpty get() = pictures.isEmpty() && files.isEmpty() && notes.isEmpty() && plain.isEmpty()
}

/**
 * The attachments of one message field: pictures, text files and PDFs (read now, sent with the message), and notes.
 * Every field that can carry attachments owns one, shows it with [TextInput]'s `attachments` parameter, opens the picker with
 * [open] and draws it with `AttachBrowser`, so attaching works the same everywhere.
 *
 * With [forModel] false (capture) nothing is read: any file can be picked and [plain] holds the paths, to be copied into the vault.
 */
class Attachments(
    private val modelCanSee: () -> Boolean = { true },
    private val startDir: () -> Path? = { null },
    private val onFolder: (Path) -> Unit = {},
    val forModel: Boolean = true,
) {
    val pictures = mutableStateListOf<ImageAttachment>()
    val picturePaths = mutableStateListOf<Path>()
    val files = mutableStateListOf<FileAttachment>()
    val notes = mutableStateListOf<String>()
    val plain = mutableStateListOf<Path>()

    /** The open file browser, or null. While it is open it owns the keys: send them to [handle]. */
    var browser by mutableStateOf<FilePickerState?>(null)
        private set

    val browsing: Boolean get() = browser != null

    /** True while the browser's filter has the keyboard, so single-key shortcuts must stay off. */
    val filtering: Boolean get() = browser?.filtering == true

    val isEmpty: Boolean get() = pictures.isEmpty() && files.isEmpty() && notes.isEmpty() && plain.isEmpty()

    /** Rows the attachment line takes: none, or one. */
    val rows: Int get() = if (isEmpty) 0 else 1

    /** How many more pictures and files fit. */
    private val room: Int
        get() = if (forModel) (ImageFiles.MAX_COUNT - pictures.size) + (TextFiles.MAX_COUNT - files.size) else PLAIN_MAX - plain.size

    fun handle(key: Key): Boolean = browser?.handle(key) ?: false

    fun clear() {
        pictures.clear(); picturePaths.clear(); files.clear(); notes.clear(); plain.clear()
    }

    /** Takes back the last attachment shown; false when there is none. */
    fun removeLast(): Boolean {
        when {
            plain.isNotEmpty() -> plain.removeAt(plain.lastIndex)
            files.isNotEmpty() -> files.removeAt(files.lastIndex)
            pictures.isNotEmpty() -> { pictures.removeAt(pictures.lastIndex); picturePaths.removeAt(picturePaths.lastIndex) }
            notes.isNotEmpty() -> notes.removeAt(notes.lastIndex)
            else -> return false
        }
        return true
    }

    /** What is attached now, leaving it attached. */
    fun peek(): SentAttachments = SentAttachments(pictures.toList(), picturePaths.toList(), files.toList(), notes.toList(), plain.toList())

    /** Hands over everything attached and starts empty again. */
    fun drain(): SentAttachments = peek().also { clear() }

    /** Puts a handed-over set back, as when a reply is tried again with the same attachments. */
    fun restore(sent: SentAttachments) {
        clear()
        pictures += sent.pictures; picturePaths += sent.picturePaths; files += sent.files; notes += sent.notes; plain += sent.plain
    }

    /** Why [paths] cannot be attached, or null when they can. */
    private fun problem(paths: List<Path>): String? {
        if (!forModel) {
            if (plain.size + paths.size > PLAIN_MAX) return "At most $PLAIN_MAX files at once."
            paths.forEach {
                if (!Files.isRegularFile(it)) return "${it.fileName} is not a file."
                if (Files.size(it) > PLAIN_MAX_BYTES) return "${it.fileName} is larger than ${PLAIN_MAX_BYTES / (1024 * 1024)} MB."
            }
            return null
        }
        val (pics, texts) = paths.partition(ImageFiles::isImage)
        if (pics.isNotEmpty() && !modelCanSee()) return "This model cannot look at pictures. Attach text files or PDFs, or choose a vision model in Settings → Ollama → Model."
        return (if (pics.isEmpty()) null else ImageFiles.validate(pics, already = pictures.size))
            ?: (if (texts.isEmpty()) null else TextFiles.validate(texts, already = files.size))
    }

    /** Attaches [paths] (reading them now). Returns what to tell the user: the text, and whether it is an error. */
    private fun attach(paths: List<Path>): Pair<String, Boolean> {
        problem(paths)?.let { return it to true }
        if (!forModel) {
            plain += paths
            return "Attached ${paths.size} file${if (paths.size == 1) "" else "s"}. They are copied into the vault when you save." to false
        }
        val (pics, texts) = paths.partition(ImageFiles::isImage)
        return runCatching { pics.map(ImageFiles::load) to texts.map(TextFiles::load) }
            .map { (loadedPics, loadedFiles) ->
                pictures += loadedPics
                picturePaths += pics
                files += loadedFiles
                val n = loadedPics.size + loadedFiles.size
                "Attached $n file${if (n == 1) "" else "s"}. They go out with your next message." to false
            }
            .getOrElse { (it.message ?: "Could not read the file.") to true }
    }

    /** Opens the browser. [notify] is told the outcome (text, isError) when files were chosen or opening was refused. */
    fun open(notify: (String, Boolean) -> Unit) {
        if (room <= 0) {
            notify(if (forModel) "A message can carry at most ${ImageFiles.MAX_COUNT} pictures and ${TextFiles.MAX_COUNT} files." else "At most $PLAIN_MAX files at once.", true)
            return
        }
        val accepts: (Path) -> Boolean = when {
            !forModel -> { _ -> true }
            modelCanSee() -> TextFiles::isAttachable
            else -> TextFiles::isReadable
        }
        browser = FilePickerState(
            start = startDir(),
            accepts = accepts,
            limit = room,
            onDone = { paths ->
                browser = null
                val (text, error) = attach(paths)
                notify(text, error)
            },
            onCancel = { browser = null },
            onFolder = onFolder,
        )
    }

    companion object {
        const val PLAIN_MAX = 5
        const val PLAIN_MAX_BYTES = 50L * 1024 * 1024
    }
}

/** The attachments as one line (cut to the width), with how to take one back. Nothing is drawn while there are none. */
@Composable
fun AttachmentLine(attachments: Attachments) {
    if (attachments.isEmpty) return
    val items = attachments.notes.map { "▤ ${it.substringAfterLast('/').removeSuffix(".md")}" } +
        attachments.pictures.map { "▣ ${sanitizeForTerminal(it.name)} (${ImageFiles.size(it.bytes)})" } +
        attachments.files.map { "❐ ${sanitizeForTerminal(it.name)}" } +
        attachments.plain.map { "❐ ${sanitizeForTerminal(it.fileName.toString())}" }
    Label("Attached  " + items.joinToString("   ") + "      Backspace on an empty field removes the last", color = Palette.Accent)
}

/** The hints for the keys while the file browser is open. */
fun attachBrowserHints(attachments: Attachments): List<String> =
    if (attachments.filtering) listOf("↑↓  move", "Enter  open", "Esc  clear filter")
    else listOf("Space  tick", "Enter  open / attach", "Tab  attach ticked", "←  up", "Esc  cancel")
