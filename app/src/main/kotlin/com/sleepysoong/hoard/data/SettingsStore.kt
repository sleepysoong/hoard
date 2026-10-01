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
    private val BRAVE_KEY = stringPreferencesKey("brave_api_key")
    private val BROWSER_HOST = stringPreferencesKey("browser_ssh_host")
    private val BROWSER_PORT = intPreferencesKey("browser_ssh_port")
    private val BROWSER_USER = stringPreferencesKey("browser_ssh_user")
    /** The SSH private key, encrypted with an Android Keystore key (never stored in plain text). */
    private val BROWSER_KEY_ENC = stringPreferencesKey("browser_ssh_key_enc")
    /** Its public half ("type base64 comment"), shown so the user can add it to authorized_keys. */
    private val BROWSER_PUB = stringPreferencesKey("browser_ssh_pub")
    /** Pinned SSH host key ("type base64"), set when the user verifies the host in Settings. */
    private val BROWSER_HOST_KEY = stringPreferencesKey("browser_ssh_host_key")

    data class Settings(
        val theme: String = "system",
        /** Router group/model for new sessions. Blank = the router's first group. */
        val defaultModel: String = "",
        val defaultContext: Int = 32_000,
        /** sleepyrouter base URL, e.g. http://192.168.0.10:4567. Blank = offline mock engine. */
        val routerUrl: String = "",
        /** sleepyrouter inbound token ([server] auth_token_env). Blank = router has no auth. */
        val routerToken: String = "",
        /** The user's own Brave Search API key; blank = web_search unavailable (web_fetch still works). */
        val braveApiKey: String = "",
        /** Remote browser (browser_use): VPS reached over SSH. Blank host = not configured. */
        val browserHost: String = "",
        val browserPort: Int = 22,
        val browserUser: String = "",
        /** Keystore-encrypted private key blob ([com.sleepysoong.hoard.browser.SecretStore]). */
        val browserKeyEncrypted: String = "",
        /** Public key line of that key (not secret). */
        val browserPublicKey: String = "",
        /** Pinned host key "type base64" (blank = not verified yet). */
        val browserHostKey: String = ""
    ) {
        val browserConfigured: Boolean get() = browserHost.isNotBlank() && browserUser.isNotBlank() && browserKeyEncrypted.isNotBlank()

        override fun toString() = "Settings(theme=$theme, defaultModel=$defaultModel, defaultContext=$defaultContext, " +
            "routerUrl=$routerUrl, routerToken=${if (routerToken.isBlank()) "none" else "set"}, " +
            "braveApiKey=${if (braveApiKey.isBlank()) "none" else "set"}, browser=${if (browserConfigured) "$browserUser@$browserHost:$browserPort" else "none"}, " +
            "browserKey=${if (browserKeyEncrypted.isBlank()) "none" else "set"}, browserHostKey=${if (browserHostKey.isBlank()) "unverified" else "pinned"})"
    }

    fun flow(ctx: Context): Flow<Settings> = ctx.prefs.data.map { p ->
        Settings(
            theme = p[THEME] ?: "system",
            defaultModel = p[DEFAULT_MODEL] ?: "",
            defaultContext = p[DEFAULT_CONTEXT] ?: 32_000,
            routerUrl = p[ROUTER_URL] ?: "",
            routerToken = p[ROUTER_TOKEN] ?: "",
            braveApiKey = p[BRAVE_KEY] ?: "",
            browserHost = p[BROWSER_HOST] ?: "",
            browserPort = p[BROWSER_PORT] ?: 22,
            browserUser = p[BROWSER_USER] ?: "",
            browserKeyEncrypted = p[BROWSER_KEY_ENC] ?: "",
            browserPublicKey = p[BROWSER_PUB] ?: "",
            browserHostKey = p[BROWSER_HOST_KEY] ?: ""
        )
    }

    suspend fun setTheme(ctx: Context, v: String) { ctx.prefs.edit { it[THEME] = v } }
    suspend fun setDefaultModel(ctx: Context, v: String) { ctx.prefs.edit { it[DEFAULT_MODEL] = v } }
    suspend fun setDefaultContext(ctx: Context, v: Int) { ctx.prefs.edit { it[DEFAULT_CONTEXT] = v } }
    suspend fun setRouterUrl(ctx: Context, v: String) { ctx.prefs.edit { it[ROUTER_URL] = v.trim() } }
    suspend fun setRouterToken(ctx: Context, v: String) { ctx.prefs.edit { it[ROUTER_TOKEN] = v.trim() } }
    suspend fun setBraveApiKey(ctx: Context, v: String) { ctx.prefs.edit { it[BRAVE_KEY] = v.trim() } }

    /** Saves the SSH target; another host or port un-pins the host key (it must be verified again). */
    suspend fun setBrowserTarget(ctx: Context, host: String, port: Int, user: String) {
        ctx.prefs.edit {
            val moved = (it[BROWSER_HOST] ?: "") != host.trim() || (it[BROWSER_PORT] ?: 22) != port
            it[BROWSER_HOST] = host.trim(); it[BROWSER_PORT] = port; it[BROWSER_USER] = user.trim()
            if (moved) it.remove(BROWSER_HOST_KEY)
        }
    }
    /** The Keystore-encrypted private key and its public line; blank [encrypted] removes both. */
    suspend fun setBrowserKey(ctx: Context, encrypted: String, publicKey: String) {
        ctx.prefs.edit {
            if (encrypted.isBlank()) { it.remove(BROWSER_KEY_ENC); it.remove(BROWSER_PUB) }
            else { it[BROWSER_KEY_ENC] = encrypted; it[BROWSER_PUB] = publicKey }
        }
    }
    suspend fun setBrowserHostKey(ctx: Context, v: String) { ctx.prefs.edit { if (v.isBlank()) it.remove(BROWSER_HOST_KEY) else it[BROWSER_HOST_KEY] = v } }

    suspend fun current(ctx: Context): Settings = flow(ctx).first()

    /** Back to factory defaults. */
    suspend fun reset(ctx: Context) { ctx.prefs.edit { it.clear() } }
}
