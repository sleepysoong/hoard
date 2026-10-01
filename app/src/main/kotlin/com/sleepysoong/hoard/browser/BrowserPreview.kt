package com.sleepysoong.hoard.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI-only observation of the shared Chrome. No images enter chat history or model input. */
data class BrowserPreviewTarget(val sessionId: String, val browser: RemoteBrowser)

object BrowserPreviews {
    private val mutableTarget = MutableStateFlow<BrowserPreviewTarget?>(null)
    val target: StateFlow<BrowserPreviewTarget?> = mutableTarget.asStateFlow()

    /** Only the chat currently using the shared browser gets its live view. */
    fun show(sessionId: String, browser: RemoteBrowser) {
        if (sessionId.isNotBlank() && browser.supportsPreview) {
            mutableTarget.value = BrowserPreviewTarget(sessionId, browser)
        }
    }

    fun dismiss(target: BrowserPreviewTarget) {
        mutableTarget.compareAndSet(target, null)
    }

    /** A configuration change/closed SSH connection must not leave an old view attached. */
    fun clear(browser: RemoteBrowser) {
        mutableTarget.update { if (it?.browser === browser) null else it }
    }
}
