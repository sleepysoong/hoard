package com.sleepysoong.hoard.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Target of the PendingIntent handed to Termux. Termux fills in the result bundle
 * and sends it; the request ID we put in the intent routes it to the one waiting
 * [TermuxBridge.executeTermux] call, so concurrent commands never mix results.
 * Not exported: PendingIntent.send() runs with Hoard's own identity.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val bundle = intent.getBundleExtra(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE) ?: Bundle()
        // Nobody waiting (timed out, cancelled, or the process restarted): drop it.
        TermuxPendingResults.complete(id, bundle)
    }

    companion object {
        const val ACTION_RESULT = "com.sleepysoong.hoard.TERMUX_RESULT"
        const val EXTRA_REQUEST_ID = "com.sleepysoong.hoard.termux.REQUEST_ID"
    }
}

/** In-flight requests: request ID → the result the caller is suspended on. */
internal object TermuxPendingResults {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Bundle>>()

    fun register(id: String): CompletableDeferred<Bundle> =
        CompletableDeferred<Bundle>().also { check(pending.putIfAbsent(id, it) == null) { "duplicate request id $id" } }

    fun complete(id: String, result: Bundle): Boolean = pending.remove(id)?.complete(result) ?: false

    fun remove(id: String) { pending.remove(id) }

    val size: Int get() = pending.size
}
