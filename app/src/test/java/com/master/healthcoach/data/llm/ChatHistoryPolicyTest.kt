package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.ChatMessageEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `annotates turns with relative day and date boundary across yesterday and today`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = ZonedDateTime.of(2026, 10, 13, 8, 30, 0, 0, zone)
        val yesterdayNight = ZonedDateTime.of(2026, 10, 12, 22, 15, 0, 0, zone)
            .toInstant().toEpochMilli()
        val yesterdayReply = ZonedDateTime.of(2026, 10, 12, 22, 16, 0, 0, zone)
            .toInstant().toEpochMilli()
        val todayMorning = ZonedDateTime.of(2026, 10, 13, 8, 29, 0, 0, zone)
            .toInstant().toEpochMilli()

        val messages = listOf(
            ChatMessageEntity(
                id = 1L,
                role = "user",
                content = "今日は焼き魚を食べました",
                createdAtEpochMillis = yesterdayNight,
            ),
            ChatMessageEntity(
                id = 2L,
                role = "assistant",
                content = "高たんぱくで良い夕食ですね！",
                createdAtEpochMillis = yesterdayReply,
            ),
            ChatMessageEntity(
                id = 3L,
                role = "user",
                content = "おはよう！昨日の歩数と今日の体重どうかな？",
                createdAtEpochMillis = todayMorning,
            ),
        )

        val turns = ChatHistoryPolicy.formatTurnsWithChronology(
            messages = messages,
            currentMessageId = 3L,
            now = now,
        )

        assertEquals(3, turns.size)
        assertTrue(turns[0].text.contains("2026-10-12"))
        assertTrue(turns[0].text.contains("（昨日）"))
        assertTrue(turns[0].text.endsWith("今日は焼き魚を食べました"))
        assertTrue(turns[1].text.contains("（昨日）"))
        assertTrue(
            turns[2].text.startsWith(
                "【日付変更: 2026-10-12（昨日） → 2026-10-13（今日）】",
            ),
        )
        assertTrue(turns[2].text.contains("2026-10-13"))
        assertTrue(turns[2].text.contains("（今日）"))
        assertTrue(turns[2].text.endsWith("おはよう！昨日の歩数と今日の体重どうかな？"))
    }

    @Test
    fun `strips echoed leading timestamp and date boundary tags`() {
        val raw = "【日付変更: 2026-10-12（昨日） → 2026-10-13（今日）】\n" +
            "[発言日時: 2026-10-13(火) 08:30（今日）]\n" +
            "おはようございます！昨日の歩数は8,200歩でした。"
        assertEquals(
            "おはようございます！昨日の歩数は8,200歩でした。",
            ChatHistoryPolicy.stripLeadingTimestampTag(raw),
        )
        assertEquals(
            "おはようございます！",
            ChatHistoryPolicy.stripLeadingTimestampTag("おはようございます！"),
        )
    }

    @Test
    fun `computes relative day labels accurately`() {
        val today = LocalDate.of(2026, 10, 13)
        assertEquals("今日", ChatHistoryPolicy.relativeDayLabel(today, today))
        assertEquals("昨日", ChatHistoryPolicy.relativeDayLabel(today.minusDays(1), today))
        assertEquals("一昨日", ChatHistoryPolicy.relativeDayLabel(today.minusDays(2), today))
        assertEquals("5日前", ChatHistoryPolicy.relativeDayLabel(today.minusDays(5), today))
    }

    @Test
    fun `retains all messages within 28 days even when exceeding recent window`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val today = LocalDate.of(2026, 10, 13)
        // Retention cutoff is 2026-09-16T00:00:00+09:00 (28 days including today: 09-16 .. 10-13)
        val cutoffMillis = today.minusDays(27).atStartOfDay(zone).toInstant().toEpochMilli()

        val messagesWithin28Days = (1L..30L).map { id ->
            ChatMessageEntity(
                id = id,
                role = if (id % 2L == 1L) "user" else "assistant",
                content = "msg-$id",
                createdAtEpochMillis = cutoffMillis + id * 1_000L,
            )
        }

        assertNull(
            ChatHistoryPolicy.maxDeletableMessageIdForRetention(
                messagesAscending = messagesWithin28Days,
                summarizedThroughMessageId = 30L,
                retentionCutoffEpochMillis = cutoffMillis,
            ),
        )
    }

    @Test
    fun `deletes only summarized messages older than 28 days and outside recent window`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val today = LocalDate.of(2026, 10, 13)
        val cutoffMillis = today.minusDays(27).atStartOfDay(zone).toInstant().toEpochMilli()

        // IDs 1..5 are older than 28 days; IDs 6..30 are within 28 days (total 30 messages, recent window keeps 11..30)
        val messages = (1L..30L).map { id ->
            val createdAt = if (id <= 5L) {
                cutoffMillis - (6L - id) * 60_000L
            } else {
                cutoffMillis + (id - 5L) * 60_000L
            }
            ChatMessageEntity(
                id = id,
                role = if (id % 2L == 1L) "user" else "assistant",
                content = "msg-$id",
                createdAtEpochMillis = createdAt,
            )
        }

        assertEquals(
            5L,
            ChatHistoryPolicy.maxDeletableMessageIdForRetention(
                messagesAscending = messages,
                summarizedThroughMessageId = 25L,
                retentionCutoffEpochMillis = cutoffMillis,
            ),
        )
    }
}


