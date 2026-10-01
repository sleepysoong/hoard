package com.sleepysoong.hoard

import com.sleepysoong.hoard.tools.ToolRegistry
import com.sleepysoong.hoard.tools.WebFetchTool
import com.sleepysoong.hoard.tools.WebSearchTool
import com.sleepysoong.hoard.tools.fetch.HtmlExtractor
import com.sleepysoong.hoard.tools.fetch.PageFetcher
import com.sleepysoong.hoard.tools.search.BraveSearchProvider
import com.sleepysoong.hoard.tools.search.Freshness
import com.sleepysoong.hoard.tools.search.SearchHit
import com.sleepysoong.hoard.tools.search.SearchNormalizer
import com.sleepysoong.hoard.tools.search.SearchRequest
import com.sleepysoong.hoard.tools.search.SearchResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections

/**
 * web_search / web_fetch against local HTTP servers (no internet): Brave request
 * parameters, normalization, 429 backoff, SSRF blocking, redirects, extraction.
 * Plain JVM (no Robolectric): the tools don't touch Android.
 */
class WebToolsTest {
    private val servers = mutableListOf<HttpServer>()
    @After fun stop() = servers.forEach { it.stop(0) }

    private fun server(handler: (HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex -> try { handler(ex) } finally { ex.close() } }
        s.start(); servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun HttpExchange.send(status: Int, body: String, type: String = "application/json", headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        responseHeaders.add("Content-Type", type)
        val b = body.toByteArray()
        sendResponseHeaders(status, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) responseBody.use { it.write(b) }
    }

    private fun query(ex: HttpExchange): Map<String, String> = ex.requestURI.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }
        .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    private val braveBody = """
        {"type":"search","query":{"original":"kotlin corutine","altered":"kotlin coroutine","more_results_available":true},
         "web":{"type":"search","results":[
           {"title":"Coroutines <strong>guide</strong>","url":"https://kotlinlang.org/docs/coroutines-guide.html","description":"Kotlin &amp; coroutines ${"long ".repeat(120)}","language":"en","page_age":"2026-09-25T08:00:00"},
           {"title":"Coroutines guide (dup)","url":"https://www.kotlinlang.org/docs/coroutines-guide.html/#top","description":"duplicate"},
           {"title":"","url":"https://empty-title.example","description":"x"},
           {"title":"No url","url":"","description":"x"},
           {"title":"FTP","url":"ftp://files.example/x","description":"x"},
           {"title":"코루틴 기초","url":"https://example.kr/basics","description":"기초 설명","language":"ko","age":"3 days ago"}
         ]}}
    """.trimIndent()

    // --- web_search -------------------------------------------------------

    @Test fun braveRequestUsesFixedDefaultsAndMapsOptions() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Pair<Map<String, String>, String?>>())
        val base = server { ex -> seen += query(ex) to ex.requestHeaders.getFirst("X-Subscription-Token"); ex.send(200, braveBody) }
        val tool = WebSearchTool(BraveSearchProvider("brave-key-123", endpoint = "$base/res/v1/web/search"))
        tool.execute(Json.parseToJsonElement("""{"query":"kotlin corutine","count":3,"freshness":"week","country":"kr","language":"KO"}""").jsonObject)
        val (q, key) = seen.single()
        assertEquals("brave-key-123", key)
        assertEquals("kotlin corutine", q["q"])
        // Over-fetches candidates (shown count + headroom) so the re-ranker has room; capped at MAX_COUNT.
        assertEquals((3 + WebSearchTool.CANDIDATE_HEADROOM).toString(), q["count"])
        assertEquals("pw", q["freshness"])
        assertEquals("KR", q["country"])
        assertEquals("ko", q["search_lang"])
        assertEquals("true", q["extra_snippets"])
        assertEquals("false", q["text_decorations"])
        assertEquals("web,news,faq,discussions", q["result_filter"])
        assertEquals("true", q["operators"])

        // Defaults: fixed quality params, richer result filter, and no country/search_lang (never taken from the locale).
        java.util.Locale.setDefault(java.util.Locale.KOREA)
        tool.execute(Json.parseToJsonElement("""{"query":"x"}""").jsonObject)
        val d = seen.last().first
        assertEquals((SearchRequest.DEFAULT_COUNT + WebSearchTool.CANDIDATE_HEADROOM).coerceAtMost(SearchRequest.MAX_COUNT).toString(), d["count"])
        assertNull(d["freshness"]); assertNull(d["country"]); assertNull(d["search_lang"])
        assertEquals("true", d["extra_snippets"]); assertEquals("false", d["text_decorations"])
        assertEquals("true", d["operators"]); assertEquals("web,news,faq,discussions", d["result_filter"])
        // Excessive caller counts are clamped to 10 displayed results, plus candidate headroom.
        tool.execute(Json.parseToJsonElement("""{"query":"x","count":50}""").jsonObject)
        assertEquals("18", seen.last().first["count"])
    }

    @Test fun freshnessMapping() {
        assertEquals(listOf("pd", "pw", "pm", "py"), listOf(Freshness.Day, Freshness.Week, Freshness.Month, Freshness.Year).map(BraveSearchProvider::freshnessParam))
    }

    @Test fun searchOutputIsNormalizedNotBraveShaped() = runBlocking {
        val base = server { ex -> ex.send(200, braveBody) }
        val out = WebSearchTool(BraveSearchProvider("k", endpoint = "$base/s")).execute(Json.parseToJsonElement("""{"query":"kotlin corutine"}""").jsonObject)
        assertEquals(setOf("query", "correctedQuery", "results", "hasMore"), out.keys)
        assertEquals("kotlin corutine", out.str("query"))
        assertEquals("query correction preserved", "kotlin coroutine", out.str("correctedQuery"))
        assertEquals(true, out["hasMore"]!!.jsonPrimitive.content.toBoolean())
        val results = out["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals("blank title/url, non-http and duplicate URLs dropped", 2, results.size)
        assertEquals(listOf("search_1", "search_2"), results.map { it.str("id") })
        // The engine's leading result remains first and keeps its normalized metadata.
        val first = results[0]
        assertEquals("web", first.str("type"))
        assertEquals("Coroutines guide", first.str("title"))
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", first.str("url"))
        assertTrue(first.str("snippet")!!.startsWith("Kotlin & coroutines"))
        assertTrue("snippet truncated", first.str("snippet")!!.length <= SearchNormalizer.MAX_SNIPPET + 1 && first.str("snippet")!!.endsWith("…"))
        assertEquals("en", first.str("language"))
        assertEquals("2026-09-25", first.str("pageAge"))
        assertEquals("ko", results[1].str("language"))
        assertEquals("3 days ago", results[1].str("pageAge"))
        // Nothing Brave-specific leaks through.
        val flat = out.toString()
        listOf("\"web\":", "more_results_available", "page_age", "\"description\"", "\"type\":\"search\"").forEach {
            assertFalse("$it leaked: $flat", flat.contains(it))
        }
    }

    @Test fun rateLimitIsRetriedWithBackoffHonouringReset() = runBlocking {
        var calls = 0
        val base = server { ex ->
            calls++
            if (calls <= 2) ex.send(429, """{"type":"ErrorResponse","error":{"code":"RATE_LIMITED"}}""", headers = mapOf("X-RateLimit-Remaining" to "0, 1400", "X-RateLimit-Reset" to "3, 100000"))
            else ex.send(200, braveBody)
        }
        val waits = mutableListOf<Long>()
        val out = WebSearchTool(BraveSearchProvider("k", endpoint = "$base/s", sleep = { waits += it })).execute(Json.parseToJsonElement("""{"query":"q"}""").jsonObject)
        assertEquals(3, calls)
        assertEquals("waits at least the per-second reset, growing exponentially", listOf(3000L, 3000L), waits)
        assertEquals(2, out["results"]!!.jsonArray.size)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L), (0..4).map { BraveSearchProvider.backoffMs(it, null) })
    }

    @Test fun exhaustedMonthlyQuotaFailsFastAndPersistent429GivesUp() = runBlocking {
        var calls = 0
        val quota = server { ex -> calls++; ex.send(429, "{}", headers = mapOf("X-RateLimit-Remaining" to "1, 0", "X-RateLimit-Reset" to "1, 1419704")) }
        val reg = ToolRegistry(listOf(WebSearchTool(BraveSearchProvider("k", endpoint = "$quota/s", sleep = {}))))
        val o = reg.execute("web_search", """{"query":"q"}""")
        assertTrue(o.isError); assertEquals(1, calls)
        assertTrue(o.output, o.output.contains("한도"))

        var calls2 = 0
        val busy = server { ex -> calls2++; ex.send(429, "{}") }
        val o2 = ToolRegistry(listOf(WebSearchTool(BraveSearchProvider("k", endpoint = "$busy/s", maxRetries = 3, sleep = {})))).execute("web_search", """{"query":"q"}""")
        assertTrue(o2.isError); assertEquals("1 + 3 retries", 4, calls2)
    }

    @Test fun badKeyAndBadArgumentsBecomeToolErrorsForTheModel() = runBlocking {
        val base = server { ex -> ex.send(401, "{}") }
        val reg = ToolRegistry(listOf(WebSearchTool(BraveSearchProvider("bad", endpoint = "$base/s"))))
        assertTrue(reg.execute("web_search", """{"query":"q"}""").output.contains("거부"))
        assertTrue(reg.execute("web_search", """{}""").output.contains("query is required"))
        assertTrue(reg.execute("web_search", """{"query":"q","freshness":"decade"}""").output.contains("freshness"))
        assertTrue(reg.execute("web_search", "not json").output.contains("JSON object"))
        assertTrue(reg.execute("web_nope", "{}").output.contains("unknown tool"))
        Json.parseToJsonElement(reg.execute("web_search", "{}").output).jsonObject.let { assertTrue(it.containsKey("error")) }
    }

    @Test fun tenResultsAreReturnedAndDescriptionTeachesQueryRefinement() = runBlocking {
        val hits = (1..12).joinToString(",") { """{"title":"T$it","url":"https://e$it.example/p","description":"d"}""" }
        val base = server { ex -> ex.send(200, """{"query":{"original":"q"},"web":{"results":[$hits]}}""") }
        val tool = WebSearchTool(BraveSearchProvider("k", endpoint = "$base/s"))
        val out = tool.execute(Json.parseToJsonElement("""{"query":"q"}""").jsonObject)
        assertEquals((1..10).map { "search_$it" }, out["results"]!!.jsonArray.map { it.jsonObject.str("id") })
        val d = tool.description
        listOf("web_fetch", "parallel", "site:", "\"exact phrase\"", "-excluded", "AND", "OR", "NOT", "filetype:", "English", "faq", "discussion", "relatedQueries").forEach {
            assertTrue("description mentions $it", d.contains(it))
        }
        val props = tool.parameters["properties"]!!.jsonObject
        assertTrue(props["country"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("Omit otherwise"))
        assertTrue(props["language"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("Omit otherwise"))
    }

    @Test fun toolDescriptionsSeparateDiscoverFromRead() {
        val search = WebSearchTool(BraveSearchProvider("k"))
        assertTrue(search.description.contains("summaries, NOT the full page"))
        assertTrue(search.description.contains("call web_fetch"))
        val schema = search.parameters
        assertEquals(listOf("query"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("extra_snippets is internal only", schema.toString().contains("extra"))
        assertEquals(listOf("url"), WebFetchTool().parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("search/fetch are read-only: run in parallel", search.parallelSafe && WebFetchTool().parallelSafe)
    }

    @Test fun onlyConsecutiveReadOnlyCallsShareABatch() {
        val b = com.sleepysoong.hoard.engine.RouterAiEngine.batches(listOf("fetch1", "fetch2", "write", "read", "grep", "edit", "fetch3")) { it !in setOf("write", "edit") }
        assertEquals(listOf(listOf("fetch1", "fetch2"), listOf("write"), listOf("read", "grep"), listOf("edit"), listOf("fetch3")), b)
    }

    @Test fun normalizerDedupesAndCleans() {
        val r = SearchNormalizer.results(SearchResponse("q", null, listOf(
            SearchHit("A", "https://Example.com/a/", "x"),
            SearchHit("A2", "https://example.com/a#frag", "y"),
            SearchHit("B", "https://example.com/a?page=2", "&lt;b&gt; &#x41;"),
            SearchHit(" ", "https://example.com/c", "z")
        )), 10)
        assertEquals(listOf("https://Example.com/a/", "https://example.com/a?page=2"), r.map { it.url })
        assertEquals("<b> A", r[1].snippet)
    }

    // --- web_fetch --------------------------------------------------------

    /** Loopback is private, so tests allow it explicitly; production uses isPublicAddress. */
    private fun localFetcher(maxBytes: Int = 3 * 1024 * 1024) = PageFetcher(addressPolicy = { it.isLoopbackAddress }, maxBytes = maxBytes)

    private val articleHtml = """
        <!doctype html><html><head><title>Hoard 소개 | 블로그</title><style>body{color:red}</style>
        <script>alert('x')</script></head><body>
        <header><nav><a href="/">홈</a> <a href="/about">소개</a></nav></header>
        <div class="cookie-banner">쿠키에 동의하시겠습니까?</div>
        <aside class="sidebar">인기 글 목록</aside>
        <article>
          <h1>Hoard는 무엇인가</h1>
          <p>Hoard는 <strong>안드로이드</strong> AI 채팅 앱입니다. 라우터 <a href="/router">sleepyrouter</a>를 통해 답합니다.</p>
          <div class="ad-slot">광고입니다 지금 구매하세요</div>
          <h2>특징</h2>
          <ul><li>리퀴드 글래스 UI</li><li>백그라운드 답변<ul><li>WorkManager</li></ul></li></ul>
          <table><tr><th>항목</th><th>값</th></tr><tr><td>minSdk</td><td>31</td></tr></table>
          <pre>fun main() = println("hi")</pre>
          <p>본문의 마지막 문단입니다. 충분히 긴 텍스트가 있어야 본문으로 인식됩니다. 더 많은 설명을 덧붙입니다.</p>
        </article>
        <div class="share-buttons">공유하기</div>
        <footer>© 2026 sleepysoong · 개인정보처리방침</footer>
        </body></html>
    """.trimIndent()

    @Test fun fetchExtractsMainContentAsMarkdown() = runBlocking {
        val base = server { ex -> ex.send(200, articleHtml, "text/html; charset=utf-8") }
        val out = WebFetchTool(localFetcher()).execute(Json.parseToJsonElement("""{"url":"$base/post"}""").jsonObject)
        val content = out.str("content")!!
        assertEquals("$base/post", out.str("url"))
        assertNull("no redirect → no finalUrl", out["finalUrl"])
        assertEquals(200, out["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("text/html", out.str("contentType"))
        assertEquals("Hoard 소개 | 블로그", out.str("title"))
        assertTrue(content, content.contains("# Hoard는 무엇인가"))
        assertTrue(content, content.contains("**안드로이드**"))
        assertTrue("links absolute: $content", content.contains("[sleepyrouter]($base/router)"))
        assertTrue(content, content.contains("## 특징"))
        assertTrue(content, content.contains("- 리퀴드 글래스 UI"))
        assertTrue(content, content.contains("  - WorkManager"))
        assertTrue(content, content.contains("| minSdk | 31 |"))
        assertTrue(content, content.contains("```\nfun main() = println(\"hi\")\n```"))
        listOf("alert(", "color:red", "홈", "쿠키", "인기 글", "광고", "공유하기", "개인정보").forEach {
            assertFalse("\"$it\" should be stripped:\n$content", content.contains(it))
        }
    }

    @Test fun redirectsAreFollowedAndReportFinalUrl() = runBlocking {
        lateinit var base: String
        base = server { ex ->
            when (ex.requestURI.path) {
                "/old" -> ex.send(301, "", "text/plain", mapOf("Location" to "/mid"))
                "/mid" -> ex.send(302, "", "text/plain", mapOf("Location" to "$base/new"))
                else -> ex.send(200, "<html><head><title>새 주소</title></head><body><p>옮겨진 페이지의 본문입니다.</p></body></html>", "text/html")
            }
        }
        val out = WebFetchTool(localFetcher()).execute(Json.parseToJsonElement("""{"url":"$base/old"}""").jsonObject)
        assertEquals("$base/old", out.str("url"))
        assertEquals("$base/new", out.str("finalUrl"))
        assertEquals("새 주소", out.str("title"))
        assertTrue(out.str("content")!!.contains("옮겨진 페이지"))
    }

    @Test fun privateNetworkTargetsAreBlocked() = runBlocking {
        // Default policy: loopback/LAN/metadata/CGNAT/ULA refused before connecting.
        val reg = ToolRegistry(listOf(WebFetchTool()))
        for (u in listOf("http://127.0.0.1:1/", "http://localhost/", "http://192.168.0.1/", "http://10.0.0.5/", "http://169.254.169.254/latest/meta-data/",
                         "http://100.64.0.1/", "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/", "file:///etc/passwd", "ftp://x.example/", "http://user:pw@example.com/")) {
            val o = reg.execute("web_fetch", """{"url":"$u"}""")
            assertTrue("$u must be refused: ${o.output}", o.isError)
        }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertTrue(it, PageFetcher.isPublicAddress(InetAddress.getByName(it))) }
        listOf("172.16.5.4", "0.0.0.0", "224.0.0.1", "fe80::1", "198.18.0.1").forEach { assertFalse(it, PageFetcher.isPublicAddress(InetAddress.getByName(it))) }
    }

    @Test fun redirectToPrivateAddressIsBlocked() = runBlocking {
        // Public-looking host (policy allows 127.0.0.1 only as the *first* hop), then a hop to a LAN address.
        val base = server { ex -> ex.send(302, "", "text/plain", mapOf("Location" to "http://192.168.1.1/admin")) }
        val o = ToolRegistry(listOf(WebFetchTool(localFetcher()))).execute("web_fetch", """{"url":"$base/go"}""")
        assertTrue(o.output, o.isError && o.output.contains("192.168.1.1"))
    }

    @Test fun longPagesAreTruncatedAndBinaryRefused() = runBlocking {
        val big = "<html><body><article>" + (1..400).joinToString("") { "<p>문단 $it: ${"내용 ".repeat(30)}</p>" } + "</article></body></html>"
        val base = server { ex ->
            when (ex.requestURI.path) {
                "/big" -> ex.send(200, big, "text/html")
                "/pdf" -> ex.send(200, "%PDF-1.7", "application/pdf")
                "/txt" -> ex.send(200, "plain  text\nline2", "text/plain; charset=utf-8")
                "/404" -> ex.send(404, "<html>nope</html>", "text/html")
                else -> ex.send(200, "x".repeat(5000), "text/plain")
            }
        }
        val out = WebFetchTool(localFetcher(), maxChars = 2000).execute(Json.parseToJsonElement("""{"url":"$base/big"}""").jsonObject)
        assertEquals(true, out["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(out.str("content")!!.length < 2200)
        assertTrue(out.str("content")!!.contains("[… truncated"))

        val reg = ToolRegistry(listOf(WebFetchTool(localFetcher(maxBytes = 1000))))
        assertTrue(reg.execute("web_fetch", """{"url":"$base/pdf"}""").output.contains("unsupported content type"))
        assertTrue(reg.execute("web_fetch", """{"url":"$base/404"}""").output.contains("HTTP 404"))
        val txt = Json.parseToJsonElement(reg.execute("web_fetch", """{"url":"$base/txt"}""").output).jsonObject
        assertEquals("plain  text\nline2", txt.str("content"))
        val capped = Json.parseToJsonElement(reg.execute("web_fetch", """{"url":"$base/huge"}""").output).jsonObject
        assertEquals("download capped at maxBytes", true, capped["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1000, capped.str("content")!!.length)
    }

    @Test fun extractorFallsBackToDensestBlockWithoutArticle() {
        val html = """<html><body><div class="menu"><a href="/a">A</a><a href="/b">B</a></div>
            <div id="wrap"><div class="post-body"><p>${"첫 문단 내용입니다. ".repeat(10)}</p><p>${"둘째 문단 내용입니다. ".repeat(10)}</p></div>
            <div class="comments"><p>댓글: 좋은 글이네요 정말로 그렇습니다 동의합니다</p></div></div></body></html>"""
        val x = HtmlExtractor.extract(html, "https://ex.example/p")
        assertTrue(x.content, x.content.startsWith("첫 문단"))
        assertTrue(x.content.contains("둘째 문단"))
        assertFalse(x.content, x.content.contains("댓글"))
    }

    @Test fun tablesInsideCustomElementsStayTables() {
        val html = "<html><body><article><p>${"설명 ".repeat(60)}</p><markdown-table><table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table></markdown-table></article></body></html>"
        val x = HtmlExtractor.extract(html, "https://ex.example/")
        assertTrue(x.content, x.content.contains("| A | B |\n| --- | --- |\n| 1 | 2 |"))
    }

    @Test fun legacyCharsetIsDecoded() {
        val bytes = "<html><head><title>한글</title></head><body><p>EUC-KR 본문입니다</p></body></html>".toByteArray(charset("EUC-KR"))
        val x = HtmlExtractor.extract(bytes, "EUC-KR", "https://ex.example/")
        assertEquals("한글", x.title)
        assertTrue(x.content.contains("EUC-KR 본문입니다"))
    }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
