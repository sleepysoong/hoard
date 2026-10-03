package com.sleepysoong.hoard.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Bounded RFB 3.3/3.7/3.8 client for an SSH loopback forward, never an exposed VNC
 * endpoint. Tight compression negotiates JPEG quality on the server; raw/copy/fill,
 * palette and gradient blocks are also supported. There is no autonomous receive
 * loop: the visible UI requests one frame at a time, at most 10fps.
 */
class VncConnection private constructor(private val ioDispatcher: CoroutineDispatcher) : AutoCloseable {
    private val socket = Socket()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean()
    private val frameLock = Mutex()
    private val writeLock = Any()
    private val pendingIo = linkedSetOf<CompletableDeferred<*>>()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val streams = Array(4) { Inflater() }
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var pixels = IntArray(0)
    private var lastQuality = -1
    private val heldKeys = linkedSetOf<Int>()
    private var buttons = 0
    private var pointerX = 0
    private var pointerY = 0
    val isOpen: Boolean get() = !closed.get() && socket.isConnected && !socket.isClosed

    /** Cancelling a blocked read closes its socket immediately, not after a timeout. */
    private suspend fun <T> io(block: () -> T): T {
        val result = CompletableDeferred<T>()
        synchronized(pendingIo) {
            if (closed.get()) throw BrowserException("VNC 연결이 닫혔습니다")
            pendingIo += result
        }
        val worker = scope.launch(ioDispatcher) {
            try {
                result.complete(block())
            } catch (e: Exception) {
                result.completeExceptionally(
                    if (e is BrowserException) e else BrowserException("VNC 화면 연결 실패: ${e.message}", e))
                close()
            }
        }
        try { return result.await() }
        catch (cancelled: CancellationException) { close(); throw cancelled }
        finally {
            synchronized(pendingIo) { pendingIo -= result }
            worker.cancel()
        }
    }

    private fun connect(port: Int, password: String) {
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 5_000
        socket.tcpNoDelay = true
        input = DataInputStream(socket.getInputStream().buffered())
        output = DataOutputStream(socket.getOutputStream().buffered())
        val version = ByteArray(12).also(input::readFully).decodeToString()
        val minor = when (version) {
            "RFB 003.003\n" -> 3
            "RFB 003.007\n" -> 7
            "RFB 003.008\n", "RFB 003.889\n" -> 8
            else -> throw BrowserException("VNC 프로토콜을 지원하지 않습니다")
        }
        output.write("RFB 003.00$minor\n".toByteArray()); output.flush()
        val security = if (minor == 3) input.readInt() else {
            val count = input.readUnsignedByte()
            if (count == 0) throw BrowserException("VNC 연결이 거부되었습니다: ${reason()}")
            val types = ByteArray(count).also(input::readFully).map { it.toInt() and 255 }
            val selected = when {
                password.isNotEmpty() && 2 in types -> 2
                password.isEmpty() && 1 in types -> 1
                password.isEmpty() && 2 in types -> throw BrowserException("VNC 비밀번호가 필요합니다. 설정 → 원격 브라우저에서 저장하세요")
                else -> throw BrowserException("VNC 인증 방식을 지원하지 않습니다 (SSH 터널 + 없음/기존 VNC 비밀번호 인증만 지원)")
            }
            output.writeByte(selected); output.flush(); selected
        }
        when (security) {
            0 -> throw BrowserException("VNC 연결이 거부되었습니다: ${reason()}")
            1 -> if (password.isNotEmpty()) throw BrowserException("VNC 서버가 비밀번호 인증을 제공하지 않습니다. 설정의 VNC 비밀번호와 서버를 확인하세요")
            2 -> {
                if (password.isEmpty()) throw BrowserException("VNC 비밀번호가 필요합니다. 설정 → 원격 브라우저에서 저장하세요")
                val challenge = ByteArray(16).also(input::readFully)
                val secret = password.toByteArray(Charsets.ISO_8859_1).copyOf(8)
                for (i in secret.indices) secret[i] = (Integer.reverse(secret[i].toInt() and 255) ushr 24).toByte()
                val cipher = Cipher.getInstance("DES/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "DES"))
                output.write(cipher.doFinal(challenge)); output.flush(); secret.fill(0)
            }
            else -> throw BrowserException("VNC 인증 방식을 지원하지 않습니다")
        }
        if (security == 2 || minor >= 8) {
            if (input.readInt() != 0) throw BrowserException("VNC 인증 실패" + if (minor >= 8) ": ${reason()}" else "")
        }
        output.writeByte(1); output.flush() // shared: never disconnect another human viewer
        resize(input.readUnsignedShort(), input.readUnsignedShort())
        input.readFully(ByteArray(16)) // request our own fixed true-colour format below
        skip(input.readInt(), 16_384)
        synchronized(writeLock) {
            output.write(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
            output.flush()
        }
    }

    suspend fun frame(quality: Int = 55): ByteArray = frameLock.withLock {
        io {
            synchronized(writeLock) {
                val q = quality.coerceIn(1, 100)
                if (lastQuality != q) {
                    val encodings = intArrayOf(7, 1, 0, -223, -224, -256 + 6, -32 + (q / 10).coerceIn(0, 9), -512 + q)
                    output.writeByte(2); output.writeByte(0); output.writeShort(encodings.size)
                    encodings.forEach(output::writeInt); lastQuality = q
                }
                output.writeByte(3); output.writeByte(0) // full update: static pages cannot stall forever
                output.writeShort(0); output.writeShort(0); output.writeShort(width); output.writeShort(height)
                output.flush()
            }
            var received = false
            repeat(128) {
                if (!received) when (input.readUnsignedByte()) {
                    0 -> { readUpdate(); received = true }
                    2 -> Unit // bell
                    3 -> { input.readFully(ByteArray(3)); skip(input.readInt(), 1_048_576) } // clipboard is never imported
                    else -> throw BrowserException("VNC 서버 메시지를 지원하지 않습니다")
                }
            }
            checkFrame(received, "VNC 화면 업데이트를 받지 못했습니다")
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), it) }
                    .toByteArray().also { checkFrame(it.size <= MAX_BYTES, "VNC 이미지 크기 제한 초과") }
            } finally { bitmap.recycle() }
        }
    }

    private fun readUpdate() {
        input.readByte()
        val count = input.readUnsignedShort()
        checkFrame(count <= 4096 || count == 65535, "VNC 사각형 개수 제한 초과")
        repeat(minOf(count, 4096)) {
            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort(); val encoding = input.readInt()
            if (encoding == -224) return
            if (encoding == -223) { resize(w, h); return@repeat }
            checkFrame(w > 0 && h > 0 && x + w <= width && y + h <= height, "VNC 화면 좌표가 범위를 벗어났습니다")
            when (encoding) {
                0 -> {
                    val row = ByteArray(w * 4)
                    repeat(h) { dy ->
                        input.readFully(row)
                        repeat(w) { dx ->
                            val p = dx * 4
                            pixels[(y + dy) * width + x + dx] = rgb(row[p + 2], row[p + 1], row[p])
                        }
                    }
                }
                1 -> {
                    val sx = input.readUnsignedShort(); val sy = input.readUnsignedShort()
                    checkFrame(sx + w <= width && sy + h <= height, "VNC 복사 좌표가 범위를 벗어났습니다")
                    val rows = if (y > sy) h - 1 downTo 0 else 0 until h
                    for (dy in rows) pixels.copyInto(pixels, (y + dy) * width + x, (sy + dy) * width + sx, (sy + dy) * width + sx + w)
                }
                7 -> tight(x, y, w, h)
                else -> throw BrowserException("VNC 이미지 인코딩을 지원하지 않습니다: $encoding")
            }
        }
        checkFrame(count != 65535, "VNC 업데이트 종료 표시를 받지 못했습니다")
    }

    private fun tight(x: Int, y: Int, w: Int, h: Int) {
        val control = input.readUnsignedByte()
        for (i in 0..3) if (control and (1 shl i) != 0) synchronized(streams[i]) { streams[i].reset() }
        val kind = control ushr 4
        when (kind) {
            8 -> {
                val colour = rgb(input.readByte(), input.readByte(), input.readByte())
                repeat(h) { dy -> java.util.Arrays.fill(pixels, (y + dy) * width + x, (y + dy) * width + x + w, colour) }
            }
            9 -> {
                val bytes = compactBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                checkFrame(bounds.outWidth == w && bounds.outHeight == h, "VNC JPEG 크기가 사각형과 다릅니다")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw BrowserException("VNC JPEG를 읽을 수 없습니다")
                try { bitmap.getPixels(pixels, y * width + x, width, 0, 0, w, h) } finally { bitmap.recycle() }
            }
            in 0..7 -> {
                val filter = if (kind and 4 != 0) input.readUnsignedByte() else 0
                val palette = if (filter == 1) IntArray(input.readUnsignedByte() + 1) {
                    rgb(input.readByte(), input.readByte(), input.readByte())
                } else null
                checkFrame(filter in 0..2 && (palette == null || palette.size >= 2), "VNC 필터를 지원하지 않습니다")
                val rowSize = if (palette?.size == 2) (w + 7) / 8 else w * if (palette == null) 3 else 1
                val size = rowSize * h
                val data = if (size < 12) ByteArray(size).also(input::readFully) else {
                    val compressed = compactBytes()
                    val stream = streams[kind and 3]
                    synchronized(stream) {
                        stream.setInput(compressed)
                        ByteArray(size).also { decoded ->
                            var n = 0
                            while (n < size) {
                                val read = stream.inflate(decoded, n, size - n)
                                checkFrame(read > 0, "VNC 압축 데이터가 잘렸습니다")
                                n += read
                            }
                            val extra = ByteArray(1)
                            checkFrame(stream.inflate(extra) == 0 && stream.needsInput(), "VNC 압축 데이터 크기가 잘못되었습니다")
                        }
                    }
                }
                val previous = IntArray(w * 3)
                repeat(h) { dy ->
                    val left = IntArray(3); val aboveLeft = IntArray(3)
                    repeat(w) { dx ->
                        val colour = when {
                            palette?.size == 2 -> palette[(data[dy * rowSize + dx / 8].toInt() ushr (7 - dx % 8)) and 1]
                            palette != null -> {
                                val index = data[dy * rowSize + dx].toInt() and 255
                                checkFrame(index < palette.size, "VNC 팔레트 범위를 벗어났습니다"); palette[index]
                            }
                            filter == 2 -> {
                                val channels = IntArray(3) { c ->
                                    val up = previous[dx * 3 + c]
                                    val predicted = (left[c] + up - aboveLeft[c]).coerceIn(0, 255)
                                    val value = (predicted + (data[(dy * w + dx) * 3 + c].toInt() and 255)) and 255
                                    aboveLeft[c] = up; previous[dx * 3 + c] = value; left[c] = value; value.toByte().toInt()
                                }
                                rgb(channels[0].toByte(), channels[1].toByte(), channels[2].toByte())
                            }
                            else -> { val p = (dy * w + dx) * 3; rgb(data[p], data[p + 1], data[p + 2]) }
                        }
                        pixels[(y + dy) * width + x + dx] = colour
                    }
                }
            }
            else -> throw BrowserException("VNC Tight 인코딩을 지원하지 않습니다")
        }
    }

    suspend fun input(event: DesktopInput) = io { synchronized(writeLock) {
        when (event) {
            is DesktopInput.Pointer -> pointer(event.x, event.y, event.buttons and 7)
            is DesktopInput.Scroll -> {
                fun wheel(delta: Int, negative: Int, positive: Int) {
                    if (delta != 0) repeat((abs(delta.toLong()) / 80).toInt().coerceIn(1, 20)) {
                        val mask = buttons
                        pointer(event.x, event.y, mask or if (delta < 0) negative else positive)
                        pointer(event.x, event.y, mask)
                    }
                }
                wheel(event.dy, 8, 16); wheel(event.dx, 32, 64)
            }
            is DesktopInput.Key -> key(event.keysym, event.down)
            is DesktopInput.Chord -> {
                checkFrame(event.keys.size in 1..8, "VNC 키 조합 크기 제한 초과")
                try { event.keys.forEach { key(it, true) } } finally { event.keys.asReversed().forEach { key(it, false) } }
            }
            is DesktopInput.Text -> {
                releaseHeld()
                val points = event.text.codePoints().toArray()
                checkFrame(points.size <= 2000, "VNC 텍스트는 한 번에 2000자까지 입력할 수 있습니다")
                points.forEach { code ->
                    val sym = when (code) { 10, 13 -> 0xff0d; 9 -> 0xff09; in 32..255 -> code; in 256..0x10ffff -> 0x01000000 or code; else -> 0 }
                    if (sym != 0) { key(sym, true); key(sym, false) }
                }
            }
            DesktopInput.ReleaseHeld -> releaseHeld()
        }
        output.flush()
    } }

    suspend fun releaseInputs() = io { synchronized(writeLock) {
        releaseHeld()
        output.flush()
    } }

    private fun releaseHeld() {
        heldKeys.toList().asReversed().forEach { key(it, false) }
        if (buttons != 0) pointer(pointerX, pointerY, 0)
    }

    private fun pointer(x: Int, y: Int, mask: Int) {
        pointerX = x.coerceIn(0, width - 1); pointerY = y.coerceIn(0, height - 1); buttons = mask
        output.writeByte(5); output.writeByte(mask); output.writeShort(pointerX); output.writeShort(pointerY)
    }
    private fun key(sym: Int, down: Boolean) {
        checkFrame(sym > 0, "VNC 키 값이 잘못되었습니다")
        if (down) { checkFrame(heldKeys.size < 64 || sym in heldKeys, "VNC 누른 키 개수 제한 초과"); heldKeys += sym }
        else heldKeys -= sym
        output.writeByte(4); output.writeByte(if (down) 1 else 0); output.writeShort(0); output.writeInt(sym)
    }
    private fun resize(w: Int, h: Int) {
        checkFrame(w > 0 && h > 0 && w.toLong() * h <= MAX_PIXELS, "VNC 화면 크기 제한 초과")
        width = w; height = h; pixels = IntArray(w * h)
    }
    private fun compactBytes(): ByteArray {
        var size = input.readUnsignedByte(); val first = size
        size = size and 127
        if (first and 128 != 0) {
            val second = input.readUnsignedByte(); size = size or ((second and 127) shl 7)
            if (second and 128 != 0) size = size or (input.readUnsignedByte() shl 14)
        }
        checkFrame(size in 1..MAX_BYTES, "VNC 압축 이미지 크기 제한 초과")
        return ByteArray(size).also(input::readFully)
    }
    private fun reason(): String {
        val size = input.readInt(); checkFrame(size in 0..16_384, "VNC 오류 메시지 크기 제한 초과")
        return ByteArray(size).also(input::readFully).decodeToString().take(160)
    }
    private fun skip(size: Int, limit: Int) {
        checkFrame(size in 0..limit, "VNC 메시지 크기 제한 초과")
        var remaining = size; val chunk = ByteArray(4096)
        while (remaining > 0) { val n = minOf(remaining, chunk.size); input.readFully(chunk, 0, n); remaining -= n }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Resolve callers independently of the IO dispatcher: a cancelled worker
        // may still be queued behind unrelated work and never reach its body.
        val pending = synchronized(pendingIo) { pendingIo.toList().also { pendingIo.clear() } }
        pending.forEach { it.completeExceptionally(BrowserException("VNC 연결이 닫혔습니다")) }
        runCatching { socket.shutdownInput() }
        // Release held input even on a cancelled capture. A dead write path must
        // not delay disconnecting the blocked reader or block the UI thread.
        scope.launch { delay(150); runCatching { socket.close() } }
        scope.launch {
            try {
                synchronized(writeLock) {
                    if (::output.isInitialized) {
                        releaseHeld()
                        output.flush()
                    }
                }
            } catch (_: Exception) { /* A severed transport cannot deliver releases. */ }
            finally {
                runCatching { socket.close() }
                streams.forEach { synchronized(it) { it.end() } }
                scope.cancel()
            }
        }
    }

    companion object {
        private const val MAX_PIXELS = 8_000_000L
        private const val MAX_BYTES = 5 * 1024 * 1024
        private fun rgb(r: Byte, g: Byte, b: Byte) = 0xff000000.toInt() or ((r.toInt() and 255) shl 16) or ((g.toInt() and 255) shl 8) or (b.toInt() and 255)
        private fun checkFrame(condition: Boolean, message: String) { if (!condition) throw BrowserException(message) }
        suspend fun open(localPort: Int, password: String = "", ioDispatcher: CoroutineDispatcher = Dispatchers.IO): VncConnection {
            val client = VncConnection(ioDispatcher)
            try { client.io { client.connect(localPort, password) }; return client }
            catch (e: Exception) { client.close(); throw e }
        }
    }
}
