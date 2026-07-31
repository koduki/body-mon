package com.master.healthcoach.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatAttachmentPolicyTest {
    @Test
    fun `normalizes supported and extension based mime types`() {
        assertEquals(
            "image/jpeg",
            ChatAttachmentPolicy.normalizeMimeType("image/jpg", "meal.jpg"),
        )
        assertEquals(
            "application/pdf",
            ChatAttachmentPolicy.normalizeMimeType("application/octet-stream", "report.PDF"),
        )
        assertEquals(
            "text/plain",
            ChatAttachmentPolicy.normalizeMimeType("text/markdown", "notes.md"),
        )
        assertEquals(
            "image/heic",
            ChatAttachmentPolicy.normalizeMimeType("image/heic", "photo.heic"),
        )
        assertNull(ChatAttachmentPolicy.normalizeMimeType("application/zip", "archive.zip"))
    }

    @Test
    fun `rejects empty oversized and over total files`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateSize(0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateSize(ChatAttachmentPolicy.MAX_FILE_BYTES + 1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatAttachmentPolicy.validateSize(
                2 * 1024 * 1024,
                ChatAttachmentPolicy.MAX_TOTAL_BYTES - 1024,
            )
        }
    }
}
