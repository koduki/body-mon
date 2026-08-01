package com.master.healthcoach.data.llm

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChatAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val data: ByteArray,
) {
    val sizeBytes: Long
        get() = data.size.toLong()
}

data class ChatAttachmentSelection(
    val attachments: List<ChatAttachment>,
    val warnings: List<String>,
)

object ChatAttachmentPolicy {
    const val MAX_ATTACHMENT_COUNT = 4
    const val MAX_FILE_BYTES = 10L * 1024 * 1024

    /**
     * Inline data is base64 encoded. Keeping raw attachments at or below 12 MiB leaves
     * room for the system instruction and conversation within Gemini's 20 MB request limit.
     */
    const val MAX_TOTAL_BYTES = 12L * 1024 * 1024

    private val directlySupportedMimeTypes = setOf(
        "application/json",
        "application/pdf",
        "image/bmp",
        "image/heic",
        "image/heif",
        "image/jpeg",
        "image/png",
        "image/webp",
        "text/css",
        "text/csv",
        "text/html",
        "text/javascript",
        "text/plain",
        "text/rtf",
        "text/xml",
    )

    private val mimeTypeByExtension = mapOf(
        "bmp" to "image/bmp",
        "csv" to "text/csv",
        "css" to "text/css",
        "htm" to "text/html",
        "html" to "text/html",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "js" to "text/javascript",
        "json" to "application/json",
        "md" to "text/plain",
        "pdf" to "application/pdf",
        "png" to "image/png",
        "rtf" to "text/rtf",
        "text" to "text/plain",
        "txt" to "text/plain",
        "webp" to "image/webp",
        "xml" to "text/xml",
        "yaml" to "text/plain",
        "yml" to "text/plain",
    )

    fun normalizeMimeType(reportedMimeType: String?, displayName: String): String? {
        val reported = reportedMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (reported == "image/jpg") return "image/jpeg"
        if (reported in directlySupportedMimeTypes) return reported

        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        mimeTypeByExtension[extension]?.let { return it }

        // Markdown and other textual document providers often use a more specific
        // text subtype. Sending them as plain text is supported by Gemini.
        if (reported?.startsWith("text/") == true) return "text/plain"
        return null
    }

    fun validateSize(fileBytes: Long, existingBytes: Long) {
        require(fileBytes > 0L) { "空のファイルは添付できません" }
        require(fileBytes <= MAX_FILE_BYTES) {
            "1ファイルは10MB以下にしてください"
        }
        require(existingBytes + fileBytes <= MAX_TOTAL_BYTES) {
            "添付ファイルは合計12MB以下にしてください"
        }
    }
}

class ChatAttachmentReader(
    private val contentResolver: ContentResolver,
) {
    suspend fun read(
        uris: List<Uri>,
        existing: List<ChatAttachment>,
    ): ChatAttachmentSelection = withContext(Dispatchers.IO) {
        val accepted = mutableListOf<ChatAttachment>()
        val warnings = mutableListOf<String>()
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
        var totalBytes = existing.sumOf { it.sizeBytes }

        uris.distinctBy { it.toString() }.forEach { uri ->
            val uriId = uri.toString()
            if (uriId in existingIds) return@forEach
            if (existing.size + accepted.size >= ChatAttachmentPolicy.MAX_ATTACHMENT_COUNT) {
                warnings += "添付は最大${ChatAttachmentPolicy.MAX_ATTACHMENT_COUNT}件です"
                return@forEach
            }

            val metadata = queryMetadata(uri)
            val mimeType = ChatAttachmentPolicy.normalizeMimeType(
                contentResolver.getType(uri),
                metadata.displayName,
            )
            if (mimeType == null) {
                warnings += "${metadata.displayName}: 対応していない形式です"
                return@forEach
            }

            runCatching {
                metadata.sizeBytes?.let {
                    ChatAttachmentPolicy.validateSize(it, totalBytes)
                }
                val remainingBytes = ChatAttachmentPolicy.MAX_TOTAL_BYTES - totalBytes
                val readLimit = minOf(ChatAttachmentPolicy.MAX_FILE_BYTES, remainingBytes)
                val data = readBytes(uri, readLimit)
                ChatAttachmentPolicy.validateSize(data.size.toLong(), totalBytes)
                ChatAttachment(
                    id = uriId,
                    displayName = metadata.displayName,
                    mimeType = mimeType,
                    data = data,
                )
            }.onSuccess {
                accepted += it
                existingIds += it.id
                totalBytes += it.sizeBytes
            }.onFailure {
                warnings += "${metadata.displayName}: ${it.message ?: "読み込めませんでした"}"
            }
        }

        ChatAttachmentSelection(
            attachments = accepted,
            warnings = warnings.distinct(),
        )
    }

    private fun queryMetadata(uri: Uri): AttachmentMetadata {
        var name: String? = null
        var size: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return AttachmentMetadata(
            displayName = name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.map { if (it.isISOControl()) ' ' else it }
                ?.joinToString("")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(MAX_DISPLAY_NAME_LENGTH)
                ?: uri.lastPathSegment?.takeLast(MAX_DISPLAY_NAME_LENGTH)
                ?: "添付ファイル",
            sizeBytes = size?.takeIf { it >= 0L },
        )
    }

    private fun readBytes(uri: Uri, limit: Long): ByteArray {
        require(limit > 0L) { "添付ファイルは合計12MB以下にしてください" }
        val stream = contentResolver.openInputStream(uri)
            ?: error("ファイルを開けませんでした")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) {
                    if (limit < ChatAttachmentPolicy.MAX_FILE_BYTES) {
                        "添付ファイルは合計12MB以下にしてください"
                    } else {
                        "1ファイルは10MB以下にしてください"
                    }
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private data class AttachmentMetadata(
        val displayName: String,
        val sizeBytes: Long?,
    )

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 120
    }
}
