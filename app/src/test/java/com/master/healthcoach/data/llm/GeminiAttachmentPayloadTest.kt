package com.master.healthcoach.data.llm

import java.util.Base64
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAttachmentPayloadTest {
    @Test
    fun `adds inline data and filename to the current user turn`() {
        val bytes = "sample document".encodeToByteArray()
        val attachment = ChatAttachment(
            id = "content://document/1",
            displayName = "meal-notes.txt",
            mimeType = "text/plain",
            data = bytes,
        )

        val content = geminiContents(
            listOf(
                GeminiTurn(
                    role = "user",
                    text = "この内容を見て",
                    attachments = listOf(attachment),
                ),
            ),
        ).single()
        val parts = content.getValue("parts").jsonArray
        val inlineData = parts.last().jsonObject.getValue("inlineData").jsonObject

        assertEquals("user", content.getValue("role").jsonPrimitive.content)
        assertEquals("text/plain", inlineData.getValue("mimeType").jsonPrimitive.content)
        assertEquals(
            Base64.getEncoder().encodeToString(bytes),
            inlineData.getValue("data").jsonPrimitive.content,
        )
        assertTrue(parts.first().jsonObject.getValue("text").jsonPrimitive.content.contains("meal-notes.txt"))
        assertTrue(parts.first().jsonObject.getValue("text").jsonPrimitive.content.contains("この内容を見て"))
    }

    @Test
    fun `does not add inline data to historical text turns`() {
        val content = geminiContents(listOf(GeminiTurn("assistant", "回答"))).single()
        val parts = content.getValue("parts").jsonArray

        assertEquals("model", content.getValue("role").jsonPrimitive.content)
        assertEquals(1, parts.size)
        assertEquals("回答", parts.single().jsonObject.getValue("text").jsonPrimitive.content)
    }
}
