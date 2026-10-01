package com.sleepysoong.hoard.tools.search

import com.sleepysoong.hoard.tools.readCapped
import com.sleepysoong.hoard.tools.withConnection
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * Brave Web Search API (`GET /res/v1/web/search`). Only this class knows Brave's
 * parameters and response shape; it hands back provider-neutral [SearchHit]s.
 *
 * Fixed parameters: `result_filter=web,news,faq,discussions` (so a how-to query can
 * surface a direct Q&A card and a "what are people saying" query a forum thread, not
 * just ten blue links), `text_decorations=false`, `operators=true`, and
 * `extra_snippets` following [SearchRequest.extraSnippets] (on by default).
 *
 * 429/5xx: retried with backoff, waiting at least the per-second window from
 * `X-RateLimit-Reset`. An exhausted long-window quota (e.g. monthly) fails
 * immediately — waiting seconds can't fix it.
 *
 * The API key is the user's own (Settings), never bundled in the app.
 */
class BraveSearchProvider(
    private val apiKey: String,
    private val endpoint: String = ENDPOINT,
    private val maxRetries: Int = 3,
    /** Injectable for tests (no real sleeping). */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) : SearchProvider {
    override val id = "brave"

    override suspend fun search(request: SearchRequest): SearchResponse {
        if (apiKey.isBlank()) throw SearchException("Brave Search API 키가 설정되지 않았습니다 (설정 → 웹 도구)")
        val url = buildUrl(request)
        var attempt = 0
        while (true) {
            val r = get(url)
            when {
                r.status in 200..299 -> return parse(r.body, request.query)
                r.status == 429 || r.status in 500..599 -> {
                    val quotaExhausted = r.status == 429 && longWindowExhausted(r.remaining, r.reset)
                    if (quotaExhausted) throw SearchException("Brave Search 사용량 한도를 모두 썼습니다 (HTTP 429)")
                    if (attempt >= maxRetries) {
                        throw SearchException("Brave Search가 계속 거절했습니다 (HTTP ${r.status}, ${attempt + 1}회 시도)", retryable = true)
                    }
                    sleep(backoffMs(attempt, r.reset))
                    attempt++
                }
                r.status == 401 || r.status == 403 -> throw SearchException("Brave Search API 키가 거부됐습니다 (HTTP ${r.status})")
                r.status == 422 || r.status == 400 -> throw SearchException("Brave Search가 요청을 거부했습니다 (HTTP ${r.status}): ${errorDetail(r.body)}")
                else -> throw SearchException("Brave Search 오류 (HTTP ${r.status})")
            }
        }
    }

    internal fun buildUrl(r: SearchRequest): String {
        val params = linkedMapOf(
            "q" to r.query,
            "count" to r.count.coerceIn(1, SearchRequest.MAX_COUNT).toString(),
            "result_filter" to "web,news,faq,discussions",
            "text_decorations" to "false",
            "extra_snippets" to r.extraSnippets.toString(),
            "operators" to "true"
        )
        r.freshness?.let { params["freshness"] = freshnessParam(it) }
        r.country?.takeIf { it.isNotBlank() }?.let { params["country"] = it.trim().uppercase() }
        r.language?.takeIf { it.isNotBlank() }?.let { params["search_lang"] = it.trim().lowercase() }
        return endpoint + "?" + params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
    }

    private class Raw(val status: Int, val body: String, val remaining: String?, val reset: String?)

    private suspend fun get(url: String): Raw {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-Subscription-Token", apiKey)
        return try {
            withConnection(conn) { c ->
                val status = c.responseCode
                val stream = if (status in 200..299) c.inputStream else c.errorStream
                val body = stream?.use { it.readCapped(MAX_BODY).first.decodeToString() }.orEmpty()
                Raw(status, body, c.getHeaderField("X-RateLimit-Remaining"), c.getHeaderField("X-RateLimit-Reset"))
            }
        } catch (e: SocketTimeoutException) {
            throw SearchException("Brave Search 응답 시간 초과", retryable = true)
        } catch (e: IOException) {
            throw SearchException("Brave Search에 연결할 수 없습니다 (${e.message})", retryable = true)
        }
    }

    internal fun parse(body: String, fallbackQuery: String): SearchResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw SearchException("Brave Search 응답을 해석할 수 없습니다")
        val q = root["query"] as? JsonObject
        val original = q?.str("original") ?: fallbackQuery
        val altered = q?.str("altered")?.takeIf { it.isNotBlank() && it != original }
        val related = (q?.get("related_queries") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .filter { !it.equals(original, ignoreCase = true) }
            .distinct()

        val hits = ArrayList<SearchHit>()
        // Keep the engine's web ranking as the baseline; supplemental sections follow it.
        for (el in webResults(root, "web")) hits += toHit(el, ResultType.Web)
        for (el in webResults(root, "news")) hits += toHit(el, ResultType.News)
        for (el in webResults(root, "discussions")) hits += toHit(el, ResultType.Discussion)
        // FAQ excerpts are source content, not verified answers. Duplicate URLs are merged later.
        for (el in ((root["faq"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()) {
            val o = el as? JsonObject ?: continue
            val answer = o.str("answer")
            hits += SearchHit(
                title = o.str("title") ?: o.str("question"),
                url = o.str("url"),
                snippet = answer ?: o.str("question"),
                type = ResultType.Faq,
                source = o.host(),
                question = o.str("question"),
                answer = answer
            )
        }

        val more = (q?.get("more_results_available") as? JsonPrimitive)?.booleanOrNull ?: false
        return SearchResponse(query = original, alteredQuery = altered, hits = hits, moreResultsAvailable = more, relatedQueries = related)
    }

    private fun webResults(root: JsonObject, section: String): List<JsonObject> =
        ((root[section] as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun toHit(o: JsonObject, type: ResultType) = SearchHit(
        title = o.str("title"),
        url = o.str("url"),
        snippet = o.str("description"),
        language = o.str("language"),
        pageAge = o.str("page_age") ?: o.str("age"),
        extraSnippets = (o["extra_snippets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        type = type,
        source = o.host()
    )

    /** Brave's clean host label (`profile.long_name` / `meta_url.hostname`), if present. */
    private fun JsonObject.host(): String? {
        (this["profile"] as? JsonObject)?.str("long_name")?.takeIf { it.isNotBlank() }?.let { return it }
        (this["meta_url"] as? JsonObject)?.str("hostname")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun errorDetail(body: String): String =
        runCatching {
            val e = json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
            e?.str("detail") ?: e?.str("code")
        }.getOrNull() ?: body.take(160)

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        private const val MAX_BODY = 2 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun freshnessParam(f: Freshness) = when (f) {
            Freshness.Day -> "pd"
            Freshness.Week -> "pw"
            Freshness.Month -> "pm"
            Freshness.Year -> "py"
        }

        private fun csvLongs(v: String?): List<Long?> = v?.split(",")?.map { it.trim().toLongOrNull() }.orEmpty()

        /** Exponential backoff (1s, 2s, 4s…, max 8s) but never shorter than the burst window reset. */
        internal fun backoffMs(attempt: Int, resetHeader: String?): Long {
            val exp = (1000L shl attempt.coerceAtMost(3)).coerceAtMost(8_000)
            val burstReset = csvLongs(resetHeader).firstOrNull()?.takeIf { it in 0..30 }?.times(1000) ?: 0
            return maxOf(exp, burstReset)
        }

        /**
         * `X-RateLimit-Remaining: 1, 0` + `X-RateLimit-Reset: 1, 1419704`: the monthly window
         * is empty. Any window that resets in over a minute with 0 left = give up now.
         */
        internal fun longWindowExhausted(remaining: String?, reset: String?): Boolean {
            val rem = csvLongs(remaining)
            val res = csvLongs(reset)
            return rem.indices.any { i -> rem[i] == 0L && (res.getOrNull(i) ?: 0) > 60 }
        }
    }
}
