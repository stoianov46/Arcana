package com.arcana.brain.app

import java.io.File
import java.util.concurrent.TimeUnit

data class CellSize(val columns: Int, val rows: Int)

/**
 * Asks the OS for the real size of the controlling terminal (`stty size`), independent of whether the terminal emulator
 * answers Mosaic's size queries. `ARCANA_COLUMNS` / `ARCANA_ROWS` override everything (diagnostics, unusual terminals).
 */
object TerminalSize {
    fun probe(env: Map<String, String> = System.getenv()): CellSize? {
        val envCols = env["ARCANA_COLUMNS"]?.toIntOrNull()
        val envRows = env["ARCANA_ROWS"]?.toIntOrNull()
        if (envCols != null && envRows != null) return CellSize(envCols, envRows)
        return runCatching {
            val tty = File("/dev/tty")
            if (!tty.exists()) return null
            val process = ProcessBuilder("stty", "size")
                .redirectInput(ProcessBuilder.Redirect.from(tty))
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            parseStty(out)
        }.getOrNull()
    }

    /** `stty size` prints "<rows> <columns>". */
    fun parseStty(output: String): CellSize? {
        val parts = output.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val rows = parts[0].toIntOrNull() ?: return null
        val cols = parts[1].toIntOrNull() ?: return null
        return if (rows > 0 && cols > 0) CellSize(cols, rows) else null
    }
}

/**
 * Mosaic 0.18 installs a native SIGWINCH handler that calls back into the JVM from a thread the JVM doesn't know about.
 * On macOS that kills the process every time the terminal window is resized. Once Mosaic has installed its handler it is
 * replaced with a harmless JVM-managed one; resizes are picked up by [TerminalSize] polling instead.
 */
object ResizeSignalGuard {
    fun install() {
        runCatching {
            sun.misc.Signal.handle(sun.misc.Signal("WINCH")) { /* handled by TerminalSize polling */ }
        }
    }
}
