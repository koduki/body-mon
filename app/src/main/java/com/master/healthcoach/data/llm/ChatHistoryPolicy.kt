package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.ChatMessageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Chat UI and Gemini share the same recent window. Older turns are kept only until
 * they are folded into [conversation_memory], then discarded from Room.
 */
object ChatHistoryPolicy {
    const val CONTEXT_MESSAGE_LIMIT = 20

    private val TURN_TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm", Locale.JAPANESE)

    private val LEADING_TAG_REGEX =
        Regex("""^(?:【日付変更:[^\n]*】\s*)*(?:\[発言日時:[^\]]*]\s*)+""")

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

    /** Relative day label anchored to [today] (e.g. 今日, 昨日, 一昨日, 3日前). */
    fun relativeDayLabel(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> "今日"
            days == 1L -> "昨日"
            days == 2L -> "一昨日"
            days > 2L -> "${days}日前"
            days == -1L -> "明日"
            else -> "${-days}日後"
        }
    }

    /**
     * Annotates each chat turn with its actual timestamp and relative day label anchored
     * to [now], and inserts an explicit date-change marker whenever consecutive turns
     * cross a date boundary so the model never confuses yesterday's turns with today's.
     */
    fun formatTurnsWithChronology(
        messages: List<ChatMessageEntity>,
        currentMessageId: Long,
        attachments: List<ChatAttachment> = emptyList(),
        now: ZonedDateTime = ZonedDateTime.now(),
    ): List<GeminiTurn> {
        val today = now.toLocalDate()
        val zone = now.zone
        var previousDate: LocalDate? = null
        return messages.map { messageEntity ->
            val zoned = Instant.ofEpochMilli(messageEntity.createdAtEpochMillis).atZone(zone)
            val msgDate = zoned.toLocalDate()
            val relLabel = relativeDayLabel(msgDate, today)
            val timestamp = zoned.format(TURN_TIMESTAMP_FORMATTER)
            val dateBoundaryPrefix = if (previousDate != null && previousDate != msgDate) {
                val prevRel = relativeDayLabel(previousDate!!, today)
                "【日付変更: $previousDate（$prevRel） → $msgDate（$relLabel）】\n"
            } else {
                ""
            }
            previousDate = msgDate
            val cleanContent = stripLeadingTimestampTag(messageEntity.content)
            val annotatedText = "$dateBoundaryPrefix[発言日時: $timestamp（$relLabel）]\n$cleanContent"
            GeminiTurn(
                role = messageEntity.role,
                text = annotatedText,
                attachments = if (messageEntity.id == currentMessageId) attachments else emptyList(),
            )
        }
    }

    /** Strips any echoed timestamp/date-change tag from the start of a model reply. */
    fun stripLeadingTimestampTag(text: String): String =
        text.replaceFirst(LEADING_TAG_REGEX, "").trim()
}

