package com.arcana.brain.core

/** [front] (a frontmatter block, or empty) with [tags] merged into its `tags:` line; a block is created when there is none. */
fun mergeFrontmatterTags(front: String, tags: List<String>): String {
    if (tags.isEmpty()) return front
    if (front.isBlank()) return "---\ntags: [${tags.joinToString(", ")}]\n---\n\n"
    val lines = front.trimEnd('\n').lines()
    val close = lines.indexOfLast { it.trim() == "---" }.takeIf { it > 0 } ?: return front
    val middle = lines.subList(1, close).toMutableList()
    val existing = VaultIndex.frontmatterTags(front)
    // the old tag lines go, each together with a block list that follows it
    var at = middle.indexOfFirst { VaultIndex.isTagKey(it) }
    while (at >= 0) {
        var end = at + 1
        while (end < middle.size && middle[end].trimStart().startsWith("-")) end++
        repeat(end - at) { middle.removeAt(at) }
        at = middle.indexOfFirst { VaultIndex.isTagKey(it) }
    }
    middle += "tags: [${(existing + tags.map { VaultIndex.cleanTag(it) }.filter { it.isNotEmpty() }).distinct().joinToString(", ")}]"
    return "---\n" + middle.joinToString("\n") + "\n---\n\n"
}
