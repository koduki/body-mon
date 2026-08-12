package com.master.healthcoach.data.llm

/**
 * Chat UI and Gemini share the same recent window. Older turns are kept only until
 * they are folded into [conversation_memory], then discarded from Room.
 */
object ChatHistoryPolicy {
    const val CONTEXT_MESSAGE_LIMIT = 20

    /**
     * Highest message id that may be deleted, or null when nothing is eligible.
     * Eligible rows are already covered by the long-term summary and sit outside
     * the recent window passed to Gemini / shown in the chat screen.
     */
    fun maxDeletableMessageId(
        messageIdsAscending: List<Long>,
        summarizedThroughMessageId: Long,
        keepRecent: Int = CONTEXT_MESSAGE_LIMIT,
    ): Long? {
        if (keepRecent <= 0 || messageIdsAscending.isEmpty()) return null
        if (summarizedThroughMessageId <= 0L) return null
        if (messageIdsAscending.size <= keepRecent) return null
        val oldestKeptId = messageIdsAscending[messageIdsAscending.size - keepRecent]
        val maxDeletable = minOf(oldestKeptId - 1, summarizedThroughMessageId)
        return maxDeletable.takeIf { cutoff -> messageIdsAscending.any { it <= cutoff } }
    }
}
