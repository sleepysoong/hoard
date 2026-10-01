package com.sleepysoong.hoard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.engine.AttachmentEncoder
import com.sleepysoong.hoard.testing.ChatHarness
import com.sleepysoong.hoard.testing.FakeRouter
import com.sleepysoong.hoard.testing.FakeRouter.Companion.completed
import com.sleepysoong.hoard.testing.FakeRouter.Companion.created
import com.sleepysoong.hoard.testing.FakeRouter.Companion.routingFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Attachments reach the model as real content, not just names.
 * Failure modes: bytes never read (URI lost), wrong part type per kind, generic
 * picker MIME ("image/-star") trusted blindly, huge photos sent raw, unreadable or
 * oversized files silently dropped, old turns re-uploading every file.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AttachmentTest {
    @get:Rule val tmp = TemporaryFolder()
    private var router: FakeRouter? = null
    @After fun stop() { router?.close() }

    private fun png(w: Int, h: Int, name: String): File = tmp.newFile(name).also { f ->
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun noisyJpeg(side: Int, name: String): File = tmp.newFile(name).also { f ->
        val rnd = java.util.Random(1)
        val px = IntArray(side * side) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val bmp = Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
    }

    private fun att(f: File, mime: String) = UiAttachment("att-" + f.name, f.name, mime, f.length(), Uri.fromFile(f))

    private fun encoder(): AttachmentEncoder {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        return AttachmentEncoder(AttachmentEncoder.contentReader(app.contentResolver))
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun eachKindBecomesTheRightPart() {
        val enc = encoder()
        // Photo picker gives "image/*": sniff the real type from the bytes.
        val img = enc.encode(att(png(64, 32, "a.png"), "image/*"))
        assertEquals("input_image", img.s("type"))
        assertTrue(img.s("image_url").startsWith("data:image/png;base64,"))
        val decoded = Base64.decode(img.s("image_url").substringAfter(","), Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        assertEquals("small images are sent untouched", 64, bmp.width)

        val txt = tmp.newFile("notes.md").apply { writeText("# 회의록\n- 결정: 출시 연기") }
        val t = enc.encode(att(txt, "*/*"))
        assertEquals("input_text", t.s("type"))
        assertTrue(t.s("text").contains("[첨부 파일: notes.md]") && t.s("text").contains("결정: 출시 연기"))

        val pdf = tmp.newFile("report.pdf").apply { writeBytes("%PDF-1.4\n%fake\n".toByteArray()) }
        val p = enc.encode(att(pdf, "*/*"))
        assertEquals("input_file", p.s("type"))
        assertEquals("report.pdf", p.s("filename"))
        assertTrue(p.s("file_data").startsWith("data:application/pdf;base64,"))
    }

    @Test fun hugePhotoIsDownscaledToJpeg() {
        val big = noisyJpeg(3000, "camera.jpg")
        assertTrue("fixture is big: ${big.length()}", big.length() > AttachmentEncoder.IMAGE_SEND_AS_IS_BYTES)
        val img = encoder().encode(att(big, "image/*"))
        assertTrue(img.s("image_url").startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.decode(img.s("image_url").substringAfter(","), Base64.DEFAULT)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        assertTrue("longest side ≤ ${AttachmentEncoder.MAX_IMAGE_SIDE}: ${o.outWidth}", maxOf(o.outWidth, o.outHeight) <= AttachmentEncoder.MAX_IMAGE_SIDE)
        assertTrue("smaller than the original", bytes.size < big.length())
    }

    @Test fun unreadableOrTooBigIsReportedNotDropped() {
        val gone = UiAttachment("x", "사라진.png", "image/*", 0, Uri.fromFile(File(tmp.root, "missing.png")))
        val a = encoder().encode(gone)
        assertEquals("input_text", a.s("type"))
        assertTrue(a.s("text"), a.s("text").contains("사라진.png") && a.s("text").contains("보내지 못했습니다"))

        val huge = AttachmentEncoder { ByteArray(AttachmentEncoder.MAX_FILE_BYTES + 1) }
            .encode(UiAttachment("y", "big.bin", "*/*", 0, null))
        assertTrue(huge.s("text").contains("너무 큼"))
    }

    /** The read itself is bounded: a provider stream must not be read to the end before rejection. */
    @Test fun oversizeStreamIsRejectedBeforeReadingItWhole() {
        var bytesRead = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int { bytesRead++; return 0x41 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len, 64 * 1024); bytesRead += n; b.fill(0x41, off, off + n); return n
            }
        }
        val enc = AttachmentEncoder(AttachmentEncoder.contentReader { endless })
        val out = enc.encode(UiAttachment("z", "archive.bin", "*/*", 0, Uri.parse("file:///dev/zero")))
        assertTrue(out.s("text").contains("너무 큼"))
        assertTrue("read stopped at the cap, not at EOF: $bytesRead", bytesRead <= AttachmentEncoder.MAX_IMAGE_BYTES + 1024)
    }

    @Test fun chatSendsFilesOnlyWithTheNewestMessage() {
        val h = ChatHarness()
        router = FakeRouter()
        runBlocking { SettingsStore.setRouterUrl(h.app, router!!.url) }
        router!!.enqueue(
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("사진 봤어요"))),
            FakeRouter.Reply.Sse(listOf(routingFrame(), created(), completed("메모 봤어요")))
        )
        val photo = att(png(40, 40, "p.png"), "image/*")
        h.vm.send("이 사진 뭐야?", listOf(photo), "coding")
        h.awaitReplies()
        val memo = tmp.newFile("todo.txt").apply { writeText("우유 사기") }
        h.vm.send("이 메모도", listOf(att(memo, "text/plain")), "coding")
        h.awaitReplies()

        val first = Json.parseToJsonElement(router!!.requests[0].body).jsonObject["input"]!!.jsonArray
        val firstParts = first.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_text", "input_image"), firstParts.map { it.s("type") })
        assertTrue(firstParts[1].s("image_url").startsWith("data:image/png;base64,"))

        val second = Json.parseToJsonElement(router!!.requests[1].body).jsonObject["input"]!!.jsonArray.map { it.jsonObject }
        val oldTurn = second.first { it["content"]!!.jsonArray[0].jsonObject.s("text").startsWith("이 사진 뭐야?") }
        val oldParts = oldTurn["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("answered turn: named, not re-uploaded", listOf("input_text"), oldParts.map { it.s("type") })
        assertTrue(oldParts[0].s("text").contains("p.png"))
        val newParts = second.last()["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue(newParts.any { it.s("type") == "input_text" && it.s("text").contains("우유 사기") })
        assertTrue("request carries no image bytes the second time", !router!!.requests[1].body.contains("data:image/png"))
    }
}
