package com.sleepysoong.hoard.browser

/** UI pixels describe an aspect ratio, never an emulated web viewport. */
data class BrowserViewport(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

/** Actual outer Chrome window in VNC desktop pixels, including tabs/address bar. */
data class BrowserWindow(val left: Int, val top: Int, val width: Int, val height: Int)
