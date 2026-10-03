package com.sleepysoong.hoard.browser

/** UI-only input for the desktop, including Chrome's tabs/address bar. Never a tool or chat message. */
sealed interface DesktopInput {
    data class Pointer(val x: Int, val y: Int, val buttons: Int = 0) : DesktopInput
    data class Scroll(val x: Int, val y: Int, val dx: Int = 0, val dy: Int) : DesktopInput
    data class Key(val keysym: Int, val down: Boolean) : DesktopInput
    data class Chord(val keys: List<Int>) : DesktopInput
    data class Text(val text: String) : DesktopInput
    data object ReleaseHeld : DesktopInput
}

/** Exclusive, cancellable manual ownership. Release before allowing any queued AI action. */
interface BrowserControl {
    suspend fun input(event: DesktopInput)
    /** Resize the real Chrome while this lease owns it, never race an AI action. */
    suspend fun resizeViewport(width: Int, height: Int) {}
    suspend fun release()
}
