package com.master.healthcoach.ui

import org.junit.Assert.*
import org.junit.Test

class ChatMarkdownTest {
    @Test fun `code remains literal and unfinished fences preserve content`() {
        val blocks = ChatMarkdown.parse("## 傾向\n\n```text\n# not a heading\n- not a bullet")
        assertEquals(ChatBlock.Heading("傾向", 2), blocks[0])
        assertEquals(ChatBlock.Code("# not a heading\n- not a bullet", "text"), blocks[1])
    }
    @Test fun `health comparison table preserves header and values`() {
        val blocks = ChatMarkdown.parse("|項目|値|\n|---|---:|\n|歩数|7,380|\n\nこの値は当日途中です。")
        assertEquals(listOf(listOf("項目", "値"), listOf("歩数", "7,380")), (blocks[0] as ChatBlock.Table).rows)
        assertEquals(ChatBlock.Paragraph("この値は当日途中です。"), blocks[1])
    }
    @Test fun `unsupported html and ordinary pipes remain visible text`() {
        assertEquals(listOf(ChatBlock.Paragraph("<script>alert(1)</script>\nP | F | C")), ChatMarkdown.parse("<script>alert(1)</script>\nP | F | C"))
    }
}
