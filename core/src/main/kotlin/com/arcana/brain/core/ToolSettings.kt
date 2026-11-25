package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * What the settings say about the model's tools, gathered in one place ([of]) instead of being put together field by field where a [ToolBox] is made. The things that
 * are not settings (the vault, the semantic and project searches, the run being recorded) are handed to the [ToolBox] beside it. Proposal 0007, phase 3.
 */
data class ToolSettings(
    /** Allowed folders besides the vault and the project (Settings → Tools → Allowed folders), home expanded. */
    val folders: List<Path> = emptyList(),
    /** Where replaced and edited files are copied first. */
    val backups: Path,
    val outputFolder: String = "",
    /** The model that writes (it is named in the notes it creates). */
    val model: String = "Arcana",
    /** Plan mode: only the tools that read. */
    val readOnly: Boolean = false,
    val shell: Boolean = false,
    val web: Boolean = false,
    val searchBackend: String = "duckduckgo",
    val searchUrl: String = "",
    val searchKey: String = "",
    val ollamaKey: String = "",
    /** The vault folder of skills; blank = skills off. */
    val skillsFolder: String = "",
    /** The project folder when one is set and exists. */
    val project: Path? = null,
    val searchTuning: SearchTuning = SearchTuning(),
    /** The vault's notes are searched (Search in has `vault`). */
    val notesSearch: Boolean = true,
    val searchMode: RetrievalMode = RetrievalMode.Both,
) {
    companion object {
        /** `~` and `~/…` as the user's home folder; anything else as written. */
        fun expandHome(path: String): Path =
            if (path == "~" || path.startsWith("~/")) Path.of(System.getProperty("user.home"), path.removePrefix("~").trimStart('/')) else Path.of(path)

        /** The project folder of [config] when it is set and is a folder; null otherwise. */
        fun projectFolder(config: AppConfig): Path? =
            config.projectFolder.trim().takeIf { it.isNotEmpty() }?.let { expandHome(it) }?.takeIf { Files.isDirectory(it) }

        /**
         * The tool settings of [config]: [skillsFolder] is the folder the app resolved for the vault (blank when skills are off), [backups] where copies go;
         * plan mode makes the tools read-only, and the allowed folders come from the `;`-separated list with `~` expanded.
         */
        fun of(config: AppConfig, backups: Path, skillsFolder: String, notesSearch: Boolean): ToolSettings = ToolSettings(
            folders = config.toolFolders.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { expandHome(it) },
            backups = backups,
            outputFolder = config.outputFolder,
            model = config.model,
            readOnly = config.toolMode == "plan",
            shell = config.toolShell,
            web = config.toolWeb,
            searchBackend = config.searchBackend, searchUrl = config.searchUrl, searchKey = config.searchKey, ollamaKey = config.ollamaKey,
            skillsFolder = if (config.skillsEnabled) skillsFolder else "",
            project = projectFolder(config),
            searchTuning = SearchTuning.of(config),
            notesSearch = notesSearch,
            searchMode = RetrievalMode.of(config.searchBy),
        )
    }
}
