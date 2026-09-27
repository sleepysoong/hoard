package com.sleepysoong.hoard

import com.sleepysoong.hoard.engine.RouterAiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Plain JVM: what the user types in 설정 → 라우터, and what they see when it fails. */
class RouterAddressTest {
    @Test fun bareHostPortMeansHttp() {
        assertEquals("http://sleepysoong.kro.kr:4567", RouterAiEngine.normalizeBaseUrl("sleepysoong.kro.kr:4567"))
        assertEquals("http://sleepysoong.kro.kr:4567", RouterAiEngine.normalizeBaseUrl("  sleepysoong.kro.kr:4567/ "))
        assertEquals("https://r.example.com", RouterAiEngine.normalizeBaseUrl("https://r.example.com"))
        assertEquals("http://192.168.0.10:4567", RouterAiEngine.normalizeBaseUrl("192.168.0.10:4567"))
        assertEquals("", RouterAiEngine.normalizeBaseUrl("   "))
        assertEquals("http://sleepysoong.kro.kr:4567/hoard/v1/responses", RouterAiEngine.endpoint("sleepysoong.kro.kr:4567", "responses"))
    }

    @Test fun refusedConnectionIsExplainedNotShownAsAMangledUrl() {
        // The exact JDK/OkHttp wording the user saw.
        val msg = RouterAiEngine.describeConnectError(ConnectException("Failed to connect to sleepysoong.kro.kr/104.251.216.153:4567"))
        assertTrue(msg, msg.contains("(sleepysoong.kro.kr:4567)"))
        assertFalse("no '/IP' fragment that looks like a wrong address: $msg", msg.contains("/104.251.216.153"))
        assertTrue(msg.contains("0.0.0.0"))
    }

    @Test fun otherFailures() {
        assertTrue(RouterAiEngine.describeConnectError(UnknownHostException("nope.invalid")).contains("주소를 찾을 수 없습니다"))
        assertTrue(RouterAiEngine.describeConnectError(SocketTimeoutException("connect timed out")).contains("응답하지 않습니다"))
    }

    @Test fun realRefusedSocketGetsTheExplanation() {
        val port = ServerSocket(0).use { it.localPort } // closed now: connection refused
        val e = runCatching { java.net.Socket("127.0.0.1", port).close() }.exceptionOrNull() as ConnectException
        assertTrue(RouterAiEngine.describeConnectError(e).contains("외부 접속"))
    }
}
