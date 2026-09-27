package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.tools.search.BraveSearchProvider
import com.sleepysoong.hoard.tools.search.SearchProvider

/**
 * The tools a reply may use, from Settings. web_fetch needs nothing; web_search
 * needs the user's own Brave Search API key (never bundled with the app).
 * Tests override [override] to inject fakes.
 */
object WebTools {
    @Volatile var override: ToolRegistry? = null

    fun registry(enabled: Boolean, braveApiKey: String, searchProvider: SearchProvider? = null): ToolRegistry? {
        override?.let { return it }
        if (!enabled) return null
        val tools = buildList<Tool> {
            val provider = searchProvider ?: braveApiKey.trim().takeIf { it.isNotEmpty() }?.let { BraveSearchProvider(it) }
            if (provider != null) add(WebSearchTool(provider))
            add(WebFetchTool())
        }
        return ToolRegistry(tools)
    }
}
