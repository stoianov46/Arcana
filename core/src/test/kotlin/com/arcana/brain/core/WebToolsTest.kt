package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Web tools: web_search (the parsers, the fallback order and the DuckDuckGo cooldown, a local SearXNG), fetch_url against a local server, http_request and curl refused, the web policy, HTML to text, failed-read messages. */
class WebToolsTest {
    @Test
    fun httpRequestIsTheGatedWayToPostAndCurlIsRefused() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/echo") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            seen += "${ex.requestMethod} ${ex.requestURI} auth=${ex.requestHeaders.getFirst("Authorization")} type=${ex.requestHeaders.getFirst("Content-Type")} body=$body"
            val bytes = """{"ok":true,"got":${body.length}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(201, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/moved") { ex -> ex.responseHeaders.add("Location", "/echo"); ex.sendResponseHeaders(307, -1); ex.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            // the real request: method, headers and body reach the server; a non-2xx answer is returned as text
            val out = httpRequest("post", "$base/echo?x=1", "Authorization: Bearer s3cret\nContent-Type: application/json", """{"a":1}""", allowPrivate = true)
            assertTrue("→  HTTP 201, application/json" in out && """{"ok":true,"got":7}""" in out, out)
            assertEquals("POST /echo?x=1 auth=Bearer s3cret type=application/json body={\"a\":1}", seen.single())
            // a POST is never repeated at the address a redirect names; a GET follows it (every hop checked)
            assertTrue("redirected to $base/echo" in httpRequest("POST", "$base/moved", "", "x", allowPrivate = true) && seen.size == 1)
            assertTrue("→  HTTP 201" in httpRequest("GET", "$base/moved", "", "", allowPrivate = true))
            // the gate
            assertTrue(HttpGate.refusal("TRACE", "https://example.com", "", "") != null)
            assertTrue(HttpGate.refusal("GET", "https://example.com", "", "body")!!.contains("no body"))
            assertTrue(HttpGate.refusal("POST", "https://example.com", "", "x".repeat(HttpGate.MAX_BODY + 1))!!.contains("at most"))
            assertTrue(HttpGate.refusal("POST", "https://example.com", "no colon here", "")!!.contains("Name: value"))
            assertTrue(HttpGate.refusal("POST", "https://example.com", "Host: evil.example", "")!!.contains("cannot be given"))
            assertTrue(HttpGate.refusal("POST", "http://localhost:8080/x", "", "")!!.contains("own computer"), "private addresses are never reached")
            assertTrue(HttpGate.refusal("POST", "https://user:pw@example.com/", "", "") != null)
            assertEquals(null, HttpGate.refusal("POST", "https://example.com/api", "Content-Type: application/json", "{}"))
        } finally { server.stop(0) }
        // what the user is shown: credentials hidden, the body and the destination visible
        val preview = HttpGate.preview("post", "https://api.example.com/v1/x?api_key=abc123&q=1", "Authorization: Bearer s3cret\nX-Note: hello", "line1\nline2", 12)
        val shown = preview.joinToString("\n")
        assertTrue("s3cret" !in shown && "abc123" !in shown && "••••" in shown, shown)
        assertTrue("POST https://api.example.com/v1/x?api_key=••••&q=1" in shown && "X-Note: hello" in shown && "Body, 11 characters:" in shown && "This sends data to api.example.com" in shown && "carries credentials" in shown, shown)
        assertTrue("carries credentials" !in HttpGate.preview("GET", "https://example.com", "", "", 12).joinToString())
        // through the toolbox: a Web tool (always asks), refused for private addresses, and the shell no longer offers curl
        val root = Files.createTempDirectory("arcana-http")
        val box = ToolBox(Vault(Files.createDirectories(root.resolve("vault"))), emptyList(), root.resolve("b"), shell = true, web = true)
        assertTrue(box.find("http_request")!!.risk == ToolRisk.Web && "http_request" in box.describe())
        assertTrue(box.execute(call("http_request", "url" to "http://127.0.0.1:9/x", "method" to "POST")).startsWith("Error:") && "own computer" in box.execute(call("http_request", "url" to "http://127.0.0.1:9/x")))
        val curl = box.execute(call("run_command", "command" to "curl -X POST https://example.com -d x"))
        assertTrue(curl.startsWith("Error: Arcana never runs") && "http_request" in curl, curl)
        val hint = box.execute(call("wget", "url" to "x")).substringAfter("Arcana has no")
        assertTrue("http_request" in hint && "run curl" !in hint, "the hint points at http_request, not at a shell: $hint")
    }

    @Test
    fun aLimitedSearchBackendFallsBackToTheOnesThatAreSetUpAndDuckDuckGoCoolsDown() {
        WebSearch.resetCooldown()
        try {
            assertEquals(listOf("duckduckgo"), WebSearch.order("duckduckgo", "", ""))
            assertEquals(listOf("duckduckgo", "brave", "searxng"), WebSearch.order("duckduckgo", "http://localhost:8080", "key"))
            assertEquals(listOf("brave", "searxng", "duckduckgo"), WebSearch.order("brave", "http://x", "k"))
            assertEquals(listOf("ollama", "searxng", "duckduckgo"), WebSearch.order("ollama", "http://x", "", "ok"))
            assertEquals(listOf("duckduckgo", "ollama", "brave"), WebSearch.order("duckduckgo", "", "k", "ok"))
            val calls = ArrayList<String>()
            val hit = SearchResult("A", "https://a.dev", "")
            val (used, results) = WebSearch.searchWithFallback("q", 5, "duckduckgo", "", "key") { b -> calls += b; if (b == "duckduckgo") throw SearchLimited("limited") else listOf(hit) }
            assertEquals("brave", used); assertEquals(listOf(hit), results); assertEquals(listOf("duckduckgo", "brave"), calls)
            // nothing set up: the error names the way out and says not to retry
            val e = runCatching { WebSearch.searchWithFallback("q", 5, "duckduckgo", "", "") { throw SearchLimited("DuckDuckGo is limiting requests right now (HTTP 202).") } }.exceptionOrNull()
            assertTrue(e != null && "HTTP 202" in e.message.orEmpty() && "Brave Search" in e.message.orEmpty() && "Do NOT call web_search again" in e.message.orEmpty(), e?.message)
            // it says what to do instead: no guessing addresses, carry on from the vault, mark claims unverified; it no longer sends the model to "a page you know"
            val advice = e!!.message.orEmpty()
            assertTrue("do NOT guess addresses" in advice && "search_notes" in advice && "unverified" in advice && "wait tool cannot help" in advice, advice)
            assertTrue("read a page you know" !in advice, advice)
            assertTrue("fetch_url directly" in advice && "never one built from a guess" in advice, advice)
            assertEquals(50, WebSearch.braveQuery((1..80).joinToString(" ") { "w$it" }).split(" ").size, "Brave takes at most 50 words")
            assertEquals(400, WebSearch.braveQuery("x".repeat(900)).length, "and 400 characters")
        } finally { WebSearch.resetCooldown() }
    }

    @Test
    fun aFailedPageReadSaysWhatToDoNext() {
        assertTrue("HTTP 410" in statusMessage("https://a.dev/x", 410) && "does not exist" in statusMessage("https://a.dev/x", 410))
        assertTrue("do not retry the same address" in statusMessage("https://a.dev/x", 403))
        assertTrue("wait tool" in statusMessage("https://a.dev/x", 429) && "once" in statusMessage("https://a.dev/x", 429))
        assertTrue("Retry once later" in statusMessage("https://a.dev/x", 503))
        assertEquals("https://a.dev/x answered HTTP 418.", statusMessage("https://a.dev/x", 418))
    }

    @Test
    fun webAddressesOnTheOwnMachineOrNetworkAreRefused() {
        for (bad in listOf("ftp://example.com/a", "file:///etc/passwd", "http://localhost:8080/", "http://127.0.0.1/", "http://10.0.0.5/", "http://192.168.1.1/admin",
            "http://172.16.0.9/", "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://0.0.0.0/", "http://100.64.1.2/", "http://user:pw@example.com/", "not a url", "http:///nohost")) {
            assertTrue(WebPolicy.refusal(bad) != null, bad)
        }
        assertTrue(WebPolicy.refusal("http://127.0.0.1/", allowPrivate = true) == null)
    }

    @Test
    fun htmlBecomesReadableText() {
        val html = """<html><head><title>Docs &amp; more</title><style>p{color:red}</style><script>alert(1)</script></head>
            <body><h1>Intro</h1><p>Hello <b>world</b> &mdash; see <a href="https://x.dev/a">the guide</a>.</p><ul><li>one</li><li>two &#38; three</li></ul><!-- hidden --></body></html>"""
        val text = htmlToText(html)
        assertTrue(text.startsWith("# Docs & more"), text)
        assertTrue("# Intro" in text && "Hello world — see the guide (https://x.dev/a)." in text && "- one" in text && "- two & three" in text, text)
        assertTrue("alert" !in text && "color:red" !in text && "hidden" !in text, text)
    }

    @Test
    fun fetchUrlReadsTextFollowsSafeRedirectsAndReadsLongPagesInParts() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type); ex.sendResponseHeaders(code, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.createContext("/page") { reply(it, 200, "text/html; charset=utf-8", "<html><title>T</title><body><p>Hi there</p></body></html>".toByteArray()) }
        server.createContext("/long") { reply(it, 200, "text/plain", "x".repeat(25_000).toByteArray()) }
        server.createContext("/json") { reply(it, 200, "application/json", """{"a":1}""".toByteArray()) }
        server.createContext("/gone") { reply(it, 404, "text/plain", "no".toByteArray()) }
        server.createContext("/bin") { reply(it, 200, "image/png", byteArrayOf(1, 2, 3)) }
        server.createContext("/go") { it.responseHeaders.add("Location", "/page"); it.sendResponseHeaders(302, -1); it.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertTrue("Hi there" in fetchUrl("$base/page", allowPrivate = true))
            assertTrue("Hi there" in fetchUrl("$base/go", allowPrivate = true))
            assertTrue("""{"a":1}""" in fetchUrl("$base/json", allowPrivate = true))
            val long = fetchUrl("$base/long", allowPrivate = true)
            assertTrue("continue with offset=20000" in long, long.takeLast(80))
            assertTrue("continue" !in fetchUrl("$base/long", offset = 20_000, allowPrivate = true))
            assertTrue(runCatching { fetchUrl("$base/gone", allowPrivate = true) }.exceptionOrNull()!!.message!!.contains("HTTP 404"))
            val gone = runCatching { fetchUrl("$base/gone", allowPrivate = true) }.exceptionOrNull()!!.message!!
            assertTrue("does not exist" in gone && "Do not guess addresses" in gone && "web_search" in gone, gone)
            assertTrue(runCatching { fetchUrl("$base/bin", allowPrivate = true) }.exceptionOrNull()!!.message!!.contains("not text"))
            // the real tool never reaches this server: it is on the loopback address
            val vaultRoot = Files.createTempDirectory("arcana-web")
            val box = ToolBox(null, listOf(vaultRoot), vaultRoot.resolve("b"), web = true)
            assertTrue(box.execute(call("fetch_url", "url" to "$base/page")).startsWith("Error:"))
            assertTrue("fetch_url" in box.describe())
            assertTrue(ToolBox(null, listOf(vaultRoot), vaultRoot.resolve("b"), readOnly = true).find("fetch_url") == null)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun webSearchParsesAllThreeBackendsAndAsksBeforeEverySearch() {
        val ddg = """<div class="results"><div class="result results_links"><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&amp;rut=abc">Example <b>Domain</b> &amp; more</a></h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">This domain is for use in <b>examples</b>.</a></div>
            <div class="result result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad=1">An ad</a><a class="result__snippet">ad text</a></div>
            <div class="result"><a class="result__a" href="https://kotlinlang.org/docs">Kotlin docs</a><a class="result__snippet" href="x">Official <b>documentation</b></a></div></div>"""
        val d = WebSearch.parseDuckDuckGo(ddg, 5)
        assertEquals(listOf("Example Domain & more", "Kotlin docs"), d.map { it.title })
        assertEquals("https://example.com/a?x=1", d[0].url)
        assertEquals("This domain is for use in examples.", d[0].snippet)
        assertEquals(1, WebSearch.parseDuckDuckGo(ddg, 1).size)
        assertTrue(runCatching { WebSearch.parseDuckDuckGo("<html>Unfortunately, bots use DuckDuckGo too. anomaly-modal</html>", 5) }.exceptionOrNull()!!.message!!.contains("human check"))
        val searx = WebSearch.parseSearx("""{"results":[{"title":"A","url":"https://a.dev","content":"about <b>a</b>"},{"url":"https://b.dev"},{"title":"no url"}]}""", 5)
        assertEquals(listOf("A", "https://b.dev"), searx.map { it.title }); assertEquals("about a", searx[0].snippet)
        val ollama = WebSearch.parseOllama("""{"results":[{"title":"O <b>hit</b>","url":"https://o.dev","content":"about o"},{"title":"no url"}]}""", 5)
        assertEquals(listOf(SearchResult("O hit", "https://o.dev", "about o")), ollama)
        assertTrue(runCatching { WebSearch.parseOllama("""{"error":"x"}""", 5) }.exceptionOrNull()!!.message!!.contains("Ollama"))
        val brave = WebSearch.parseBrave("""{"web":{"results":[{"title":"Brave <strong>hit</strong>","url":"https://c.dev","description":"desc"}]}}""", 5)
        assertEquals("Brave hit", brave.single().title)
        assertTrue(WebSearch.format("kotlin", "duckduckgo", d).contains("1. Example Domain & more\n   https://example.com/a?x=1\n   This domain"))
        assertTrue(WebSearch.format("zzz", "brave", emptyList()).startsWith("No results"))
        // a SearXNG server on the user's own network is reachable (they configured it); the tool asks before every search
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/search") { ex ->
            val body = """{"results":[{"title":"Hit for ${ex.requestURI.query.substringAfter("q=").substringBefore("&")}","url":"https://hit.dev","content":"snippet"}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val root = Files.createTempDirectory("arcana-search")
            val box = ToolBox(null, listOf(root), root.resolve("b"), web = true, searchBackend = "searxng", searchUrl = "http://127.0.0.1:${server.address.port}")
            val out = box.execute(call("web_search", "query" to "kotlin flow", "limit" to "3"))
            assertTrue("1. Hit for kotlin+flow" in out && "https://hit.dev" in out, out)
            val spec = box.find("web_search")!!
            assertEquals(ToolRisk.Web, spec.risk)
            assertTrue(spec.preview(call("web_search", "query" to "kotlin flow").arguments).any { "SearXNG" in it })
            // Changed 2026-11-24: this test predates the fallback (WebSearch.searchWithFallback). A backend that is not set up now hands the search on to the
            // others, and DuckDuckGo needs no setup, so these two calls went out to the real DuckDuckGo and passed only when it failed (offline or a human check).
            // DuckDuckGo is put in its cooldown here, so there is nothing to fall back to: no network, and the reply is the chosen backend's own reason.
            WebSearch::class.java.getDeclaredField("duckBlockedUntil").apply { isAccessible = true }.setLong(null, System.currentTimeMillis() + 60_000)
            try {
                assertTrue("No SearXNG address" in ToolBox(null, listOf(root), root.resolve("b"), web = true, searchBackend = "searxng").execute(call("web_search", "query" to "x")))
                assertTrue("No Brave Search API key" in ToolBox(null, listOf(root), root.resolve("b"), web = true, searchBackend = "brave").execute(call("web_search", "query" to "x")))
            } finally { WebSearch.resetCooldown() }
            // switched off: not offered, and a call says how to ask for it
            val off = ToolBox(null, listOf(root), root.resolve("b"))
            assertTrue(off.find("web_search") == null && off.find("run_command") == null && off.switchedOff == listOf("shell", "web"))
            assertTrue(off.execute(call("fetch_url", "url" to "https://x.dev")).let { "switched off" in it && TURN_ON_TOOLS in it && "\"web\"" in it })
            assertTrue("turn_on_tools" in off.describe() && "\"shell\"" in off.describe())
            assertTrue(turnOnToolsDefinition(listOf("web")).toString().let { "\"enum\":[\"web\"]" in it })
            assertTrue("- web_search(query, limit?)" in box.cheatSheet())
            assertTrue(ToolBox(null, listOf(root), root.resolve("b"), readOnly = true).find("web_search") == null)
        } finally {
            server.stop(0)
        }
    }
}
