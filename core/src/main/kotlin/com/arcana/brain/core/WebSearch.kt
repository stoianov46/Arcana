package com.arcana.brain.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One hit of a web search. */
data class SearchResult(val title: String, val url: String, val snippet: String)

/**
 * Searching the web for the `web_search` tool, through one of three backends: DuckDuckGo's plain HTML page (no setup, no key; it can
 * refuse after many queries), a SearXNG server the user runs (JSON API; its address may be on their own network), or the Brave Search API
 * (needs a key). Only the search words leave the computer. The parsers are pure functions so they can be tested without a network.
 */
object WebSearch {
    const val DEFAULT_LIMIT = 5
    const val MAX_LIMIT = 10
    private val client: HttpClient by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(WebPolicy.TIMEOUT_SECONDS)).build() }

    /** Where the query goes, for the confirmation dialog: the backend's name and host. */
    fun describe(backend: String, searxUrl: String): String = when (backend) {
        "searxng" -> "your SearXNG server (${runCatching { URI(searxUrl).host }.getOrNull() ?: searxUrl})"
        "brave" -> "the Brave Search API (api.search.brave.com)"
        else -> "DuckDuckGo (html.duckduckgo.com)"
    }

    /** The results for [query] (at most [limit]); throws IllegalArgumentException with a user-facing reason when the search cannot be done. */
    fun search(query: String, limit: Int, backend: String, searxUrl: String, key: String): List<SearchResult> {
        require(query.isNotBlank()) { "Give the words to search for." }
        val n = limit.coerceIn(1, MAX_LIMIT)
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8)
        return when (backend) {
            "searxng" -> {
                require(searxUrl.isNotBlank()) { "No SearXNG address is set. Add it in Settings → Tools → Search address, or choose another search backend." }
                val base = searxUrl.trim().trimEnd('/')
                parseSearx(get("$base/search?q=$q&format=json", emptyMap(), "SearXNG"), n)
            }
            "brave" -> {
                require(key.isNotBlank()) { "No Brave Search API key is set. Add it in Settings → Tools → Search API key, or choose another search backend." }
                parseBrave(get("https://api.search.brave.com/res/v1/web/search?q=$q&count=$n", mapOf("X-Subscription-Token" to key.trim(), "Accept" to "application/json"), "Brave Search"), n)
            }
            else -> parseDuckDuckGo(get("https://html.duckduckgo.com/html/?q=$q", emptyMap(), "DuckDuckGo"), n)
        }
    }

    private fun get(url: String, headers: Map<String, String>, name: String): String {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(WebPolicy.TIMEOUT_SECONDS))
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Arcana/1.0")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.GET().build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) {
            throw IllegalArgumentException("Could not reach $name: ${e.message ?: e.javaClass.simpleName}")
        }
        val code = response.statusCode()
        if (code == 403 && name == "SearXNG") throw IllegalArgumentException("SearXNG refused the request (HTTP 403). Its settings must allow format json.")
        if (code == 401 || code == 403) throw IllegalArgumentException("$name refused the request (HTTP $code): check the API key.")
        if (code == 429 || code == 202) throw IllegalArgumentException("$name is limiting requests right now (HTTP $code). Try again later or choose another search backend in Settings → Tools.")
        if (code !in 200..299) throw IllegalArgumentException("$name answered HTTP $code.")
        return response.body().take(WebPolicy.MAX_BYTES)
    }

    /** The results on a DuckDuckGo HTML result page; ads are skipped. */
    internal fun parseDuckDuckGo(html: String, limit: Int): List<SearchResult> {
        val anchors = Regex("""<a\b[^>]*class="[^"]*result__a[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).toList()
        val snippets = Regex("""<a\b[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).map { clean(it.groupValues[1]) }.toList()
        val results = ArrayList<SearchResult>()
        anchors.forEachIndexed { i, m ->
            val href = Regex("""href="([^"]*)"""").find(m.value)?.groupValues?.get(1) ?: return@forEachIndexed
            val url = realAddress(decode(href)) ?: return@forEachIndexed
            results += SearchResult(clean(m.groupValues[1]), url, snippets.getOrElse(i) { "" })
        }
        if (results.isEmpty() && Regex("anomaly|challenge|captcha|unusual traffic", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            throw IllegalArgumentException("DuckDuckGo asked for a human check instead of answering. Try again later, or choose SearXNG or Brave in Settings → Tools.")
        }
        return results.take(limit)
    }

    internal fun parseSearx(json: String, limit: Int): List<SearchResult> {
        val items = (runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()?.get("results") as? JsonArray) ?: throw IllegalArgumentException("SearXNG did not answer with JSON results.")
        return items.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val url = text(o, "url") ?: return@mapNotNull null
            SearchResult(clean(text(o, "title").orEmpty()).ifEmpty { url }, url, clean(text(o, "content").orEmpty()))
        }.take(limit)
    }

    internal fun parseBrave(json: String, limit: Int): List<SearchResult> {
        val web = (runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()?.get("web") as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
        return web.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val url = text(o, "url") ?: return@mapNotNull null
            SearchResult(clean(text(o, "title").orEmpty()).ifEmpty { url }, url, clean(text(o, "description").orEmpty()))
        }.take(limit)
    }

    /** `1. Title`, the address and a snippet for each result. */
    fun format(query: String, backend: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return "No results for \"$query\". Try other words."
        return "Search results for \"$query\" (via ${backend.ifBlank { "duckduckgo" }}). Open one with fetch_url:\n\n" + results.withIndex().joinToString("\n\n") { (i, r) ->
            "${i + 1}. ${r.title}\n   ${r.url}" + if (r.snippet.isBlank()) "" else "\n   ${r.snippet.take(300)}"
        }
    }

    private fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun clean(html: String) = decodeEntities(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
    private fun decode(s: String) = decodeEntities(s)

    /** DuckDuckGo wraps results in a redirect (`//duckduckgo.com/l/?uddg=<address>&rut=…`); this is the address behind it, null for ads. */
    private fun realAddress(href: String): String? {
        val target = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: href
        val full = if (target.startsWith("//")) "https:$target" else target
        if (!full.startsWith("http")) return null
        if (full.contains("duckduckgo.com/y.js")) return null
        return full
    }
}
