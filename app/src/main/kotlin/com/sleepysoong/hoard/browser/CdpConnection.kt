package com.sleepysoong.hoard.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A CDP command failed (the browser answered with an error). */
class CdpException(val method: String, message: String) : BrowserException("$method: $message")

/** The CDP WebSocket closed (tunnel, SSH or Chrome went away). */
class CdpClosedException(message: String) : BrowserException(message)

/**
 * One browser-level Chrome DevTools Protocol connection over the SSH tunnel
 * (ws://127.0.0.1:<local port>/devtools/browser/<id>). Page commands use flat
 * sessions (`sessionId` from Target.attachToTarget), so one socket drives every tab.
 */
class CdpConnection private constructor(val localPort: Int) {
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val listeners = CopyOnWriteArrayList<(method: String, params: JsonObject, sessionId: String?) -> Unit>()
    private lateinit var socket: WebSocket
    @Volatile var isOpen = false
        private set
    @Volatile private var closeReason: String? = null

    /** Called for every CDP event (on OkHttp's reader thread: keep it quick). */
    fun onEvent(listener: (method: String, params: JsonObject, sessionId: String?) -> Unit) { listeners += listener }

    suspend fun send(method: String, params: JsonObject = EMPTY, sessionId: String? = null, timeoutMs: Long = 30_000): JsonObject {
        if (!isOpen) throw CdpClosedException("브라우저(CDP) 연결이 끊겼습니다" + closeReason?.let { ": $it" }.orEmpty())
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        val msg = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            sessionId?.let { put("sessionId", it) }
        }
        if (!socket.send(msg.toString())) {
            pending.remove(id)
            throw CdpClosedException("브라우저(CDP) 연결이 끊겼습니다")
        }
        val response = try {
            withTimeout(timeoutMs) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw BrowserException("브라우저가 응답하지 않습니다 ($method, ${timeoutMs / 1000}초)")
        } finally {
            // A preview leaves the foreground often; cancelled frame requests must
            // not accumulate unresolved replies in the shared CDP connection.
            pending.remove(id)
        }
        (response["error"] as? JsonObject)?.let { err ->
            throw CdpException(method, (err["message"] as? JsonPrimitive)?.contentOrNull ?: err.toString())
        }
        return response["result"] as? JsonObject ?: EMPTY
    }

    fun close() {
        isOpen = false
        runCatching { socket.close(1000, null) }
        failPending("closed")
    }

    private fun failPending(reason: String) {
        closeReason = reason
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(CdpClosedException("브라우저(CDP) 연결이 끊겼습니다: $reason")) }
    }

    private inner class Listener(private val opened: CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isOpen = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val id = (o["id"] as? JsonPrimitive)?.intOrNull
            if (id != null) {
                pending.remove(id)?.complete(o)
                return
            }
            val method = (o["method"] as? JsonPrimitive)?.contentOrNull ?: return
            val params = o["params"] as? JsonObject ?: EMPTY
            val session = (o["sessionId"] as? JsonPrimitive)?.contentOrNull
            listeners.forEach { runCatching { it(method, params, session) } }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            webSocket.close(1000, null)
            failPending("closed ($code)")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            failPending("closed ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isOpen = false
            opened.completeExceptionally(t)
            failPending(t.message ?: t::class.simpleName.orEmpty())
        }
    }

    companion object {
        val EMPTY = JsonObject(emptyMap())
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** WebSocket frames can be screenshots (MBs): no read timeout, the reply timeout lives in [send]. */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Finds Chrome's browser endpoint through the tunnel (GET /json/version) and opens
         * the WebSocket. The advertised URL names the VPS port (9222); the path is reused on
         * the tunnel's local port.
         */
        suspend fun open(localPort: Int): CdpConnection {
            val base = "http://127.0.0.1:$localPort"
            val version = try {
                http.newCall(Request.Builder().url("$base/json/version").build()).execute().use { r ->
                    if (!r.isSuccessful) throw BrowserException("CDP /json/version 응답 HTTP ${r.code}")
                    json.parseToJsonElement(r.body.string()).jsonObject
                }
            } catch (e: BrowserException) {
                throw e
            } catch (e: Exception) {
                throw BrowserException("SSH 터널로 Chrome(CDP)에 닿지 않습니다: ${e.message}", e)
            }
            val advertised = (version["webSocketDebuggerUrl"] as? JsonPrimitive)?.contentOrNull
                ?: throw BrowserException("Chrome이 webSocketDebuggerUrl을 알려주지 않았습니다")
            val path = runCatching { URI(advertised).rawPath }.getOrNull()?.takeIf { it.startsWith("/devtools/") }
                ?: throw BrowserException("알 수 없는 CDP 주소: $advertised")
            val conn = CdpConnection(localPort)
            val opened = CompletableDeferred<Unit>()
            conn.socket = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:$localPort$path").build(), conn.Listener(opened))
            try {
                withTimeout(15_000) { opened.await() }
            } catch (e: TimeoutCancellationException) {
                conn.close()
                throw BrowserException("CDP WebSocket 연결 시간 초과")
            } catch (e: Exception) {
                conn.close()
                throw BrowserException("CDP WebSocket 연결 실패: ${e.message}", e)
            }
            return conn
        }

        /** Convenience for a JSON result field. */
        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
