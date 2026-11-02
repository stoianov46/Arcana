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
/** What a failed page read says, with what to do next: a small model that is only told "HTTP 404" guesses another address, or retries a refusal. */
internal fun statusMessage(url: String, status: Int): String = "$url answered HTTP $status" + when (status) {
    404, 410 -> ": that address does not exist. Do not guess addresses: use only one that came from a web_search result, from the user's message or notes, or from a link on a page you have read; if you have none, run web_search or say you could not find it."
    401, 403 -> ": the site refuses automated readers. Use another source; do not retry the same address."
    429 -> ": it is limiting requests. Use the wait tool (up to 120 seconds) once and retry once."
    in 500..599 -> ": the server has an error. Retry once later, then use another source."
    else -> "."
}

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
        if (status !in 200..299) throw IllegalArgumentException(statusMessage(current, status))
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

/**
 * The gate for HTTP requests of every kind (the `http_request` tool): which methods, which addresses, how much may be sent, and how a request is
 * shown to the user before it leaves. It exists so a model never needs `curl` in a shell: here the address is checked ([WebPolicy]), the whole
 * request is shown, credentials are hidden in that view, and the user approves it.
 */
object HttpGate {
    val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

    /** The largest body a request may carry. */
    const val MAX_BODY = 200_000

    /** Headers the HTTP stack sets itself (and that would only be used to confuse a server); never sent. */
    private val RESTRICTED = setOf("host", "content-length", "connection", "transfer-encoding", "upgrade", "expect", "te", "trailer", "proxy-connection", "keep-alive")

    private val SECRET_NAME = Regex("(?i)authorization|cookie|api[-_]?key|token|secret|password|passwd|signature|x-auth")
    private val SECRET_QUERY = Regex("(?i)([?&](?:api[-_]?key|apikey|key|token|access_token|secret|password|auth|signature)=)[^&#\\s]+")

    fun isSecretHeader(name: String) = SECRET_NAME.containsMatchIn(name)

    /** `Name: value` lines as pairs; a line without a colon, with a bad name or with a restricted name is an error the model can read. */
    fun parseHeaders(text: String): List<Pair<String, String>> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val at = line.indexOf(':')
        require(at > 0) { "A header must look like `Name: value`, not \"$line\"." }
        val name = line.substring(0, at).trim()
        val value = line.substring(at + 1).trim()
        require(name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+"))) { "\"$name\" is not a valid header name." }
        require('\n' !in value && '\r' !in value) { "A header value cannot contain a line break." }
        require(name.lowercase() !in RESTRICTED) { "The $name header is set by Arcana and cannot be given." }
        name to value
    }

    /** The reason this request is not allowed, or null. [allowPrivate] is for tests that talk to a local server. */
    fun refusal(method: String, url: String, headers: String, body: String, allowPrivate: Boolean = false): String? {
        if (method.uppercase() !in METHODS) return "The method must be one of ${METHODS.joinToString()}, not \"$method\"."
        WebPolicy.refusal(url, allowPrivate)?.let { return it }
        if (body.length > MAX_BODY) return "The body is ${body.length} characters; at most $MAX_BODY can be sent."
        if (body.isNotEmpty() && method.uppercase() in setOf("GET", "HEAD")) return "A $method request has no body. Use POST, PUT or PATCH to send one."
        runCatching { parseHeaders(headers) }.exceptionOrNull()?.let { return it.message }
        return null
    }

    /** [url] with the value of a key-like query parameter hidden, for showing it to the user and logging it. */
    fun maskUrl(url: String): String = SECRET_QUERY.replace(url) { it.groupValues[1] + "••••" }

    /** The request as lines for the approval dialog: what is sent, to whom, with credentials hidden. */
    fun preview(method: String, url: String, headers: String, body: String, maxBodyLines: Int): List<String> = buildList {
        val host = runCatching { URI(url.trim()).host }.getOrNull().orEmpty()
        add("${method.uppercase()} ${maskUrl(url.trim())}")
        val parsed = runCatching { parseHeaders(headers) }.getOrDefault(emptyList())
        parsed.forEach { (name, value) -> add("$name: ${if (isSecretHeader(name)) "•••• (hidden here, sent to $host)" else value.take(120)}") }
        if (body.isNotEmpty()) {
            add("Body, ${body.length} characters:")
            body.lines().take(maxBodyLines).forEach { add("  ${it.take(160)}") }
            if (body.lines().size > maxBodyLines) add("  … (${body.lines().size - maxBodyLines} more lines)")
        }
        val sends = body.isNotEmpty() || method.uppercase() !in setOf("GET", "HEAD")
        add(if (sends) "This sends data to $host. Check the address and the body before you allow it." else "Only the request itself leaves your computer; no files are sent.")
        if (parsed.any { isSecretHeader(it.first) } || SECRET_QUERY.containsMatchIn(url)) add("It carries credentials: allow it only if you trust $host.")
    }
}

/**
 * Sends the request and returns the status, a few useful headers and the text of the answer. Every method is checked by [HttpGate] first.
 * Only GET and HEAD follow redirects (each hop re-checked); for the others the redirect is reported, never followed, so a body is not sent on
 * to an address the user did not see. A non-2xx answer is returned as text (an API's error message is what the model needs), not thrown.
 */
internal fun httpRequest(method: String, url: String, headers: String, body: String, allowPrivate: Boolean = false): String {
    val verb = method.trim().uppercase().ifEmpty { "GET" }
    HttpGate.refusal(verb, url, headers, body, allowPrivate)?.let { throw IllegalArgumentException(it) }
    val sendHeaders = HttpGate.parseHeaders(headers)
    var current = url.trim()
    repeat(WebPolicy.MAX_REDIRECTS + 1) {
        WebPolicy.refusal(current, allowPrivate)?.let { throw IllegalArgumentException(it) }
        val builder = HttpRequest.newBuilder(URI(current)).timeout(Duration.ofSeconds(WebPolicy.TIMEOUT_SECONDS)).header("User-Agent", "Arcana/1.0 (local assistant)")
        sendHeaders.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = builder.method(verb, publisher).build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) } catch (e: Exception) {
            throw IllegalArgumentException("Could not reach ${HttpGate.maskUrl(current)}: ${e.message ?: e.javaClass.simpleName}")
        }
        val status = response.statusCode()
        val location = response.headers().firstValue("Location").orElse(null)
        if (status in listOf(301, 302, 303, 307, 308) && location != null) {
            response.body().close()
            val next = URI(current).resolve(location).toString()
            if (verb != "GET" && verb != "HEAD") return "$verb ${HttpGate.maskUrl(current)}  →  HTTP $status, redirected to $next. A $verb request is not repeated at another address by itself: if that address is right, send the request there."
            current = next
            return@repeat
        }
        val type = response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase()
        val bytes = response.body().use { it.readNBytes(WebPolicy.MAX_BYTES + 1) }
        val truncated = bytes.size > WebPolicy.MAX_BYTES
        val shown = bytes.copyOf(minOf(bytes.size, WebPolicy.MAX_BYTES))
        val interesting = listOf("retry-after", "x-ratelimit-remaining", "x-ratelimit-reset", "ratelimit-remaining", "location").mapNotNull { n -> response.headers().firstValue(n).orElse(null)?.let { "$n: $it" } }
        val text = when {
            verb == "HEAD" || shown.isEmpty() -> "(no body)"
            type.startsWith("text/") || type.endsWith("json") || type.endsWith("xml") || type == "application/javascript" || type.isEmpty() -> String(shown, Charsets.UTF_8)
            else -> "($type, ${shown.size} bytes: not text, not shown)"
        }
        val part = text.take(WebPolicy.READ_CHARS)
        val more = if (text.length > part.length || truncated) "\n… (cut: the answer is longer than ${WebPolicy.READ_CHARS} characters)" else ""
        return "$verb ${HttpGate.maskUrl(current)}  →  HTTP $status${if (type.isEmpty()) "" else ", $type"}${if (interesting.isEmpty()) "" else "\n" + interesting.joinToString("\n")}\n\n${part.trim()}$more"
    }
    throw IllegalArgumentException("Too many redirects.")
}
