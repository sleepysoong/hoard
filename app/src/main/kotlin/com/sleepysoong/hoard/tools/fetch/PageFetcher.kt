package com.sleepysoong.hoard.tools.fetch

import com.sleepysoong.hoard.tools.readCapped
import com.sleepysoong.hoard.tools.withConnection
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/** Bytes of a downloaded page plus where they actually came from. */
class FetchedPage(
    val requestedUrl: String,
    val finalUrl: String,
    val status: Int,
    /** Media type without parameters, lowercased ("text/html"). */
    val contentType: String,
    val charset: String?,
    val body: ByteArray,
    /** The body was cut at [PageFetcher.maxBytes]. */
    val bodyTruncated: Boolean
)

class FetchException(message: String) : Exception(message)

/**
 * Downloads a public web page for web_fetch.
 *
 * The URL comes from a model — i.e. possibly from a prompt-injected web page — and
 * the request runs from the user's phone, inside their network. So:
 *  - only http/https, no credentials in the URL;
 *  - every address the host resolves to must be public (no loopback, LAN,
 *    link-local/cloud metadata, CGNAT, ULA, multicast…), checked again on
 *    every redirect hop (redirects are followed manually, max [maxRedirects]);
 *  - bounded size ([maxBytes]) and timeouts.
 * Residual risk: DNS can change between the check and the connection (rebinding);
 * HttpURLConnection offers no way to pin the checked address.
 */
class PageFetcher(
    private val addressPolicy: (InetAddress) -> Boolean = ::isPublicAddress,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    val maxBytes: Int = 3 * 1024 * 1024,
    private val maxRedirects: Int = 5,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) {
    suspend fun fetch(url: String): FetchedPage {
        var current = validate(url)
        var hops = 0
        while (true) {
            val conn = current.toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            conn.setRequestProperty("Accept-Language", "ko,en;q=0.8")
            val result = try {
                withConnection(conn) { c ->
                    val status = c.responseCode
                    if (status in 300..399 && status != 304) {
                        Redirect(c.getHeaderField("Location"), status)
                    } else {
                        val stream = (if (status >= 400) c.errorStream else c.inputStream)
                        val raw = stream?.let { if (c.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(it) else it }
                        val (bytes, cut) = raw?.use { it.readCapped(maxBytes) } ?: (ByteArray(0) to false)
                        val (type, charset) = parseContentType(c.contentType)
                        FetchedPage(url, current.toString(), status, type, charset, bytes, cut)
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw FetchException("시간 초과: ${current.host}")
            } catch (e: IOException) {
                throw FetchException("연결 실패: ${current.host} (${e.message})")
            }
            when (result) {
                is FetchedPage -> return result
                is Redirect -> {
                    val location = result.location ?: throw FetchException("HTTP ${result.status} redirect without Location")
                    if (++hops > maxRedirects) throw FetchException("too many redirects (> $maxRedirects)")
                    current = validate(current.resolve(location.trim()).toString())
                }
            }
        }
    }

    private class Redirect(val location: String?, val status: Int)

    /** Throws [FetchException] unless [url] is a public http(s) URL. */
    fun validate(url: String): URI {
        val u = parseUri(url.trim()) ?: throw FetchException("invalid URL: $url")
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw FetchException("only http/https URLs can be fetched")
        if (u.rawUserInfo != null) throw FetchException("URLs with credentials are not allowed")
        val host = u.host?.trim('[', ']') ?: throw FetchException("URL has no host")
        if (host.equals("localhost", true) || host.endsWith(".localhost", true) || host.endsWith(".local", true) || host.endsWith(".internal", true)) {
            throw FetchException("blocked non-public host: $host")
        }
        val addresses = try {
            resolve(host)
        } catch (e: IOException) {
            throw FetchException("unknown host: $host")
        }
        if (addresses.isEmpty()) throw FetchException("unknown host: $host")
        addresses.firstOrNull { !addressPolicy(it) }?.let {
            throw FetchException("blocked non-public address: $host → ${it.hostAddress}")
        }
        return u
    }

    companion object {
        /** Parses a URL; internationalized hosts (한국.kr) become punycode (URI can't hold them). */
        fun parseUri(url: String): URI? {
            val direct = runCatching { URI(url).normalize() }.getOrNull()
            if (direct?.host != null) return direct
            return runCatching {
                val u = URL(url)
                URI(u.protocol, u.userInfo, java.net.IDN.toASCII(u.host), u.port, u.path.ifEmpty { "/" }, u.query, u.ref).normalize()
            }.getOrNull() ?: direct
        }

        const val USER_AGENT = "Hoard/1.0 (Android; +https://github.com/sleepysoong/hoard)"

        fun parseContentType(header: String?): Pair<String, String?> {
            if (header.isNullOrBlank()) return "" to null
            val parts = header.split(";").map { it.trim() }
            val charset = parts.drop(1).firstOrNull { it.startsWith("charset=", true) }
                ?.substringAfter("=")?.trim('"', '\'', ' ')?.takeIf { it.isNotEmpty() }
            return parts[0].lowercase() to charset
        }

        /** Public unicast only. */
        fun isPublicAddress(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
            val b = a.address
            return when (a) {
                is Inet4Address -> isPublicV4(b)
                is Inet6Address -> {
                    // IPv4-mapped (::ffff:a.b.c.d) / compatible: judge the embedded IPv4.
                    val mapped = b.take(10).all { it.toInt() == 0 } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
                    if (mapped) return isPublicV4(b.copyOfRange(12, 16))
                    val first = b[0].toInt() and 0xff
                    if (first and 0xfe == 0xfc) return false // fc00::/7 unique local
                    if (first == 0xfe && (b[1].toInt() and 0xc0) == 0xc0) return false // fec0::/10 site-local
                    if (b[0].toInt() == 0x20 && b[1].toInt() == 0x01 && b[2].toInt() == 0x0d && (b[3].toInt() and 0xff) == 0xb8) return false // 2001:db8::/32 doc
                    true
                }
                else -> false
            }
        }

        private fun isPublicV4(b: ByteArray): Boolean {
            val o0 = b[0].toInt() and 0xff
            val o1 = b[1].toInt() and 0xff
            return when {
                o0 == 0 || o0 == 10 || o0 == 127 -> false
                o0 == 100 && o1 in 64..127 -> false // CGNAT
                o0 == 169 && o1 == 254 -> false // link-local / cloud metadata
                o0 == 172 && o1 in 16..31 -> false
                o0 == 192 && o1 == 168 -> false
                o0 == 192 && o1 == 0 && (b[2].toInt() and 0xff) in listOf(0, 2) -> false
                o0 == 198 && o1 in 18..19 -> false // benchmarking
                o0 == 198 && o1 == 51 && (b[2].toInt() and 0xff) == 100 -> false
                o0 == 203 && o1 == 0 && (b[2].toInt() and 0xff) == 113 -> false
                o0 >= 224 -> false // multicast, reserved, broadcast
                else -> true
            }
        }
    }
}
