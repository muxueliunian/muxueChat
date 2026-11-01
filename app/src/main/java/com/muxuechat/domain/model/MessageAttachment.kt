package com.muxuechat.domain.model

/**
 * 用户消息附带的一张图片（MULTIMODAL_PLAN 3.2）。只描述元数据，不含字节；
 * 文件位置由 data.local 按 [id] 决定，domain 不关心路径。
 */
data class MessageAttachment(
    val id: String,
    val messageId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val orderIndex: Int,
)

/**
 * 已导入、已压缩但尚未随消息发出的图片。字节已写入私有目录的待发送区，
 * 发送时移动到正式目录并成为 [MessageAttachment]。
 */
data class PendingImage(
    val id: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
)

/**
 * 编辑历史用户消息时的图片变化（D4）：保留哪些原附件、追加哪些待发送图片。
 * 顺序即编辑后的显示与发送顺序：先保留的原图，再新增的图。
 */
data class MessageImageEdit(
    val keptAttachmentIds: List<String>,
    val addedImages: List<PendingImage>,
) {
    val totalCount: Int get() = keptAttachmentIds.size + addedImages.size

    companion object {
        /** 只改文字、不动图片。 */
        fun keepAll(attachments: List<MessageAttachment>) = MessageImageEdit(
            keptAttachmentIds = attachments.sortedBy { it.orderIndex }.map { it.id },
            addedImages = emptyList(),
        )
    }
}

/** 图片输入的固定规格（D3 与 3.2）。集中在这里，导入、规划与 UI 共用。 */
object ImageInputSpec {
    const val MAX_IMAGES_PER_MESSAGE = 4
    const val MAX_LONG_EDGE_PX = 1568
    const val JPEG_QUALITY = 85
    const val MIME_JPEG = "image/jpeg"

    /** 一次请求里图片 base64 的总量上限；超出时从最旧的历史图片开始省略，本轮图片永不省略。 */
    const val MAX_IMAGE_PAYLOAD_BYTES = 20L * 1024 * 1024

    /** base64 编码后的字节数（不含 data URL 前缀）。 */
    fun base64Size(byteSize: Long): Long = (byteSize + 2) / 3 * 4

    /**
     * 按长边等比缩小到 [maxLongEdge] 以内；本来就不超过时原样返回。
     * 结果每边至少 1px。
     */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE_PX): Pair<Int, Int> {
        require(width > 0 && height > 0) { "图片尺寸无效" }
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longEdge
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return scaledWidth to scaledHeight
    }
}
