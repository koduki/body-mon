package com.master.healthcoach.data

import androidx.room.withTransaction
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.db.AppMetadataEntity
import com.master.healthcoach.data.db.ReportArchiveEntity
import com.master.healthcoach.data.diagnostics.DiagnosticsStore
import com.master.healthcoach.data.llm.ConversationContextPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.ChatMessageEntity
import com.master.healthcoach.data.db.ConversationMemoryEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.HealthCoachDatabase
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import com.master.healthcoach.data.db.NutritionMealEntity
import com.master.healthcoach.data.db.WeeklyReportEntity
import com.master.healthcoach.data.health.HealthConnectAvailability
import com.master.healthcoach.data.health.HealthConnectGateway
import com.master.healthcoach.data.llm.ChatHistoryPolicy
import com.master.healthcoach.domain.HealthDataExporter
import com.master.healthcoach.domain.WeeklyReportBuilder
import com.master.healthcoach.domain.WeeklySnapshot
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HealthRepository(
    private val database: HealthCoachDatabase,
    private val gateway: HealthConnectGateway,
    private val json: Json,
    val diagnostics: DiagnosticsStore? = null,
) {
    private val dao = database.dao()

    val daily: Flow<List<DailyHealthSummaryEntity>> = dao.observeDaily()
    val body: Flow<List<BodyCompositionEntity>> = dao.observeBody()
    val sources: Flow<List<HealthSourceStatusEntity>> = dao.observeSources()
    val exerciseSessions: Flow<List<ExerciseSessionEntity>> = dao.observeExerciseSessions()
    val nutritionMeals: Flow<List<NutritionMealEntity>> = dao.observeNutritionMeals()
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
        val runId = DiagnosticsStore.id()
        val started = System.nanoTime()
        diagnostics?.event("sync", "started", correlation = runId)
        dao.setMetadata(AppMetadataEntity("lastSyncAttempt", System.currentTimeMillis().toString()))
        try {
            val report = syncInternal()
            dao.setMetadata(AppMetadataEntity("lastSyncSuccess", System.currentTimeMillis().toString()))
            dao.setMetadata(AppMetadataEntity("lastSyncOutcome", "success"))
            diagnostics?.event("sync", "success", (System.nanoTime() - started) / 1_000_000, runId)
            return report
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            dao.setMetadata(AppMetadataEntity("lastSyncOutcome", "failure"))
            diagnostics?.event("sync", "failure", (System.nanoTime() - started) / 1_000_000, runId, error = e)
            throw e
        }
    }

    private suspend fun syncInternal(): WeeklyReportEntity {
        val today = LocalDate.now()
        val earliestDaily = dao.earliestDailyDate()?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val canBackfillHistory = gateway.hasHistoryPermission()
        val needsInitialBackfill = canBackfillHistory && (
            earliestDaily == null || earliestDaily > today.minusDays(89) ||
                dao.needsMetricBackfill(today.minusDays(89).toString())
            )
        val bundle = gateway.sync(days = if (needsInitialBackfill) 90 else 31)
        database.withTransaction {
            val existing = dao.getDaily(
                bundle.daily.first().date, bundle.daily.last().date,
            ).associateBy { it.date }
            dao.upsertDaily(bundle.daily.map { day ->
                day.copy(nutritionComplete = day.intakeCaloriesKcal != null && existing[day.date]?.nutritionComplete == true)
            })
            dao.upsertBody(bundle.body)
            dao.upsertSources(bundle.sources)
            dao.deleteExerciseSessionsInRange(
                bundle.rangeStartEpochMillis,
                bundle.rangeEndEpochMillisExclusive,
            )
            dao.upsertExerciseSessions(bundle.exerciseSessions)
            dao.deleteNutritionMealsInRange(
                bundle.rangeStartEpochMillis,
                bundle.rangeEndEpochMillisExclusive,
            )
            dao.upsertNutritionMeals(bundle.nutritionMeals)
        }
        diagnostics?.event("sync", "success", counters = mapOf("days" to bundle.daily.size.toLong(), "body" to bundle.body.size.toLong(), "sessions" to bundle.exerciseSessions.size.toLong(), "meals" to bundle.nutritionMeals.size.toLong()))
        return rebuildWeekly()
    }

    suspend fun rebuildWeekly(today: LocalDate = LocalDate.now()): WeeklyReportEntity = database.withTransaction {
        val from = today.minusDays(89).toString()
        val daily = dao.getDaily(from, today.toString())
        val body = dao.getBody(from, today.toString())
        val snapshot = WeeklyReportBuilder.build(
            today = today,
            daily = daily,
            body = body,
            goal = dao.getGoal(),
        )
        WeeklyReportEntity(
            weekStart = snapshot.weekStart,
            snapshotJson = json.encodeToString(snapshot),
            generatedAtEpochMillis = System.currentTimeMillis(),
        ).also {
            dao.upsertWeekly(it)
            val inputs = buildJsonObject {
                put("daily", json.encodeToJsonElement(daily)); put("body", json.encodeToJsonElement(body))
                put("goal", json.encodeToJsonElement(dao.getGoal()))
            }.toString()
            if (inputs.toByteArray().size <= 160_000) {
                dao.archiveReport(ReportArchiveEntity(
                    id = UUID.randomUUID().toString(), periodStart = snapshot.weekStart, periodEnd = snapshot.weekEnd,
                    generatedAt = it.generatedAtEpochMillis, revision = dao.metadata("revision")?.toLongOrNull() ?: 0,
                    policyVersion = ConversationContextPolicy.VERSION, commitSha = BuildConfig.COMMIT_SHA,
                    snapshotJson = it.snapshotJson, inputsJson = inputs,
                ))
                dao.pruneReports(System.currentTimeMillis() - 90 * DiagnosticsStore.DAY)
            }
        }
    }

    suspend fun saveGoal(goal: GoalEntity) {
        dao.upsertGoal(goal.copy(id = 1))
        rebuildWeekly()
    }
    suspend fun setNutritionComplete(date: String, complete: Boolean) {
        dao.setNutritionComplete(date, complete)
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
            from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1,
        )

    suspend fun getNutritionMeals(from: LocalDate, to: LocalDate) =
        dao.getNutritionMeals(
            from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1,
        )

    suspend fun addMessage(
        role: String,
        content: String,
        attachmentNames: String? = null,
        modelId: String? = null,
        appVersionName: String? = null,
        policyVersion: String? = null,
        toolTraceJson: String? = null,
    ): Long = dao.insertMessage(
        ChatMessageEntity(
            role = role,
            content = content,
            attachmentNames = attachmentNames,
            createdAtEpochMillis = System.currentTimeMillis(),
            modelId = modelId,
            appVersionName = appVersionName,
            policyVersion = policyVersion,
            toolTraceJson = toolTraceJson,
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
     * Drops summarized turns only when they are outside both the recent prompt window
     * ([keepRecent]) and the 28-day retention window ([ChatHistoryPolicy.RETENTION_DAYS]).
     */
    suspend fun pruneChatHistory(
        keepRecent: Int = ChatHistoryPolicy.CONTEXT_MESSAGE_LIMIT,
        today: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val memory = dao.getMemory() ?: return
        val retentionCutoffEpochMillis = today
            .minusDays(ChatHistoryPolicy.RETENTION_DAYS - 1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val maxDeletable = ChatHistoryPolicy.maxDeletableMessageIdForRetention(
            messagesAscending = dao.getAllMessages(),
            summarizedThroughMessageId = memory.summarizedThroughMessageId,
            retentionCutoffEpochMillis = retentionCutoffEpochMillis,
            keepRecent = keepRecent,
        ) ?: return
        dao.deleteMessagesUpTo(maxDeletable)
        dao.pruneEvidence(System.currentTimeMillis() - 7 * DiagnosticsStore.DAY)
    }

    suspend fun exportTwentyEightDaysJson(
        exportedAt: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val zoneId = exportedAt.zone
        val today = exportedAt.toLocalDate()
        val (from, to) = HealthDataExporter.dateRange(today)
        val (startMillis, endMillis) = HealthDataExporter.epochRange(from, to, zoneId)
        // Export current definitions and the requested day's report, never a stale cache.
        val weeklyEntity = rebuildWeekly(today)
        val weeklySnapshot = runCatching {
            json.decodeFromString<WeeklySnapshot>(weeklyEntity.snapshotJson)
        }.getOrNull()
        val payload = HealthDataExporter.buildPayload(
            exportedAt = exportedAt,
            daily = dao.getDaily(from.toString(), to.toString()),
            body = dao.getBody(from.toString(), to.toString()),
            exerciseSessions = dao.getExerciseSessions(startMillis, endMillis),
            nutritionMeals = dao.getNutritionMeals(startMillis, endMillis),
            goal = dao.getGoal(),
            weeklySnapshot = weeklySnapshot,
            chatMessages = dao.getMessagesInRange(startMillis, endMillis),
            conversationMemory = dao.getMemory(),
        )
        return HealthDataExporter.encodeToJson(payload)
    }

    suspend fun clearAll() {
        database.withTransaction {
            dao.clearDaily()
            dao.clearBody()
            dao.clearSources()
            dao.clearExerciseSessions()
            dao.clearNutritionMeals()
            dao.clearGoals()
            dao.clearWeekly()
            dao.clearMessages()
            dao.clearMemory()
            dao.clearEvents()
            dao.clearEvidence()
            dao.clearReports()
            dao.clearMetadata()
        }
    }
}
