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

// The tools that leave the computer: web_search, fetch_url and http_request (each asks first). Split out of Tools.kt (proposal 0007, phase 3): extension functions of ToolBox, which offers them in `specs`.

internal fun ToolBox.searchTool() = ToolSpec(
    "web_search", "Search the web and get the best results: title, address and a snippet each. Use it to find current information or a page, then read a result with fetch_url. The user approves every search; only the search words are sent. If it says search is paused, do not retry.",
    schema("query" to ("string" to "The search words"), "limit" to ("integer" to "How many results (default ${WebSearch.DEFAULT_LIMIT}, at most ${WebSearch.MAX_LIMIT})"), required = listOf("query")),
    ToolRisk.Web, { it.str("query") },
    preview = { a -> listOf("Search the web for:", a.str("query").trim(), "Sent to ${WebSearch.describe(searchBackend, searchUrl)}; nothing else leaves your computer.") },
) { a ->
    val query = a.str("query").trim()
    val (used, results) = WebSearch.searchWithFallback(query, a.int("limit") ?: WebSearch.DEFAULT_LIMIT, searchBackend, searchUrl, searchKey, ollamaKey)
    WebSearch.format(query, used, results) + (if (used != searchBackend.ifBlank { "duckduckgo" }) "\n(The chosen search backend was limiting requests, so $used answered.)" else "")
}

internal fun ToolBox.webTool() = ToolSpec(
    "fetch_url", "Read a public web page or document and get its text (HTML is reduced to readable text; JSON, text and PDF work too). Use it for documentation, articles and APIs the user points to or you need. The user approves each address. Never invent an address: use one from a web_search result, the user's message, a note, or a link on a page you read. A long page is read in parts with offset.",
    schema(
        "url" to ("string" to "The full http(s) address"),
        "offset" to ("integer" to "Character to start from, to read on in a long page (the previous result says which)"),
        required = listOf("url"),
    ),
    ToolRisk.Web, { it.str("url") },
    preview = { a ->
        val url = a.str("url").trim()
        url.chunked(76) + listOf("Only the page text comes back; your files are not sent. Read the address for anything odd.")
    },
) { a ->
    fetchUrl(a.str("url"), a.int("offset") ?: 0)
}

internal fun ToolBox.httpTool() = ToolSpec(
    "http_request", "Send an HTTP request to a public web address and get the status and the answer (JSON, text). The one way to POST, PUT, PATCH or DELETE, or to send headers (an API key) or a body: do NOT use curl or wget in a shell, they are refused. For simply reading a page use fetch_url. The user sees the whole request, with credentials hidden, and approves it every time; only public addresses are reachable.",
    schema(
        "url" to ("string" to "The full http(s) address"),
        "method" to ("string" to "GET (default), POST, PUT, PATCH, DELETE or HEAD"),
        "headers" to ("string" to "Optional headers, one `Name: value` per line, for example: Content-Type: application/json"),
        "body" to ("string" to "Optional body (at most ${HttpGate.MAX_BODY} characters), for POST, PUT and PATCH; for JSON send the JSON text and set Content-Type"),
        required = listOf("url"),
    ),
    ToolRisk.Web, { "${it.str("method").ifBlank { "GET" }.uppercase()} ${HttpGate.maskUrl(it.str("url").trim())}" },
    preview = { a -> HttpGate.preview(a.str("method").ifBlank { "GET" }, a.str("url"), a.str("headers"), a.str("body"), PREVIEW_LINES) },
) { a ->
    httpRequest(a.str("method").ifBlank { "GET" }, a.str("url"), a.str("headers"), a.str("body"))
}
