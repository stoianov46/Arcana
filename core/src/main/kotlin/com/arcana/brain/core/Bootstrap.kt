package com.arcana.brain.core

import java.nio.file.Files

/** One thing the setup can create in a vault, and whether it is there already. */
data class SetupItem(val id: String, val title: String, val detail: String, val done: Boolean)

/**
 * Sets a vault up for Arcana: the memory file (with its rules for using tools) and the skills folder (a guide to writing skills and the
 * starter skills). It only ever creates what is missing; an existing file is never touched. The first-run wizard, the palette command
 * and `arcana --init` all use it.
 */
object Bootstrap {
    const val MEMORY = "memory"
    const val SKILLS_FOLDER = "skills-folder"
    const val SKILLS_GUIDE = "skills-guide"
    const val STARTER_SKILLS = "starter-skills"
    const val OUTPUT = "output-folder"

    /** What can be created in [vault] with these [config] settings, and what already exists. */
    fun items(vault: Vault, config: AppConfig): List<SetupItem> {
        val skills = Layout.skillsFolder(vault.root, config.skillsFolder)
        val skillsDir = vault.root.resolve(skills)
        return listOf(
            SetupItem(MEMORY, "Memory file", "${vault.memoryName()}: who you are and the rules that make a model use its tools; read in every chat", Files.exists(vault.memoryFile())),
            SetupItem(SKILLS_FOLDER, "Skills folder", "$skills/: where skills (runbooks the model follows step by step) live", Files.isDirectory(skillsDir)),
            SetupItem(SKILLS_GUIDE, "Skills guide", "$skills/Skills Guide.md: how to write a skill", Files.exists(skillsDir.resolve("Skills Guide.md"))),
            SetupItem(STARTER_SKILLS, "Starter skills", "${StarterSkills.all.joinToString { it.name }}: ready to use, and examples to copy", StarterSkills.all.all { Files.exists(skillsDir.resolve("${it.name}/${it.name}.skill.md")) }),
            SetupItem(OUTPUT, "Output folder", "${config.outputFolder.ifEmpty { "(vault root)" }}/: where notes Arcana writes go", config.outputFolder.isEmpty() || Files.isDirectory(vault.root.resolve(config.outputFolder))),
        )
    }

    /** Creates the [ids] that are missing and returns one line for each thing it made (empty when everything was there). */
    fun create(vault: Vault, config: AppConfig, ids: Set<String>): List<String> {
        val skills = Layout.skillsFolder(vault.root, config.skillsFolder)
        val skillsDir = vault.root.resolve(skills)
        val made = ArrayList<String>()
        fun write(path: java.nio.file.Path, text: String, shown: String) {
            if (Files.exists(path)) return
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, text)
            made += shown
        }
        if (MEMORY in ids && !Files.exists(vault.memoryFile())) {
            vault.ensureMemory()
            made += vault.memoryName()
        }
        if (SKILLS_FOLDER in ids && !Files.isDirectory(skillsDir)) {
            Files.createDirectories(skillsDir)
            made += "$skills/"
        }
        if (SKILLS_GUIDE in ids) write(skillsDir.resolve("Skills Guide.md"), StarterSkills.GUIDE, "$skills/Skills Guide.md")
        if (STARTER_SKILLS in ids) StarterSkills.all.forEach { write(skillsDir.resolve("${it.name}/${it.name}.skill.md"), it.text, "$skills/${it.name}/${it.name}.skill.md") }
        if (OUTPUT in ids && config.outputFolder.isNotEmpty() && !Files.isDirectory(vault.root.resolve(config.outputFolder))) {
            Files.createDirectories(vault.root.resolve(config.outputFolder))
            made += "${config.outputFolder}/"
        }
        return made
    }
}
