package com.sleepysoong.hoard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    private val WEB_TOOLS = booleanPreferencesKey("web_tools")
    private val BRAVE_KEY = stringPreferencesKey("brave_api_key")
    private val TERMUX = booleanPreferencesKey("termux_exec")
    private val FILES = booleanPreferencesKey("file_tools")
    private val FILES_FULL = booleanPreferencesKey("file_tools_full_storage")

    data class Settings(
        val theme: String = "system",
        /** Router group/model for new sessions. Blank = the router's first group. */
        val defaultModel: String = "",
        val defaultContext: Int = 32_000,
        /** sleepyrouter base URL, e.g. http://192.168.0.10:4567. Blank = offline mock engine. */
        val routerUrl: String = "",
        /** sleepyrouter inbound token ([server] auth_token_env). Blank = router has no auth. */
        val routerToken: String = "",
        /** Offer web_search / web_fetch to the model (router mode only). */
        val webToolsEnabled: Boolean = true,
        /** The user's own Brave Search API key; blank = web_search unavailable (web_fetch still works). */
        val braveApiKey: String = "",
        /** Offer termux_exec (shell commands in Termux). Off by default: it runs real commands. */
        val termuxEnabled: Boolean = false,
        /** Offer read_file / write_file / edit_file / glob / grep over the app's workspace folder. */
        val fileToolsEnabled: Boolean = true,
        /** File tools may also use the phone's shared storage (needs "All files access"). */
        val fileToolsFullStorage: Boolean = false
    ) {
        override fun toString() = "Settings(theme=$theme, defaultModel=$defaultModel, defaultContext=$defaultContext, " +
            "routerUrl=$routerUrl, routerToken=${if (routerToken.isBlank()) "none" else "set"}, " +
            "webTools=$webToolsEnabled, braveApiKey=${if (braveApiKey.isBlank()) "none" else "set"}, termux=$termuxEnabled, files=$fileToolsEnabled, fullStorage=$fileToolsFullStorage)"
    }

    fun flow(ctx: Context): Flow<Settings> = ctx.prefs.data.map { p ->
        Settings(
            theme = p[THEME] ?: "system",
            defaultModel = p[DEFAULT_MODEL] ?: "",
            defaultContext = p[DEFAULT_CONTEXT] ?: 32_000,
            routerUrl = p[ROUTER_URL] ?: "",
            routerToken = p[ROUTER_TOKEN] ?: "",
            webToolsEnabled = p[WEB_TOOLS] ?: true,
            braveApiKey = p[BRAVE_KEY] ?: "",
            termuxEnabled = p[TERMUX] ?: false,
            fileToolsEnabled = p[FILES] ?: true,
            fileToolsFullStorage = p[FILES_FULL] ?: false
        )
    }

    suspend fun setTheme(ctx: Context, v: String) { ctx.prefs.edit { it[THEME] = v } }
    suspend fun setDefaultModel(ctx: Context, v: String) { ctx.prefs.edit { it[DEFAULT_MODEL] = v } }
    suspend fun setDefaultContext(ctx: Context, v: Int) { ctx.prefs.edit { it[DEFAULT_CONTEXT] = v } }
    suspend fun setRouterUrl(ctx: Context, v: String) { ctx.prefs.edit { it[ROUTER_URL] = v.trim() } }
    suspend fun setRouterToken(ctx: Context, v: String) { ctx.prefs.edit { it[ROUTER_TOKEN] = v.trim() } }
    suspend fun setWebToolsEnabled(ctx: Context, v: Boolean) { ctx.prefs.edit { it[WEB_TOOLS] = v } }
    suspend fun setBraveApiKey(ctx: Context, v: String) { ctx.prefs.edit { it[BRAVE_KEY] = v.trim() } }
    suspend fun setTermuxEnabled(ctx: Context, v: Boolean) { ctx.prefs.edit { it[TERMUX] = v } }
    suspend fun setFileToolsEnabled(ctx: Context, v: Boolean) { ctx.prefs.edit { it[FILES] = v } }
    suspend fun setFileToolsFullStorage(ctx: Context, v: Boolean) { ctx.prefs.edit { it[FILES_FULL] = v } }

    suspend fun current(ctx: Context): Settings = flow(ctx).first()

    /** Back to factory defaults. */
    suspend fun reset(ctx: Context) { ctx.prefs.edit { it.clear() } }
}
