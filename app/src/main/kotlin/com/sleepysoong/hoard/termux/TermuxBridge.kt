package com.sleepysoong.hoard.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Runs one-shot Termux commands. The tool layer only knows this interface. */
interface TermuxExecutor {
    /**
     * Runs `bash -lc [command]` in Termux (background, no terminal session) and
     * suspends until Termux reports the finished command.
     * @throws TermuxException when the command could not be run or no result came back.
     */
    suspend fun executeTermux(command: String, cwd: String? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): TermuxResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/** The Android side Termux needs; faked in tests. */
interface TermuxPlatform {
    fun isTermuxInstalled(): Boolean
    fun hasRunCommandPermission(): Boolean
    /** Starts RunCommandService. May throw SecurityException / IllegalStateException. */
    fun startService(intent: Intent)
}

class AndroidTermuxPlatform(private val context: Context) : TermuxPlatform {
    override fun isTermuxInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TermuxConstants.TERMUX_PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun hasRunCommandPermission(): Boolean =
        context.checkSelfPermission(TermuxConstants.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    // RunCommandService calls startForeground itself; Android 8+ requires starting it this way
    // (a plain startService from a background worker is refused).
    override fun startService(intent: Intent) {
        context.startForegroundService(intent)
    }
}

/**
 * Termux's official RUN_COMMAND API: an intent to `com.termux.app.RunCommandService`
 * with `bash -lc <command>` (the user's login shell environment), background mode,
 * and a PendingIntent that Termux sends back with stdout / stderr / exitCode.
 *
 * Only for short commands: Termux returns output through a Binder transaction and
 * truncates it when too large (reported as `*Truncated`). No PTY, no streaming.
 */
class TermuxBridge(
    private val context: Context,
    private val platform: TermuxPlatform = AndroidTermuxPlatform(context)
) : TermuxExecutor {

    override suspend fun executeTermux(command: String, cwd: String?, timeoutMs: Long): TermuxResult {
        require(command.isNotBlank()) { "command must not be blank" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        if (!platform.isTermuxInstalled()) throw TermuxException.NotInstalled()
        if (!platform.hasRunCommandPermission()) throw TermuxException.PermissionDenied()

        val id = UUID.randomUUID().toString()
        val result = TermuxPendingResults.register(id)
        try {
            try {
                platform.startService(buildRunCommandIntent(context, id, command, cwd))
            } catch (e: SecurityException) {
                throw TermuxException.PermissionDenied(e.message)
            } catch (e: IllegalStateException) {
                throw TermuxException.StartFailed(e.message ?: e.toString())
            }
            // Cancellable wait (stop button, regenerate): leaving it drops the request.
            val bundle = withTimeoutOrNull(timeoutMs) { result.await() } ?: throw TermuxException.Timeout(timeoutMs)
            return parseResult(bundle)
        } finally {
            TermuxPendingResults.remove(id)
        }
    }

    companion object {
        const val BASH_PATH = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash"
        const val HOME_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH

        /** Termux's "no error" code (Errno.ERRNO_SUCCESS / Activity.RESULT_OK). */
        private const val ERR_SUCCESS = -1

        fun buildRunCommandIntent(context: Context, requestId: String, command: String, cwd: String?): Intent =
            Intent(RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND).apply {
                component = ComponentName(TermuxConstants.TERMUX_PACKAGE_NAME, TERMUX_APP.RUN_COMMAND_SERVICE_NAME)
                putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, BASH_PATH)
                putExtra(RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, arrayOf("-lc", command))
                putExtra(RUN_COMMAND_SERVICE.EXTRA_WORKDIR, cwd?.takeIf { it.isNotBlank() } ?: HOME_PATH)
                putExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, true)
                putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_LABEL, "Hoard termux_exec")
                putExtra(RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT, resultPendingIntent(context, requestId))
            }

        /**
         * Explicit (required for a mutable PendingIntent on Android 14+) and mutable (Termux
         * fills in the result bundle). The data URI + request code make each one distinct, so
         * concurrent requests never share or overwrite a PendingIntent.
         */
        fun resultPendingIntent(context: Context, requestId: String): PendingIntent {
            val intent = Intent(context, TermuxResultReceiver::class.java)
                .setAction(TermuxResultReceiver.ACTION_RESULT)
                .setData(Uri.parse("hoard-termux://result/$requestId"))
                .putExtra(TermuxResultReceiver.EXTRA_REQUEST_ID, requestId)
            return PendingIntent.getBroadcast(
                context, requestId.hashCode(), intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /** Termux's result bundle → [TermuxResult], or the specific failure. */
        fun parseResult(b: Bundle): TermuxResult {
            val err = b.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR, ERR_SUCCESS)
            val errmsg = b.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG).orEmpty()
            if (err != ERR_SUCCESS) {
                if (errmsg.contains("allow-external-apps")) throw TermuxException.ExternalAppsDisabled(errmsg.trim())
                throw TermuxException.ExecutionFailed(errmsg.trim().ifEmpty { "error code $err" })
            }
            if (!b.containsKey(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE)) {
                throw TermuxException.ExecutionFailed(errmsg.trim().ifEmpty { "Termux returned no exit code" })
            }
            return TermuxResult(
                stdout = b.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT).orEmpty(),
                stderr = b.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR).orEmpty(),
                exitCode = b.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE),
                stdoutTruncated = !b.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH).isNullOrEmpty(),
                stderrTruncated = !b.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH).isNullOrEmpty()
            )
        }
    }
}
