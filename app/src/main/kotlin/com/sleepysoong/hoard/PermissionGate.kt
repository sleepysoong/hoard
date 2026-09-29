package com.sleepysoong.hoard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.sleepysoong.hoard.termux.AndroidTermuxPlatform
import com.sleepysoong.hoard.tools.files.FileTools
import com.termux.shared.termux.TermuxConstants

/**
 * Every app launch: ask for whatever the always-on tools still need.
 *  1. runtime permissions in one dialog — notifications (reply finished) and Termux
 *     RUN_COMMAND (only when Termux is installed);
 *  2. then, if missing, Android's "All files access" screen (shared storage for the file tools).
 * Once per launch (rememberSaveable: not again on rotation), never blocks the UI.
 */
@Composable
fun PermissionGate() {
    val ctx = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!FileTools.hasAllFilesAccess()) allFiles.launchCatching(allFilesIntent(ctx))
    }
    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        val missing = missingRuntimePermissions(ctx)
        when {
            missing.isNotEmpty() -> runCatching { runtime.launch(missing.toTypedArray()) }
            !FileTools.hasAllFilesAccess() -> allFiles.launchCatching(allFilesIntent(ctx))
        }
    }
}

internal fun missingRuntimePermissions(ctx: Context): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    if (AndroidTermuxPlatform(ctx).isTermuxInstalled()) add(TermuxConstants.PERMISSION_RUN_COMMAND)
}.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

private fun allFilesIntent(ctx: Context) =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

private fun androidx.activity.result.ActivityResultLauncher<Intent>.launchCatching(intent: Intent) {
    // Some devices lack the per-app screen: fall back to the general list.
    runCatching { launch(intent) }.onFailure { runCatching { launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
}
