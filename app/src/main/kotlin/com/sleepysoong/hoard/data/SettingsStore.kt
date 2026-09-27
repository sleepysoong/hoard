package com.sleepysoong.hoard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore("hoard-settings")

object SettingsStore {
    private val THEME = stringPreferencesKey("theme") // system | light | dark
    private val DEFAULT_MODEL = stringPreferencesKey("default_model")
    private val DEFAULT_CONTEXT = intPreferencesKey("default_context")
    private val ROUTER_URL = stringPreferencesKey("router_url")
    private val ROUTER_TOKEN = stringPreferencesKey("router_token")

    data class Settings(
        val theme: String = "system",
        val defaultModel: String = "hoard-1-pro",
        val defaultContext: Int = 32_000,
        /** sleepyrouter base URL, e.g. http://192.168.0.10:4567. Blank = offline mock engine. */
        val routerUrl: String = "",
        /** sleepyrouter inbound token ([server] auth_token_env). Blank = router has no auth. */
        val routerToken: String = ""
    ) {
        override fun toString() = "Settings(theme=$theme, defaultModel=$defaultModel, defaultContext=$defaultContext, " +
            "routerUrl=$routerUrl, routerToken=${if (routerToken.isBlank()) "none" else "set"})"
    }

    fun flow(ctx: Context): Flow<Settings> = ctx.prefs.data.map { p ->
        Settings(
            theme = p[THEME] ?: "system",
            defaultModel = p[DEFAULT_MODEL] ?: "hoard-1-pro",
            defaultContext = p[DEFAULT_CONTEXT] ?: 32_000,
            routerUrl = p[ROUTER_URL] ?: "",
            routerToken = p[ROUTER_TOKEN] ?: ""
        )
    }

    suspend fun setTheme(ctx: Context, v: String) { ctx.prefs.edit { it[THEME] = v } }
    suspend fun setDefaultModel(ctx: Context, v: String) { ctx.prefs.edit { it[DEFAULT_MODEL] = v } }
    suspend fun setDefaultContext(ctx: Context, v: Int) { ctx.prefs.edit { it[DEFAULT_CONTEXT] = v } }
    suspend fun setRouterUrl(ctx: Context, v: String) { ctx.prefs.edit { it[ROUTER_URL] = v.trim() } }
    suspend fun setRouterToken(ctx: Context, v: String) { ctx.prefs.edit { it[ROUTER_TOKEN] = v.trim() } }

    suspend fun current(ctx: Context): Settings = flow(ctx).first()

    /** Back to factory defaults. */
    suspend fun reset(ctx: Context) { ctx.prefs.edit { it.clear() } }
}
