package com.sleepysoong.hoard.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser_use URL guard: the VPS's own loopback/metadata is never a web page,
 * in every form a browser accepts. Failure modes: alternate IPv4 spellings
 * (dotted-octal/hex, one large number), trailing-dot DNS names, non-http schemes.
 * The chat fixture still reaches its own http page over CDP *actions*; this guard
 * is the model-facing boundary.
 */
class BrowserUrlGuardTest {
    private fun rejects(url: String) = assertTrue("$url must be refused", runCatching { BrowserService.checkUrl(url) }.isFailure)
    private fun accepts(url: String) = assertFalse("$url must be allowed", runCatching { BrowserService.checkUrl(url) }.isFailure)

    @Test fun loopbackAndMetadataByName() {
        listOf("http://localhost/x", "http://localhost./x", "http://foo.localhost/x", "https://LOCALHOST/x",
            "http://metadata.google.internal/computeMetadata/v1/", "http://metadata.google.internal./x", "localhost").forEach(::rejects)
        accepts("http://notlocalhost.example/x")
    }

    @Test fun numericIpv4InEveryFormChromeAccepts() {
        listOf("http://127.0.0.1/x", "http://127.1/x", "http://2130706433/x", "http://0x7f000001/x",
            "http://0x7f.0.0.1/x", "http://0177.0.0.1/x", "http://0.0.0.0/x", "http://0/x", "http://0x0/x",
            "http://169.254.169.254/latest/meta-data/").forEach(::rejects)
        accepts("http://8.8.8.8/x")
        // Not a valid IP and not parseable as a host: refused rather than confused.
        rejects("http://999.1.1.1/x")
    }

    @Test fun bracketedLoopbackV6() {
        rejects("http://[::1]/x")
        rejects("http://[::ffff:127.0.0.1]/x")
    }

    @Test fun schemesAndShape() {
        rejects("")
        rejects("   ")
        rejects("file:///etc/passwd")
        accepts("https://example.com/a b".replace(" ", "%20"))
        org.junit.Assert.assertEquals("https://example.com", BrowserService.checkUrl("example.com"))
    }
}
