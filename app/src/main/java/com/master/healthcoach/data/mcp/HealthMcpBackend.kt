package com.master.healthcoach.data.mcp

import android.os.Build
import androidx.room.withTransaction
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.db.*
import com.master.healthcoach.data.diagnostics.DiagnosticsStore
import com.master.healthcoach.data.diagnostics.FlightEventRecord
import com.master.healthcoach.data.diagnostics.W3cTraceContext
import com.master.healthcoach.data.llm.*
import com.master.healthcoach.domain.WeeklyReportBuilder
import com.master.healthcoach.domain.WorkoutHeartAnalytics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

class HealthMcpBackend(
    private val db: HealthCoachDatabase?,
    private val repository: HealthRepository,
    private val diagnostics: DiagnosticsStore,
    private val permissions: () -> SharePermissions,
    private val sessionExpiresAt: () -> Long,
    daoOverride: HealthCoachDao? = null,
    private val transactionRunner: (suspend (suspend () -> JsonObject) -> JsonObject)? = null,
) {
    private val daoOrNull: HealthCoachDao? = daoOverride ?: runCatching { db?.dao() }.getOrNull()
    private val dao: HealthCoachDao get() = daoOrNull ?: throw McpFailure("DB_UNAVAILABLE")
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val queries = HealthQueryService(repository)
    fun tools(): JsonArray = McpTools.available(permissions())

    private suspend fun inTransaction(block: suspend () -> JsonObject): JsonObject {
        val runner = transactionRunner
        if (runner != null) return runner(block)
        val database = db
        if (database != null) {
            return try {
                database.withTransaction { block() }
            } catch (e: McpFailure) {
                throw e
            } catch (e: Exception) {
                // Allow Room-independent diagnostics tools to succeed even if SQLite/Room cannot open
                block()
            }
        }
        return block()
    }

    suspend fun call(name: String, args: JsonObject): JsonElement {
        val callStartMs = System.currentTimeMillis()
        val incomingTrace = currentCoroutineContext()[McpTraceContextElement]?.traceContext
        val mcpSpanId = W3cTraceContext.generateSpanId()
        McpTools.check(name, args, permissions())
        // Permission queries touch Health Connect and should not hold a Room transaction.
        val granted = if (name == "get_app_diagnostics") runCatching { repository.grantedPermissions().sorted() }.getOrDefault(emptyList()) else emptyList()
        val result = try {
            inTransaction {
            val revision = runCatching { daoOrNull?.metadata("revision") }.getOrNull().orEmpty()
            val today = LocalDate.now()
            val to = date(args, "to", if (name == "get_workout_hr_comparison") today.minusDays(1) else today)
            val from = date(args, "from", if (name == "get_heart_rate_series") to else to.minusDays(27))
            if (from > to || ChronoUnit.DAYS.between(from, to) >= 90 || to > today) throw McpFailure("INVALID_RANGE")
            val zone = ZoneId.systemDefault()
            val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            val limit = number(args, "limit", 50, 1, if (name == "get_heart_rate_series") 500 else if (name == "get_sync_diagnostics") 50 else 100)
            val fingerprint = digest(name + JsonObject(args.filterKeys { it != "cursor" }.toSortedMap()).toString())
            val cursor = decodeCursor(args.string("cursor"), revision, fingerprint)
            var next: String? = null
            var effectiveFrom = from.toString()
            var effectiveTo = to.toString()
            val latestSyncRun = diagnostics.flightRecorder.syncRuns(limit = 1).firstOrNull()
            val activeTraceId = incomingTrace?.traceId
                ?: latestSyncRun?.traceId
                ?: runCatching { daoOrNull?.metadata("lastSyncTraceId") }.getOrNull()?.takeIf { W3cTraceContext.isValidTraceId(it) }
                ?: W3cTraceContext.generateTraceId()
            val data: JsonElement = when (name) {
                "get_app_context" -> buildJsonObject {
                    put("appVersion", BuildConfig.VERSION_NAME); put("commitSha", BuildConfig.COMMIT_SHA)
                    put("permissions", json.encodeToJsonElement(permissions()))
                    put("sessionExpiresAt", Instant.ofEpochMilli(sessionExpiresAt()).toString())
                    put("healthAvailableFrom", dao.earliestDailyDate()?.let(::JsonPrimitive) ?: JsonNull)
                    put("conversationAvailableFromEpochMillis", dao.earliestMessage()?.let(::JsonPrimitive) ?: JsonNull)
                    put("readOnly", true); put("metricPageIsPartialRange", true)
                    put("limitations", "BIA and energy expenditure are estimates. Origin candidates are not proven contributors. No automatic Health Connect refresh. Use GitHub at commitSha for code investigation.")
                }
                "get_profile_and_goals" -> json.encodeToJsonElement(dao.getGoal())
                "get_workout_hr_comparison" -> {
                    val periodEnd = date(args, "to", today.minusDays(1))
                    if (periodEnd >= today) throw McpFailure("INVALID_RANGE")
                    val analysisFrom = periodEnd.minusDays(27)
                    val fromMs = analysisFrom.atStartOfDay(zone).toInstant().toEpochMilli()
                    val endExclusiveMs = periodEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val rows = dao.getExerciseSessions(fromMs, endExclusiveMs - 1)
                    val comparison = WorkoutHeartAnalytics.compare28Days(rows.map {
                        WorkoutHeartAnalytics.ComparableWorkout(
                            it.exerciseType, it.durationMinutes, it.workoutHeartAverageBpm,
                            it.startEpochMillis, it.workoutHeartCoverageMinutes ?: 0,
                        )
                    }, endExclusiveMs)
val allWindows = rows.map {
                        WorkoutHeartAnalytics.Workout(it.recordId, it.startEpochMillis, it.endEpochMillis, it.exerciseType)
                    }
                    val measuredDrops = rows.mapNotNull { item ->
                        val window = WorkoutHeartAnalytics.Workout(item.recordId, item.startEpochMillis, item.endEpochMillis, item.exerciseType)
                        item.recoveryDrop1MinBpm?.takeIf {
                            WorkoutHeartAnalytics.recoveryWindowClear(window, allWindows, 75_000L)
                        }
                    }
                    effectiveFrom = analysisFrom.toString(); effectiveTo = periodEnd.toString()
                    buildJsonObject {
                        put("periodStart", analysisFrom.toString())
                        put("periodEnd", periodEnd.toString())
                        put("workoutCount", rows.size)
                        put("recovery1MinObservedSessions", measuredDrops.size)
                        put("recovery1MinMeanDropBpm", measuredDrops.takeIf { it.size >= 2 }?.average()?.let(::JsonPrimitive) ?: JsonNull)
                        put("comparisons", json.encodeToJsonElement(comparison))
                        put("limitations", "Duration and exercise type only; speed, slope, power, temperature, sleep, medication, and sensor calibration are unknown. No clinical or VO2max inference. Null means insufficient observations.")
                    }
                }
                "get_heart_rate_series" -> {
                    val sessionId = args.string("sessionId")
                    // Month-wide raw data can be large. Use indexed paging or 1..60 minute buckets.
                    val resolution = number(args, "resolutionMinutes", 1, 0, 60)
                    val pageSize = number(args, "limit", 120, 1, 500)
                    val total = dao.countHeartSamples(start, end + 1, sessionId)
                    if (resolution == 0) {
                        val token = if (cursor.isBlank()) emptyList() else cursor.split('|', limit = 4)
                        if (token.isNotEmpty() && (token.size != 4 || token[0] != "r")) throw McpFailure("INVALID_CURSOR")
                        val afterMs = token.getOrNull(1)?.toLongOrNull() ?: if (token.isEmpty()) Long.MIN_VALUE else throw McpFailure("INVALID_CURSOR")
                        val afterSource = token.getOrNull(2).orEmpty()
                        val afterBpm = token.getOrNull(3)?.toLongOrNull() ?: if (token.isEmpty()) -1L else throw McpFailure("INVALID_CURSOR")
                        val rows = dao.pageHeartSamples(start, end + 1, sessionId, afterMs, afterSource, afterBpm, pageSize + 1)
                        if (rows.size > pageSize) {
                            val last = rows[pageSize - 1]
                            next = "r|${last.timestampEpochMillis}|${last.sourceRecordId}|${last.bpm}"
                        }
                        buildJsonObject {
                            put("mode", "raw"); put("resolutionMinutes", 0)
                            put("matchingSampleCount", total); put("workoutSessionIdFilter", sessionId)
                            put("samples", buildJsonArray {
                                rows.take(pageSize).forEach { sample -> add(buildJsonObject {
                                    put("timestampEpochMillis", sample.timestampEpochMillis)
                                    put("bpm", sample.bpm)
                                    put("origin", sample.dataOrigin)
                                    put("sessionId", sample.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                                    put("phase", sample.phase)
                                }) }
                            })
                            put("qualityNote", "Record-linked original samples. 'post_workout' is a 3-minute time window after a recorded session; 'unclassified' is not resting HR. Sensor artefacts may exist. No gaps are interpolated.")
                        }
                    } else {
                        val bucketMs = resolution * 60_000L
                        val parts = if (cursor.isBlank()) emptyList() else cursor.split('|', limit = 2)
                        if (parts.isNotEmpty() && (parts.size != 2 || parts[0] != "b")) throw McpFailure("INVALID_CURSOR")
                        val after = parts.getOrNull(1)?.toLongOrNull() ?: if (parts.isEmpty()) -1L else throw McpFailure("INVALID_CURSOR")
                        val rows = dao.pageHeartBuckets(start, end + 1, sessionId, bucketMs, after, pageSize + 1)
                        if (rows.size > pageSize) {
                            val last = rows[pageSize - 1]
                            next = "b|${last.bucketStartMillis / bucketMs}"
                        }
                        buildJsonObject {
                            put("mode", "bucket"); put("resolutionMinutes", resolution)
                            put("matchingSampleCount", total); put("workoutSessionIdFilter", sessionId)
                            put("buckets", buildJsonArray {
                                rows.take(pageSize).forEach { item -> add(buildJsonObject {
                                    put("startEpochMillis", item.bucketStartMillis)
                                    put("averageBpm", item.averageBpm)
                                    put("minimumBpm", item.minimumBpm)
                                    put("maximumBpm", item.maximumBpm)
                                    put("sampleCount", item.sampleCount)
                                }) }
                            })
                            put("qualityNote", "Observed buckets only; gaps are not interpolated. Unfiltered buckets may mix activity phases and must not be interpreted as resting HR.")
                        }
                    }
                }
                "get_health_metrics", "get_data_quality" -> {
                    val pageFrom = if (cursor.isBlank()) from else runCatching { LocalDate.parse(cursor) }.getOrElse { throw McpFailure("INVALID_CURSOR") }
                    if (pageFrom !in from..to) throw McpFailure("INVALID_CURSOR")
                    val days = number(args, "limit", 7, 1, 7)
                    val pageTo = minOf(to, pageFrom.plusDays(days - 1L))
                    effectiveFrom = pageFrom.toString(); effectiveTo = pageTo.toString()
                    if (pageTo < to) next = pageTo.plusDays(1).toString()
                    if (name == "get_health_metrics") {
                        val metric = args.string("metric")
                        val function = mapOf("body" to "get_body_composition", "activity" to "get_activity_summary", "exercise" to "get_exercise_summary", "sleep" to "get_sleep_summary", "heart_rate" to "get_heart_rate_summary", "intensity" to "get_activity_intensity_summary", "metabolism" to "get_metabolism_summary", "nutrition" to "get_nutrition_summary")[metric] ?: throw McpFailure("INVALID_METRIC")
                        queries.execute(GeminiFunctionCall(function, buildJsonObject { put("from", effectiveFrom); put("to", effectiveTo) }))
                    } else buildJsonObject {
                        put("days", buildJsonArray { dao.getDaily(effectiveFrom, effectiveTo).forEach { day -> add(buildJsonObject {
                            put("date", day.date); put("nutritionComplete", day.nutritionComplete)
                            put("nutritionRecordStatus", day.nutritionRecordStatus); put("intakeMissing", day.intakeCaloriesKcal == null)
                            put("stepsMissing", day.steps == null); put("sleepMissing", day.sleepMinutes == null)
                            put("sleepDurationSource", day.sleepDurationSource)
                            put("heartRateCoverageMinutes", day.heartRateCoverageMinutes?.let(::JsonPrimitive) ?: JsonNull)
                            put("heartRateOutsideExerciseSamples", day.heartRateOutsideExerciseSamples?.let(::JsonPrimitive) ?: JsonNull)
                            put("originCandidates", parseOrNull(day.metricOriginsJson))
                        }) } })
                        put("sources", json.encodeToJsonElement(dao.getSources()))
                    }
                }
                "list_weekly_reports" -> {
                    val rows = dao.reports(from.toString(), to.toString(), cursor, limit + 1)
                    if (rows.size > limit) next = rows[limit - 1].id
                    buildJsonArray { rows.take(limit).forEach { add(reportMetadata(it)) } }
                }
                "get_weekly_report" -> {
                    val id = args.string("reportId")
                    if (id.isNotEmpty()) {
                        val report = dao.report(id) ?: throw McpFailure("NOT_FOUND")
                        buildJsonObject { put("metadata", reportMetadata(report)); put("reportMode", "saved"); put("snapshot", parseOrNull(report.snapshotJson)) }
                    } else if (args.string("periodEnd").isNotEmpty()) reconstruct(args, false)
                    else {
                        val legacy = dao.getLatestWeekly() ?: throw McpFailure("NO_DATA")
                        buildJsonObject { put("reportMode", "saved_cache"); put("generatedAtEpochMillis", legacy.generatedAtEpochMillis); put("snapshot", parseOrNull(legacy.snapshotJson)); put("historicalInputsAvailable", false) }
                    }
                }
                "search_conversations" -> {
                    val role = args.string("role")
                    if (role !in setOf("", "user", "assistant")) throw McpFailure("INVALID_ARGUMENT")
                    val rows = dao.searchMessages(start, end, args.string("query"), role, cursor.toLongOrNull() ?: 0, limit + 1)
                    if (rows.size > limit) next = rows[limit - 1].id.toString()
                    buildJsonArray { rows.take(limit).forEach { row -> add(buildJsonObject {
                        put("id", row.id); put("role", row.role); put("createdAt", Instant.ofEpochMilli(row.createdAtEpochMillis).toString())
                        put("excerpt", row.content.take(500)); put("truncated", row.content.length > 500)
                    }) } }
                }
                "get_conversation_context" -> {
                    val id = args["messageId"]?.jsonPrimitive?.longOrNull ?: throw McpFailure("INVALID_ARGUMENT")
                    val row = dao.message(id) ?: throw McpFailure("NOT_FOUND")
                    val rows = dao.messagesBefore(id, number(args, "before", 2, 0, 5)).reversed() + row + dao.messagesAfter(id, number(args, "after", 2, 0, 5))
                    buildJsonObject {
                        put("messages", buildJsonArray { rows.forEach { msg -> add(buildJsonObject {
                            put("id", msg.id); put("role", msg.role); put("createdAt", Instant.ofEpochMilli(msg.createdAtEpochMillis).toString())
                            val offset = if (msg.id == id) number(args, "contentOffset", 0, 0, 10_000_000) else 0
                            put("text", chunk(msg.content, offset, 4000)); put("attachmentsOmitted", msg.attachmentNames != null)
                        }) } })
                        if (args["includeMemory"]?.jsonPrimitive?.booleanOrNull == true) {
                            val memory = dao.getMemory()
                            put("memory", chunk(memory?.summary.orEmpty(), number(args, "memoryOffset", 0, 0, 10_000_000), 4000))
                            put("memoryUpdatedAtEpochMillis", memory?.updatedAtEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    }
                }
                "get_app_diagnostics" -> buildJsonObject {
                    put("appVersion", BuildConfig.VERSION_NAME); put("commitSha", BuildConfig.COMMIT_SHA); put("sdkInt", Build.VERSION.SDK_INT)
                    put("databaseVersion", 12); put("policyVersion", ConversationContextPolicy.VERSION)
                    put("grantedPermissions", json.encodeToJsonElement(granted)); put("captureEnabled", diagnostics.enabled); put("detailedCapture", diagnostics.detailed)
                    put("otlpExportEnabled", diagnostics.otlpSettings.enabled)
                    val events = runCatching { daoOrNull?.events(start, end, "", 0, 10001) }.getOrNull().orEmpty()
                    put("eventCount", events.size); put("sampleLimited", events.size > 10000)
                    put("counts", buildJsonObject { events.groupingBy { it.category + ":" + it.outcome }.eachCount().forEach { (k,v) -> put(k,v) } })
                    put("lastSyncTraceId", (latestSyncRun?.traceId ?: runCatching { daoOrNull?.metadata("lastSyncTraceId") }.getOrNull())?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastSyncLastStage", (latestSyncRun?.lastSuccessfulStage ?: runCatching { daoOrNull?.metadata("lastSyncLastStage") }.getOrNull()?.takeIf { it.isNotEmpty() })?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastSyncFailedStage", (latestSyncRun?.failedStage ?: runCatching { daoOrNull?.metadata("lastSyncFailedStage") }.getOrNull()?.takeIf { it.isNotEmpty() })?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastSyncErrorKind", (latestSyncRun?.errorKind ?: runCatching { daoOrNull?.metadata("lastSyncErrorKind") }.getOrNull()?.takeIf { it.isNotEmpty() })?.let(::JsonPrimitive) ?: JsonNull)
                    put("flightRecorderRunCount", diagnostics.flightRecorder.syncRuns(limit = 60).size)
                    put("recentProcessExits", json.encodeToJsonElement(diagnostics.flightRecorder.processExits(limit = 10)))
                    put("retentionDays", 14); put("historicalEventsBeforeFeatureUnavailable", true)
                }
                "get_sync_diagnostics" -> {
                    val filter = args.string("traceId").ifBlank { args.string("runId") }
                    val pageSize = number(args, "limit", 20, 1, 50)
                    val offset = if (cursor.isBlank()) 0 else cursor.toIntOrNull()?.takeIf { it >= 0 } ?: throw McpFailure("INVALID_CURSOR")
                    val allRuns = diagnostics.flightRecorder.syncRuns(traceIdFilter = filter, limit = 60)
                    val pageRuns = allRuns.drop(offset).take(pageSize + 1)
                    if (pageRuns.size > pageSize) {
                        next = (offset + pageSize).toString()
                    }
                    val checkpoint = diagnostics.flightRecorder.readCheckpoint()
                    buildJsonObject {
                        put("latestRunSummary", buildJsonObject {
                            put("runId", latestSyncRun?.runId?.let(::JsonPrimitive) ?: JsonNull)
                            put("traceId", (latestSyncRun?.traceId ?: runCatching { daoOrNull?.metadata("lastSyncTraceId") }.getOrNull())?.let(::JsonPrimitive) ?: JsonNull)
                            put("trigger", latestSyncRun?.trigger?.let(::JsonPrimitive) ?: JsonNull)
                            put("outcome", (latestSyncRun?.outcome ?: runCatching { daoOrNull?.metadata("lastSyncOutcome") }.getOrNull())?.let(::JsonPrimitive) ?: JsonNull)
                            put("lastSuccessfulStage", (latestSyncRun?.lastSuccessfulStage ?: runCatching { daoOrNull?.metadata("lastSyncLastStage") }.getOrNull()?.takeIf { it.isNotEmpty() })?.let(::JsonPrimitive) ?: JsonNull)
                            put("failedStage", (latestSyncRun?.failedStage ?: runCatching { daoOrNull?.metadata("lastSyncFailedStage") }.getOrNull()?.takeIf { it.isNotEmpty() })?.let(::JsonPrimitive) ?: JsonNull)
                            put("errorKind", (latestSyncRun?.errorKind ?: runCatching { daoOrNull?.metadata("lastSyncErrorKind") }.getOrNull()?.takeIf { it.isNotEmpty() })?.let(::JsonPrimitive) ?: JsonNull)
                            put("startedAtEpochMillis", latestSyncRun?.startedAtEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
                            put("endedAtEpochMillis", latestSyncRun?.endedAtEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
                            put("durationMs", latestSyncRun?.durationMs?.let(::JsonPrimitive) ?: JsonNull)
                        })
                        put("activeStageCheckpoint", checkpoint?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                        put("runs", json.encodeToJsonElement(pageRuns.take(pageSize)))
                        put("recentProcessExits", json.encodeToJsonElement(diagnostics.flightRecorder.processExits(limit = 10)))
                        put("otlpExportEnabled", diagnostics.otlpSettings.enabled)
                        put("privacyGuarantee", "Stage timings, record counts, and allowlisted error kinds only. No health values, chat messages, or prompts.")
                    }
                }
                "list_app_events" -> {
                    val category = args.string("category")
                    if (category.isNotEmpty() && category !in DiagnosticsStore.CATEGORIES) throw McpFailure("INVALID_ARGUMENT")
                    val rows = dao.events(start, end, category, cursor.toLongOrNull() ?: 0, limit + 1)
                    if (rows.size > limit) next = rows[limit - 1].id.toString()
                    json.encodeToJsonElement(rows.take(limit))
                }
                "get_response_evidence" -> {
                    val id = args["messageId"]?.jsonPrimitive?.longOrNull ?: throw McpFailure("INVALID_ARGUMENT")
                    val row = dao.message(id) ?: throw McpFailure("NOT_FOUND")
                    val captured = diagnostics.evidence(id)
                    val payload = buildJsonObject {
                        put("messageId", id); put("modelId", row.modelId?.let(::JsonPrimitive) ?: JsonNull)
                        put("appVersion", row.appVersionName?.let(::JsonPrimitive) ?: JsonNull); put("policyVersion", row.policyVersion?.let(::JsonPrimitive) ?: JsonNull)
                        put("toolTrace", parseOrNull(row.toolTraceJson)); put("promptAvailable", captured != null)
                        put("capturedInputs", captured ?: JsonNull); put("captureLevel", if (captured == null) "metadata" else "inputs")
                        put("limitations", "No hidden model reasoning; initial processed text and bounded tool trace only. Attachments and full provider rounds are omitted. Missing captures cannot be reconstructed as historical truth.")
                    }
                    chunk(payload.toString(), number(args, "offset", 0, 0, 10_000_000), 16000)
                }
                "get_calculation_trace" -> {
                    val id = args.string("reportId")
                    val payload = if (id.isEmpty()) reconstruct(args, true) else {
                        val report = dao.report(id) ?: throw McpFailure("NOT_FOUND")
                        buildJsonObject { put("metadata", reportMetadata(report)); put("reportMode", "saved"); put("inputs", parseOrNull(report.inputsJson)); put("snapshot", parseOrNull(report.snapshotJson)) }
                    }
                    chunk(payload.toString(), number(args, "offset", 0, 0, 10_000_000), 16000)
                }
                else -> throw McpFailure("UNKNOWN_TOOL")
            }
            val periodFiltered = name in setOf("get_workout_hr_comparison", "get_heart_rate_series", "get_health_metrics", "get_data_quality", "list_weekly_reports", "search_conversations", "get_app_diagnostics", "list_app_events")
            if (name == "get_weekly_report") {
                val snapshot = (data as? JsonObject)?.get("snapshot") as? JsonObject
                effectiveFrom = snapshot?.string("weekStart").orEmpty()
                effectiveTo = snapshot?.string("weekEnd").orEmpty()
            }
            val lastSyncSuccess = runCatching { daoOrNull?.metadata("lastSyncSuccess")?.toLongOrNull() }.getOrNull()
                ?: latestSyncRun?.takeIf { it.outcome == "success" }?.endedAtEpochMillis
            val lastSyncOutcome = runCatching { daoOrNull?.metadata("lastSyncOutcome") }.getOrNull()
                ?: latestSyncRun?.outcome
            val lastSyncTraceId = runCatching { daoOrNull?.metadata("lastSyncTraceId") }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: latestSyncRun?.traceId
            buildJsonObject {
                put("schemaVersion", 1); put("generatedAt", Instant.now().toString()); put("timeZone", zone.id)
                put("dataRevision", revision); put("snapshotId", revision + ":" + fingerprint)
                put("traceId", activeTraceId)
                put("traceparent", W3cTraceContext.formatTraceparent(activeTraceId, mcpSpanId))
                put("requestedPeriod", if (periodFiltered) buildJsonObject { put("from", from.toString()); put("to", to.toString()) } else JsonNull)
                put("effectivePeriod", if (periodFiltered || name == "get_weekly_report") buildJsonObject { put("from", effectiveFrom); put("to", effectiveTo) } else JsonNull)
                put("lastSyncSuccessEpochMillis", lastSyncSuccess?.let(::JsonPrimitive) ?: JsonNull)
                put("lastSyncOutcome", lastSyncOutcome?.let(::JsonPrimitive) ?: JsonNull)
                put("lastSyncTraceId", lastSyncTraceId?.let(::JsonPrimitive) ?: JsonNull)
                put("todayIsPartial", true); put("data", data)
                put("nextCursor", next?.let { JsonPrimitive(encodeCursor(revision, fingerprint, it)) } ?: JsonNull)
                put("warnings", buildJsonArray { add(JsonPrimitive("Cached data only; null/missing is not zero. Metrics describe effectivePeriod; follow nextCursor for the remaining requested period.")) })
            }
            }
        } catch (e: Throwable) {
            if (diagnostics.enabled && incomingTrace != null) {
                val now = System.currentTimeMillis()
                val errEvent = FlightEventRecord(
                    timestampEpochMillis = now,
                    traceId = incomingTrace.traceId,
                    spanId = mcpSpanId,
                    parentSpanId = incomingTrace.parentSpanId,
                    category = "mcp",
                    outcome = "failure",
                    durationMs = (now - callStartMs).coerceAtLeast(0),
                    stringAttributes = mapOf("mcp.tool" to name, "mcp.outcome" to "failure"),
                    appVersion = BuildConfig.VERSION_NAME,
                    commitSha = BuildConfig.COMMIT_SHA,
                )
                diagnostics.flightRecorder.recordEvent(errEvent)
                diagnostics.otlpExporter.enqueueEvent(errEvent)
            }
            throw e
        }
        McpTools.check(name, args, permissions())
        if (diagnostics.enabled && incomingTrace != null) {
            val now = System.currentTimeMillis()
            val okEvent = FlightEventRecord(
                timestampEpochMillis = now,
                traceId = incomingTrace.traceId,
                spanId = mcpSpanId,
                parentSpanId = incomingTrace.parentSpanId,
                category = "mcp",
                outcome = "success",
                durationMs = (now - callStartMs).coerceAtLeast(0),
                stringAttributes = mapOf("mcp.tool" to name, "mcp.outcome" to "success"),
                appVersion = BuildConfig.VERSION_NAME,
                commitSha = BuildConfig.COMMIT_SHA,
            )
            diagnostics.flightRecorder.recordEvent(okEvent)
            diagnostics.otlpExporter.enqueueEvent(okEvent)
        }
        return result
    }
    private suspend fun reconstruct(args: JsonObject, inputs: Boolean): JsonObject {
        val end = date(args, "periodEnd", LocalDate.now().minusDays(1))
        if (end >= LocalDate.now() || end < LocalDate.now().minusDays(89)) throw McpFailure("INVALID_RANGE")
        val daily = repository.getDaily(end.minusDays(89), end)
        val body = repository.getBody(end.minusDays(89), end)
val goal = dao.getGoal()
        val zone = ZoneId.systemDefault()
        val fromMs = end.minusDays(27).atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val sessions = dao.getExerciseSessions(fromMs, toMs)
        return buildJsonObject {
            put("reportMode", "reconstructed"); put("commitSha", BuildConfig.COMMIT_SHA); put("policyVersion", ConversationContextPolicy.VERSION)
            put("snapshot", json.encodeToJsonElement(WeeklyReportBuilder.build(end.plusDays(1), daily, body, goal, exerciseSessions = sessions)))
            if (inputs) put("inputs", buildJsonObject { put("daily", json.encodeToJsonElement(daily)); put("body", json.encodeToJsonElement(body)); put("goal", json.encodeToJsonElement(goal)); put("exerciseSessions", json.encodeToJsonElement(sessions)) })
            put("limitations", "Current stored inputs and current calculation rules, not historical originals. See snapshot dataLimitations for inclusion/exclusion and coverage.")
        }
    }
    private fun reportMetadata(r: ReportArchiveEntity) = buildJsonObject {
        put("reportId", r.id); put("periodStart", r.periodStart); put("periodEnd", r.periodEnd); put("generatedAtEpochMillis", r.generatedAt)
        put("inputRevision", r.revision); put("policyVersion", r.policyVersion); put("commitSha", r.commitSha)
    }
    private fun parseOrNull(s: String?): JsonElement = s?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull
    private fun date(a: JsonObject, key: String, default: LocalDate) = a.string(key).takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw McpFailure("INVALID_RANGE") } } ?: default
    private fun number(a: JsonObject, key: String, default: Int, low: Int, high: Int): Int {
        val n = a[key]?.jsonPrimitive?.intOrNull ?: if (key in a) throw McpFailure("INVALID_ARGUMENT") else default
        if (n !in low..high) throw McpFailure("INVALID_ARGUMENT")
        return n
    }
    private fun chunk(text: String, offset: Int, size: Int): JsonObject {
        if (offset > text.length) throw McpFailure("INVALID_OFFSET")
        return buildJsonObject { put("text", text.drop(offset).take(size)); put("offset", offset); put("totalCharacters", text.length); put("nextOffset", if (offset + size < text.length) JsonPrimitive(offset + size) else JsonNull) }
    }
    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        fun encodeCursor(revision: String, fingerprint: String, position: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$revision|$fingerprint|$position".toByteArray())
        fun decodeCursor(cursor: String, revision: String, fingerprint: String): String {
            if (cursor.isEmpty()) return ""
            val parts = runCatching { Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8).split('|', limit = 3) }.getOrElse { throw McpFailure("INVALID_CURSOR") }
            if (parts.size != 3 || parts[1] != fingerprint) throw McpFailure("INVALID_CURSOR")
            if (parts[0] != revision) throw McpFailure("STALE_CURSOR")
            return parts[2]
        }
    }
}

internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
