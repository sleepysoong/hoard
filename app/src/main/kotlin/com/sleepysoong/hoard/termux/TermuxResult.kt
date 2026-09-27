package com.sleepysoong.hoard.termux

/**
 * Outcome of one finished Termux command. A non-zero [exitCode] is a normal
 * result (the command ran and failed), not a transport error.
 */
data class TermuxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    /** Termux cut stdout/stderr to fit the Binder transaction (see `*_original_length`). */
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false
)

/**
 * Why a command could not be run or its result not received. Each setup problem
 * is its own type so the user/model can be told exactly what to fix.
 */
sealed class TermuxException(val code: String, message: String) : Exception(message) {
    class NotInstalled : TermuxException(
        "termux_not_installed",
        "Termux (com.termux) is not installed. Install Termux from F-Droid or GitHub."
    )

    class PermissionDenied(detail: String? = null) : TermuxException(
        "termux_permission_denied",
        "Hoard lacks the com.termux.permission.RUN_COMMAND permission. Grant it in Hoard 설정 → Termux." +
            (detail?.let { " ($it)" } ?: "")
    )

    class ExternalAppsDisabled(detail: String) : TermuxException(
        "termux_external_apps_disabled",
        "Termux refuses commands from other apps (allow-external-apps is not true). In Termux run: " +
            "mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings" +
            " — Termux said: $detail"
    )

    class Timeout(val timeoutMs: Long) : TermuxException(
        "termux_timeout",
        "No result from Termux within $timeoutMs ms. The command may still be running in Termux; " +
            "termux_exec is for short one-shot commands."
    )

    class StartFailed(detail: String) : TermuxException("termux_start_failed", "Could not start Termux RunCommandService: $detail")

    class ExecutionFailed(detail: String) : TermuxException("termux_execution_failed", "Termux could not execute the command: $detail")
}
