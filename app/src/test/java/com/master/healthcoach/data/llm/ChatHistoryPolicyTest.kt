package com.master.healthcoach.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryPolicyTest {
    @Test
    fun `keeps all messages when within recent window`() {
        val ids = (1L..20L).toList()
        assertNull(
            ChatHistoryPolicy.maxDeletableMessageId(
                messageIdsAscending = ids,
                summarizedThroughMessageId = 20L,
            ),
        )
    }

    @Test
    fun `does not delete unsummarized messages outside the window`() {
        val ids = (1L..30L).toList()
        assertNull(
            ChatHistoryPolicy.maxDeletableMessageId(
                messageIdsAscending = ids,
                summarizedThroughMessageId = 0L,
            ),
        )
        assertEquals(
            5L,
            ChatHistoryPolicy.maxDeletableMessageId(
                messageIdsAscending = ids,
                summarizedThroughMessageId = 5L,
            ),
        )
    }

    @Test
    fun `deletes only summarized ids below the recent window`() {
        val ids = (1L..40L).toList()
        // recent window keeps 21..40; summarized through 25 → delete 1..20
        assertEquals(
            20L,
            ChatHistoryPolicy.maxDeletableMessageId(
                messageIdsAscending = ids,
                summarizedThroughMessageId = 25L,
            ),
        )
        // summarized through 10 → delete 1..10 only
        assertEquals(
            10L,
            ChatHistoryPolicy.maxDeletableMessageId(
                messageIdsAscending = ids,
                summarizedThroughMessageId = 10L,
            ),
        )
    }

    @Test
    fun `respects a custom keepRecent size`() {
        val ids = (1L..10L).toList()
        assertEquals(
            7L,
            ChatHistoryPolicy.maxDeletableMessageId(
                messageIdsAscending = ids,
                summarizedThroughMessageId = 100L,
                keepRecent = 3,
            ),
        )
    }
}
