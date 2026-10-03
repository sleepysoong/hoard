package com.sleepysoong.hoard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration

/**
 * Replies always continue in the background (product intent: leaving the app
 * never stops an answer) — there is deliberately no setting to turn this off.
 */
class HoardApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        com.sleepysoong.hoard.diagnostics.AppLog.init(this)
        // A silent crash/starvation must not go unrecorded: log it before the system handler.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                com.sleepysoong.hoard.diagnostics.AppLog.e("Crash", "uncaught on ${thread.name}", error)
            }
            previous?.uncaughtException(thread, error)
        }
        com.sleepysoong.hoard.diagnostics.AppLog.i("HoardApp", "app start")
        com.sleepysoong.hoard.data.HoardRepository.init(java.io.File(filesDir, "hoard-store.json"))
        // Scheduler recovery off the main thread: re-arm active schedules (catch-up policy
        // for anything due while Hoard wasn't running) and fail runs a killed process left "running".
        Thread({
            runCatching { com.sleepysoong.hoard.schedule.WorkManagerScheduler.services(this).second.reconcile() }
            runCatching { kotlinx.coroutines.runBlocking {
                com.sleepysoong.hoard.work.ChatResponseWorker.recoverLegacyContinuations(this@HoardApp)
            } }.onFailure { com.sleepysoong.hoard.diagnostics.AppLog.e("Goal", "legacy continuation recovery failed", it) }
        }, "hoard-scheduler-reconcile").start()
        val channel = NotificationChannel(
            "hoard-replies",
            "Hoard 백그라운드 답변",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        // Leaving the screen (or being killed from the background) must not lose the last
        // ~400 ms of debounced changes: write through when the UI goes away.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStopped(a: android.app.Activity) = com.sleepysoong.hoard.data.HoardRepository.get().flush()
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}
