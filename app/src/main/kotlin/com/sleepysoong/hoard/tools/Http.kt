package com.sleepysoong.hoard.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs blocking [HttpURLConnection] work on IO so that cancelling the coroutine
 * (stop button, regenerate) closes the socket instead of waiting out timeouts.
 * disconnect() runs on a separate thread: on some Android builds it can block on
 * the stalled read's lock, and the cancelling thread must not freeze with it.
 */
private val disconnectScope = CoroutineScope(Dispatchers.IO)

@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> withConnection(conn: java.net.HttpURLConnection, block: (java.net.HttpURLConnection) -> T): T =
    withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val watcher = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) disconnectScope.launch { runCatching { conn.disconnect() } }
        }
        try {
            block(conn)
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive() // a socket we closed: report cancellation
            throw e
        } finally {
            watcher?.dispose()
            conn.disconnect()
        }
    }

/** Reads at most [limit] bytes; returns them and whether the stream had more. */
internal fun java.io.InputStream.readCapped(limit: Int): Pair<ByteArray, Boolean> {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) return out.toByteArray() to false
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray() to (read() >= 0)
}
