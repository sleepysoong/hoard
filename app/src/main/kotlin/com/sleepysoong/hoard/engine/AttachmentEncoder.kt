package com.sleepysoong.hoard.engine

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.tools.readCapped
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * Turns picked attachments into OpenAI Responses input parts:
 *  - images            → `input_image` with a base64 data URL (downscaled when huge)
 *  - text-like files   → `input_text` with the file contents inlined (works on every provider,
 *                         including sleepyrouter's Chat Completions bridge)
 *  - anything else     → `input_file` (filename + base64 data URL, e.g. PDF)
 * A file that can't be read or is too large is never silently dropped: the model gets
 * an `input_text` note saying so, and the user sees it in the request trace.
 */
class AttachmentEncoder(private val read: (UiAttachment) -> ByteArray?) {

    fun encode(a: UiAttachment): JsonObject {
        val bytes = try {
            read(a)
        } catch (e: Exception) {
            return note(a, "읽을 수 없음: ${e.javaClass.simpleName}")
        } ?: return note(a, "읽을 수 없음")
        val mime = sniffMime(a, bytes)
        // Photos are re-encoded small below, so only raw-sent content is capped tightly.
        val cap = if (mime.startsWith("image/")) MAX_IMAGE_BYTES else MAX_FILE_BYTES
        if (bytes.size > cap) return note(a, "너무 큼 (${bytes.size / 1_000_000}MB > ${cap / 1_000_000}MB)")
        return when {
            mime.startsWith("image/") -> {
                val (outMime, outBytes) = shrinkImage(mime, bytes)
                buildJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:$outMime;base64," + b64(outBytes))
                }
            }
            isTextLike(mime, a.name) -> {
                val text = bytes.decodeToString()
                val body = if (text.length > MAX_INLINE_CHARS) text.take(MAX_INLINE_CHARS) + "\n…(이하 ${text.length - MAX_INLINE_CHARS}자 생략)" else text
                buildJsonObject {
                    put("type", "input_text")
                    put("text", "[첨부 파일: ${a.name}]\n```\n$body\n```")
                }
            }
            else -> buildJsonObject {
                put("type", "input_file")
                put("filename", a.name)
                put("file_data", "data:$mime;base64," + b64(bytes))
            }
        }
    }

    private fun note(a: UiAttachment, why: String) = buildJsonObject {
        put("type", "input_text")
        put("text", "[첨부 파일 ${a.name} 을(를) 보내지 못했습니다: $why]")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** Photos from the camera can be 10+ MB; send ≤ [MAX_IMAGE_SIDE] px JPEG instead. */
    private fun shrinkImage(mime: String, bytes: ByteArray): Pair<String, ByteArray> {
        if (bytes.size <= IMAGE_SEND_AS_IS_BYTES) return mime to bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return mime to bytes
        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return mime to bytes
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val buf = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, buf)
        return "image/jpeg" to buf.toByteArray()
    }

    companion object {
        /** Base64 + JSON inflate a file ~2.7× in memory while sending; 10 MB keeps that bounded. */
        const val MAX_FILE_BYTES = 10_000_000
        /** Camera photos are often >10 MB; they are downscaled before sending. */
        const val MAX_IMAGE_BYTES = 40_000_000
        const val IMAGE_SEND_AS_IS_BYTES = 1_500_000
        const val MAX_IMAGE_SIDE = 2048
        const val MAX_INLINE_CHARS = 100_000

        private val TEXT_EXT = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "log",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp",
            "swift", "rb", "php", "sh", "sql", "html", "css", "gradle", "properties"
        )

        fun isTextLike(mime: String, name: String): Boolean =
            mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/x-yaml", "application/toml") ||
                name.substringAfterLast('.', "").lowercase() in TEXT_EXT

        /** Picker MIME is often generic ("image/…" or "any"): trust magic bytes, then the extension. */
        fun sniffMime(a: UiAttachment, b: ByteArray): String {
            fun starts(vararg sig: Int) = b.size >= sig.size && sig.indices.all { (b[it].toInt() and 0xFF) == sig[it] }
            return when {
                starts(0x89, 0x50, 0x4E, 0x47) -> "image/png"
                starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
                starts(0x47, 0x49, 0x46, 0x38) -> "image/gif"
                b.size >= 12 && starts(0x52, 0x49, 0x46, 0x46) && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "image/webp"
                starts(0x25, 0x50, 0x44, 0x46) -> "application/pdf"
                a.mime.isNotBlank() && !a.mime.endsWith("/*") -> a.mime
                else -> when (a.name.substringAfterLast('.', "").lowercase()) {
                    "pdf" -> "application/pdf"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "json" -> "application/json"
                    else -> if (a.mime.startsWith("image/")) "image/jpeg" else "application/octet-stream"
                }
            }
        }

        /** Reads through the ContentResolver (photo picker / SAF / file URIs). */
        fun contentReader(cr: ContentResolver): (UiAttachment) -> ByteArray? =
            contentReader { uri -> cr.openInputStream(uri) }

        /**
         * Bounded read: never loads more than [MAX_IMAGE_BYTES] + 1 byte, so a huge or
         * misbehaving provider stream is rejected by [encode]'s size note instead of OOMing.
         */
        internal fun contentReader(open: (Uri) -> java.io.InputStream?): (UiAttachment) -> ByteArray? = { a ->
            a.uri?.let { uri ->
                open(uri)?.use { stream ->
                    stream.readCapped(MAX_IMAGE_BYTES + 1).first
                }
            }
        }

        /**
         * Real name / MIME / size for a picked URI. Document URIs also get a persistable
         * read grant, so the background worker can still read them later.
         */
        fun describe(cr: ContentResolver, uri: Uri, id: String, fallbackName: String): UiAttachment {
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = fallbackName
            var size = 0L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val mime = runCatching { cr.getType(uri) }.getOrNull() ?: ""
            return UiAttachment(id = id, name = name, mime = mime, sizeBytes = size, uri = uri)
        }
    }
}
