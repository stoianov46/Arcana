package com.arcana.brain.core

/** What [Vault.auditMemory] found in the memory file: lines that repeat an earlier one, and lines that name a note that is not there. */
data class MemoryAudit(
    /** (the repeated line, the earlier line it repeats) */
    val duplicates: List<Pair<String, String>>,
    /** (the line, the note it names that does not exist) */
    val deadPaths: List<Pair<String, String>>,
) {
    val clean: Boolean get() = duplicates.isEmpty() && deadPaths.isEmpty()
}

/** Comparing and reading the lines of the memory file (`ARCANA.md`): what counts as the same fact, and which notes a line points at. */
object MemoryLines {
    /** Lower case, without punctuation (but keeping `/`, `[`, `]` and `-` / `.` inside words, which paths and links need), one space between words. */
    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[.-]+(?=\\s|$)"), " ").replace(Regex("[^\\p{L}\\p{N}\\s/\\[\\].-]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun words(text: String): Set<String> = normalize(text).split(' ').filter { it.length > 2 }.toSet()

    /** The same fact: equal after [normalize], or (for lines of at least three real words each) at least 80 % of their words in common. */
    fun similar(a: String, b: String): Boolean {
        if (normalize(a) == normalize(b)) return true
        val wa = words(a)
        val wb = words(b)
        if (wa.size < 3 || wb.size < 3) return false
        return wa.intersect(wb).size.toDouble() / wa.union(wb).size >= 0.8
    }

    /** The notes a line points at: `[[Note]]` links (the name) and words ending in `.md` (a path, relative to the vault or `~/…` / absolute). */
    fun noteRefs(text: String): List<String> {
        val links = Regex("\\[\\[([^\\]|#]+)").findAll(text).map { it.groupValues[1].trim() }
        val paths = Regex("\\S+\\.md\\b").findAll(text).map { it.value.trim('`', '"', '\'', '(', ')', ',', ';', ':') }.filter { it.endsWith(".md") }
        return (links + paths).filter { it.isNotEmpty() }.distinct().toList()
    }
}
