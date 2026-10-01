package com.sleepysoong.hoard.tools.search

/**
 * Provider-neutral web search. [com.sleepysoong.hoard.tools.WebSearchTool] talks
 * only to this, so Brave can be swapped (e.g. a `SerperSearchProvider` for
 * google.serper.dev, Tavily, SearXNG) without touching the tool schema the model
 * sees: map [SearchRequest] to the provider's query and its results to [SearchHit].
 */
interface SearchProvider {
    /** Provider name for logs/UI ("brave"). */
    val id: String

    /** @throws SearchException on a failure worth telling the model about. */
    suspend fun search(request: SearchRequest): SearchResponse
}

enum class Freshness { Day, Week, Month, Year;
    companion object {
        fun parse(v: String?): Freshness? = when (v?.trim()?.lowercase()) {
            null, "" -> null
            "day" -> Day
            "week" -> Week
            "month" -> Month
            "year" -> Year
            else -> throw IllegalArgumentException("freshness must be one of day, week, month, year")
        }
    }
}

data class SearchRequest(
    val query: String,
    val count: Int = DEFAULT_COUNT,
    val freshness: Freshness? = null,
    /**
     * ISO 3166-1 alpha-2, e.g. "KR". Only when the query is clearly regional —
     * never filled in from the device locale (that narrows results for everything else).
     */
    val country: String? = null,
    /** ISO 639-1, e.g. "ko". Only when results in one language are clearly wanted. */
    val language: String? = null,
    /**
     * A few extra excerpts per result, giving the model more context per hit so it can
     * often answer without a web_fetch round-trip. On by default; the normalizer keeps
     * the count bounded so it never floods the context.
     */
    val extraSnippets: Boolean = true
) {
    companion object {
        /**
         * Candidates asked from the provider. We over-fetch a little beyond what the model
         * finally sees so the relevance re-ranker has room to reorder and drop weak hits.
         */
        const val DEFAULT_COUNT = 10
        const val MAX_COUNT = 20
    }
}

/** What kind of result a hit came from — lets the model tell a Q&A card from a forum thread. */
enum class ResultType { Web, News, Faq, Discussion }

/** Raw-ish provider result before normalization (may contain duplicates/blank fields). */
data class SearchHit(
    val title: String?,
    val url: String?,
    val snippet: String?,
    val language: String? = null,
    /** Provider's page date, any format (normalized later). */
    val pageAge: String? = null,
    val extraSnippets: List<String> = emptyList(),
    /** Which section of the provider response this came from. */
    val type: ResultType = ResultType.Web,
    /** Clean host label from the provider (e.g. "kotlinlang.org"), else derived from the URL. */
    val source: String? = null,
    /** For FAQ hits: the matched question, if any. */
    val question: String? = null,
    /** For FAQ hits: the provider's direct answer, if any. */
    val answer: String? = null
)

data class SearchResponse(
    val query: String,
    /** The query the provider actually ran when it corrected the input (spellcheck), else null. */
    val alteredQuery: String? = null,
    val hits: List<SearchHit>,
    val moreResultsAvailable: Boolean = false,
    /** Provider-suggested follow-up queries — good hints when the first results are weak. */
    val relatedQueries: List<String> = emptyList()
)

class SearchException(message: String, val retryable: Boolean = false) : Exception(message)
