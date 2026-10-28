package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path

/** What [Layout.migrate] did: one line per move, and the settings to keep afterwards. */
data class Migration(val lines: List<String>, val config: AppConfig)

/**
 * Where Arcana keeps its own working files (skills, the memory file) apart from the notes it produces (reports, research, AI notes), so
 * the vault's search, graph and folders show only content.
 *
 * - **In the vault**, `.arcana/` (a hidden folder: Obsidian and the note search skip it): `.arcana/skills/` and `.arcana/ARCANA.md`.
 * - **For the user**, next to the settings (`~/.config/arcana/`): `skills/` and `ARCANA.md` that apply to every vault. A vault skill of
 *   the same name wins; the global memory comes first, then the vault's.
 *
 * Nothing moves by itself: a vault that already has `Skills/` or a root `ARCANA.md` keeps using them until [migrate] moves them.
 */
object Layout {
    const val INTERNAL = ".arcana"
    const val SKILLS_DIR = "skills"
    private const val LEGACY_SKILLS = "Skills"

    /** The folder with the user's own settings, skills and memory (`~/.config/arcana`, or where `ARCANA_CONFIG` points). */
    fun globalDir(): Path = ConfigStore.defaultPath().parent

    fun globalSkills(): Path = globalDir().resolve(SKILLS_DIR)

    fun globalMemory(): Path = globalDir().resolve(Vault.MEMORY_FILE)

    /**
     * The vault folder (relative) that holds the skills: the [setting] when it names one; otherwise `.arcana/skills` when it exists,
     * else an existing `Skills/` (the old place), else `.arcana/skills` (a vault that has none yet).
     */
    fun skillsFolder(root: Path, setting: String): String {
        val named = setting.trim().trim('/')
        if (named.isNotEmpty()) return named
        val internal = "$INTERNAL/$SKILLS_DIR"
        return when {
            Files.isDirectory(root.resolve(internal)) -> internal
            Files.isDirectory(root.resolve(LEGACY_SKILLS)) -> LEGACY_SKILLS
            else -> internal
        }
    }

    /** The memory file when no path is set: `.arcana/ARCANA.md` when it exists, else an existing root `ARCANA.md`, else `.arcana/ARCANA.md`. */
    fun defaultMemory(root: Path): Path {
        val internal = root.resolve("$INTERNAL/${Vault.MEMORY_FILE}")
        val legacy = root.resolve(Vault.MEMORY_FILE)
        return if (!Files.exists(internal) && Files.exists(legacy)) legacy else internal
    }

    /**
     * Moves `Skills/` to `.arcana/skills/` and a root `ARCANA.md` to `.arcana/ARCANA.md` (a target that already exists is left alone and
     * reported), and clears the settings that pointed at the old places. Notes that mention them by a link are not rewritten.
     */
    fun migrate(root: Path, config: AppConfig): Migration {
        val lines = ArrayList<String>()
        var updated = config
        fun move(from: Path, to: Path, shown: String) {
            when {
                !Files.exists(from) -> return
                Files.exists(to) -> lines += "Left $shown where it is: ${root.relativize(to)} exists already."
                else -> { Files.createDirectories(to.parent); Files.move(from, to); lines += "Moved $shown to ${root.relativize(to)}" }
            }
        }
        val skillsSetting = config.skillsFolder.trim().trim('/')
        if (skillsSetting.isEmpty() || skillsSetting == LEGACY_SKILLS) {
            move(root.resolve(LEGACY_SKILLS), root.resolve("$INTERNAL/$SKILLS_DIR"), "$LEGACY_SKILLS/")
            if (!Files.exists(root.resolve(LEGACY_SKILLS))) updated = updated.copy(skillsFolder = "")
        } else lines += "Left the skills where the setting puts them ($skillsSetting)."
        if (config.memoryPath.isBlank() || config.memoryPath.trim() == Vault.MEMORY_FILE) {
            move(root.resolve(Vault.MEMORY_FILE), root.resolve("$INTERNAL/${Vault.MEMORY_FILE}"), Vault.MEMORY_FILE)
            if (!Files.exists(root.resolve(Vault.MEMORY_FILE))) updated = updated.copy(memoryPath = "")
        } else lines += "Left the memory file where the setting puts it (${config.memoryPath})."
        if (lines.isEmpty()) lines += "Nothing to move."
        return Migration(lines, updated)
    }
}
