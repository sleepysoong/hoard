package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.tools.fetch.FetchException
import com.sleepysoong.hoard.tools.fetch.HtmlExtractor
import com.sleepysoong.hoard.tools.fetch.PageFetcher
import com.sleepysoong.hoard.tools.search.SearchNormalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * `web_fetch`: read a page. Downloads the URL (public http(s) only, see
 * [PageFetcher]), extracts the main content of HTML as Markdown, passes
 * plain-text types through, and caps the length.
 *
 * Output: `{"url","finalUrl"?,"status","title","contentType","content","truncated"}` —
 * `finalUrl` only when a redirect happened.
 */
class WebFetchTool(
    private val fetcher: PageFetcher = PageFetcher(),
    private val maxChars: Int = DEFAULT_MAX_CHARS
) : Tool {
    override val name = NAME
    override val description =
        "Download a web page and return its main readable content as Markdown (navigation, ads, scripts removed). " +
            "Use it to actually read a page — a web_search result's url or a URL the user gave — before relying on its contents. " +
            "Public http(s) HTML and text pages only; long pages are truncated."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "Absolute http(s) URL of the page to read.")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("url")) })
        put("additionalProperties", false)
    }

    override fun label(args: JsonObject): String {
        val url = args.string("url").orEmpty()
        val short = runCatching { URI(url).let { (it.host ?: "") + (it.rawPath ?: "") } }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        return "페이지 읽기 · " + short.take(80)
    }

    override fun summarize(output: JsonObject): String {
        val title = output.string("title").orEmpty().ifBlank { "(제목 없음)" }
        val len = output.string("content")?.length ?: 0
        val cut = (output["truncated"] as? JsonPrimitive)?.content == "true"
        val redirected = output.string("finalUrl")?.let { " → $it" }.orEmpty()
        return "$title · ${len}자" + (if (cut) " (잘림)" else "") + redirected
    }

    override suspend fun execute(args: JsonObject): JsonObject {
        val url = args.string("url")?.trim().orEmpty()
        if (url.isEmpty()) throw ToolException("url is required")
        val page = try {
            fetcher.fetch(url)
        } catch (e: FetchException) {
            throw ToolException(e.message ?: "fetch failed")
        }
        if (page.status >= 400) throw ToolException("HTTP ${page.status} from ${page.finalUrl}")

        val type = page.contentType.ifEmpty { sniff(page.body) }
        val (title, text) = when {
            type == "text/html" || type == "application/xhtml+xml" -> {
                val x = HtmlExtractor.extract(page.body, page.charset, page.finalUrl)
                x.title to x.content
            }
            type.startsWith("text/") || type in TEXT_TYPES || type.endsWith("+json") || type.endsWith("+xml") -> {
                val cs = page.charset?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
                "" to page.body.toString(cs).trim()
            }
            else -> throw ToolException("unsupported content type \"$type\" (only HTML and text pages can be read)")
        }
        val truncated = text.length > maxChars || page.bodyTruncated
        val content = if (text.length > maxChars) cutAtBoundary(text, maxChars) +
            "\n\n[… truncated: ${text.length - maxChars} more characters]" else text
        return buildJsonObject {
            put("url", url)
            if (page.finalUrl != url && page.finalUrl.trimEnd('/') != url.trimEnd('/')) put("finalUrl", page.finalUrl)
            put("status", page.status)
            put("title", SearchNormalizer.clean(title))
            put("contentType", type)
            put("content", content.ifEmpty { "(no readable text content)" })
            put("truncated", truncated)
        }
    }

    private fun sniff(body: ByteArray): String {
        val head = body.take(512).toByteArray().decodeToString().trimStart().lowercase()
        return if (head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<head")) "text/html" else "text/plain"
    }

    private fun cutAtBoundary(text: String, max: Int): String {
        val cut = text.take(max)
        val para = cut.lastIndexOf("\n\n")
        return if (para > max * 0.7) cut.take(para) else cut
    }

    companion object {
        const val NAME = "web_fetch"
        /** ~5k tokens of Latin text; enough for an article, small next to a 128k context. */
        const val DEFAULT_MAX_CHARS = 20_000
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml", "application/yaml")
    }
}
