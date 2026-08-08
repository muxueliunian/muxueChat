package com.muxuechat.data.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** 提取成功后的文档信息。正文只在内存中流转，不落库、不写日志。 */
data class ImportedDocument(
    val fileName: String,
    val text: String,
    val charCount: Int = text.length,
)

/** 提取失败的原因，全部是可直接展示给用户的中文。 */
sealed interface DocumentImportResult {
    data class Success(val document: ImportedDocument) : DocumentImportResult
    data class Failure(val reason: String) : DocumentImportResult
}

/**
 * 从 SAF 选中的 Uri 里读出纯文本。
 *
 * 首版只支持 .txt / .md：工程里没有 PDF/DOCX 解析依赖，引入需要先评估体积、
 * 许可证、内存占用和维护成本，不在本期范围。
 *
 * 安全约束：正文**绝不写入 Logcat，也不放进异常信息**。文档可能包含用户隐私，
 * 而错误信息经常会被顺手记进日志。
 */
class DocumentTextExtractor(
    private val contentResolver: ContentResolver,
) {

    suspend fun extract(uri: Uri): DocumentImportResult = withContext(Dispatchers.IO) {
        val metadata = runCatching { queryMetadata(uri) }.getOrNull()
            ?: return@withContext DocumentImportResult.Failure("无法读取该文件的信息，请换一个文件或重新选择。")

        val fileName = metadata.first
        val size = metadata.second

        if (size != null && size > MAX_FILE_SIZE_BYTES) {
            return@withContext DocumentImportResult.Failure(
                "文件大小 ${formatSize(size)} 超过上限 ${formatSize(MAX_FILE_SIZE_BYTES)}，请改用更小的文件。",
            )
        }
        if (!isSupportedName(fileName)) {
            return@withContext DocumentImportResult.Failure(
                "首版只支持 .txt 和 .md 文件。PDF、DOCX、EPUB 需要额外的解析库，暂未支持。",
            )
        }

        val raw = runCatching { readText(uri) }.getOrElse { error ->
            // 只记异常类型，不记 message——它可能包含路径或内容片段。
            return@withContext DocumentImportResult.Failure(
                "读取文件失败（${error.javaClass.simpleName}），请确认文件仍然存在且可访问。",
            )
        } ?: return@withContext DocumentImportResult.Failure("无法打开该文件，可能已被移动或删除。")

        val normalized = normalize(raw)
        when {
            normalized.isBlank() ->
                DocumentImportResult.Failure("这个文件是空的，没有可分析的内容。")

            normalized.length > MAX_TEXT_LENGTH ->
                // 不静默截断：截断会让模型基于残缺资料生成设定，用户却毫不知情。
                DocumentImportResult.Failure(
                    "文档正文 ${normalized.length} 字，超过上限 $MAX_TEXT_LENGTH 字。" +
                        "首版不支持分块分析，请先手动截取需要的部分。",
                )

            else -> DocumentImportResult.Success(
                ImportedDocument(fileName = fileName, text = normalized),
            )
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long?> {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                return (name ?: uri.lastPathSegment ?: "未命名文件") to size
            }
        }
        return (uri.lastPathSegment ?: "未命名文件") to null
    }

    private fun readText(uri: Uri): String? =
        contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
        }

    private fun isSupportedName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return SUPPORTED_EXTENSIONS.any { lower.endsWith(it) }
    }

    companion object {
        /** 原始文件大小上限。 */
        const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024

        /** 规范化后的文本长度上限。 */
        const val MAX_TEXT_LENGTH = 120_000

        val SUPPORTED_EXTENSIONS = listOf(".txt", ".md", ".markdown")

        // SAF 选择器的 MIME 过滤。部分文件管理器对 text/markdown 支持不佳，故一并放开通配的 text 类型。
        // 注意这里用行注释而不是 KDoc：Kotlin 的块注释可以嵌套，"text/" 后跟星号会开启一个
        // 嵌套注释，把后面的代码整段吞掉。
        val SAF_MIME_TYPES = arrayOf("text/plain", "text/markdown", "text/*")

        /**
         * 规范化文本：去掉 UTF-8 BOM、统一换行、压掉过多空行。
         *
         * 抽成静态函数是为了让粘贴文本走同一套处理，不出现"文件导入和粘贴表现不一致"。
         */
        fun normalize(raw: String): String = raw
            .removePrefix(BOM)
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(EXCESS_BLANK_LINES, "\n\n")
            .trim()

        /** UTF-8 BOM。写成转义而不是裸字符，避免源码里出现不可见字符。 */
        private const val BOM = "\uFEFF"

        private val EXCESS_BLANK_LINES = Regex("\n{3,}")

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
