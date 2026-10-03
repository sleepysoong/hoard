package com.sleepysoong.hoard.browser

import com.sleepysoong.hoard.browser.CdpConnection.Companion.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** One browser_use action (validated by the tool; the service only executes). */
sealed interface BrowserAction {
    val name: String

    data class Open(val url: String, val newTab: Boolean = false) : BrowserAction { override val name = "open" }
    data object State : BrowserAction { override val name = "state" }
    data class Click(val elementId: Int) : BrowserAction { override val name = "click" }
    data class Type(val elementId: Int, val text: String, val clear: Boolean = true, val submit: Boolean = false) : BrowserAction { override val name = "type" }
    data class Press(val key: String) : BrowserAction { override val name = "press" }
    data class Scroll(val down: Boolean, val screens: Double = 1.0, val elementId: Int? = null) : BrowserAction { override val name = "scroll" }
    data object Back : BrowserAction { override val name = "back" }
    data object Forward : BrowserAction { override val name = "forward" }
    data object Reload : BrowserAction { override val name = "reload" }
    data object Tabs : BrowserAction { override val name = "tabs" }
    data class SwitchTab(val tabId: Int) : BrowserAction { override val name = "switch_tab" }
    data class CloseTab(val tabId: Int? = null) : BrowserAction { override val name = "close_tab" }
    data object Screenshot : BrowserAction { override val name = "screenshot" }
}

/** An action's JSON result for the model, plus the image for `screenshot`. */
class BrowserResult(val json: JsonObject, val screenshot: ByteArray? = null)

/**
 * The browser control layer: turns [BrowserAction]s into CDP commands on the VPS
 * Chrome (tabs = page targets, flat sessions, input events, page scripts).
 * Knows nothing about SSH: [RemoteBrowserManager] hands it a live [CdpConnection]
 * through [bind] (again after every reconnect).
 *
 * Every action except `tabs` and `screenshot` answers with a fresh page state, so
 * the model always holds the current element ids.
 */
class BrowserService {
    @Volatile private lateinit var cdp: CdpConnection
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class PageSession(val targetId: String, val sessionId: String) {
        @Volatile var loading = false
    }

    private data class Download(val file: String, val url: String, var state: String, var received: Long = 0, var total: Long = 0, var reported: Boolean = false)

    /** Tab ids shown to the model: small stable numbers per page target. */
    private val tabIds = LinkedHashMap<String, Int>()
    private var nextTab = 1
    @Volatile private var current: String? = null
    private val sessions = ConcurrentHashMap<String, PageSession>()
    /** Document token of the latest state per tab (element ids are only valid for it). */
    private val docTokens = ConcurrentHashMap<String, String>()
    /** Page targets opened by another tab (e.g. a target=_blank link): targetId → openerId. */
    private val openedBy = ConcurrentHashMap<String, String>()
    private val downloads = ConcurrentHashMap<String, Download>()
    @Volatile private var dialog: String? = null

    /** Uses a (new) CDP connection: page sessions belong to the old socket, tabs and ids stay. */
    suspend fun bind(connection: CdpConnection) {
        cdp = connection
        sessions.clear()
        connection.onEvent(::onEvent)
        runCatching { cdp.send("Target.setDiscoverTargets", buildJsonObject { put("discover", true) }) }
        // Chrome's own download folder on the VPS (~/Downloads), with progress events.
        runCatching { cdp.send("Browser.setDownloadBehavior", buildJsonObject { put("behavior", "default"); put("eventsEnabled", true) }) }
    }

    /** Chrome was restarted: every target id is new. */
    fun reset() {
        synchronized(tabIds) { tabIds.clear(); nextTab = 1 }
        current = null
        sessions.clear(); docTokens.clear(); openedBy.clear()
    }

    /** Re-read the real foreground tab after a person uses Chrome's desktop UI. */
    fun afterManualControl() { current = null; docTokens.clear() }

    /** Resize the real outer window, not Emulation.setDeviceMetricsOverride: CDP,
     * Chrome toolbar and VNC must all agree about the same headful browser.
     */
    suspend fun fitPreviewWindow(viewport: BrowserViewport): BrowserWindow {
        val page = page()
        val screen = eval(page, "({w:screen.availWidth,h:screen.availHeight})") as? JsonObject
            ?: throw BrowserException("원격 화면 크기를 확인하지 못했습니다")
        val maxWidth = screen.num("w").toInt().coerceIn(500, 8192)
        val maxHeight = screen.num("h").toInt().coerceIn(300, 8192)
        val scale = minOf(maxWidth.toDouble() / viewport.width, maxHeight.toDouble() / viewport.height)
        // Native Chrome has a minimum window size. Keep its toolbar usable when
        // a very small server display cannot exactly reproduce a phone's ratio.
        val width = (viewport.width * scale).toInt().coerceIn(500, maxWidth)
        val height = (viewport.height * scale).toInt().coerceIn(300, maxHeight)
        val window = cdp.send("Browser.getWindowForTarget", buildJsonObject { put("targetId", page.targetId) })
        val id = window["windowId"] ?: throw BrowserException("Chrome 창을 확인하지 못했습니다")
        cdp.send("Browser.setWindowBounds", buildJsonObject {
            put("windowId", id); putJsonObject("bounds") { put("windowState", "normal") }
        })
        cdp.send("Browser.setWindowBounds", buildJsonObject {
            put("windowId", id); putJsonObject("bounds") {
                put("left", 0); put("top", 0); put("width", width); put("height", height)
            }
        })
        delay(150) // window-manager configure events are asynchronous to the CDP reply
        val actual = cdp.send("Browser.getWindowBounds", buildJsonObject { put("windowId", id) })["bounds"] as? JsonObject
            ?: throw BrowserException("Chrome 창 크기를 확인하지 못했습니다")
        docTokens.clear() // responsive layout may have moved every previous element
        return BrowserWindow(actual.num("left").toInt(), actual.num("top").toInt(),
            actual.num("width").toInt(), actual.num("height").toInt())
    }

    suspend fun perform(action: BrowserAction): BrowserResult = when (action) {
        is BrowserAction.Open -> open(action)
        BrowserAction.State -> BrowserResult(state(page(), "state"))
        is BrowserAction.Click -> click(action.elementId)
        is BrowserAction.Type -> type(action)
        is BrowserAction.Press -> press(action.key)
        is BrowserAction.Scroll -> scroll(action)
        BrowserAction.Back -> history(-1)
        BrowserAction.Forward -> history(+1)
        BrowserAction.Reload -> reload()
        BrowserAction.Tabs -> BrowserResult(tabsJson())
        is BrowserAction.SwitchTab -> switchTab(action.tabId)
        is BrowserAction.CloseTab -> closeTab(action.tabId)
        BrowserAction.Screenshot -> screenshot()
    }

    // ---------------------------------------------------------------- actions

    private suspend fun open(a: BrowserAction.Open): BrowserResult {
        val url = if (a.url.startsWith("/") && !a.url.startsWith("//")) {
            val here = (eval(page(), "location.href") as? JsonPrimitive)?.content.orEmpty()
            val base = runCatching { URI(here) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
                ?: throw BrowserException("${a.url} is a path, but the current tab has no web page to resolve it against: give a full URL")
            checkUrl(base.resolve(a.url).toString())
        } else a.url
        val s = if (a.newTab) {
            val id = cdp.send("Target.createTarget", buildJsonObject { put("url", "about:blank") }).str("targetId")
                ?: throw BrowserException("새 탭을 만들 수 없습니다")
            tabId(id)
            current = id
            session(id)
        } else page()
        activate(s.targetId)
        val nav = cdp.send("Page.navigate", buildJsonObject { put("url", url) }, s.sessionId, timeoutMs = 45_000)
        nav.str("errorText")?.takeIf { it.isNotBlank() }?.let { throw BrowserException("페이지를 열 수 없습니다: $it ($url)") }
        settle(s, maxMs = 20_000)
        return BrowserResult(state(s, "open", note = if (a.newTab) "opened in a new tab" else null))
    }

    private suspend fun click(id: Int): BrowserResult {
        val s = page()
        val loc = locate(s, id, scroll = true)
        if (loc.flag("select")) {
            throw BrowserException("element $id is a <select>: choose an option with action=type and the option's text")
        }
        val before = openedBy.keys.toSet()
        val note: String
        if (loc.flag("hit")) {
            val x = loc.num("x"); val y = loc.num("y")
            mouse(s, "mouseMoved", x, y)
            mouse(s, "mousePressed", x, y, clicks = 1)
            mouse(s, "mouseReleased", x, y, clicks = 1)
            note = "clicked element $id"
        } else {
            // Something (a banner, a modal) sits on top of it: a DOM click still reaches it.
            (evalFn(s, PageScripts.JS_CLICK, id, docToken(s)) as? JsonObject)?.str("error")?.let { throw elementError(id, it) }
            note = "clicked element $id via JavaScript (covered by ${loc.str("covering").orEmpty().ifBlank { "another element" }})"
        }
        settle(s)
        // A link that opened a new tab: continue there (like a person would).
        val opened = openedBy.entries.firstOrNull { it.key !in before && it.value == s.targetId }?.key
        if (opened != null) {
            current = opened
            val ns = session(opened)
            activate(opened)
            settle(ns)
            return BrowserResult(state(ns, "click", note = "$note; it opened a new tab (now the current tab)"))
        }
        return BrowserResult(state(s, "click", note = note))
    }

    private suspend fun type(a: BrowserAction.Type): BrowserResult {
        val s = page()
        val doc = docToken(s)
        val focus = evalFn(s, PageScripts.FOCUS_FIELD, a.elementId, doc, a.clear) as? JsonObject ?: JsonObject(emptyMap())
        focus.str("error")?.let { throw elementError(a.elementId, it) }
        val note: String
        if (focus.str("kind") == "select") {
            val r = evalFn(s, PageScripts.CHOOSE_OPTION, a.elementId, doc, a.text) as? JsonObject ?: JsonObject(emptyMap())
            if (r.str("error") == "no_option") {
                val options = (r["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                throw BrowserException("no option \"${a.text}\" in select ${a.elementId}; options: ${options.joinToString(", ")}")
            }
            r.str("error")?.let { throw elementError(a.elementId, it) }
            note = "selected \"${r.str("chosen")}\" in element ${a.elementId}"
        } else {
            if (a.text.isNotEmpty()) {
                cdp.send("Input.insertText", buildJsonObject { put("text", a.text) }, s.sessionId)
            } else if (a.clear && (focus["had"] as? JsonPrimitive)?.booleanOrNull == true) {
                key(s, Keys.parse("Backspace"))
            }
            note = if (a.text.isEmpty()) "cleared element ${a.elementId}" else "typed into element ${a.elementId}"
        }
        if (a.submit) key(s, Keys.parse("Enter"))
        settle(s, maxMs = if (a.submit) 15_000 else 3_000)
        return BrowserResult(state(s, "type", note = note + if (a.submit) " and pressed Enter" else ""))
    }

    private suspend fun press(keyName: String): BrowserResult {
        val s = page()
        key(s, Keys.parse(keyName))
        settle(s)
        return BrowserResult(state(s, "press", note = "pressed $keyName"))
    }

    private suspend fun scroll(a: BrowserAction.Scroll): BrowserResult {
        val s = page()
        val vp = eval(s, PageScripts.VIEWPORT) as? JsonObject
        val vh = vp?.num("h")?.takeIf { it > 0 } ?: 800.0
        // The wheel scrolls whatever is under the pointer: the page, or the given element's scroll area.
        val (x, y) = if (a.elementId != null) {
            val loc = locate(s, a.elementId, scroll = false)
            loc.num("x") to loc.num("y")
        } else {
            ((vp?.num("w")?.takeIf { it > 0 } ?: 1000.0) / 2) to (vh / 2)
        }
        val dy = (vh * 0.85 * a.screens).let { if (a.down) it else -it }
        cdp.send("Input.dispatchMouseEvent", buildJsonObject {
            put("type", "mouseWheel"); put("x", x); put("y", y); put("deltaX", 0); put("deltaY", dy)
        }, s.sessionId)
        delay(450)
        settle(s, maxMs = 2_000)
        return BrowserResult(state(s, "scroll", note = "scrolled ${if (a.down) "down" else "up"} ${a.screens} screen(s)"))
    }

    private suspend fun history(step: Int): BrowserResult {
        val s = page()
        val h = cdp.send("Page.getNavigationHistory", sessionId = s.sessionId)
        val index = (h["currentIndex"] as? JsonPrimitive)?.intOrNull ?: 0
        val entries = (h["entries"] as? JsonArray).orEmpty()
        val entry = entries.getOrNull(index + step) as? JsonObject
            ?: throw BrowserException(if (step < 0) "no previous page in this tab's history" else "no next page in this tab's history")
        cdp.send("Page.navigateToHistoryEntry", buildJsonObject { put("entryId", (entry["id"] as JsonPrimitive).intOrNull ?: 0) }, s.sessionId)
        settle(s, maxMs = 15_000)
        return BrowserResult(state(s, if (step < 0) "back" else "forward"))
    }

    private suspend fun reload(): BrowserResult {
        val s = page()
        cdp.send("Page.reload", buildJsonObject { put("ignoreCache", false) }, s.sessionId)
        settle(s, maxMs = 20_000)
        return BrowserResult(state(s, "reload"))
    }

    private suspend fun switchTab(tab: Int): BrowserResult {
        val target = pages().firstOrNull { tabId(it.id) == tab }?.id
            ?: throw BrowserException("no tab $tab; tabs: " + pages().joinToString { tabId(it.id).toString() })
        current = target
        activate(target)
        val s = session(target)
        return BrowserResult(state(s, "switch_tab", note = "switched to tab $tab"))
    }

    private suspend fun closeTab(tab: Int?): BrowserResult {
        val all = pages()
        val target = if (tab == null) (current ?: all.firstOrNull()?.id) else all.firstOrNull { tabId(it.id) == tab }?.id
        target ?: throw BrowserException("no tab ${tab ?: ""} to close")
        val closedId = tabId(target)
        // Closing the last tab would close the window and quit Chrome: open a blank one first.
        if (all.size <= 1) {
            cdp.send("Target.createTarget", buildJsonObject { put("url", "about:blank") }).str("targetId")?.let { tabId(it) }
        }
        cdp.send("Target.closeTarget", buildJsonObject { put("targetId", target) })
        sessions.remove(target); docTokens.remove(target)
        synchronized(tabIds) { tabIds.remove(target) }
        if (current == target) current = null
        delay(200)
        val s = page() // falls back to another tab, or a new blank one
        activate(s.targetId)
        return BrowserResult(state(s, "close_tab", note = "closed tab $closedId"))
    }

    private suspend fun screenshot(): BrowserResult {
        val s = page()
        activate(s.targetId)
        val r = cdp.send("Page.captureScreenshot", buildJsonObject { put("format", "jpeg"); put("quality", 75) }, s.sessionId, timeoutMs = 30_000)
        val bytes = Base64.getDecoder().decode(r.str("data") ?: throw BrowserException("스크린샷을 받지 못했습니다"))
        val info = eval(s, "({url: location.href, title: document.title, w: window.innerWidth, h: window.innerHeight})") as? JsonObject
        return BrowserResult(buildJsonObject {
            put("action", "screenshot")
            put("tab", tabId(s.targetId))
            put("url", info?.str("url").orEmpty())
            put("title", info?.str("title").orEmpty())
            put("width", info?.num("w")?.toInt() ?: 0)
            put("height", info?.num("h")?.toInt() ?: 0)
        }, screenshot = bytes)
    }

    // ---------------------------------------------------------------- state

    private suspend fun state(s: PageSession, action: String, note: String? = null): JsonObject {
        val raw = try {
            evalFn(s, PageScripts.STATE, buildJsonObject { put("maxElements", MAX_ELEMENTS); put("maxText", MAX_TEXT) }) as? JsonObject
        } catch (e: CdpException) {
            // The page navigated while we read it: read the new document once it loaded.
            settle(s, maxMs = 10_000)
            evalFn(s, PageScripts.STATE, buildJsonObject { put("maxElements", MAX_ELEMENTS); put("maxText", MAX_TEXT) }) as? JsonObject
        } ?: throw BrowserException("페이지 상태를 읽을 수 없습니다")
        raw.str("doc")?.let { docTokens[s.targetId] = it }
        val count = pages().size
        return buildJsonObject {
            put("action", action)
            note?.let { put("result", it) }
            putJsonObject("tab") { put("id", tabId(s.targetId)); put("open_tabs", count) }
            for ((k, v) in raw) if (k != "doc") put(k, v)
            dialog?.let { put("dialog", it); dialog = null }
            reportDownloads()?.let { put("downloads", it) }
        }
    }

    private fun reportDownloads(): JsonArray? {
        val fresh = downloads.values.filter { !it.reported }
        if (fresh.isEmpty()) return null
        return buildJsonArray {
            for (d in fresh) {
                if (d.state != "inProgress") d.reported = true
                add(buildJsonObject {
                    put("file", d.file); put("url", d.url); put("state", d.state)
                    if (d.total > 0) put("bytes", d.total)
                    put("location", "the VPS Chrome's download folder (~/Downloads)")
                })
            }
        }
    }

    private suspend fun tabsJson(): JsonObject {
        val all = pages()
        val cur = foreground(all) ?: current?.takeIf { id -> all.any { it.id == id } } ?: all.firstOrNull()?.id
        return buildJsonObject {
            put("action", "tabs")
            put("tabs", buildJsonArray {
                for (t in all) add(buildJsonObject {
                    put("id", tabId(t.id)); put("title", t.title); put("url", t.url)
                    if (t.id == cur) put("current", true)
                })
            })
        }
    }

    // ---------------------------------------------------------------- CDP plumbing

    private data class Target(val id: String, val title: String, val url: String)

    private suspend fun pages(): List<Target> =
        (cdp.send("Target.getTargets")["targetInfos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "page" && !it.str("url").orEmpty().startsWith("devtools://") }
            .map { Target(it.str("targetId").orEmpty(), it.str("title").orEmpty(), it.str("url").orEmpty()) }
            .also { live -> synchronized(tabIds) { live.forEach { tabId(it.id) } } }

    private fun tabId(targetId: String): Int = synchronized(tabIds) { tabIds.getOrPut(targetId) { nextTab++ } }

    private suspend fun foreground(all: List<Target>): String? {
        var visible: String? = null
        for (target in all) {
            val state = try { eval(session(target.id), "({visible:document.visibilityState==='visible',focused:document.hasFocus()})") as? JsonObject }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: BrowserException) { null }
            if (state?.flag("visible") == true) {
                if (state.flag("focused")) return target.id
                if (visible == null) visible = target.id
            }
        }
        return visible
    }

    /** The current tab's session (first tab, or a new blank one, when there is none). */
    private suspend fun page(): PageSession {
        val all = pages()
        val id = current?.takeIf { c -> all.any { it.id == c } } ?: foreground(all) ?: all.firstOrNull()?.id
            ?: cdp.send("Target.createTarget", buildJsonObject { put("url", "about:blank") }).str("targetId")
            ?: throw BrowserException("열린 탭이 없고 새 탭도 만들 수 없습니다")
        current = id
        val page = session(id)
        activate(id)
        return page
    }

    private suspend fun session(targetId: String): PageSession {
        sessions[targetId]?.let { return it }
        val sid = cdp.send("Target.attachToTarget", buildJsonObject { put("targetId", targetId); put("flatten", true) }).str("sessionId")
            ?: throw BrowserException("탭에 연결할 수 없습니다")
        val s = PageSession(targetId, sid)
        sessions[targetId] = s
        // Page events: loading state and JavaScript dialogs (which would block every script).
        runCatching { cdp.send("Page.enable", sessionId = sid) }
        // Do not emulate foreground focus: it would make hidden tabs report as
        // visible and disagree with the actual Chrome window shown over VNC.
        return s
    }

    private suspend fun activate(targetId: String) {
        cdp.send("Target.activateTarget", buildJsonObject { put("targetId", targetId) })
        cdp.send("Page.bringToFront", sessionId = session(targetId).sessionId)
    }

    private fun onEvent(method: String, params: JsonObject, sessionId: String?) {
        when (method) {
            "Target.targetCreated" -> {
                val info = params["targetInfo"] as? JsonObject ?: return
                val opener = info.str("openerId")
                if (info.str("type") == "page" && opener != null) openedBy[info.str("targetId").orEmpty()] = opener
            }
            "Target.targetDestroyed" -> params.str("targetId")?.let { id -> sessions.remove(id); docTokens.remove(id) }
            "Target.detachedFromTarget" -> params.str("sessionId")?.let { sid -> sessions.values.removeIf { it.sessionId == sid } }
            "Page.frameStartedLoading", "Page.frameStoppedLoading" -> {
                val s = sessions.values.firstOrNull { it.sessionId == sessionId } ?: return
                if (params.str("frameId") == s.targetId) s.loading = method == "Page.frameStartedLoading"
            }
            "Page.javascriptDialogOpening" -> {
                val type = params.str("type").orEmpty()
                dialog = "$type: ${params.str("message").orEmpty().take(300)} (accepted automatically)"
                scope.launch {
                    runCatching {
                        cdp.send("Page.handleJavaScriptDialog", buildJsonObject {
                            put("accept", true)
                            if (type == "prompt") put("promptText", params.str("defaultPrompt").orEmpty())
                        }, sessionId)
                    }
                }
            }
            "Browser.downloadWillBegin" -> {
                val guid = params.str("guid") ?: return
                downloads[guid] = Download(params.str("suggestedFilename").orEmpty(), params.str("url").orEmpty(), "inProgress")
            }
            "Browser.downloadProgress" -> {
                val d = downloads[params.str("guid") ?: return] ?: return
                d.state = params.str("state") ?: d.state
                (params["receivedBytes"] as? JsonPrimitive)?.doubleOrNull?.let { d.received = it.toLong() }
                (params["totalBytes"] as? JsonPrimitive)?.doubleOrNull?.let { d.total = it.toLong() }
                if (d.state != "inProgress") d.reported = false
            }
        }
    }

    /**
     * Waits until the main frame isn't loading (Page events) and the document is
     * `complete`, then for the DOM to go quiet. Bounded by [maxMs]: a page that never
     * finishes (streams, long polls) is read as it is.
     */
    private suspend fun settle(s: PageSession, maxMs: Long = 8_000) {
        delay(250) // let a navigation the action triggered start
        val deadline = System.currentTimeMillis() + maxMs
        suspend fun waitLoaded() {
            while (System.currentTimeMillis() < deadline) {
                if (!s.loading) {
                    val state = runCatching { (eval(s, "document.readyState", timeoutMs = 3_000) as? JsonPrimitive)?.content }.getOrNull()
                    if (state == "complete") return
                }
                delay(150)
            }
        }
        waitLoaded()
        repeat(2) {
            val ok = runCatching { eval(s, PageScripts.DOM_QUIET, awaitPromise = true, timeoutMs = 5_000) }.isSuccess
            if (ok) return
            delay(300) // the context was replaced by a navigation: wait for the new document
            waitLoaded()
        }
    }

    private suspend fun mouse(s: PageSession, type: String, x: Double, y: Double, clicks: Int = 0) {
        cdp.send("Input.dispatchMouseEvent", buildJsonObject {
            put("type", type); put("x", x); put("y", y)
            if (clicks > 0) { put("button", "left"); put("buttons", if (type == "mousePressed") 1 else 0); put("clickCount", clicks) }
        }, s.sessionId)
    }

    private suspend fun key(s: PageSession, k: Keys.Key) {
        val base = buildJsonObject {
            put("modifiers", k.modifiers)
            put("key", k.key); put("code", k.code)
            put("windowsVirtualKeyCode", k.keyCode); put("nativeVirtualKeyCode", k.keyCode)
        }
        val down = JsonObject(base + buildJsonObject {
            put("type", if (k.text != null) "keyDown" else "rawKeyDown")
            k.text?.let { put("text", it); put("unmodifiedText", it) }
        })
        cdp.send("Input.dispatchKeyEvent", down, s.sessionId)
        cdp.send("Input.dispatchKeyEvent", JsonObject(base + buildJsonObject { put("type", "keyUp") }), s.sessionId)
    }

    private suspend fun locate(s: PageSession, id: Int, scroll: Boolean): JsonObject {
        val r = evalFn(s, PageScripts.LOCATE, id, docToken(s), scroll) as? JsonObject ?: throw elementError(id, "unknown")
        r.str("error")?.let { throw elementError(id, it) }
        return r
    }

    private fun docToken(s: PageSession): String =
        docTokens[s.targetId] ?: throw BrowserException("no element ids for this tab yet: call action=state first")

    private fun elementError(id: Int, code: String) = BrowserException(
        when (code) {
            "stale" -> "the page changed since the last state (navigation/reload), so element $id is no longer valid: use the ids of the latest state"
            "unknown" -> "element $id is not in the latest state: use an id listed there (call state to refresh)"
            "detached" -> "element $id was removed from the page (the page updated): call state and use the new ids"
            "hidden" -> "element $id is not visible now: call state (it may be collapsed or scrolled away)"
            "not_editable" -> "element $id is not a text field: pick an input/textarea/editable element"
            "readonly" -> "element $id is disabled or read-only"
            else -> "element $id: $code"
        }
    )

    /** Calls `(fn)(args…)` in the page and returns its JSON value. */
    private suspend fun evalFn(s: PageSession, fn: String, vararg args: Any?): JsonElement? {
        val argList = args.joinToString(",") { a ->
            when (a) {
                null -> "null"
                is JsonElement -> a.toString()
                is String -> JsonPrimitive(a).toString()
                is Boolean, is Number -> a.toString()
                else -> JsonPrimitive(a.toString()).toString()
            }
        }
        return eval(s, "($fn)($argList)")
    }

    private suspend fun eval(s: PageSession, expression: String, awaitPromise: Boolean = false, timeoutMs: Long = 15_000): JsonElement? {
        val r = cdp.send("Runtime.evaluate", buildJsonObject {
            put("expression", expression)
            put("returnByValue", true)
            put("awaitPromise", awaitPromise)
            put("userGesture", true)
        }, s.sessionId, timeoutMs)
        (r["exceptionDetails"] as? JsonObject)?.let { ex ->
            val desc = ((ex["exception"] as? JsonObject)?.str("description") ?: ex.str("text")).orEmpty()
            throw CdpException("Runtime.evaluate", desc.lineSequence().firstOrNull().orEmpty())
        }
        return (r["result"] as? JsonObject)?.get("value")
    }

    private fun JsonObject.num(key: String): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    companion object {
        const val MAX_ELEMENTS = 150
        const val MAX_TEXT = 6_000

        /** Loopback / link-local / cloud metadata: the VPS itself, never a web page. */
        // Also numeric forms Chrome accepts for 127.0.0.1 (2130706433, 0x7f000001, 127.1).
        private val BLOCKED_HOSTS = Regex(
            "^(localhost|.*\\.localhost|127\\..*|0(\\..*)?|\\d+|0x[0-9a-f]+|\\[?::1?]?|\\[?::ffff:127\\..*|169\\.254\\..*|metadata\\.google\\.internal)$",
            RegexOption.IGNORE_CASE
        )

        /**
         * Normalizes a model-supplied URL for `open`: bare domains get https://, only
         * http(s) and about:blank are allowed, and the VPS's own loopback / metadata
         * addresses are refused (the browser runs on the server, next to its services).
         */
        fun checkUrl(input: String): String {
            val raw = input.trim()
            if (raw.isEmpty()) throw BrowserException("url is required")
            if (raw == "about:blank") return raw
            val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw) && !Regex("^[^/:]+:\\d+").containsMatchIn(raw)) raw else "https://$raw"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: throw BrowserException("invalid url: $raw")
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw BrowserException("only http(s) pages can be opened (got ${uri.scheme}:)")
            val host = uri.host ?: throw BrowserException("invalid url (no host): $raw")
            if (BLOCKED_HOSTS.matches(host)) throw BrowserException("$host is the server itself (loopback/metadata): not allowed")
            return uri.toString()
        }
    }
}

/** Key names for `press`: "Enter", "Escape", "ArrowDown", "Control+A", "Shift+Tab", "a", … */
internal object Keys {
    class Key(val key: String, val code: String, val keyCode: Int, val text: String?, val modifiers: Int)

    private class Def(val code: String, val keyCode: Int, val text: String? = null)

    private val NAMED = mapOf(
        "Enter" to Def("Enter", 13, "\r"), "Tab" to Def("Tab", 9), "Escape" to Def("Escape", 27),
        "Backspace" to Def("Backspace", 8), "Delete" to Def("Delete", 46), " " to Def("Space", 32, " "),
        "ArrowUp" to Def("ArrowUp", 38), "ArrowDown" to Def("ArrowDown", 40), "ArrowLeft" to Def("ArrowLeft", 37), "ArrowRight" to Def("ArrowRight", 39),
        "Home" to Def("Home", 36), "End" to Def("End", 35), "PageUp" to Def("PageUp", 33), "PageDown" to Def("PageDown", 34),
        "Insert" to Def("Insert", 45)
    ) + (1..12).associate { "F$it" to Def("F$it", 111 + it) }

    private val ALIASES = mapOf(
        "return" to "Enter", "esc" to "Escape", "space" to " ", "spacebar" to " ", "del" to "Delete",
        "up" to "ArrowUp", "down" to "ArrowDown", "left" to "ArrowLeft", "right" to "ArrowRight",
        "pgup" to "PageUp", "pgdn" to "PageDown", "pagedown" to "PageDown", "pageup" to "PageUp"
    )

    /** Alt, Control, Meta: the key is a shortcut, not text. */
    private const val NON_TEXT = 1 or 2 or 4
    private const val SHIFT = 8

    private val MODIFIERS = mapOf("alt" to 1, "option" to 1, "control" to 2, "ctrl" to 2, "meta" to 4, "cmd" to 4, "command" to 4, "shift" to 8)

    fun parse(spec: String): Key {
        // "Control++" = Control and the "+" key.
        val t = spec.trim()
        val parts = (if (t.endsWith("++")) t.dropLast(2).split("+") + "+" else t.split("+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw BrowserException("key is required (e.g. Enter, Escape, ArrowDown, Control+A)")
        var mods = 0
        for (m in parts.dropLast(1)) mods = mods or (MODIFIERS[m.lowercase()] ?: throw BrowserException("unknown modifier \"$m\" (use Control, Shift, Alt, Meta)"))
        val name = parts.last()
        val canonical = NAMED.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: ALIASES[name.lowercase()]
        if (canonical != null) {
            val d = NAMED.getValue(canonical)
            val text = d.text?.takeIf { (mods and NON_TEXT) == 0 }
            return Key(if (canonical == " ") " " else canonical, d.code, d.keyCode, text, mods)
        }
        if (name.length == 1) {
            val c = name[0]
            val upper = c.uppercaseChar()
            val code = when {
                upper in 'A'..'Z' -> "Key$upper"
                c in '0'..'9' -> "Digit$c"
                else -> ""
            }
            val keyCode = if (upper in 'A'..'Z' || c in '0'..'9') upper.code else 0
            val printable = (mods and NON_TEXT) == 0
            val shown = if ((mods and SHIFT) != 0) upper.toString() else c.toString()
            return Key(shown, code, keyCode, if (printable) shown else null, mods)
        }
        throw BrowserException("unknown key \"$name\" (e.g. Enter, Tab, Escape, Backspace, ArrowDown, PageDown, F5, a, Control+A)")
    }
}
