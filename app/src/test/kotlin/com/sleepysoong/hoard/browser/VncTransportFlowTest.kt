package com.sleepysoong.hoard.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.zip.Deflater

/** Real RFB socket → decoder → desktop image/input. Failure paths: authentication
 * downgrade, missing password, malformed rectangles, decompression, persistent zlib
 * state, resized displays, stuck keys/buttons, and cancellation during reads or
 * native focus settling without blocking the live frame channel.
 * This wire fixture is test-only, not an SSH or real Chrome verification.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VncTransportFlowTest {
    private fun artifact(name: String, bytes: ByteArray) {
        val dir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "vnc").apply { mkdirs() }
        File(dir, "$name.jpg").writeBytes(bytes)
    }

    @Test fun wholeDesktopIncludesToolbarAndQualityIsNegotiatedOnTheWire() = runBlocking {
        Fixture { peer ->
            peer.handshake(80, 60)
            assertTrue(peer.encodings().contains(-32 + 2))
            peer.request()
            peer.update(2) {
                fill(0, 0, 80, 15, 210, 35, 25)
                fill(0, 15, 80, 45, 40, 150, 55)
            }
            assertTrue(peer.encodings().contains(-32 + 9))
            peer.request()
            peer.update(1) { fill(0, 0, 80, 60, 40, 150, 55) }
        }.use { fixture ->
            VncConnection.open(fixture.port).use { client ->
                val jpeg = client.frame(20)
                artifact("desktop-toolbar", jpeg)
                val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                assertEquals(80, image.width); assertEquals(60, image.height)
                assertTrue("toolbar is not cropped", Color.red(image.getPixel(40, 5)) > 160)
                assertTrue("page content remains in the same image", Color.green(image.getPixel(40, 40)) > 100)
                image.recycle()
                artifact("desktop-high-quality", client.frame(90))
            }
            fixture.verify()
        }
    }

    @Test fun desktopMouseUnicodeAndHeldInputsAreReleased() = runBlocking {
        val packets = CopyOnWriteArrayList<String>()
        Fixture { peer ->
            peer.handshake(80, 60); peer.encodings(); peer.request()
            peer.update(1) { fill(0, 0, 80, 60, 40, 150, 55) }
            while (true) {
                when (val type = peer.input.read()) {
                    -1 -> break
                    5 -> packets += "pointer:${peer.input.readUnsignedByte()}:${peer.input.readUnsignedShort()},${peer.input.readUnsignedShort()}"
                    4 -> {
                        val down = peer.input.readUnsignedByte(); peer.input.readUnsignedShort()
                        packets += "key:$down:${peer.input.readInt()}"
                    }
                    else -> error("unexpected client message $type")
                }
            }
        }.use { fixture ->
            VncConnection.open(fixture.port).use { client ->
                client.frame(55)
                client.input(DesktopInput.Pointer(33, 7, 1))
                client.input(DesktopInput.Key(0xffe3, true))
                client.input(DesktopInput.Text("한A"))
                client.releaseInputs()
            }
            fixture.verify()
        }
        assertTrue(packets.toString(), packets.contains("pointer:1:33,7"))
        assertTrue(packets.toString(), packets.contains("pointer:0:33,7"))
        assertTrue(packets.toString(), packets.contains("key:1:${0x01000000 or '한'.code}"))
        assertTrue(packets.toString(), packets.contains("key:0:${0x01000000 or '한'.code}"))
        assertTrue("explicit text must not become a shortcut under a held modifier",
            packets.indexOf("key:0:65507") < packets.indexOf("key:1:${0x01000000 or '한'.code}"))
        assertEquals("modifier must not remain held", listOf("key:1:65507", "key:0:65507"), packets.filter { it.endsWith(":65507") })
        val dir = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "vnc").apply { mkdirs() }
        File(dir, "input-packets.txt").writeText(packets.joinToString("\n"))
    }

    @Test fun rawPalettePersistentZlibCopyAndDesktopResizeDecodeTogether() = runBlocking {
        Fixture { peer ->
            peer.handshake(80, 60); peer.encodings(); peer.request()
            val deflater = Deflater()
            fun compressed(rgb: ByteArray): ByteArray {
                deflater.setInput(rgb)
                val out = ByteArray(4096)
                return out.copyOf(deflater.deflate(out, 0, out.size, Deflater.SYNC_FLUSH))
            }
            peer.update(3) {
                rect(0, 0, 80, 20, 0)
                repeat(80 * 20) { output.write(byteArrayOf(20, 30, 200.toByte(), 0)) }
                rect(0, 20, 80, 20, 7); output.writeByte(0x52)
                output.writeByte(1); output.writeByte(1) // palette filter, two colours
                output.write(byteArrayOf(20, 160.toByte(), 20, 20, 20, 160.toByte()))
                val paletteStream = Deflater()
                paletteStream.setInput(ByteArray(20 * 10))
                val paletteBytes = ByteArray(4096)
                compact(paletteBytes.copyOf(paletteStream.deflate(paletteBytes, 0, paletteBytes.size, Deflater.SYNC_FLUSH)))
                paletteStream.end()
                rect(0, 40, 80, 20, 7); output.writeByte(0x01) // reset stream 0
                val rgb = ByteArray(80 * 20 * 3) { if (it % 3 == 2) 190.toByte() else 25 }
                compact(compressed(rgb))
            }
            peer.request()
            peer.update(2) {
                rect(0, 40, 80, 20, 7); output.writeByte(0) // reuse the same zlib stream
                val rgb = ByteArray(80 * 20 * 3) { if (it % 3 == 0) 200.toByte() else 25 }
                compact(compressed(rgb))
                rect(0, 0, 80, 20, 1); output.writeShort(0); output.writeShort(40)
            }
            peer.request()
            peer.update(2) { rect(0, 0, 90, 70, -223); fill(0, 0, 90, 70, 20, 160, 20) }
            deflater.end()
        }.use { fixture ->
            VncConnection.open(fixture.port).use { client ->
                val first = client.frame(90); artifact("mixed-encodings", first)
                BitmapFactory.decodeByteArray(first, 0, first.size).also {
                    assertTrue(Color.red(it.getPixel(40, 10)) > 160)
                    assertTrue(Color.green(it.getPixel(40, 30)) > 130)
                    assertTrue(Color.blue(it.getPixel(40, 50)) > 160)
                    it.recycle()
                }
                val copied = client.frame(90); artifact("persistent-stream-copy", copied)
                BitmapFactory.decodeByteArray(copied, 0, copied.size).also {
                    assertTrue(Color.red(it.getPixel(40, 10)) > 160); it.recycle()
                }
                val resized = client.frame(90); artifact("resized-desktop", resized)
                BitmapFactory.decodeByteArray(resized, 0, resized.size).also {
                    assertEquals(90, it.width); assertEquals(70, it.height); it.recycle()
                }
            }
            fixture.verify()
        }
    }

    @Test fun malformedDesktopRectanglesFailInsteadOfAllocatingOrShowingStalePixels() = runBlocking {
        Fixture { peer ->
            peer.handshake(80, 60); peer.encodings(); peer.request()
            peer.update(1) { rect(79, 0, 65535, 65535, 0) }
        }.use { fixture ->
            VncConnection.open(fixture.port).use { client ->
                try { client.frame(55); fail("out-of-bounds frame must be refused") }
                catch (e: BrowserException) { assertTrue(e.message.orEmpty().contains("VNC")) }
            }
            fixture.verify()
        }
    }

    @Test fun cancellingASilentFrameClosesTheSocketPromptly() = runBlocking {
        val requested = CountDownLatch(1)
        Fixture { peer ->
            peer.handshake(80, 60)
            assertEquals(4, peer.input.readUnsignedByte()); assertEquals(1, peer.input.readUnsignedByte())
            peer.input.readUnsignedShort(); assertEquals(0xffe3, peer.input.readInt())
            peer.encodings(); peer.request(); requested.countDown()
            assertEquals("cancelled capture releases a held modifier", 4, peer.input.readUnsignedByte())
            assertEquals(0, peer.input.readUnsignedByte()); peer.input.readUnsignedShort(); assertEquals(0xffe3, peer.input.readInt())
            assertEquals("cancelled capture disconnects, no receive loop remains", -1, peer.input.read())
        }.use { fixture ->
            VncConnection.open(fixture.port).use { client ->
                client.input(DesktopInput.Key(0xffe3, true))
                val waiting = async { client.frame(55) }
                while (requested.count > 0) delay(10)
                val start = System.nanoTime()
                waiting.cancelAndJoin()
                assertTrue("cancellation cannot wait for the read timeout", System.nanoTime() - start < 1_000_000_000)
            }
            fixture.verify()
        }
    }

    @Test fun authenticationIsNeverSilentlyDowngradedAndMissingPasswordIsExplained() = runBlocking {
        for (configured in listOf(false, true)) Fixture { peer ->
            peer.output.write("RFB 003.008\n".toByteArray()); peer.output.flush()
            peer.input.readFully(ByteArray(12))
            peer.output.write(byteArrayOf(2, 1, 2)); peer.output.flush()
            assertEquals(if (configured) 2 else 1, peer.input.readUnsignedByte())
            if (configured) {
                peer.output.write(ByteArray(16)); peer.output.flush()
                peer.input.readFully(ByteArray(16))
            }
            peer.output.writeInt(1); peer.output.writeInt(6); peer.output.write("denied".toByteArray()); peer.output.flush()
        }.use { fixture ->
            try { VncConnection.open(fixture.port, if (configured) "testpw" else ""); fail("server rejection must be reported") }
            catch (e: BrowserException) { assertTrue(e.message.orEmpty().contains("VNC")) }
            fixture.verify()
        }
        Fixture { peer ->
            peer.output.write("RFB 003.008\n".toByteArray()); peer.output.flush()
            peer.input.readFully(ByteArray(12))
            peer.output.write(byteArrayOf(1, 2)); peer.output.flush()
            assertEquals(-1, peer.input.read())
        }.use { fixture ->
            try { VncConnection.open(fixture.port); fail("VNC password is required") }
            catch (e: BrowserException) { assertTrue(e.message.orEmpty().contains("비밀번호")) }
            fixture.verify()
        }
    }

    @Test fun textFocusWaitDoesNotBlockFramesOrSurviveAClosedDesktop() = runBlocking {
        val released = CountDownLatch(1)
        val packets = CopyOnWriteArrayList<String>()
        val focusWait = StandardTestDispatcher()
        Fixture { peer ->
            peer.handshake(80, 60); peer.encodings(); peer.request()
            peer.update(1) { fill(0, 0, 80, 60, 40, 150, 55) }
            while (true) when (val type = peer.input.read()) {
                -1 -> break
                4 -> {
                    val down = peer.input.readUnsignedByte(); peer.input.readUnsignedShort()
                    val sym = peer.input.readInt(); packets += "key:$down:$sym"
                    if (down == 0 && sym == 0xffe3) released.countDown()
                }
                3 -> {
                    peer.input.readFully(ByteArray(9))
                    peer.update(1) { fill(0, 0, 80, 60, 40, 150, 55) }
                }
                else -> error("unexpected client message $type")
            }
        }.use { fixture ->
            VncConnection.open(fixture.port).use { client ->
                client.frame(55)
                client.input(DesktopInput.Key(0xffe3, true))
                val typing = async(focusWait, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { client.input(DesktopInput.Text("must not reach the closed desktop")) }
                }
                assertTrue("text first releases the remote modifier", released.await(2, TimeUnit.SECONDS))
                focusWait.scheduler.runCurrent()
                assertFalse("text waits for the native focus change", typing.isCompleted)
                artifact("focus-settling-live-frame", withTimeout(2_000) { client.frame(55) })
                client.close()
                focusWait.scheduler.advanceUntilIdle()
                val failure = withTimeout(2_000) { typing.await() }.exceptionOrNull()
                assertTrue("pending text cannot outlive the desktop: $failure", failure is BrowserException)
            }
            fixture.verify()
        }
        assertEquals("closing during focus settling must not deliver any text", listOf("key:1:65507", "key:0:65507"), packets.toList())
    }

    @Test fun closingBeforeTheIoWorkerStartsDoesNotStrandThePendingFrame() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val unblock = CountDownLatch(1)
        try {
            Fixture { peer ->
                peer.handshake(80, 60)
                assertEquals("closed before any frame request", -1, peer.input.read())
            }.use { fixture ->
                VncConnection.open(fixture.port, ioDispatcher = dispatcher).use { client ->
                    val occupied = CountDownLatch(1)
                    executor.submit { occupied.countDown(); unblock.await(4, TimeUnit.SECONDS) }
                    assertTrue(occupied.await(2, TimeUnit.SECONDS))
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.frame(55) } }
                    client.close()
                    val failure = withTimeout(2_000) { pending.await() }.exceptionOrNull()
                    assertTrue("external close must resolve even an unstarted frame: $failure", failure is BrowserException)
                }
                fixture.verify()
            }
        } finally { unblock.countDown(); dispatcher.close() }
    }

    private class Fixture(script: (Peer) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        @Volatile private var failure: Throwable? = null
        private val done = CountDownLatch(1)
        private val thread = Thread({
            try { server.accept().use { it.soTimeout = 5_000; script(Peer(it)) } }
            catch (e: Throwable) { failure = e }
            finally { done.countDown() }
        }, "rfb-test-peer").apply { isDaemon = true; start() }
        fun verify() { assertTrue("fixture finished", done.await(6, TimeUnit.SECONDS)); failure?.let { throw AssertionError("RFB fixture failed", it) } }
        override fun close() { server.close(); thread.join(1_000) }
    }

    private class Peer(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        fun handshake(w: Int, h: Int) {
            output.write("RFB 003.008\n".toByteArray()); output.flush()
            val version = ByteArray(12).also(input::readFully)
            assertEquals("RFB 003.008\n", version.decodeToString())
            output.write(byteArrayOf(1, 1)); output.flush()
            assertEquals(1, input.readUnsignedByte())
            output.writeInt(0); output.flush()
            assertEquals("shared desktop", 1, input.readUnsignedByte())
            output.writeShort(w); output.writeShort(h)
            output.write(byteArrayOf(32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
            output.writeInt(7); output.write("desktop".toByteArray()); output.flush()
            assertEquals(0, input.readUnsignedByte()); input.readFully(ByteArray(3))
            val format = ByteArray(16).also(input::readFully)
            assertEquals(32, format[0].toInt()); assertEquals(1, format[3].toInt())
        }
        fun encodings(): List<Int> {
            assertEquals(2, input.readUnsignedByte()); input.readByte()
            return List(input.readUnsignedShort()) { input.readInt() }
        }
        fun request() { assertEquals(3, input.readUnsignedByte()); input.readFully(ByteArray(9)) }
        fun update(count: Int, rectangles: Peer.() -> Unit) {
            output.writeByte(0); output.writeByte(0); output.writeShort(count); rectangles(); output.flush()
        }
        fun rect(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
            output.writeShort(x); output.writeShort(y); output.writeShort(w); output.writeShort(h); output.writeInt(encoding)
        }
        fun fill(x: Int, y: Int, w: Int, h: Int, r: Int, g: Int, b: Int) {
            rect(x, y, w, h, 7); output.writeByte(0x80); output.write(byteArrayOf(r.toByte(), g.toByte(), b.toByte()))
        }
        fun compact(bytes: ByteArray) {
            var size = bytes.size
            output.writeByte((size and 127) or if (size > 127) 128 else 0)
            if (size > 127) {
                size = size ushr 7
                output.writeByte((size and 127) or if (size > 127) 128 else 0)
                if (size > 127) output.writeByte(size ushr 7)
            }
            output.write(bytes)
        }
    }
}
