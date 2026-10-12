package com.master.healthcoach.domain

import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.ChatMessageEntity
import com.master.healthcoach.data.db.ConversationMemoryEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.NutritionMealEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HealthDataExportPayload(
    val schemaVersion: Int = 1,
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val exportedAtIso: String,
    val exportedAtEpochMillis: Long,
    val timeZone: String,
    val periodStart: String,
    val periodEnd: String,
    val daysIncluded: Int = HealthDataExporter.EXPORT_DAYS.toInt(),
    val goal: GoalEntity? = null,
    val weeklySnapshot: WeeklySnapshot? = null,
    val measuredData: MeasuredDataExport,
    val conversationHistory: ConversationHistoryExport,
)

@Serializable
data class MeasuredDataExport(
    val dailySummaries: List<DailyHealthSummaryEntity>,
    val bodyCompositions: List<BodyCompositionEntity>,
    val exerciseSessions: List<ExerciseSessionEntity>,
    val nutritionMeals: List<NutritionMealEntity>,
)

@Serializable
data class ConversationHistoryExport(
    val messageCount: Int,
    val messages: List<ExportedChatMessage>,
    val conversationMemory: ExportedConversationMemory? = null,
)

@Serializable
data class ExportedChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val attachmentNames: List<String> = emptyList(),
    val createdAtEpochMillis: Long,
    val createdAtIso: String,
)

@Serializable
data class ExportedConversationMemory(
    val summary: String,
    val summarizedThroughMessageId: Long,
    val updatedAtEpochMillis: Long,
    val updatedAtIso: String,
)

object HealthDataExporter {
    const val EXPORT_DAYS = 28L

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun formatAppVersionLabel(
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
    ): String = "v$versionName ($versionCode)"

    fun defaultFileName(today: LocalDate = LocalDate.now()): String =
        "health-coach-export-$today.json"

    fun dateRange(today: LocalDate): Pair<LocalDate, LocalDate> =
        today.minusDays(EXPORT_DAYS - 1) to today

    fun epochRange(from: LocalDate, to: LocalDate, zoneId: ZoneId): Pair<Long, Long> {
        val startMillis = from.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillisInclusive = to.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
        return startMillis to endMillisInclusive
    }

    fun buildPayload(
        exportedAt: ZonedDateTime = ZonedDateTime.now(),
        daily: List<DailyHealthSummaryEntity>,
        body: List<BodyCompositionEntity>,
        exerciseSessions: List<ExerciseSessionEntity>,
        nutritionMeals: List<NutritionMealEntity>,
        goal: GoalEntity?,
        weeklySnapshot: WeeklySnapshot?,
        chatMessages: List<ChatMessageEntity>,
        conversationMemory: ConversationMemoryEntity?,
    ): HealthDataExportPayload {
        val zoneId = exportedAt.zone
        val today = exportedAt.toLocalDate()
        val (from, to) = dateRange(today)
        val (startMillis, endMillis) = epochRange(from, to, zoneId)
        val fromStr = from.toString()
        val toStr = to.toString()

        val filteredDaily = daily
            .filter { it.date in fromStr..toStr }
            .sortedBy { it.date }
        val filteredBody = body
            .filter { it.date in fromStr..toStr }
            .sortedBy { it.date }
        val filteredSessions = exerciseSessions
            .filter { it.startEpochMillis in startMillis..endMillis }
            .sortedBy { it.startEpochMillis }
        val filteredMeals = nutritionMeals
            .filter { it.date in fromStr..toStr || it.startEpochMillis in startMillis..endMillis }
            .sortedBy { it.startEpochMillis }
        val filteredMessages = chatMessages
            .filter { it.createdAtEpochMillis in startMillis..endMillis }
            .sortedBy { it.id }
            .map { msg ->
                ExportedChatMessage(
                    id = msg.id,
                    role = msg.role,
                    content = msg.content,
                    attachmentNames = msg.attachmentNames
                        ?.lines()
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                    createdAtEpochMillis = msg.createdAtEpochMillis,
                    createdAtIso = formatIso(msg.createdAtEpochMillis, zoneId),
                )
            }

        val exportedMemory = conversationMemory
            ?.takeIf { it.summary.isNotBlank() }
            ?.let { mem ->
                ExportedConversationMemory(
                    summary = mem.summary,
                    summarizedThroughMessageId = mem.summarizedThroughMessageId,
                    updatedAtEpochMillis = mem.updatedAtEpochMillis,
                    updatedAtIso = formatIso(mem.updatedAtEpochMillis, zoneId),
                )
            }

        return HealthDataExportPayload(
            exportedAtIso = exportedAt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            exportedAtEpochMillis = exportedAt.toInstant().toEpochMilli(),
            timeZone = zoneId.id,
            periodStart = fromStr,
            periodEnd = toStr,
            daysIncluded = EXPORT_DAYS.toInt(),
            goal = goal,
            weeklySnapshot = weeklySnapshot,
            measuredData = MeasuredDataExport(
                dailySummaries = filteredDaily,
                bodyCompositions = filteredBody,
                exerciseSessions = filteredSessions,
                nutritionMeals = filteredMeals,
            ),
            conversationHistory = ConversationHistoryExport(
                messageCount = filteredMessages.size,
                messages = filteredMessages,
                conversationMemory = exportedMemory,
            ),
        )
    }

    fun encodeToJson(payload: HealthDataExportPayload): String =
        prettyJson.encodeToString(payload)

    private fun formatIso(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
