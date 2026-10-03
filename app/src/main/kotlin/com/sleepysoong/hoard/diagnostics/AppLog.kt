package com.sleepysoong.hoard.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log: every line goes to logcat AND an on-device ring buffer
 * (filesDir/logs/hoard.log, rolled at 256 KB) so Settings can show/copy it without adb.
 * Tokens, keys and message contents are redacted; log structure and timings only.
 */
object AppLog {
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val MAX_TAIL_LINES = 400
    private const val TAG = "Hoard"

    private val lock = Any()
    @Volatile private var store: File? = null
    private val _tail = MutableStateFlow<List<String>>(emptyList())
    val tail: StateFlow<List<String>> = _tail.asStateFlow()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            store = File(context.applicationContext.filesDir, "logs/hoard.log").also { it.parentFile?.mkdirs() }
        }
    }

    fun d(tag: String, msg: String) = write('D', tag, msg, null).let { }
    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = write('W', tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write('E', tag, msg, tr)

    fun clear() = synchronized(lock) {
        _tail.value = emptyList()
        store?.delete()
        File(store?.parentFile, "hoard.log.1").delete()
    }

    private fun write(level: Char, tag: String, msg: String, tr: Throwable?) = synchronized(lock) {
        // SimpleDateFormat is not thread-safe; formatting belongs under the lock too.
        val line = "${time.format(Date())} $level/$tag[${Thread.currentThread().name.takeLast(24)}]: ${redact(msg)}" +
            (tr?.let { "\n" + redact(it.stackTraceToString()).take(12_000) } ?: "")
        // Use the same redacted causal stack in logcat and in-app copying. Passing
        // the original Throwable to Log would bypass credential masking.
        when (level) {
            'D' -> Log.d(TAG, "$tag: $line")
            'I' -> Log.i(TAG, "$tag: $line")
            'W' -> Log.w(TAG, "$tag: $line")
            else -> Log.e(TAG, "$tag: $line")
        }
        _tail.value = (_tail.value + line).takeLast(MAX_TAIL_LINES)
        val f = store ?: return@synchronized
        runCatching {
            if (f.length() > MAX_FILE_BYTES) {
                File(f.parentFile, "hoard.log.1").let { old -> old.delete(); f.renameTo(old) }
            }
            f.appendText(line + "\n")
        }
        Unit
    }

    /** Never persist credentials or private keys. */
    internal fun redact(text: String): String {
        var out = text
        out = out.replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [redacted]")
        out = out.replace(Regex("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----"), "[private key redacted]")
        out = out.replace(Regex("(?i)(token|password|api[_-]?key)\"?\\s*[=:]\\s*\"?[^\\s\",}]+\"?"), "$1=[redacted]")
        return out
    }
}
