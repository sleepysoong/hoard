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
     * Internal only (not in the tool schema): extra excerpts per result. Off by default —
     * web_fetch reads pages, so search should not flood the context.
     */
    val extraSnippets: Boolean = false
) {
    companion object {
        /** 10 candidates: the model picks the relevant ones and web_fetches them. */
        const val DEFAULT_COUNT = 10
        const val MAX_COUNT = 10
    }
}

/** Raw-ish provider result before normalization (may contain duplicates/blank fields). */
data class SearchHit(
    val title: String?,
    val url: String?,
    val snippet: String?,
    val language: String? = null,
    /** Provider's page date, any format (normalized later). */
    val pageAge: String? = null,
    val extraSnippets: List<String> = emptyList()
)

data class SearchResponse(
    val query: String,
    /** The query the provider actually ran when it corrected the input (spellcheck), else null. */
    val alteredQuery: String? = null,
    val hits: List<SearchHit>,
    val moreResultsAvailable: Boolean = false
)

class SearchException(message: String, val retryable: Boolean = false) : Exception(message)
