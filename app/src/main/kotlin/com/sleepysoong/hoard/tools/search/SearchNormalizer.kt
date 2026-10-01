package com.sleepysoong.hoard.tools.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Provider-neutral output of web_search — the only shape the model ever sees:
 *
 * ```
 * {
 *   "query": "…",
 *   "correctedQuery": "…"?,          // provider spellcheck, when it ran a different query
 *   "results": [
 *     {"id":"search_1","type":"web|news|faq|discussion","title","url","source",
 *      "snippet","extraSnippets"?,"question"?,"answer"?,"language"?,"pageAge"?}
 *   ],
 *   "relatedQueries": ["…"]?,        // follow-up query ideas when results are weak
 *   "hasMore": false
 * }
 * ```
 *
 * On top of dropping title-less / non-http(s) / duplicate hits and cleaning markup,
 * the normalizer **re-ranks** the candidates by relevance to the query (term overlap,
 * preserving provider rank, combining duplicate excerpts and using query relevance;
 * freshness is only a ranking signal when the caller explicitly requests recent pages),
 * then trims to the requested count. This is the layer that keeps output
 * provider-neutral: nothing Brave-shaped leaks through.
 */
object SearchNormalizer {
    const val MAX_SNIPPET = 320
    const val MAX_TITLE = 200
    const val MAX_ANSWER = 500
    const val MAX_EXTRA_SNIPPETS = 3
    const val MAX_RELATED = 6

    data class Result(
        val id: String,
        val type: ResultType,
        val title: String,
        val url: String,
        val source: String,
        val snippet: String,
        val question: String?,
        val answer: String?,
        val language: String?,
        val pageAge: String?,
        val extraSnippets: List<String>
    )

    fun results(response: SearchResponse, limit: Int, preferRecent: Boolean = false): List<Result> {
        val effectiveQuery = response.alteredQuery ?: response.query
        val terms = queryTerms(effectiveQuery)
        val candidates = LinkedHashMap<String, Result>()

        for (h in response.hits) {
            val title = clean(h.title).take(MAX_TITLE)
            val url = h.url?.trim().orEmpty()
            val key = dedupeKey(url) ?: continue
            if (title.isEmpty()) continue

            val snippet = truncate(clean(h.snippet), MAX_SNIPPET)
            val answer = h.answer?.let { truncate(clean(it), MAX_ANSWER) }?.takeIf { it.isNotEmpty() }
            val extras = h.extraSnippets.asSequence()
                .map { truncate(clean(it), MAX_SNIPPET) }
                .filter { it.isNotEmpty() && it != snippet }
                .distinct()
                .take(MAX_EXTRA_SNIPPETS)
                .toList()
            // The URL, not an arbitrary provider label, is the source identity.
            val source = hostOf(url)
            val result = Result(
                id = "", // assigned after ranking
                type = h.type,
                title = title,
                url = url,
                source = source,
                snippet = snippet,
                question = clean(h.question).take(MAX_TITLE).takeIf { it.isNotEmpty() },
                answer = answer,
                language = h.language?.takeIf { it.isNotBlank() },
                pageAge = pageAge(h.pageAge),
                extraSnippets = extras
            )
            val previous = candidates[key]
            candidates[key] = if (previous == null) result else {
                // Keep the highest-ranked URL/title, but don't discard useful FAQ answers or
                // additional excerpts just because the same page occurs in several sections.
                val mergedSnippet = previous.snippet.ifEmpty { result.snippet }
                previous.copy(
                    snippet = mergedSnippet,
                    question = if (previous.answer == null) result.question else previous.question,
                    answer = previous.answer ?: result.answer,
                    language = previous.language ?: result.language,
                    pageAge = previous.pageAge ?: result.pageAge,
                    extraSnippets = (previous.extraSnippets + result.extraSnippets + result.snippet)
                        .filter { it.isNotEmpty() && it != mergedSnippet }
                        .distinct().take(MAX_EXTRA_SNIPPETS)
                )
            }
        }

        val scored = candidates.values.mapIndexed { index, result ->
            // A provider-rank prior prevents shallow keyword matching from replacing the
            // engine's relevance model. Supplemental sections must earn their place.
            val prior = 2.0 / (1.0 + index * 0.25)
            (prior + score(result, terms, effectiveQuery, preferRecent)) to result
        }
        return scored.sortedByDescending { it.first }
            .take(limit.coerceIn(0, SearchRequest.MAX_COUNT))
            .mapIndexed { i, (_, r) -> r.copy(id = "search_${i + 1}") }
    }

    fun toJson(response: SearchResponse, limit: Int, preferRecent: Boolean = false): JsonObject {
        val results = results(response, limit, preferRecent)
        val available = response.hits.filter { clean(it.title).isNotEmpty() }
            .mapNotNull { it.url?.trim()?.let(::dedupeKey) }.distinct().size
        return buildJsonObject {
            put("query", response.query)
            response.alteredQuery?.let { put("correctedQuery", it) }
            put("results", buildJsonArray {
                results.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("type", r.type.name.lowercase())
                        put("title", r.title)
                        put("url", r.url)
                        if (r.source.isNotEmpty()) put("source", r.source)
                        r.question?.let { put("question", it) }
                        r.answer?.let { put("answer", it) }
                        put("snippet", r.snippet)
                        r.language?.let { put("language", it) }
                        r.pageAge?.let { put("pageAge", it) }
                        if (r.extraSnippets.isNotEmpty()) put("extraSnippets", buildJsonArray { r.extraSnippets.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
            response.relatedQueries.map { clean(it).take(200) }
                .filter { it.isNotEmpty() && !it.equals(response.query, ignoreCase = true) }
                .distinctBy { it.lowercase() }.take(MAX_RELATED).takeIf { it.isNotEmpty() }?.let { rq ->
                put("relatedQueries", buildJsonArray { rq.forEach { add(JsonPrimitive(it)) } })
            }
            put("hasMore", response.moreResultsAvailable || available > results.size)
        }
    }

    // --- relevance re-ranking --------------------------------------------

    /**
     * Small adjustments to the provider-rank prior. Combines:
     *  - query-term overlap in the title (weighted) and snippet,
     *  - a small bonus for an answer excerpt or additional useful context,
     *  - a freshness nudge only when recent pages were explicitly requested,
     *  - a penalty for hits with almost no usable text.
     * The point is only to reorder within one provider's candidates, never to invent results.
     */
    internal fun score(r: Result, terms: Set<String>, rawQuery: String, preferRecent: Boolean = false): Double {
        var s = 0.0
        val title = r.title.lowercase()
        val body = (r.snippet + " " + r.extraSnippets.joinToString(" ") + " " +
            (r.question ?: "") + " " + (r.answer ?: "")).lowercase()

        if (terms.isNotEmpty()) {
            val inTitle = matchingTerms(title, terms)
            val inBody = matchingTerms(body, terms)
            s += 1.5 * inTitle / terms.size
            s += 0.75 * inBody / terms.size
            // Exact phrase match in the title is a strong signal.
            val phrase = rawQuery.trim().removeSurrounding("\"").lowercase()
            if (phrase.isNotEmpty() && title.contains(phrase)) s += 0.25
        }

        if (r.answer != null) s += 0.15
        if (preferRecent) s += freshnessBonus(r.pageAge)

        // Richer hits (extra snippets / a real answer) are more useful to answer from directly.
        if (r.extraSnippets.isNotEmpty()) s += 0.15
        // Thin, near-empty results sink.
        if (r.snippet.length < 40 && r.answer == null && r.extraSnippets.isEmpty()) s -= 0.35

        return s
    }

    /** Split a query into meaningful lowercased terms, dropping operators and short stopwords. */
    internal fun queryTerms(query: String): Set<String> {
        val stripped = query
            .replace(Regex("\\b(site|filetype|intitle|inurl):\\S+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(AND|OR|NOT)\\b"), " ")
            .replace(Regex("(?:^|\\s)-\\S+"), " ") // exclude words, not hyphens inside terms
        // Quoted phrases and +required terms remain searchable; C++/C# retain their suffixes.
        return TOKEN.findAll(stripped.lowercase()).map { it.value }
            .filter { (it.length >= 2 || it == "c" || it == "r") && it !in STOPWORDS }
            .toSet()
    }

    private fun matchingTerms(text: String, terms: Set<String>): Int {
        val words = TOKEN.findAll(text).map { it.value }.toSet()
        return terms.count { term ->
            // Korean/Japanese/Chinese text may have no word spaces or attach particles.
            if (term.any { it.code in 0x3040..0x30ff || it.code in 0x3400..0x9fff || it.code in 0xac00..0xd7af }) {
                text.contains(term)
            } else term in words
        }
    }

    /** Undated pages are neutral; don't guess dates from relative strings or punish old references. */
    private fun freshnessBonus(pageAge: String?): Double {
        val date = pageAge?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return 0.0
        val days = ChronoUnit.DAYS.between(date, LocalDate.now())
        return when {
            days in 0L..7L -> 0.3
            days in 8L..30L -> 0.2
            days in 31L..365L -> 0.1
            else -> 0.0
        }
    }

    // --- cleaning helpers ------------------------------------------------

    /** Canonical form for duplicate detection; null when not a usable http(s) URL. */
    fun dedupeKey(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return null
        if (u.rawUserInfo != null || host.isEmpty()) return null
        val port = u.port.takeIf { it != -1 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
            ?.let { ":$it" }.orEmpty()
        val path = (u.normalize().rawPath ?: "").trimEnd('/')
        val query = u.rawQuery?.split('&')?.filter { part ->
            val name = runCatching { URLDecoder.decode(part.substringBefore('='), "UTF-8") }
                .getOrDefault(part.substringBefore('=')).lowercase()
            !name.startsWith("utm_") && name !in TRACKING_PARAMS
        }?.joinToString("&")?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return "$host$port$path$query"
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull().orEmpty()

    /** "2026-09-25T08:00:00" → "2026-09-25"; other formats kept as given. */
    fun pageAge(v: String?): String? {
        val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Regex("^\\d{4}-\\d{2}-\\d{2}").find(t)?.value ?: t.take(40)
    }

    /** Strip tags, decode entities, collapse whitespace. */
    fun clean(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var t = s.replace(Regex("<[^>]*>"), " ")
        t = decodeEntities(t)
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.take(max.coerceAtLeast(0)).let {
            if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
        }
        val space = cut.lastIndexOf(' ')
        return (if (space > max * 0.6) cut.take(space) else cut).trimEnd() + "…"
    }

    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "#39" to "'")

    internal fun decodeEntities(s: String): String = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+|#39);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> decodeCodePoint(e.drop(2).toIntOrNull(16)) ?: m.value
            e.startsWith("#") -> decodeCodePoint(e.drop(1).toIntOrNull()) ?: m.value
            else -> named[e.lowercase()] ?: m.value
        }
    }

    private fun decodeCodePoint(value: Int?): String? = value
        ?.takeIf { Character.isValidCodePoint(it) && it !in 0xd800..0xdfff }
        ?.let { String(Character.toChars(it)) }

    // Kept intentionally small: only very common noise words, and none that double as
    // meaningful search terms in tech queries (e.g. "or"/"and" are already stripped as operators).
    private val STOPWORDS = setOf(
        "the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "is", "are",
        "how", "what", "why", "when", "where", "with", "vs", "do", "does", "my", "i"
    )

    private val TOKEN = Regex("[\\p{L}\\p{N}]+[+#]*")
    private val TRACKING_PARAMS = setOf("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid")
}
