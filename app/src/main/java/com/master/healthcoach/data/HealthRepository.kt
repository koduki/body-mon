package com.master.healthcoach.data

import androidx.room.withTransaction
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.db.AppMetadataEntity
import com.master.healthcoach.data.db.HealthCoachDao
import com.master.healthcoach.data.db.ReportArchiveEntity
import com.master.healthcoach.data.diagnostics.ActiveStageCheckpoint
import com.master.healthcoach.data.diagnostics.DiagnosticErrorClassifier
import com.master.healthcoach.data.diagnostics.DiagnosticsStore
import com.master.healthcoach.data.diagnostics.StageSpanRecord
import com.master.healthcoach.data.diagnostics.SyncRunDiagnostics
import com.master.healthcoach.data.diagnostics.W3cTraceContext
import com.master.healthcoach.data.health.SyncStageObserver
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
import com.master.healthcoach.data.db.HeartRateSampleEntity
import com.master.healthcoach.data.db.HealthCoachDatabase
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import com.master.healthcoach.data.db.NutritionMealEntity
import com.master.healthcoach.data.db.WeeklyReportEntity
import com.master.healthcoach.data.health.HealthConnectAvailability
import com.master.healthcoach.data.health.HealthConnectGateway
import com.master.healthcoach.data.llm.ChatHistoryPolicy
import com.master.healthcoach.domain.EnergyModel
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

fun interface SyncFaultHook {
    suspend fun onStage(stage: String)
}

class HealthRepository(
    private val database: HealthCoachDatabase?,
    private val gateway: HealthConnectGateway,
    private val json: Json,
    val diagnostics: DiagnosticsStore? = null,
    daoOverride: HealthCoachDao? = null,
    private val transactionRunner: (suspend (suspend () -> Unit) -> Unit)? = null,
) {
    private val dao: HealthCoachDao = daoOverride ?: requireNotNull(database) { "database or daoOverride is required" }.dao()

    private suspend fun <T> inTransaction(block: suspend () -> T): T {
        val runner = transactionRunner
        if (runner != null) {
            var result: T? = null
            runner { result = block() }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
        return requireNotNull(database).withTransaction { block() }
    }

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

    suspend fun sync(
        trigger: String = "direct",
        faultHook: SyncFaultHook? = null,
    ): WeeklyReportEntity {
        val traceId = DiagnosticsStore.traceId()
        val rootSpanId = DiagnosticsStore.spanId()
        val startedAtMs = System.currentTimeMillis()
        val startedNano = System.nanoTime()
        val completedStages = mutableListOf<StageSpanRecord>()
        val summaryCounters = linkedMapOf<String, Long>()
        var currentStage = "sync.init"
        var currentStageStartedMs = startedAtMs
        var lastSuccessfulStage: String? = null

        suspend fun enterStage(stage: String) {
            currentStage = stage
            currentStageStartedMs = System.currentTimeMillis()
            diagnostics?.writeStageCheckpoint(
                ActiveStageCheckpoint(
                    traceId = traceId,
                    rootSpanId = rootSpanId,
                    trigger = trigger,
                    activeStage = stage,
                    lastSuccessfulStage = lastSuccessfulStage,
                    startedAtEpochMillis = startedAtMs,
                    stageStartedAtEpochMillis = currentStageStartedMs,
                    completedStages = completedStages.toList(),
                    appVersion = BuildConfig.VERSION_NAME,
                    commitSha = BuildConfig.COMMIT_SHA,
                ),
            )
            faultHook?.onStage(stage)
        }

        fun completeStage(
            stage: String,
            startMs: Long,
            endMs: Long,
            attributes: Map<String, Long> = emptyMap(),
            stringAttributes: Map<String, String> = emptyMap(),
            booleanAttributes: Map<String, Boolean> = emptyMap(),
        ) {
            val safeEnd = endMs.coerceAtLeast(startMs)
            completedStages += StageSpanRecord(
                traceId = traceId,
                spanId = W3cTraceContext.generateSpanId(),
                parentSpanId = rootSpanId,
                name = stage,
                stage = stage,
                status = "ok",
                startEpochMillis = startMs,
                endEpochMillis = safeEnd,
                durationMs = (safeEnd - startMs).coerceAtLeast(0),
                attributes = attributes,
                stringAttributes = stringAttributes,
                booleanAttributes = booleanAttributes,
            )
            lastSuccessfulStage = stage
        }

        diagnostics?.event("sync", "started", correlation = traceId, stringAttributes = mapOf("sync.trigger" to trigger))
        try {
            val report = syncInternal(
                enterStage = ::enterStage,
                completeStage = ::completeStage,
                summaryCounters = summaryCounters,
            )
            val endedAtMs = System.currentTimeMillis().coerceAtLeast(startedAtMs)
            val durationMs = ((System.nanoTime() - startedNano) / 1_000_000).coerceAtLeast(0)
            diagnostics?.recordSyncRun(
                run = SyncRunDiagnostics(
                    runId = traceId,
                    traceId = traceId,
                    rootSpanId = rootSpanId,
                    trigger = trigger,
                    startedAtEpochMillis = startedAtMs,
                    endedAtEpochMillis = endedAtMs,
                    durationMs = durationMs,
                    outcome = "success",
                    lastSuccessfulStage = lastSuccessfulStage,
                    failedStage = null,
                    counters = summaryCounters.toMap(),
                    stages = completedStages.toList(),
                    appVersion = BuildConfig.VERSION_NAME,
                    commitSha = BuildConfig.COMMIT_SHA,
                ),
                faultOnDiagnosticsRecord = if (faultHook != null) {
                    { faultHook.onStage("diagnostics.record") }
                } else {
                    null
                },
            )
            return report
        } catch (e: CancellationException) {
            // TimeoutCancellationException is a subtype of CancellationException; record timeout before rethrowing.
            val errorKind = DiagnosticErrorClassifier.classify(e)
            if (errorKind == "timeout") {
                val endedAtMs = System.currentTimeMillis().coerceAtLeast(startedAtMs)
                val durationMs = ((System.nanoTime() - startedNano) / 1_000_000).coerceAtLeast(0)
                val frames = DiagnosticErrorClassifier.sanitizeAppFrames(e)
                val exType = DiagnosticErrorClassifier.sanitizeExceptionType(e)
                completedStages += StageSpanRecord(
                    traceId = traceId,
                    spanId = W3cTraceContext.generateSpanId(),
                    parentSpanId = rootSpanId,
                    name = currentStage,
                    stage = currentStage,
                    status = "error",
                    startEpochMillis = currentStageStartedMs,
                    endEpochMillis = endedAtMs,
                    durationMs = (endedAtMs - currentStageStartedMs).coerceAtLeast(0),
                    errorKind = errorKind,
                    exceptionType = exType,
                    appFrames = frames,
                )
                diagnostics?.recordSyncRun(
                    SyncRunDiagnostics(
                        runId = traceId,
                        traceId = traceId,
                        rootSpanId = rootSpanId,
                        trigger = trigger,
                        startedAtEpochMillis = startedAtMs,
                        endedAtEpochMillis = endedAtMs,
                        durationMs = durationMs,
                        outcome = "failure",
                        lastSuccessfulStage = lastSuccessfulStage,
                        failedStage = currentStage,
                        errorKind = errorKind,
                        exceptionType = exType,
                        appFrames = frames,
                        counters = summaryCounters.toMap(),
                        stages = completedStages.toList(),
                        appVersion = BuildConfig.VERSION_NAME,
                        commitSha = BuildConfig.COMMIT_SHA,
                    ),
                )
            } else {
                diagnostics?.clearStageCheckpoint()
            }
            throw e
        } catch (e: Exception) {
            val endedAtMs = System.currentTimeMillis().coerceAtLeast(startedAtMs)
            val durationMs = ((System.nanoTime() - startedNano) / 1_000_000).coerceAtLeast(0)
            val errorKind = DiagnosticErrorClassifier.classify(e)
            val resolvedFailedStage = if (errorKind == "migration" && currentStage in setOf("sync.init", "room.persist")) {
                "room.migration"
            } else {
                currentStage
            }
            val frames = DiagnosticErrorClassifier.sanitizeAppFrames(e)
            val exType = DiagnosticErrorClassifier.sanitizeExceptionType(e)
            completedStages += StageSpanRecord(
                traceId = traceId,
                spanId = W3cTraceContext.generateSpanId(),
                parentSpanId = rootSpanId,
                name = resolvedFailedStage,
                stage = resolvedFailedStage,
                status = "error",
                startEpochMillis = currentStageStartedMs,
                endEpochMillis = endedAtMs,
                durationMs = (endedAtMs - currentStageStartedMs).coerceAtLeast(0),
                errorKind = errorKind,
                exceptionType = exType,
                appFrames = frames,
            )
            diagnostics?.recordSyncRun(
                SyncRunDiagnostics(
                    runId = traceId,
                    traceId = traceId,
                    rootSpanId = rootSpanId,
                    trigger = trigger,
                    startedAtEpochMillis = startedAtMs,
                    endedAtEpochMillis = endedAtMs,
                    durationMs = durationMs,
                    outcome = "failure",
                    lastSuccessfulStage = lastSuccessfulStage,
                    failedStage = resolvedFailedStage,
                    errorKind = errorKind,
                    exceptionType = exType,
                    appFrames = frames,
                    counters = summaryCounters.toMap(),
                    stages = completedStages.toList(),
                    appVersion = BuildConfig.VERSION_NAME,
                    commitSha = BuildConfig.COMMIT_SHA,
                ),
            )
            throw e
        }
    }

    private suspend fun syncInternal(
        enterStage: suspend (String) -> Unit,
        completeStage: (String, Long, Long, Map<String, Long>, Map<String, String>, Map<String, Boolean>) -> Unit,
        summaryCounters: MutableMap<String, Long>,
    ): WeeklyReportEntity {
        val today = LocalDate.now()
        // Query Room backfill/goal metadata only after checking whether a migration fault hook is active.
        val earliestDaily = runCatching {
            dao.earliestDailyDate()?.let { LocalDate.parse(it) }
        }.getOrElse { error ->
            if (DiagnosticErrorClassifier.classify(error) == "migration") {
                enterStage("room.migration")
                throw error
            }
            null
        }
        val canBackfillHistory = runCatching { gateway.hasHistoryPermission() }.getOrDefault(false)
        val needsInitialBackfill = canBackfillHistory && (
            earliestDaily == null || earliestDaily > today.minusDays(89) ||
                runCatching { dao.needsMetricBackfill(today.minusDays(89).toString()) }.getOrDefault(false)
            )
        val energyGoal = runCatching { dao.getGoal() }.getOrNull()
        val syncDays = if (needsInitialBackfill) 90L else 31L
        summaryCounters["sync.days"] = syncDays

        val stageObserver = object : SyncStageObserver {
            override suspend fun onStageStarted(stage: String) {
                enterStage(stage)
            }

            override suspend fun onStageCompleted(
                stage: String,
                startEpochMillis: Long,
                endEpochMillis: Long,
                attributes: Map<String, Long>,
                stringAttributes: Map<String, String>,
                booleanAttributes: Map<String, Boolean>,
            ) {
                completeStage(stage, startEpochMillis, endEpochMillis, attributes, stringAttributes, booleanAttributes)
            }
        }

        val bundle = gateway.sync(
            days = syncDays,
            heightCm = energyGoal?.heightCm,
            sex = energyGoal?.sex,
            ageYears = energyGoal?.age,
            stageObserver = stageObserver,
        )

        summaryCounters["days"] = bundle.daily.size.toLong()
        summaryCounters["body"] = bundle.body.size.toLong()
        summaryCounters["sessions"] = bundle.exerciseSessions.size.toLong()
        summaryCounters["heartSamples"] = bundle.heartRateSamples.size.toLong()
        summaryCounters["meals"] = bundle.nutritionMeals.size.toLong()

        val persistStart = System.currentTimeMillis()
        enterStage("room.persist")
        inTransaction {
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
            dao.deleteHeartRateSamplesInRange(
                bundle.rangeStartEpochMillis,
                bundle.rangeEndEpochMillisExclusive,
            )
            // Insert in bounded batches to avoid SQLite bind/transaction overhead.
            bundle.heartRateSamples.chunked(500).forEach { dao.upsertHeartRateSamples(it) }
            val keepFrom = today.minusDays(89).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            dao.pruneHeartRateSamples(keepFrom)
            dao.deleteNutritionMealsInRange(
                bundle.rangeStartEpochMillis,
                bundle.rangeEndEpochMillisExclusive,
            )
            dao.upsertNutritionMeals(bundle.nutritionMeals)
        }
        val persistEnd = System.currentTimeMillis().coerceAtLeast(persistStart)
        completeStage(
            "room.persist",
            persistStart,
            persistEnd,
            mapOf(
                "db.daily_upserted" to bundle.daily.size.toLong(),
                "db.body_upserted" to bundle.body.size.toLong(),
                "db.sources_upserted" to bundle.sources.size.toLong(),
                "db.sessions_upserted" to bundle.exerciseSessions.size.toLong(),
                "db.heart_samples_upserted" to bundle.heartRateSamples.size.toLong(),
                "db.meals_upserted" to bundle.nutritionMeals.size.toLong(),
            ),
            emptyMap(),
            emptyMap(),
        )

        val rebuildStart = System.currentTimeMillis()
        enterStage("weekly.rebuild")
        var archived = false
        var inputsBytes = 0L
        val weekly = rebuildWeeklyInternal(today) { isArchived, bytes ->
            archived = isArchived
            inputsBytes = bytes.toLong()
        }
        val rebuildEnd = System.currentTimeMillis().coerceAtLeast(rebuildStart)
        completeStage(
            "weekly.rebuild",
            rebuildStart,
            rebuildEnd,
            mapOf("weekly.inputs_bytes" to inputsBytes),
            emptyMap(),
            mapOf("weekly.archived" to archived),
        )
        return weekly
    }

    suspend fun rebuildWeekly(today: LocalDate = LocalDate.now()): WeeklyReportEntity =
        rebuildWeeklyInternal(today)

    private suspend fun rebuildWeeklyInternal(
        today: LocalDate = LocalDate.now(),
        onArchiveMetrics: ((Boolean, Int) -> Unit)? = null,
    ): WeeklyReportEntity = inTransaction {
        val from = today.minusDays(89).toString()
        val daily = dao.getDaily(from, today.toString())
        val body = dao.getBody(from, today.toString())
        val ended = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val snapshot = WeeklyReportBuilder.build(
            today = today,
            daily = daily,
            body = body,
            goal = dao.getGoal(),
            exerciseSessions = dao.getExerciseSessions(
                today.minusDays(28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                ended - 1,
            ),
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
            val byteSize = inputs.toByteArray().size
            val canArchive = byteSize <= 160_000
            if (canArchive) {
                dao.archiveReport(ReportArchiveEntity(
                    id = UUID.randomUUID().toString(), periodStart = snapshot.weekStart, periodEnd = snapshot.weekEnd,
                    generatedAt = it.generatedAtEpochMillis, revision = dao.metadata("revision")?.toLongOrNull() ?: 0,
                    policyVersion = ConversationContextPolicy.VERSION, commitSha = BuildConfig.COMMIT_SHA,
                    snapshotJson = it.snapshotJson, inputsJson = inputs,
                ))
                dao.pruneReports(System.currentTimeMillis() - 90 * DiagnosticsStore.DAY)
            }
            onArchiveMetrics?.invoke(canArchive, byteSize)
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

    suspend fun heartSamples(fromMs: Long, toExclusiveMs: Long, sessionId: String = ""): List<HeartRateSampleEntity> {
        // Only use this for bounded windows. Large streams must use paged MCP queries.
        require(toExclusiveMs > fromMs && toExclusiveMs - fromMs <= 7 * 86_400_000L)
        return dao.pageHeartSamples(fromMs, toExclusiveMs, sessionId, Long.MIN_VALUE, "", -1L, 150_000)
    }

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

    suspend fun exportWeeklyAiJson(
        exportedAt: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val zoneId = exportedAt.zone
        val today = exportedAt.toLocalDate()
        val (weekFrom, _) = HealthDataExporter.weeklyDateRange(today, includeToday = false)
        val (startMillis, endMillis) = HealthDataExporter.epochRange(weekFrom, today, zoneId)
        val historyFromStr = today.minusDays(89).toString()
        val todayStr = today.toString()
        val allDaily = dao.getDaily(historyFromStr, todayStr)
        val allBody = dao.getBody(historyFromStr, todayStr)
        val weeklyEntity = rebuildWeekly(today)
        val goal = dao.getGoal()
        val weeklySnapshot = runCatching {
            json.decodeFromString<WeeklySnapshot>(weeklyEntity.snapshotJson)
        }.getOrNull() ?: WeeklyReportBuilder.build(
            today = today,
            daily = allDaily,
            body = allBody,
            goal = goal,
            zoneId = zoneId,
        )
        val payload = HealthDataExporter.buildWeeklyAiPayload(
            exportedAt = exportedAt,
            daily = allDaily,
            body = allBody,
            exerciseSessions = dao.getExerciseSessions(startMillis, endMillis),
            nutritionMeals = dao.getNutritionMeals(startMillis, endMillis),
            goal = goal,
            weeklySnapshot = weeklySnapshot,
            sources = dao.getSources(),
            dataRevision = dao.metadata("revision"),
            lastSyncSuccessEpochMillis = dao.metadata("lastSyncSuccess")?.toLongOrNull(),
            lastSyncOutcome = dao.metadata("lastSyncOutcome"),
        )
        return HealthDataExporter.encodeWeeklyAiToJson(payload)
    }

    suspend fun clearAll() {
        inTransaction {
            dao.clearDaily()
            dao.clearBody()
            dao.clearSources()
            dao.clearExerciseSessions()
            dao.clearHeartRateSamples()
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
