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
        val channel = NotificationChannel(
            "hoard-replies",
            "Hoard 백그라운드 답변",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
