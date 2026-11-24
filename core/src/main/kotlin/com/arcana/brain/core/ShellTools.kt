package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.arcana.brain.core.ToolBox.Companion.READ_LIMIT
import com.arcana.brain.core.ToolBox.Companion.LIST_LIMIT
import com.arcana.brain.core.ToolBox.Companion.FIND_LIMIT
import com.arcana.brain.core.ToolBox.Companion.GREP_LIMIT
import com.arcana.brain.core.ToolBox.Companion.GREP_LINE
import com.arcana.brain.core.ToolBox.Companion.RANGE_LINES
import com.arcana.brain.core.ToolBox.Companion.HIDDEN_SKIP
import com.arcana.brain.core.ToolBox.Companion.TREE_LIMIT
import com.arcana.brain.core.ToolBox.Companion.TREE_DEPTH
import com.arcana.brain.core.ToolBox.Companion.FIND_DEPTH
import com.arcana.brain.core.ToolBox.Companion.FIND_VISIT_LIMIT
import com.arcana.brain.core.ToolBox.Companion.FIND_SECONDS
import com.arcana.brain.core.ToolBox.Companion.FIND_SKIP
import com.arcana.brain.core.ToolBox.Companion.PREVIEW_LINES
import com.arcana.brain.core.ToolBox.Companion.PROJECT_FILES
import com.arcana.brain.core.ToolBox.Companion.PROJECT_NOTES_LIMIT
import com.arcana.brain.core.ToolBox.Companion.PROJECT_NOISE
import com.arcana.brain.core.ToolBox.Companion.MAX_DIFF_LINES
import com.arcana.brain.core.ToolBox.Companion.MAX_DIFF_BYTES
import com.arcana.brain.core.ToolBox.Companion.PROTECTED
import com.arcana.brain.core.ToolBox.Companion.FILE_TOOLS
import com.arcana.brain.core.ToolBox.Companion.schema
import com.arcana.brain.core.ToolBox.Companion.bool
import com.arcana.brain.core.ToolBox.Companion.str
import com.arcana.brain.core.ToolBox.Companion.int

// run_command and the read-only git tools. Split out of Tools.kt (proposal 0007, phase 3): extension functions of ToolBox, which offers them in `specs`.

internal fun ToolBox.shellTool() = ToolSpec(
    "run_command", "Run a shell command on the user's computer (macOS/Linux, /bin/sh) and get its exit code and output. For builds, tests, git and other tools. The user approves every command; it cannot wait for input, and it is stopped after the timeout.",
    schema(
        "command" to ("string" to "The command line, for example: ./gradlew test, or: git log --oneline -5"),
        "cwd" to ("string" to "Folder to run in: absolute, ~/…, or relative to $relativeTo. Must be inside an allowed folder. Default: $relativeTo"),
        "timeout_seconds" to ("integer" to "Stop after this many seconds (default ${ShellPolicy.DEFAULT_SECONDS}, at most ${ShellPolicy.MAX_SECONDS})"),
        required = listOf("command"),
    ),
    ToolRisk.Shell, { it.str("command") },
    preview = { a ->
        val seconds = (a.int("timeout_seconds") ?: ShellPolicy.DEFAULT_SECONDS).coerceIn(1, ShellPolicy.MAX_SECONDS)
        listOf("$ ${a.str("command")}", "in ${a.str("cwd").ifBlank { base?.toString() ?: "the first allowed folder" }}", "stops after ${seconds}s",
            "It runs with your user's rights and is NOT limited to the allowed folders.")
    },
) { a ->
    val command = a.str("command").trim()
    require(command.isNotEmpty()) { "Give the command to run." }
    ShellPolicy.blockedReason(command)?.let { throw IllegalArgumentException("Arcana never runs this kind of command: $it.") }
    val raw = a.str("cwd").ifBlank { "." }
    val dir = access.resolve(raw, base)
    requireExists(raw, dir)
    require(Files.isDirectory(dir)) { "$raw is a file, not a folder." }
    runShell(command, dir, (a.int("timeout_seconds") ?: ShellPolicy.DEFAULT_SECONDS).coerceIn(1, ShellPolicy.MAX_SECONDS))
}

/** The read-only git tools: they run fixed git commands, so they can only look (changes go through run_command, which asks). */
internal fun ToolBox.gitTools(): List<ToolSpec> = listOf(gitDiffTool(), gitStatusTool(), gitLogTool())

internal fun ToolBox.inRepo(a: JsonObject, command: String): String {
    val raw = a.str("cwd").ifBlank { "." }
    val dir = access.resolve(raw, base)
    requireExists(raw, dir)
    require(Files.isDirectory(dir)) { "$raw is a file, not a folder." }
    return runShell(command, dir, 30)
}

internal fun ToolBox.gitStatusTool() = ToolSpec(
    "git_status", "Show the state of a Git repository (`git status --short --branch`): the branch and which files are changed, staged or new. Read-only.",
    schema("cwd" to ("string" to "The repository folder: absolute, ~/…, or relative to $relativeTo. Must be inside an allowed folder. Default: $relativeTo")),
    ToolRisk.Read, { it.str("cwd").ifBlank { "(vault)" } },
) { a -> inRepo(a, "git --no-pager status --short --branch") }

internal fun ToolBox.gitLogTool() = ToolSpec(
    "git_log", "Show the latest commits of a Git repository (`git log`, one line each: short hash, date, author, subject). Read-only.",
    schema(
        "cwd" to ("string" to "The repository folder: absolute, ~/…, or relative to $relativeTo. Must be inside an allowed folder. Default: $relativeTo"),
        "limit" to ("integer" to "How many commits (default 10, at most 50)"),
        "path" to ("string" to "Only commits that touched this file or folder"),
    ),
    ToolRisk.Read, { listOf(it.str("cwd"), it.str("path")).filter { s -> s.isNotBlank() }.joinToString("  ") },
) { a ->
    val limit = (a.int("limit") ?: 10).coerceIn(1, 50)
    val path = a.str("path").trim()
    require('\'' !in path && '\n' !in path) { "That path cannot be used." }
    inRepo(a, "git --no-pager log --no-color -n $limit --date=short --pretty=format:'%h %ad %an  %s'" + (if (path.isEmpty()) "" else " -- '$path'"))
}

internal fun ToolBox.gitDiffTool() = ToolSpec(
    "git_diff", "Show what changed in a Git repository (`git diff`): the full patch, or just a summary or the changed file names. Read-only; use it to review changes before committing or to see what a file looked like before an edit.",
    schema(
        "cwd" to ("string" to "The repository folder: absolute, ~/…, or relative to $relativeTo. Must be inside an allowed folder. Default: $relativeTo"),
        "staged" to ("boolean" to "Show the staged changes (--staged) instead of the unstaged ones"),
        "summary" to ("string" to "Instead of the patch: `stat` (lines changed per file), `names` (only the changed file names) or `status` (names with A/M/D)"),
        "path" to ("string" to "Only this file or folder inside the repository"),
    ),
    ToolRisk.Read, { listOf(it.str("cwd"), it.str("path")).filter { s -> s.isNotBlank() }.joinToString("  ") },
    preview = { a -> listOf(gitDiffCommand(a), "in ${a.str("cwd").ifBlank { base?.toString() ?: "the first allowed folder" }}") },
) { a -> inRepo(a, gitDiffCommand(a)) }

/** The `git diff` command line for [a]. Only fixed flags and one quoted path go in, so it can only look. */
internal fun ToolBox.gitDiffCommand(a: JsonObject): String = buildString {
    append("git --no-pager diff --no-color --no-ext-diff --no-textconv")
    if (a.bool("staged")) append(" --staged")
    when (val summary = a.str("summary").trim()) {
        "" -> {}
        "stat" -> append(" --stat")
        "names" -> append(" --name-only")
        "status" -> append(" --name-status")
        else -> throw IllegalArgumentException("summary must be stat, names or status, not \"$summary\".")
    }
    val path = a.str("path").trim()
    if (path.isNotEmpty()) {
        require('\'' !in path && '\n' !in path) { "That path cannot be used." }
        append(" -- '").append(path).append("'")
    }
}
