package com.sleepysoong.hoard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.work.ChatResponseWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class HoardApp : Application(), Configuration.Provider {
    private val appScope: CoroutineScope = MainScope()

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

        // "백그라운드 답변" off: replies only run while the app is on screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                appScope.launch {
                    if (!SettingsStore.current(this@HoardApp).backgroundWork) {
                        ChatResponseWorker.cancelAll(this@HoardApp)
                    }
                }
            }
        })
    }
}
