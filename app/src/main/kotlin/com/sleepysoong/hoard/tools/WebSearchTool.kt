package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.tools.search.Freshness
import com.sleepysoong.hoard.tools.search.SearchException
import com.sleepysoong.hoard.tools.search.SearchNormalizer
import com.sleepysoong.hoard.tools.search.SearchProvider
import com.sleepysoong.hoard.tools.search.SearchRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `web_search`: discover pages. Returns titles, URLs and short snippets only —
 * reading a page is `web_fetch`'s job (Search = discover, Fetch = read).
 */
class WebSearchTool(private val provider: SearchProvider) : Tool {
    override val parallelSafe = true
    override val name = NAME
    override val guidance = "Use web_search for current or unfamiliar facts. It returns up to 10 results with snippets only, not page contents. " +
        "If results are weak, search again with a refined query: more specific keywords, English technical terms, " +
        "site:domain, \"exact phrase\", -excluded words, AND/OR/NOT, filetype:pdf."
    override val description =
        "Search the public web and return up to 10 relevant pages (title, url, short snippet). " +
            "Search results contain only summaries/snippets, NOT the page contents: do not claim to have read a page " +
            "from its snippet. Pick the most relevant urls and call web_fetch on them (several in the same turn run in parallel) " +
            "when the actual contents are needed. If results are few or off-topic, search again with a better query: " +
            "more specific keywords, English technical terms, site:example.com, \"exact phrase\", -excluded, AND / OR / NOT, filetype:pdf. " +
            "Cite sources by url."

    override val parameters: JsonObject = toolParameters {
        string("query", "Search query. Operators: site:example.com, \"exact phrase\", -exclude, AND, OR, NOT, filetype:pdf.", required = true)
        integer("count", "Number of results (default and max ${SearchRequest.MAX_COUNT}).", minimum = 1, maximum = SearchRequest.MAX_COUNT.toLong())
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
        val request = parse(args)
        val response = try {
            provider.search(request)
        } catch (e: SearchException) {
            throw ToolException(e.message ?: "search failed")
        }
        return SearchNormalizer.toJson(response, request.count)
    }

    internal fun parse(args: JsonObject): SearchRequest {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) throw ToolException("query is required")
        if (query.length > 400) throw ToolException("query is too long (max 400 characters)")
        val count = (args.number("count") ?: SearchRequest.DEFAULT_COUNT).coerceIn(1, SearchRequest.MAX_COUNT)
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
        return SearchRequest(query = query, count = count, freshness = freshness, country = country, language = language)
    }

    companion object {
        const val NAME = "web_search"
    }
}
