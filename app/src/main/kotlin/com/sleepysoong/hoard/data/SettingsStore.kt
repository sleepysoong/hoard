package com.sleepysoong.hoard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("hoard-settings")

object SettingsStore {
    private val THEME = stringPreferencesKey("theme") // system | light | dark
    private val DEFAULT_MODEL = stringPreferencesKey("default_model")
    private val DEFAULT_CONTEXT = intPreferencesKey("default_context")

    data class Settings(
        val theme: String = "system",
        val defaultModel: String = "hoard-1-pro",
        val defaultContext: Int = 32_000
    )

    fun flow(ctx: Context): Flow<Settings> = ctx.prefs.data.map { p ->
        Settings(
            theme = p[THEME] ?: "system",
            defaultModel = p[DEFAULT_MODEL] ?: "hoard-1-pro",
            defaultContext = p[DEFAULT_CONTEXT] ?: 32_000
        )
    }

    suspend fun setTheme(ctx: Context, v: String) { ctx.prefs.edit { it[THEME] = v } }
    suspend fun setDefaultModel(ctx: Context, v: String) { ctx.prefs.edit { it[DEFAULT_MODEL] = v } }
    suspend fun setDefaultContext(ctx: Context, v: Int) { ctx.prefs.edit { it[DEFAULT_CONTEXT] = v } }

    /** Back to factory defaults. */
    suspend fun reset(ctx: Context) { ctx.prefs.edit { it.clear() } }
}
