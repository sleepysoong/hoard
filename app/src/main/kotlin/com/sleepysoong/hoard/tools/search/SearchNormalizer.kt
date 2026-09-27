package com.sleepysoong.hoard.tools.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Provider-neutral output of web_search — the only shape the model ever sees:
 *
 * ```
 * {"query": "…", "correctedQuery": "…"?, "results": [{"id":"search_1","title","url","snippet","language"?,"pageAge"?}], "hasMore": false}
 * ```
 * Drops results without a title or an http(s) URL, removes duplicate URLs
 * (fragment / trailing slash / host case ignored), strips markup from snippets
 * and truncates long ones.
 */
object SearchNormalizer {
    const val MAX_SNIPPET = 300
    const val MAX_TITLE = 200

    data class Result(val id: String, val title: String, val url: String, val snippet: String, val language: String?, val pageAge: String?, val extraSnippets: List<String>)

    fun results(response: SearchResponse, limit: Int): List<Result> {
        val seen = HashSet<String>()
        val out = ArrayList<Result>()
        for (h in response.hits) {
            if (out.size >= limit) break
            val title = clean(h.title).take(MAX_TITLE)
            val url = h.url?.trim().orEmpty()
            val key = dedupeKey(url) ?: continue
            if (title.isEmpty() || !seen.add(key)) continue
            out += Result(
                id = "search_${out.size + 1}",
                title = title,
                url = url,
                snippet = truncate(clean(h.snippet), MAX_SNIPPET),
                language = h.language?.takeIf { it.isNotBlank() },
                pageAge = pageAge(h.pageAge),
                extraSnippets = h.extraSnippets.map { truncate(clean(it), MAX_SNIPPET) }.filter { it.isNotEmpty() }
            )
        }
        return out
    }

    fun toJson(response: SearchResponse, limit: Int): JsonObject {
        val results = results(response, limit)
        return buildJsonObject {
            put("query", response.query)
            response.alteredQuery?.let { put("correctedQuery", it) }
            put("results", buildJsonArray {
                results.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("title", r.title)
                        put("url", r.url)
                        put("snippet", r.snippet)
                        r.language?.let { put("language", it) }
                        r.pageAge?.let { put("pageAge", it) }
                        if (r.extraSnippets.isNotEmpty()) put("extraSnippets", buildJsonArray { r.extraSnippets.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    })
                }
            })
            put("hasMore", response.moreResultsAvailable)
        }
    }

    /** Canonical form for duplicate detection; null when not a usable http(s) URL. */
    fun dedupeKey(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = (u.rawPath ?: "").trimEnd('/')
        val query = u.rawQuery?.let { "?$it" }.orEmpty()
        return "$host$path$query"
    }

    /** "2026-09-25T08:00:00" → "2026-09-25"; other formats kept as given. */
    fun pageAge(v: String?): String? {
        val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Regex("^\\d{4}-\\d{2}-\\d{2}").find(t)?.value ?: t.take(40)
    }

    /** Strip tags, decode entities, collapse whitespace. */
    fun clean(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var t = s.replace(Regex("<[^>]*>"), "")
        t = decodeEntities(t)
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.take(max)
        val space = cut.lastIndexOf(' ')
        return (if (space > max * 0.6) cut.take(space) else cut).trimEnd() + "…"
    }

    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "#39" to "'")

    internal fun decodeEntities(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+|#39);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e.lowercase()] ?: m.value
        }
    }
}
