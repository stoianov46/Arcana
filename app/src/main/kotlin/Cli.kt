package com.arcana.brain.app

import com.arcana.brain.core.Bootstrap
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.Layout
import com.arcana.brain.core.Vault
import java.nio.file.Files

/**
 * The command line of `arcana`, for what needs no screen: `arcana --init <vault>` sets a vault up (the same as the first-run wizard) and
 * remembers it, so a script or a new machine can be ready before the first start. [run] returns the exit code, or null when the arguments
 * ask for nothing special and the app should start.
 */
object Cli {
    const val HELP = """arcana: a terminal control panel for your second brain (Obsidian vault + local Ollama model)

Usage:
  arcana                         start the app (the first start walks you through the setup)
  arcana --init <vault>          choose <vault> as the vault and create what is missing in it:
                                 the memory file (ARCANA.md), the Skills folder with its guide and the
                                 starter skills, and the output folder. Existing files are never touched.
      --skip memory,skills,output   leave some of that out (comma separated)
  arcana --migrate               move the chosen vault's Skills/ and root ARCANA.md into its hidden .arcana/ folder
                                 (.arcana/skills, .arcana/ARCANA.md), so the vault shows only content. Nothing is
                                 overwritten; a link to a moved note is not rewritten.
  arcana --help                  this text
"""

    /** What `--skip` may name, and the setup items each one covers. */
    private val groups = mapOf(
        "memory" to setOf(Bootstrap.MEMORY),
        "skills" to setOf(Bootstrap.SKILLS_FOLDER, Bootstrap.SKILLS_GUIDE, Bootstrap.STARTER_SKILLS),
        "output" to setOf(Bootstrap.OUTPUT),
    )

    /** `--migrate`: [Layout.migrate] on the vault in the settings. */
    private fun migrate(store: ConfigStore, out: (String) -> Unit): Int {
        val config = store.load()
        val dir = AppContext.expandHome(config.vaultPath.trim().ifEmpty { out("No vault is chosen yet: start Arcana once, or run arcana --init <vault>."); return 1 })
        if (!Files.isDirectory(dir)) { out("$dir is not a folder."); return 1 }
        val result = runCatching { Layout.migrate(dir.toAbsolutePath().normalize(), config) }.getOrElse { out("Could not move: ${it.message}"); return 1 }
        store.save(result.config)
        result.lines.forEach { out(it) }
        return 0
    }

    fun run(args: Array<String>, store: ConfigStore = ConfigStore(), out: (String) -> Unit = ::println): Int? {
        if (args.isEmpty()) return null
        if ("--help" in args || "-h" in args) { out(HELP.trimEnd()); return 0 }
        if ("--migrate" in args) return migrate(store, out)
        val at = args.indexOf("--init")
        if (at < 0) { out("Unknown option ${args.first()}.\n\n" + HELP.trimEnd()); return 2 }
        val path = args.getOrNull(at + 1)?.takeIf { !it.startsWith("--") } ?: run { out("--init needs the vault folder: arcana --init ~/Obsidian/Vault"); return 2 }
        val skipped = args.indexOf("--skip").let { i -> if (i < 0) emptyList() else args.getOrNull(i + 1).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } }
        skipped.firstOrNull { it !in groups }?.let { out("--skip knows memory, skills and output, not \"$it\"."); return 2 }
        val dir = AppContext.expandHome(path)
        if (!Files.isDirectory(dir)) { out("$dir is not a folder."); return 1 }
        val config = store.load().copy(vaultPath = dir.toAbsolutePath().normalize().toString(), setupDone = true)
        val vault = runCatching { Vault(dir, config.memoryPath) }.getOrElse { out(it.message ?: "Cannot open $dir."); return 1 }
        val wanted = groups.filterKeys { it !in skipped }.values.flatten().toSet()
        val made = runCatching { Bootstrap.create(vault, config, wanted) }.getOrElse { out("Could not set the vault up: ${it.message}"); return 1 }
        store.save(config)
        out("Vault: ${config.vaultPath}")
        out(if (made.isEmpty()) "Nothing to create: everything was already there." else "Created:\n" + made.joinToString("\n") { "  $it" })
        out("Start Arcana with: arcana")
        return 0
    }
}
