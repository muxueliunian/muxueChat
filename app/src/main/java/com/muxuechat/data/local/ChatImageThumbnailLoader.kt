package com.muxuechat.data.local

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import android.util.LruCache
import com.muxuechat.domain.model.ImageInputSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 聊天图片的缩略图（MULTIMODAL_PLAN 3.6）：`ImageDecoder` 按目标尺寸降采样解码，结果放进小容量内存 LruCache。
 *
 * 不引入任何图片库。只读 App 私有目录里本类自己能定位的文件（已发送附件与待发送图片），
 * 文件不存在或解码失败时返回 null，由界面显示占位。日志只记异常类型，不记路径或 id。
 */
class ChatImageThumbnailLoader(private val store: ChatImageStore) {
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun loadAttachment(attachmentId: String, maxEdgePx: Int): Bitmap? =
        load(key = "attachment:$attachmentId:$maxEdgePx", maxEdgePx = maxEdgePx) {
            store.fileOf(store.relativePathFor(attachmentId))
        }

    suspend fun loadPending(pendingId: String, maxEdgePx: Int): Bitmap? =
        load(key = "pending:$pendingId:$maxEdgePx", maxEdgePx = maxEdgePx) { store.pendingFile(pendingId) }

    private suspend fun load(key: String, maxEdgePx: Int, file: () -> File): Bitmap? {
        cache.get(key)?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            try {
                val target = file()
                if (!target.isFile) return@withContext null
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(target)) { decoder, info, _ ->
                    val (width, height) = ImageInputSpec.targetSize(
                        width = info.size.width,
                        height = info.size.height,
                        maxLongEdge = maxEdgePx.coerceAtLeast(1),
                    )
                    decoder.setTargetSize(width, height)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Thumbnail decode failed: ${error.javaClass.simpleName}")
                null
            } catch (error: OutOfMemoryError) {
                Log.w(TAG, "Thumbnail decode out of memory")
                null
            }
        }
        decoded?.let { cache.put(key, it) }
        return decoded
    }

    private companion object {
        const val TAG = "muxueChat.ImageThumb"

        /** 缩略图缓存上限；一屏最多十几张、每张几百 KB，够用且不占太多内存。 */
        const val MAX_CACHE_BYTES = 16 * 1024 * 1024
    }
}
