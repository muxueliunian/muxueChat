package com.muxuechat.data.local

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.muxuechat.domain.model.ImageInputSpec
import com.muxuechat.domain.model.PendingImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** 导入一张图片的结果。失败原因是给用户看的中文，不含 URI 或路径。 */
sealed interface ImageImportResult {
    data class Success(val image: PendingImage) : ImageImportResult
    data class Failure(val reason: String) : ImageImportResult
}

/**
 * 把系统 Photo Picker 选中的图片解码、压缩成待发送 JPEG（D3、MULTIMODAL_PLAN 3.6）。
 *
 * - `ImageDecoder` 按目标尺寸降采样解码，并自动按 EXIF 方向转正；
 * - 带透明通道的 PNG/WebP 先铺白底：JPEG 没有透明度，直接压会变黑底；
 * - GIF 与动图只取首帧；HEIC 由系统解码后同样转 JPEG；
 * - 重新编码会丢弃 EXIF（含 GPS），这是期望行为，绝不上传原图字节。
 *
 * 日志只记异常类型与尺寸：`ImageDecoder` / `ContentResolver` 的异常文案可能带 URI。
 */
class ChatImageImporter(
    private val contentResolver: ContentResolver,
    private val store: ChatImageStore,
) {
    suspend fun import(uri: Uri): ImageImportResult = withContext(Dispatchers.IO) {
        val pendingId = ChatImageStore.newId()
        try {
            val bitmap = decode(uri)
            try {
                val opaque = if (bitmap.hasAlpha()) bitmap.onWhiteBackground() else bitmap
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var byteCount = 0L
                    store.writePending(pendingId) { output ->
                        val counting = CountingOutputStream(DigestOutputStream(output, digest)) { byteCount += it }
                        if (!opaque.compress(Bitmap.CompressFormat.JPEG, ImageInputSpec.JPEG_QUALITY, counting)) {
                            throw IOException("图片压缩失败")
                        }
                        counting.flush()
                    }
                    Log.i(TAG, "Image imported: width=${opaque.width} height=${opaque.height} bytes=$byteCount")
                    ImageImportResult.Success(
                        PendingImage(
                            id = pendingId,
                            mimeType = ImageInputSpec.MIME_JPEG,
                            width = opaque.width,
                            height = opaque.height,
                            byteSize = byteCount,
                            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                        ),
                    )
                } finally {
                    if (opaque !== bitmap) opaque.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Image import denied: ${error.javaClass.simpleName}")
            store.discardPending(pendingId)
            ImageImportResult.Failure("没有权限读取这张图片，请重新选择。")
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Image import out of memory")
            store.discardPending(pendingId)
            ImageImportResult.Failure("图片过大，无法处理，请换一张。")
        } catch (error: IOException) {
            Log.w(TAG, "Image import failed: ${error.javaClass.simpleName}")
            store.discardPending(pendingId)
            ImageImportResult.Failure("无法读取这张图片，格式可能不受支持。")
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Image import rejected: ${error.javaClass.simpleName}")
            store.discardPending(pendingId)
            ImageImportResult.Failure("无法读取这张图片，格式可能不受支持。")
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = ImageInputSpec.targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            // 软件位图才能被 compress 与 Canvas 读取；硬件位图在这两处都会失败。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun Bitmap.onWhiteBackground(): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@onWhiteBackground, 0f, 0f, null)
        }
        return result
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
        private val onBytes: (Int) -> Unit,
    ) : OutputStream() {
        override fun write(b: Int) {
            delegate.write(b)
            onBytes(1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            onBytes(len)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private companion object {
        const val TAG = "muxueChat.ImageImport"
    }
}
