package com.arcana.brain.app.input

import java.util.concurrent.TimeUnit

/** Reads the system clipboard through the platform's own tool (no native dependencies). */
object Clipboard {
    /** @return true when the platform clipboard tool accepted the text. */
    fun writeText(text: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val candidates = when {
            "mac" in os -> listOf(listOf("pbcopy"))
            "win" in os -> listOf(listOf("clip"))
            else -> listOf(listOf("wl-copy"), listOf("xclip", "-selection", "clipboard"), listOf("xsel", "-ib"))
        }
        for (cmd in candidates) {
            val ok = runCatching {
                val p = ProcessBuilder(cmd).start()
                p.outputStream.use { it.write(text.toByteArray()) }
                p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    fun readText(): String? {
        val os = System.getProperty("os.name").lowercase()
        val candidates = when {
            "mac" in os -> listOf(listOf("pbpaste"))
            "win" in os -> listOf(listOf("powershell", "-NoProfile", "-Command", "Get-Clipboard"))
            else -> listOf(listOf("wl-paste", "--no-newline"), listOf("xclip", "-selection", "clipboard", "-o"), listOf("xsel", "-ob"))
        }
        for (cmd in candidates) {
            val text = runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
                val out = p.inputStream.bufferedReader().readText()
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
                out.takeIf { p.exitValue() == 0 }
            }.getOrNull()
            if (text != null) return text
        }
        return null
    }
}

/** Seam so tests never touch the developer's real clipboard. */
fun interface ClipboardWriter {
    fun write(text: String): Boolean
}

fun interface ClipboardReader {
    fun read(): String?
}
