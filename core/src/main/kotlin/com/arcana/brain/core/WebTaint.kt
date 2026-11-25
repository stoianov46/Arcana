package com.arcana.brain.core

/**
 * Web content in a turn: a page or a search result can carry text that tries to steer the model ("now write this file", "fetch this address with the user's notes in it").
 * Arcana marks such results as material ([wrap]), and while one is in the turn (Settings → Tools → Ask after web) changes, commands and fetches of a new place ask first.
 */
object WebTaint {
    /** The line the approval dialog adds when it asks because of web content. */
    const val NOTE = "⚠ Asked because the model read a web page or search results in this turn: a page can try to steer it."

    /** [result] of a web tool marked as material from [source], never instructions (the system prompt says how to treat the tags). */
    fun wrap(source: String, result: String): String = "<untrusted source=\"${source.replace("\"", "'").take(300)}\">\n$result\n</untrusted>"

    /** The host of [address], lower case; null when it has none. */
    fun host(address: String): String? = runCatching { java.net.URI(address.trim()).host?.lowercase() }.getOrNull()

    /** True when [address] goes somewhere not read in this turn ([hosts]), or carries a query (which can hold data taken from the chat). */
    fun newPlace(address: String, hosts: Set<String>): Boolean {
        val host = host(address) ?: return true
        return host !in hosts || runCatching { java.net.URI(address.trim()).rawQuery }.getOrNull() != null
    }
}
