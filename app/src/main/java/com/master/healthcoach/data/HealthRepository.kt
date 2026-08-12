package com.master.healthcoach.data

import androidx.room.withTransaction
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.ChatMessageEntity
import com.master.healthcoach.data.db.ConversationMemoryEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.HealthCoachDatabase
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import com.master.healthcoach.data.db.WeeklyReportEntity
import com.master.healthcoach.data.health.HealthConnectAvailability
import com.master.healthcoach.data.health.HealthConnectGateway
import com.master.healthcoach.data.llm.ChatHistoryPolicy
import com.master.healthcoach.domain.WeeklyReportBuilder
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HealthRepository(
    private val database: HealthCoachDatabase,
    private val gateway: HealthConnectGateway,
    private val json: Json,
) {
    private val dao = database.dao()

    val daily: Flow<List<DailyHealthSummaryEntity>> = dao.observeDaily()
    val body: Flow<List<BodyCompositionEntity>> = dao.observeBody()
    val sources: Flow<List<HealthSourceStatusEntity>> = dao.observeSources()
    val exerciseSessions: Flow<List<ExerciseSessionEntity>> = dao.observeExerciseSessions()
    val goal: Flow<GoalEntity?> = dao.observeGoal()
    val latestWeekly: Flow<WeeklyReportEntity?> = dao.observeLatestWeekly()
    val messages: Flow<List<ChatMessageEntity>> =
        dao.observeRecentMessages(ChatHistoryPolicy.CONTEXT_MESSAGE_LIMIT)
            .map { rows -> rows.sortedBy { it.id } }
    val memory: Flow<ConversationMemoryEntity?> = dao.observeMemory()

    fun availability(): HealthConnectAvailability = gateway.availability()
    fun corePermissions(): Set<String> = gateway.corePermissions
    fun requestedPermissions(): Set<String> = gateway.requestedPermissions()
    suspend fun grantedPermissions(): Set<String> = gateway.grantedPermissions()
    suspend fun hasCorePermissions(): Boolean = gateway.hasCorePermissions()
    suspend fun hasBackgroundPermission(): Boolean = gateway.hasBackgroundPermission()

    suspend fun sync(): WeeklyReportEntity {
        val today = LocalDate.now()
        val earliestDaily = dao.earliestDailyDate()?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val canBackfillHistory = gateway.hasHistoryPermission()
        val needsInitialBackfill = canBackfillHistory && (
            earliestDaily == null || earliestDaily > today.minusDays(89)
            )
        val bundle = gateway.sync(days = if (needsInitialBackfill) 90 else 28)
        database.withTransaction {
            dao.upsertDaily(bundle.daily)
            dao.upsertBody(bundle.body)
            dao.upsertSources(bundle.sources)
            dao.deleteExerciseSessionsInRange(
                bundle.rangeStartEpochMillis,
                bundle.rangeEndEpochMillisExclusive,
            )
            dao.upsertExerciseSessions(bundle.exerciseSessions)
        }
        return rebuildWeekly()
    }

    suspend fun rebuildWeekly(today: LocalDate = LocalDate.now()): WeeklyReportEntity {
        val from = today.minusDays(89).toString()
        val daily = dao.getDaily(from, today.toString())
        val body = dao.getBody(from, today.toString())
        val snapshot = WeeklyReportBuilder.build(
            today = today,
            daily = daily,
            body = body,
            goal = dao.getGoal(),
        )
        return WeeklyReportEntity(
            weekStart = snapshot.weekStart,
            snapshotJson = json.encodeToString(snapshot),
            generatedAtEpochMillis = System.currentTimeMillis(),
        ).also { dao.upsertWeekly(it) }
    }

    suspend fun saveGoal(goal: GoalEntity) {
        dao.upsertGoal(goal.copy(id = 1))
        rebuildWeekly()
    }
    suspend fun getGoal(): GoalEntity? = dao.getGoal()
    suspend fun getLatestWeekly(): WeeklyReportEntity? = dao.getLatestWeekly()

    suspend fun getDaily(from: LocalDate, to: LocalDate) =
        dao.getDaily(from.toString(), to.toString())

    suspend fun getBody(from: LocalDate, to: LocalDate) =
        dao.getBody(from.toString(), to.toString())

    suspend fun getExerciseSessions(from: LocalDate, to: LocalDate) =
        dao.getExerciseSessions(
            from.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1,
        )

    suspend fun addMessage(
        role: String,
        content: String,
        attachmentNames: String? = null,
    ): Long = dao.insertMessage(
        ChatMessageEntity(
            role = role,
            content = content,
            attachmentNames = attachmentNames,
            createdAtEpochMillis = System.currentTimeMillis(),
        ),
    )

    suspend fun getRecentMessages(
        limit: Int = ChatHistoryPolicy.CONTEXT_MESSAGE_LIMIT,
    ): List<ChatMessageEntity> =
        dao.getRecentMessages(limit).sortedBy { it.id }

    suspend fun getMessagesAfter(afterId: Long): List<ChatMessageEntity> =
        dao.getMessagesAfter(afterId)

    suspend fun messageCount(): Int = dao.messageCount()
    suspend fun getMemory(): ConversationMemoryEntity? = dao.getMemory()
    suspend fun saveMemory(summary: String, throughId: Long) {
        dao.upsertMemory(
            ConversationMemoryEntity(
                summary = summary,
                summarizedThroughMessageId = throughId,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        pruneChatHistory()
    }

    /**
     * Drops summarized turns outside the shared recent window so the chat screen
     * and Room stay aligned with the Gemini context budget.
     */
    suspend fun pruneChatHistory(
        keepRecent: Int = ChatHistoryPolicy.CONTEXT_MESSAGE_LIMIT,
    ) {
        val memory = dao.getMemory() ?: return
        val maxDeletable = ChatHistoryPolicy.maxDeletableMessageId(
            messageIdsAscending = dao.getMessageIds(),
            summarizedThroughMessageId = memory.summarizedThroughMessageId,
            keepRecent = keepRecent,
        ) ?: return
        dao.deleteMessagesUpTo(maxDeletable)
    }

    suspend fun clearAll() {
        database.withTransaction {
            dao.clearDaily()
            dao.clearBody()
            dao.clearSources()
            dao.clearExerciseSessions()
            dao.clearGoals()
            dao.clearWeekly()
            dao.clearMessages()
            dao.clearMemory()
        }
    }
}
