package com.arcana.brain.app

/** Opens a file or a link with the operating system's own handler (for `obsidian://` links, that is Obsidian itself). */
object SystemOpen {
    /** @return true when the platform's opener was started. */
    fun open(target: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            "mac" in os -> listOf("open", target)
            "win" in os -> listOf("cmd", "/c", "start", "", target)
            else -> listOf("xdg-open", target)
        }
        return runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
            true
        }.getOrDefault(false)
    }

    /** The link that opens [notePath] (a vault-relative path with `.md`) in the Obsidian vault called [vaultName]. */
    fun obsidianLink(vaultName: String, notePath: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "obsidian://open?vault=${enc(vaultName)}&file=${enc(notePath.removeSuffix(".md"))}"
    }
}
