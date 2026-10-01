package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.tools.search.Freshness
import com.sleepysoong.hoard.tools.search.SearchException
import com.sleepysoong.hoard.tools.search.SearchNormalizer
import com.sleepysoong.hoard.tools.search.SearchProvider
import com.sleepysoong.hoard.tools.search.SearchRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * `web_search`: discover pages. Returns titles, URLs and short snippets only —
 * reading a page is `web_fetch`'s job (Search = discover, Fetch = read).
 */
class WebSearchTool(private val provider: SearchProvider) : Tool {
    override val parallelSafe = true
    override val name = NAME
    override val guidance = "Use web_search for current or unfamiliar facts. It returns ranked results (title, url, source, snippets) — " +
        "including direct answer cards (type=faq) and forum threads (type=discussion) when relevant — plus relatedQueries suggestions. " +
        "Snippets are summaries, not full pages: web_fetch the best urls when you need the actual contents. " +
        "If results are weak, try a relatedQuery or refine: more specific keywords, English technical terms, " +
        "site:domain, \"exact phrase\", -excluded words, AND/OR/NOT, filetype:pdf."
    override val description =
        "Search the public web and return up to 10 results, combining the search engine's ranking with query relevance. " +
            "Each result has a title, url, source (domain), a snippet and often extraSnippets (extra excerpts from the page). " +
            "Results are typed: type=web (normal page), type=news (recent article), type=faq (a direct question/answer card, with an 'answer' field), " +
            "type=discussion (a forum/community thread). A page may also have a question/answer pair from a matching FAQ. " +
            "The response also includes relatedQueries (follow-up query ideas) and hasMore. " +
            "Snippets and extraSnippets are summaries, NOT the full page: do not claim to have read a page from them. " +
            "When you need the actual contents, pick the best urls and call web_fetch on them (several in the same turn run in parallel). " +
            "FAQ answers and snippets are unverified source excerpts, not authoritative answers; verify important claims against primary sources. " +
            "If results are few or off-topic, use one of the relatedQueries or search again with a better query: " +
            "more specific keywords, English technical terms, site:example.com, \"exact phrase\", -excluded, AND / OR / NOT, filetype:pdf. " +
            "Cite sources by url."

    override val parameters: JsonObject = toolParameters {
        string("query", "Search query. Operators: site:example.com, \"exact phrase\", -exclude, AND, OR, NOT, filetype:pdf.", required = true)
        integer("count", "Number of results to return (default ${SearchRequest.DEFAULT_COUNT}, max ${MAX_RESULTS}).", minimum = 1, maximum = MAX_RESULTS.toLong())
        string("freshness", "Only pages from the last day/week/month/year.", enum = listOf("day", "week", "month", "year"))
        string("country", "Only for clearly local queries (e.g. a Seoul restaurant): 2-letter country code like KR. Omit otherwise.")
        string("language", "Only when results must be in one language: 2-letter code like ko, en. Omit otherwise (don't guess from the user's language).")
    }

    override val title = "웹 검색"

    override fun subject(args: JsonObject) = args.string("query").orEmpty()

    override fun summarize(output: JsonObject): String {
        val results = (output["results"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("title") }
        val corrected = output.string("correctedQuery")?.let { " (보정: $it)" }.orEmpty()
        return "결과 ${results.size}개$corrected" + if (results.isEmpty()) "" else ": " + results.joinToString(" · ") { it.take(40) }
    }

    override suspend fun execute(args: JsonObject): JsonObject {
        val (request, limit) = parse(args)
        val response = try {
            provider.search(request)
        } catch (e: SearchException) {
            throw ToolException(e.message ?: "search failed")
        }
        return SearchNormalizer.toJson(response, limit, preferRecent = request.freshness != null)
    }

    /**
     * Returns the provider request (which over-fetches candidates so the re-ranker has
     * room to work) and the number of results to actually return to the model.
     */
    internal fun parse(args: JsonObject): Pair<SearchRequest, Int> {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) throw ToolException("query is required")
        if (query.length > 400) throw ToolException("query is too long (max 400 characters)")
        val limit = (args.number("count") ?: SearchRequest.DEFAULT_COUNT).coerceIn(1, MAX_RESULTS)
        // Ask the provider for a few more candidates than we show, so ranking can reorder/drop weak hits.
        val candidates = (limit + CANDIDATE_HEADROOM).coerceAtMost(SearchRequest.MAX_COUNT)
        val freshness = try {
            Freshness.parse(args.string("freshness"))
        } catch (e: IllegalArgumentException) {
            throw ToolException(e.message!!)
        }
        val country = args.string("country")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (!Regex("^[A-Za-z]{2}$").matches(it)) throw ToolException("country must be a 2-letter code like KR")
        }
        val language = args.string("language")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (!Regex("^[A-Za-z]{2}(-[A-Za-z]{2,4})?$").matches(it)) throw ToolException("language must be a code like ko or en")
        }
        return SearchRequest(query = query, count = candidates, freshness = freshness, country = country, language = language) to limit
    }

    companion object {
        const val NAME = "web_search"
        /** Most results the model ever gets back. */
        const val MAX_RESULTS = 10
        /** Extra candidates fetched beyond the shown count to feed the re-ranker. */
        const val CANDIDATE_HEADROOM = 8
    }
}
