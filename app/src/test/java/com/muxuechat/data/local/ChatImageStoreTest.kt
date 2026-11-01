package com.muxuechat.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 图片文件存放与启动孤儿扫描（MULTIMODAL_PLAN 3.1）。只做文件操作，去留规则由调用方查库决定。 */
class ChatImageStoreTest {
    private val filesDir: File = Files.createTempDirectory("muxuechat-store").toFile()
    private var now = 10L * 24 * 60 * 60 * 1000
    private val store = ChatImageStore(filesDir, clock = { now })

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun promotePending_movesFileAndRefreshesModifiedTime() {
        store.writePending("pending-1") { it.write(byteArrayOf(1, 2, 3)) }
        store.pendingFile("pending-1").setLastModified(1_000L)

        val relativePath = store.promotePending("pending-1", "attachment-1")

        assertEquals("chat_images/attachment-1.jpg", relativePath)
        assertFalse(store.pendingFile("pending-1").exists())
        assertTrue(store.exists(relativePath))
        assertEquals(now, store.fileOf(relativePath).lastModified() / 1000 * 1000)
    }

    @Test
    fun removeOrphans_keepsReferencedAndRecentFiles_deletesStaleOnes() {
        val referenced = official("referenced", ageMillis = 3 * HOUR)
        val freshUnreferenced = official("fresh", ageMillis = 10 * 60 * 1000L)
        val staleUnreferenced = official("stale", ageMillis = 2 * HOUR)
        val freshPending = pending("pending-fresh", ageMillis = HOUR)
        val stalePending = pending("pending-stale", ageMillis = 25 * HOUR)

        val removed = store.removeOrphans(setOf("chat_images/referenced.jpg"))

        assertEquals(2, removed)
        assertTrue(referenced.exists())
        // 编辑时新图在提交前就已移入正式目录：1 小时宽限期内不删。
        assertTrue(freshUnreferenced.exists())
        assertFalse(staleUnreferenced.exists())
        assertTrue(freshPending.exists())
        assertFalse(stalePending.exists())
    }

    @Test
    fun paths_rejectAnythingOutsideImageDirectory() {
        listOf("../secret.jpg", "chat_images/../x.jpg", "chat_images/a/b.jpg", "other/a.jpg", "chat_images/a.png").forEach {
            assertTrue(it, runCatching { store.fileOf(it) }.isFailure)
            assertFalse(store.exists(it))
        }
        assertTrue(runCatching { store.relativePathFor("../x") }.isFailure)
    }

    @Test
    fun commitLock_isSharedAcrossInstancesInTheSameProcess() {
        // Activity 重建会换一个 Repository 与本类实例，锁仍必须是同一把。
        assertTrue(store.commitLock === ChatImageStore(File(filesDir, "other")).commitLock)
    }

    private fun official(name: String, ageMillis: Long): File =
        File(filesDir, "chat_images/$name.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
            setLastModified(now - ageMillis)
        }

    private fun pending(name: String, ageMillis: Long): File =
        store.writePending(name) { it.write(byteArrayOf(1)) }.apply { setLastModified(now - ageMillis) }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
