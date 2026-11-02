package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** A picture ready to be sent to a model that can see. */
data class ImageAttachment(val name: String, val base64: String, val bytes: Long)

/** The rules for pictures sent with a message, and loading them. */
object ImageFiles {
    val extensions = setOf("png", "jpg", "jpeg", "webp")
    const val MAX_COUNT = 5
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

    /** A path with no file name (`/`, a drive root) is no image. */
    fun isImage(path: Path) = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() in extensions

    /** Why [paths] cannot be attached, or null when they can. [already] is how many are attached to the message already. */
    fun validate(paths: List<Path>, already: Int = 0): String? {
        if (already + paths.size > MAX_COUNT) return "A message can carry at most $MAX_COUNT images (${already + paths.size} chosen)."
        var total = 0L
        for (path in paths) {
            val name = path.fileName.toString()
            if (!Files.isRegularFile(path)) return "$name is not a file."
            if (!isImage(path)) return "$name is not a PNG, JPEG or WebP image."
            val size = Files.size(path)
            if (size > MAX_FILE_BYTES) return "$name is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB."
            total += size
        }
        if (total > MAX_TOTAL_BYTES) return "The images add up to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB."
        return null
    }

    /** A word of a message that may be a path: a quoted string, or a run of characters where `\ ` (how a terminal writes a dropped file) is a space. */
    private val pathToken = Regex("""'([^']+)'|"([^"]+)"|((?:\\.|[^\s'"\\])+)""")

    /**
     * The pictures a message names by path (pasted, or dropped into the terminal, which types the path), and the message without them.
     * A model cannot open a path: it only ever sees the picture when its bytes are sent, so a path that is an existing PNG, JPEG or WebP file
     * becomes an attachment. Quotes, `\ ` escapes, `file://` and a leading `~` are understood; [home] is what `~` means.
     */
    fun pastedPaths(text: String, home: Path? = Path.of(System.getProperty("user.home"))): Pair<List<Path>, String> {
        val found = ArrayList<Path>()
        val out = StringBuilder()
        var last = 0
        for (m in pathToken.findAll(text)) {
            val raw = m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]!!.value
            var s = raw.replace(Regex("\\\\(.)"), "$1").removePrefix("file://")
            if (s.startsWith("~/") && home != null) s = home.resolve(s.drop(2)).toString()
            if (!(s.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(s))) continue
            val path = runCatching { Path.of(s) }.getOrNull() ?: continue
            if (!isImage(path) || !Files.isRegularFile(path) || path in found) continue
            found.add(path)
            out.append(text, last, m.range.first)
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return found to out.toString().lines().joinToString("\n") { it.replace(Regex("[ \\t]{2,}"), " ").trim() }.trim()
    }

    fun load(path: Path): ImageAttachment {
        val bytes = Files.readAllBytes(path)
        return ImageAttachment(path.fileName.toString(), Base64.getEncoder().encodeToString(bytes), bytes.size.toLong())
    }

    /** "245 KB", "1.2 MB". */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
