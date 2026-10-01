package com.sleepysoong.hoard.tools

import com.sleepysoong.hoard.browser.BrowserAction
import com.sleepysoong.hoard.browser.BrowserException
import com.sleepysoong.hoard.browser.BrowserPreviews
import com.sleepysoong.hoard.browser.BrowserService
import com.sleepysoong.hoard.browser.RemoteBrowser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `browser_use`: drive a real Chrome on the user's VPS (over SSH + CDP) — one tool,
 * one `action` per call. Pages are addressed by the element ids of the latest state,
 * never by CSS selectors. All transport lives behind [RemoteBrowser].
 *
 * Search = discover (web_search), Fetch = read (web_fetch), Browser = interact.
 */
class BrowserUseTool(
    private val browser: RemoteBrowser,
    /** Where screenshots are saved (the Hoard workspace), or null to not save them. */
    private val screenshotDir: File? = null,
    /** How a saved file is shown to the model (workspace-relative path). */
    private val displayPath: (File) -> String = { it.path },
    private val sessionId: String? = null
) : Tool {
    override val name = NAME
    override val title = "브라우저"

    override val description =
        "Control a real Chrome browser running on the user's server (it keeps its own logins and cookies). " +
            "One action per call: open (url, new_tab?), state, click (element_id), type (element_id, text, clear?, submit?), " +
            "press (key), scroll (direction, amount?, element_id?), back, forward, reload, tabs, switch_tab (tab_id), " +
            "close_tab (tab_id?), screenshot. Every action except tabs/screenshot returns the page state: url, title, " +
            "visible text, and interactive elements with numeric ids — act on those ids (never CSS selectors or XPath). " +
            "Ids are only valid for the latest state: after a navigation or page update use the ids from the newest result. " +
            "Same-site link hrefs are paths (/about); open accepts such a path for the current site. " +
            "type on a <select> chooses the option with that text. screenshot saves an image on the device (you only get its path)."

    override val guidance =
        "Web tools, in order: web_search to discover URLs, web_fetch to read a known page, browser_use only for real " +
            "interaction — pages that need JavaScript (SPAs, dynamic content), clicking, filling forms, logged-in sessions, " +
            "multi-step navigation, tabs, downloads. Don't use browser_use just to read a document web_fetch can read. " +
            "browser_use is one real, shared Chrome with the user's logins: act step by step from the latest state, and never " +
            "submit purchases, payments, posts, messages or account/security changes unless the user asked for exactly that. " +
            "If a page asks for a login, CAPTCHA or 2FA you can't complete, stop and tell the user (they can finish it over VNC)."

    override val parameters: JsonObject = toolParameters {
        string("action", "What to do.", required = true, enum = ACTIONS)
        string("url", "open: the page to open (http/https URL, or a /path on the current site).")
        boolean("new_tab", "open: open in a new tab instead of the current one.")
        integer("element_id", "click / type / scroll: an element id from the latest state.", minimum = 1)
        string("text", "type: the text to enter (for a <select>: the option to choose).")
        boolean("clear", "type: replace the field's current content (default true); false appends.")
        boolean("submit", "type: press Enter after typing.")
        string("key", "press: a key or combination, e.g. Enter, Escape, Tab, ArrowDown, PageDown, Backspace, Control+A.")
        string("direction", "scroll: up or down.", enum = listOf("up", "down"))
        integer("amount", "scroll: how many screens (default 1).", minimum = 1, maximum = 10)
        integer("tab_id", "switch_tab / close_tab: a tab id from tabs (close_tab defaults to the current tab).", minimum = 1)
    }

    override fun subject(args: JsonObject): String {
        val action = args.string("action").orEmpty()
        val label = LABELS[action] ?: action
        val detail = when (action) {
            "open" -> args.string("url")?.let(::shortUrl).orEmpty() + if (args.bool("new_tab") == true) " (새 탭)" else ""
            "click" -> args.number("element_id")?.let { "#$it" }.orEmpty()
            "type" -> (args.number("element_id")?.let { "#$it " }.orEmpty() + "\"" + args.string("text").orEmpty().take(60) + "\"") +
                if (args.bool("submit") == true) " ↵" else ""
            "press" -> args.string("key").orEmpty()
            "scroll" -> (if (args.string("direction") == "up") "위로" else "아래로") + (args.number("amount")?.takeIf { it > 1 }?.let { " ${it}화면" }.orEmpty())
            "switch_tab", "close_tab" -> args.number("tab_id")?.let { "탭 $it" }.orEmpty()
            else -> ""
        }
        return listOf(label, detail).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * An older page state once a newer browser_use result exists: its element ids are no
     * longer valid, so the (large) element list goes; url, title, text and events stay.
     */
    override fun supersede(output: JsonObject): JsonObject? {
        if (output["elements"] !is JsonArray) return null
        return JsonObject(output.filterKeys { it != "elements" && it != "more_elements" } + buildJsonObject {
            put("elements", "outdated (a later browser_use result has the current ids)")
        })
    }

    override fun summarize(output: JsonObject): String {
        output.string("saved")?.let { return "저장: $it" }
        (output["tabs"] as? JsonArray)?.let { return "탭 ${it.size}개" }
        val title = output.string("title").orEmpty().ifBlank { output.string("url")?.let(::shortUrl).orEmpty() }
        val elements = (output["elements"] as? JsonArray)?.size
        return listOfNotNull(title.take(60).ifBlank { null }, elements?.let { "요소 ${it}개" }).joinToString(" · ")
    }

    override suspend fun execute(args: JsonObject): JsonObject {
        val action = parse(args)
        sessionId?.let { BrowserPreviews.show(it, browser) }
        val result = try {
            browser.execute(action)
        } catch (e: BrowserException) {
            throw ToolException(e.message ?: "browser error")
        }
        val shot = result.screenshot ?: return result.json
        val saved = screenshotDir?.let { save(it, shot) }
        return JsonObject(result.json + buildJsonObject {
            put("bytes", shot.size)
            if (saved != null) {
                put("saved", saved)
                put("note", "Saved on the user's device; the image itself is not shown to you. Use action=state to read the page.")
            }
        })
    }

    internal fun parse(args: JsonObject): BrowserAction {
        val action = args.string("action")?.trim().orEmpty()
        fun id(key: String = "element_id") = args.number(key)?.takeIf { it >= 1 } ?: throw ToolException("$key is required for $action")
        return try {
            when (action) {
                "open" -> {
                    val url = args.string("url")?.trim().orEmpty()
                    // A same-site path is resolved (and checked) against the current page by the service.
                    BrowserAction.Open(if (url.startsWith("/") && !url.startsWith("//")) url else BrowserService.checkUrl(url), newTab = args.bool("new_tab") == true)
                }
                "state" -> BrowserAction.State
                "click" -> BrowserAction.Click(id())
                "type" -> BrowserAction.Type(
                    id(),
                    text = args.string("text") ?: throw ToolException("text is required for type"),
                    clear = args.bool("clear") ?: true,
                    submit = args.bool("submit") == true
                )
                "press" -> BrowserAction.Press(args.requireString("key").trim())
                "scroll" -> BrowserAction.Scroll(
                    down = when (args.string("direction")?.lowercase()) {
                        null, "", "down" -> true
                        "up" -> false
                        else -> throw ToolException("direction must be up or down")
                    },
                    screens = (args.number("amount") ?: 1).coerceIn(1, 10).toDouble(),
                    elementId = args.number("element_id")?.takeIf { it >= 1 }
                )
                "back" -> BrowserAction.Back
                "forward" -> BrowserAction.Forward
                "reload" -> BrowserAction.Reload
                "tabs" -> BrowserAction.Tabs
                "switch_tab" -> BrowserAction.SwitchTab(id("tab_id"))
                "close_tab" -> BrowserAction.CloseTab(args.number("tab_id")?.takeIf { it >= 1 })
                "screenshot" -> BrowserAction.Screenshot
                "" -> throw ToolException("action is required: " + ACTIONS.joinToString())
                else -> throw ToolException("unknown action \"$action\"; use one of: " + ACTIONS.joinToString())
            }
        } catch (e: BrowserException) {
            throw ToolException(e.message ?: "invalid arguments")
        }
    }

    private fun save(dir: File, bytes: ByteArray): String? = runCatching {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val file = File(dir, "screenshot-$stamp.jpg")
        file.writeBytes(bytes)
        // Keep the folder small: the newest 30 screenshots.
        dir.listFiles { f -> f.name.startsWith("screenshot-") }?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
        displayPath(file)
    }.getOrNull()

    private fun shortUrl(url: String): String =
        runCatching { URI(url).let { (it.host ?: "") + (it.rawPath ?: "").trimEnd('/') } }.getOrNull()?.takeIf { it.isNotBlank() }?.take(80) ?: url.take(80)

    companion object {
        const val NAME = "browser_use"
        val ACTIONS = listOf("open", "state", "click", "type", "press", "scroll", "back", "forward", "reload", "tabs", "switch_tab", "close_tab", "screenshot")
        private val LABELS = mapOf(
            "open" to "열기", "state" to "상태", "click" to "클릭", "type" to "입력", "press" to "키", "scroll" to "스크롤",
            "back" to "뒤로", "forward" to "앞으로", "reload" to "새로고침", "tabs" to "탭 목록", "switch_tab" to "탭 전환",
            "close_tab" to "탭 닫기", "screenshot" to "스크린샷"
        )
    }
}
