package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.engine.RouterConnection
import com.sleepysoong.hoard.engine.RouterStatus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/**
 * Settings can trigger several refreshes (launch + URL edit + token edit + manual connect).
 * A slow stale probe must never overwrite the outcome of a newer one, or the app reports
 * "connected" to the wrong address and gates sends against a server the user already left.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RouterRefreshRaceTest {
    private val servers = mutableListOf<HttpServer>()
    @After fun stop() = servers.forEach { it.stop(0) }

    private fun router(delayMs: Long): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { ex ->
                if (delayMs > 0) Thread.sleep(delayMs)
                val b = """{"object":"list","data":[{"id":"coding","object":"model"}]}""".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        servers += srv
        return "http://127.0.0.1:${srv.address.port}"
    }

    @Test fun slowStaleProbeNeverOverridesTheNewerConnection() = runBlocking {
        RouterConnection.resetForTests()
        com.sleepysoong.hoard.data.HoardRepository.resetForTests()
        val slow = router(delayMs = 900)
        val fast = router(delayMs = 0)
        val staleProbe = async { RouterConnection.refresh(slow) }
        delay(100) // let the slow probe enter the request first
        val latest = RouterConnection.refresh(fast)
        staleProbe.await()
        val status = RouterConnection.status.value
        assertTrue("latest refresh wins: $status", status is RouterStatus.Connected)
        assertEquals("reports the newer address, not the stale one",
            fast, (status as RouterStatus.Connected).url)
    }
}
