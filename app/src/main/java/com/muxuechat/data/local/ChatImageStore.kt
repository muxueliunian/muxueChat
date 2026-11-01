package com.muxuechat.data.local

import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * 聊天图片在 App 私有目录里的存放（MULTIMODAL_PLAN 3.1）。
 *
 * - 已发送：`filesDir/chat_images/<attachmentId>.jpg`，数据库是唯一真相来源；
 * - 待发送：`filesDir/chat_images/pending/<pendingId>.jpg`，发送时移动到正式目录。
 *
 * 写文件一律「临时文件 + 原子 rename」，不会留下半截图片。`data_extraction_rules.xml` 已排除
 * `file` 域，这些文件不参与云备份与设备迁移，也不写入系统相册。
 *
 * 本类只做文件操作，不碰数据库；「某个文件该不该删」由 Repository 查库后决定。
 * 路径与 id 都不得进入日志（AGENT.md §6），这里的异常文案因此不带路径。
 */
class ChatImageStore(
    private val filesDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * 发送 / 编辑的「移动文件 + 提交事务」与启动孤儿扫描共用的锁。
     *
     * rename 会保留文件原来的修改时间，按时间判断保护不住「刚移入正式目录、事务还没提交」的文件，
     * 所以扫描期间必须完全排除移动与提交。锁是**进程级**的：Activity 重建会换一个 ViewModel 与
     * Repository（也就换了一个本类实例），旧实例收尾中的提交仍须与新实例的扫描互斥。
     */
    val commitLock: Mutex get() = PROCESS_COMMIT_LOCK

    private val imagesDir: File get() = File(filesDir, IMAGES_DIR)
    private val pendingDir: File get() = File(imagesDir, PENDING_DIR)

    fun relativePathFor(attachmentId: String): String {
        requireSafeId(attachmentId)
        return "$IMAGES_DIR/$attachmentId$EXTENSION"
    }

    /** 只接受本类生成的相对路径，拒绝任何越出图片目录的写法。 */
    fun fileOf(relativePath: String): File {
        val name = relativePath.removePrefix("$IMAGES_DIR/")
        require(name != relativePath && name.endsWith(EXTENSION)) { "图片路径无效" }
        requireSafeId(name.removeSuffix(EXTENSION))
        return File(imagesDir, name)
    }

    fun exists(relativePath: String): Boolean = runCatching { fileOf(relativePath).isFile }.getOrDefault(false)

    fun pendingFile(pendingId: String): File {
        requireSafeId(pendingId)
        return File(pendingDir, "$pendingId$EXTENSION")
    }

    /** 先写临时文件，写完再原子改名为待发送图片；中途失败不会留下可见的半截文件。 */
    fun writePending(pendingId: String, write: (OutputStream) -> Unit): File {
        val target = pendingFile(pendingId)
        ensureDir(pendingDir)
        val temp = File(pendingDir, "$pendingId$TEMP_SUFFIX")
        try {
            temp.outputStream().use { write(it) }
            if (!temp.renameTo(target)) throw IOException("图片保存失败")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return target
    }

    fun discardPending(pendingId: String) {
        runCatching { pendingFile(pendingId).delete() }
    }

    /**
     * 把待发送图片移入正式目录，返回相对路径。
     *
     * 移动后立即刷新修改时间：rename 保留原时间，否则孤儿扫描会把它当成很久以前的文件。
     * 调用方必须持有 [commitLock]，并在事务失败后查库决定是否删除返回的文件。
     */
    fun promotePending(pendingId: String, attachmentId: String): String {
        val source = pendingFile(pendingId)
        if (!source.isFile) throw IOException("待发送图片已丢失，请重新选择")
        val relativePath = relativePathFor(attachmentId)
        val target = fileOf(relativePath)
        ensureDir(imagesDir)
        if (!source.renameTo(target)) throw IOException("图片保存失败")
        target.setLastModified(clock())
        return relativePath
    }

    fun delete(relativePaths: Collection<String>) {
        relativePaths.forEach { path ->
            runCatching { fileOf(path).delete() }
        }
    }

    /**
     * 删除数据库不再引用的正式图片、过期的待发送图片与残留的临时文件。返回删除的文件数。
     *
     * 未被引用的正式图片还要超过 [ORPHAN_GRACE_MILLIS] 才删：编辑消息时新图在请求开始前就已移入
     * 正式目录（[promotePending] 会把修改时间刷新为当前），要等生成成功、提交事务后才被引用。
     * 只靠 [commitLock] 保护不住这段「已移入、未提交」的窗口——它跨越整次网络请求，不在锁内。
     *
     * 调用方必须持有 [commitLock]，并在数据库打开之后、按进程只执行一次。
     */
    fun removeOrphans(referencedPaths: Set<String>): Int {
        val now = clock()
        var removed = 0
        imagesDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val relativePath = "$IMAGES_DIR/${file.name}"
            val stale = when {
                file.name.endsWith(EXTENSION) ->
                    relativePath !in referencedPaths && now - file.lastModified() > ORPHAN_GRACE_MILLIS
                file.name.endsWith(TEMP_SUFFIX) -> now - file.lastModified() > PENDING_TTL_MILLIS
                else -> false
            }
            if (stale && file.delete()) removed++
        }
        pendingDir.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > PENDING_TTL_MILLIS && file.delete()) removed++
        }
        return removed
    }

    private fun ensureDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("无法创建图片目录")
    }

    private fun requireSafeId(id: String) {
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' }) {
            "图片标识无效"
        }
    }

    companion object {
        const val IMAGES_DIR = "chat_images"
        private const val PENDING_DIR = "pending"
        private const val EXTENSION = ".jpg"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_ID_LENGTH = 64

        /** 超过这个时长的待发送图片视为放弃（例如选完图后进程被杀）。 */
        const val PENDING_TTL_MILLIS = 24L * 60 * 60 * 1000

        /** 未被引用的正式图片的宽限期，覆盖编辑时「已移入正式目录、尚未提交」的整次生成。 */
        const val ORPHAN_GRACE_MILLIS = 60L * 60 * 1000

        private val PROCESS_COMMIT_LOCK = Mutex()

        fun newId(): String = UUID.randomUUID().toString()
    }
}
