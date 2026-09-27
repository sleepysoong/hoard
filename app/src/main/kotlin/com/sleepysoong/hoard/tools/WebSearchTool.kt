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
    override val name = NAME
    override val description =
        "Search the public web and return relevant pages (title, url, short snippet). " +
            "Search results contain only summaries/snippets, NOT the page contents: do not claim to have read a page " +
            "from its snippet. Use web_fetch with a result's url when the actual contents of a page are needed. " +
            "Cite sources by url."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Search query. Search operators such as site:, \"exact phrase\" and -exclude are supported.")
            }
            putJsonObject("count") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", SearchRequest.MAX_COUNT)
                put("description", "Number of results (default ${SearchRequest.DEFAULT_COUNT}, max ${SearchRequest.MAX_COUNT}).")
            }
            putJsonObject("freshness") {
                put("type", "string")
                putJsonArray("enum") { listOf("day", "week", "month", "year").forEach { add(JsonPrimitive(it)) } }
                put("description", "Only pages from the last day/week/month/year.")
            }
            putJsonObject("country") {
                put("type", "string")
                put("description", "2-letter country code to target, e.g. KR, US.")
            }
            putJsonObject("language") {
                put("type", "string")
                put("description", "2-letter content language, e.g. ko, en.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
        put("additionalProperties", false)
    }

    override fun label(args: JsonObject) = "웹 검색 · " + (args.string("query") ?: "")

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
