package com.arcana.brain.core

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * What may run without asking, and what never runs. Approval is the real protection: this only spares the user a prompt for
 * commands that cannot change anything, and refuses a few that are never a good idea.
 */
object ShellPolicy {
    /** Programs that only report facts about the machine. They take no path, so they cannot read files outside the allowed folders. */
    private val SAFE_PROGRAMS = setOf("pwd", "date", "whoami", "uname", "hostname", "which")

    /** git subcommands that only look. */
    private val SAFE_GIT = setOf("status", "log", "diff", "show", "branch", "rev-parse", "remote", "tag", "describe", "blame", "shortlog")

    /** Anything that can chain, redirect or substitute makes a command more than the one thing it appears to be. */
    private val COMPLEX = Regex("""[;&|<>`\n\r]|\$\(|\$\{""")

    /** True when [command] is a single simple command from the safe lists. */
    fun isSafe(command: String): Boolean {
        val text = command.trim()
        if (text.isEmpty() || COMPLEX.containsMatchIn(text)) return false
        val words = text.split(Regex("\\s+"))
        return when (words[0]) {
            in SAFE_PROGRAMS -> true
            "git" -> words.getOrNull(1) in SAFE_GIT && words.none { it == "-c" || it.startsWith("--output") || it.startsWith("--exec") }
            else -> false
        }
    }

    private val BLOCKED = listOf(
        Regex("""(^|[\s;&|(])sudo(\s|$)""") to "sudo (commands that need administrator rights)",
        Regex("""(^|[\s;&|(])(shutdown|reboot|halt|poweroff)(\s|$)""") to "shutting the computer down",
        Regex("""(^|[\s;&|(])(mkfs\S*|fdisk|diskutil\s+(erase|partition|reformat)\S*)(\s|$)""") to "formatting or partitioning disks",
        Regex("""\bdd\b.*\bof=/dev/""") to "writing to a disk device",
        Regex(""">\s*/dev/(sd|disk|nvme|rdisk)""") to "writing to a disk device",
        Regex(""":\(\)\s*\{""") to "a fork bomb",
        Regex("""\b(curl|wget)\b[^|;&\n]*\|\s*(sudo\s+)?(sh|bash|zsh|python\d*)\b""") to "piping a download straight into a shell",
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\s+(-[a-zA-Z]*\s+)*(/|~|[$]HOME|/\*|~/\*|\*|\.\.?)(\s|/?$)""") to "deleting a whole folder tree (rm -r on /, ~, * or ..)",
    )

    /** Why [command] is never run, or null. */
    fun blockedReason(command: String): String? = BLOCKED.firstOrNull { it.first.containsMatchIn(command) }?.second

    const val DEFAULT_SECONDS = 60
    const val MAX_SECONDS = 600
    const val OUTPUT_LIMIT = 20_000
}

/**
 * Runs [command] with `/bin/sh -c` in [cwd]: stdin closed (nothing can wait for input), stdout and stderr together, stopped after [seconds].
 * Returns the exit code and the output; a long output keeps its start and end. Interrupting the thread (a cancelled reply) stops the process.
 */
internal fun runShell(command: String, cwd: Path, seconds: Int): String {
    val process = ProcessBuilder("/bin/sh", "-c", command)
        .directory(cwd.toFile())
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        .start()
    val buffer = java.io.ByteArrayOutputStream()
    val reader = Thread { runCatching { process.inputStream.copyTo(buffer) } }.apply { isDaemon = true; start() }
    val finished = try {
        process.waitFor(seconds.toLong(), TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
        killTree(process)
        throw e
    }
    if (!finished) killTree(process)
    reader.join(2_000)
    val text = shorten(buffer.toString(Charsets.UTF_8))
    return if (finished) "exit code ${process.exitValue()}" + (if (text.isBlank()) " (no output)" else "\n$text")
    else "Timed out after ${seconds}s and was stopped." + (if (text.isBlank()) "" else "\nOutput so far:\n$text")
}

private fun killTree(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
}

private fun shorten(text: String): String {
    val limit = ShellPolicy.OUTPUT_LIMIT
    if (text.length <= limit) return text.trimEnd()
    val half = limit / 2
    return text.take(half) + "\n… ${text.length - limit} characters left out …\n" + text.takeLast(half).trimEnd()
}
