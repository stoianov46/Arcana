package com.arcana.brain.core

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/** Which addresses a web tool may reach: the public internet only, never the user's own machine or network. */
object WebPolicy {
    /** The reason [url] must not be fetched, or null. [allowPrivate] is for tests that talk to a local server. */
    fun refusal(url: String, allowPrivate: Boolean = false): String? {
        val uri = try { URI(url.trim()) } catch (e: Exception) { return "\"$url\" is not a valid web address." }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return "Only http and https addresses can be fetched."
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return "The address has no host name."
        if (uri.userInfo != null) return "Addresses with a user name or password are not fetched."
        if (allowPrivate) return null
        val addresses = try { InetAddress.getAllByName(host).toList() } catch (e: Exception) { return "The host \"$host\" could not be found." }
        if (addresses.any { isPrivate(it) }) return "\"$host\" is on your own computer or network; only public web pages can be fetched."
        return null
    }

    private fun isPrivate(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        // 100.64.0.0/10 (carrier-grade NAT) and fc00::/7 (IPv6 unique local) are not flagged by the JDK
        if (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) return true
        if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }

    const val MAX_REDIRECTS = 5
    const val MAX_BYTES = 2 * 1024 * 1024
    const val TIMEOUT_SECONDS = 20L
    const val READ_CHARS = 20_000
}

private val http: HttpClient by lazy {
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(WebPolicy.TIMEOUT_SECONDS)).build()
}

/**
 * Fetches [url] and returns its text: HTML is reduced to readable text, text and JSON come as they are, a PDF gives its text layer.
 * Redirects are followed by hand so that every hop is checked against [WebPolicy] (a public page must not bounce to your router).
 * A long page is read in parts with [offset].
 */
internal fun fetchUrl(url: String, offset: Int = 0, allowPrivate: Boolean = false): String {
    var current = url.trim()
    repeat(WebPolicy.MAX_REDIRECTS + 1) {
        WebPolicy.refusal(current, allowPrivate)?.let { throw IllegalArgumentException(it) }
        val request = HttpRequest.newBuilder(URI(current))
            .timeout(Duration.ofSeconds(WebPolicy.TIMEOUT_SECONDS))
            .header("User-Agent", "Arcana/1.0 (local assistant)")
            .header("Accept", "text/html,text/plain,application/json,application/xml,application/pdf;q=0.9,*/*;q=0.1")
            .GET().build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) } catch (e: Exception) {
            throw IllegalArgumentException("Could not fetch $current: ${e.message ?: e.javaClass.simpleName}")
        }
        val status = response.statusCode()
        if (status in listOf(301, 302, 303, 307, 308)) {
            response.body().close()
            val next = response.headers().firstValue("Location").orElse(null) ?: throw IllegalArgumentException("Redirected without saying where.")
            current = URI(current).resolve(next).toString()
            return@repeat
        }
        val type = response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase()
        val bytes = response.body().use { it.readNBytes(WebPolicy.MAX_BYTES + 1) }
        if (status !in 200..299) throw IllegalArgumentException("$current answered HTTP $status.")
        val truncated = bytes.size > WebPolicy.MAX_BYTES
        val body = bytes.copyOf(minOf(bytes.size, WebPolicy.MAX_BYTES))
        val text = when {
            type == "application/pdf" || current.lowercase().substringBefore('?').endsWith(".pdf") -> {
                val file = Files.createTempFile("arcana-web", ".pdf")
                try { Files.write(file, body); TextFiles.load(file).text } finally { Files.deleteIfExists(file) }
            }
            type.startsWith("text/html") || type == "application/xhtml+xml" || (type.isEmpty() && String(body, 0, minOf(body.size, 200)).contains("<html", true)) -> htmlToText(String(body, Charsets.UTF_8))
            type.startsWith("text/") || type.endsWith("json") || type.endsWith("xml") || type == "application/javascript" -> String(body, Charsets.UTF_8)
            else -> throw IllegalArgumentException("$current is $type, not text.")
        }
        val start = offset.coerceIn(0, text.length)
        val part = text.substring(start, minOf(text.length, start + WebPolicy.READ_CHARS))
        val more = if (start + part.length < text.length) "\n… (${text.length - start - part.length} more characters; continue with offset=${start + part.length})" else ""
        return "$current  (HTTP $status${if (type.isEmpty()) "" else ", $type"}${if (truncated) ", cut at 2 MB" else ""})\n\n${part.trim()}$more"
    }
    throw IllegalArgumentException("Too many redirects.")
}

/** Readable text from [html]: scripts and styles dropped, blocks on their own lines, headings and list items marked, links as `text (address)`. */
internal fun htmlToText(html: String): String {
    var s = html
    val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(s)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }
    s = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(s, " ")
    s = Regex("<(script|style|noscript|svg|head|template|iframe)\\b.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s, " ")
    s = Regex("<a\\b[^>]*?href\\s*=\\s*[\"']([^\"'#][^\"']*)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(s) { m ->
        val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
        if (label.isEmpty() || m.groupValues[1].startsWith("javascript:", true)) label else "$label (${m.groupValues[1]})"
    }
    s = Regex("<h([1-6])\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " }
    s = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n- ")
    s = Regex("</?(p|div|section|article|header|footer|main|nav|ul|ol|table|tr|br|hr|pre|blockquote|form|h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n")
    s = Regex("</t[dh]>", RegexOption.IGNORE_CASE).replace(s, " | ")
    s = Regex("<[^>]+>").replace(s, "")
    s = decodeEntities(s)
    s = s.lines().joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
    s = Regex("\n{3,}").replace(s, "\n\n").trim()
    return if (title.isNullOrBlank()) s else "# $title\n\n$s"
}

internal fun decodeEntities(text: String): String {
    var s = text
    mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…", "&laquo;" to "«", "&raquo;" to "»")
        .forEach { (k, v) -> s = s.replace(k, v) }
    s = Regex("&#(\\d+);").replace(s) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value }
    s = Regex("&#[xX]([0-9a-fA-F]+);").replace(s) { m -> m.groupValues[1].toIntOrNull(16)?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value }
    return s
}
