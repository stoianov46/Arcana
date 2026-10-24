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

    fun isImage(path: Path) = path.fileName.toString().substringAfterLast('.', "").lowercase() in extensions

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
