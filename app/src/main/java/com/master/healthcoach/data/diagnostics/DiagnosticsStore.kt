package com.master.healthcoach.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.db.AppEventEntity
import com.master.healthcoach.data.db.AppMetadataEntity
import com.master.healthcoach.data.db.HealthCoachDao
import com.master.healthcoach.data.db.ResponseEvidenceEntity
import com.master.healthcoach.data.security.EncryptedTextStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant

/** Only fixed labels and numeric counters can enter general telemetry. */
class DiagnosticsStore private constructor(
    private val prefs: SharedPreferences?,
    private val dao: HealthCoachDao?,
    val flightRecorder: LocalFlightRecorder,
    val otlpExporter: OtlpTraceExporter,
    initialEnabled: Boolean = true,
    initialDetailed: Boolean = false,
) {
    constructor(
        context: Context,
        dao: HealthCoachDao,
        flightRecorder: LocalFlightRecorder = LocalFlightRecorder(context),
        otlpExporter: OtlpTraceExporter = OtlpTraceExporter(
            settings = AndroidOtlpSettings(context),
            persistenceDir = flightRecorder.directory,
        ),
    ) : this(
        prefs = context.getSharedPreferences("diagnostic_capture", Context.MODE_PRIVATE),
        dao = dao,
        flightRecorder = flightRecorder,
        otlpExporter = otlpExporter,
    )

    constructor(
        dao: HealthCoachDao?,
        flightRecorder: LocalFlightRecorder,
        otlpExporter: OtlpTraceExporter = OtlpTraceExporter(
            settings = InMemoryOtlpSettings(),
            allowLocalTestServer = true,
            asyncDispatch = false,
            persistenceDir = flightRecorder.directory,
        ),
        initialEnabled: Boolean = true,
        initialDetailed: Boolean = false,
    ) : this(
        prefs = null,
        dao = dao,
        flightRecorder = flightRecorder,
        otlpExporter = otlpExporter,
        initialEnabled = initialEnabled,
        initialDetailed = initialDetailed,
    )

    private var memoryEnabled: Boolean = initialEnabled
    private var memoryDetailed: Boolean = initialDetailed
    private val crypto by lazy { EncryptedTextStore("health_coach_evidence_key") }
    private val reportJson = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    var enabled: Boolean
        get() = prefs?.getBoolean("enabled", true) ?: memoryEnabled
        set(value) {
            memoryEnabled = value
            prefs?.edit()?.putBoolean("enabled", value)?.commit()
            if (!value) {
                flightRecorder.clearCheckpoint()
                otlpExporter.clearBuffer()
            }
        }

    var detailed: Boolean
        get() = prefs?.getBoolean("detailed", false) ?: memoryDetailed
        set(value) {
            memoryDetailed = value
            prefs?.edit()?.putBoolean("detailed", value)?.commit()
        }

    val otlpSettings: OtlpSettingsStore
        get() = otlpExporter.settings

    suspend fun event(
        category: String,
        outcome: String,
        durationMs: Long = 0,
        correlation: String = "",
        counters: Map<String, Long> = emptyMap(),
        error: Throwable? = null,
        stringAttributes: Map<String, String> = emptyMap(),
        spanId: String = W3cTraceContext.generateSpanId(),
        parentSpanId: String = "",
    ) {
        if (!enabled) return
        // Diagnostics must never cause a primary user operation to fail.
        try {
            require(category in CATEGORIES && outcome in OUTCOMES)
            val safeCounters = TelemetryAllowlist.filterNumeric(counters)
            val safeStrings = TelemetryAllowlist.filterStrings(stringAttributes)
            val safeCorrelation = correlation.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{0,64}")) }.orEmpty()
            val traceId = if (W3cTraceContext.isValidTraceId(safeCorrelation)) {
                safeCorrelation
            } else {
                W3cTraceContext.generateTraceId()
            }
            val now = System.currentTimeMillis()
            val errorKind = error?.let { DiagnosticErrorClassifier.classify(it) }
            val exceptionType = error?.let { DiagnosticErrorClassifier.sanitizeExceptionType(it) }
            val appFrames = error?.let { DiagnosticErrorClassifier.sanitizeAppFrames(it) }.orEmpty()

            val flightEvent = FlightEventRecord(
                timestampEpochMillis = now,
                traceId = traceId,
                spanId = spanId,
                parentSpanId = parentSpanId,
                category = category,
                outcome = outcome,
                durationMs = durationMs.coerceAtLeast(0),
                counters = safeCounters,
                stringAttributes = safeStrings,
                errorKind = errorKind,
                exceptionType = exceptionType,
                appFrames = appFrames,
                appVersion = BuildConfig.VERSION_NAME,
                commitSha = BuildConfig.COMMIT_SHA,
            )
            flightRecorder.recordEvent(flightEvent)
            if (category != "sync" && !(category in setOf("chat", "weekly_ai") && outcome == "started")) {
                otlpExporter.enqueueEvent(flightEvent)
            }

            dao?.let { activeDao ->
                activeDao.insertEvent(
                    AppEventEntity(
                        timestamp = now,
                        category = category,
                        outcome = outcome,
                        durationMs = durationMs.coerceAtLeast(0),
                        correlationId = safeCorrelation.ifEmpty { traceId },
                        attributesJson = buildJsonObject {
                            safeCounters.forEach { (k, v) -> put(k, v) }
                            safeStrings.forEach { (k, v) -> put(k, v) }
                            if (error != null) {
                                put("errorKind", errorKind ?: "internal")
                                put("exceptionType", exceptionType ?: "Exception")
                                put("appFrames", buildJsonArray {
                                    appFrames.forEach { add(JsonPrimitive(it)) }
                                })
                            }
                        }.toString(),
                        appVersion = BuildConfig.VERSION_NAME,
                        commitSha = BuildConfig.COMMIT_SHA,
                    ),
                )
                activeDao.pruneEvents(now - 14 * DAY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            /* telemetry must not break health operations */
        }
    }

    fun writeStageCheckpoint(checkpoint: ActiveStageCheckpoint) {
        if (!enabled) return
        flightRecorder.writeCheckpoint(checkpoint)
    }

    fun clearStageCheckpoint() {
        flightRecorder.clearCheckpoint()
    }

    /**
     * Records a completed or failed sync run to both the Room-independent flight recorder,
     * the Room app_events / metadata table (within a tracked `diagnostics.record` stage),
     * and the opt-in OTLP exporter. Never propagates telemetry failures.
     */
    suspend fun recordSyncRun(
        run: SyncRunDiagnostics,
        faultOnDiagnosticsRecord: (suspend () -> Unit)? = null,
    ): SyncRunDiagnostics {
        flightRecorder.clearCheckpoint()
        if (!enabled) return run

        val diagStageStart = System.currentTimeMillis()
        val diagSpanId = W3cTraceContext.generateSpanId()
        var diagStageError: Throwable? = null

        try {
            faultOnDiagnosticsRecord?.invoke()
            dao?.let { activeDao ->
                activeDao.setMetadata(AppMetadataEntity("lastSyncAttempt", run.startedAtEpochMillis.toString()))
                activeDao.setMetadata(AppMetadataEntity("lastSyncOutcome", run.outcome))
                activeDao.setMetadata(AppMetadataEntity("lastSyncTraceId", run.traceId))
                activeDao.setMetadata(AppMetadataEntity("lastSyncLastStage", run.lastSuccessfulStage.orEmpty()))
                activeDao.setMetadata(AppMetadataEntity("lastSyncFailedStage", run.failedStage.orEmpty()))
                activeDao.setMetadata(AppMetadataEntity("lastSyncErrorKind", run.errorKind.orEmpty()))
                if (run.outcome == "success") {
                    activeDao.setMetadata(AppMetadataEntity("lastSyncSuccess", run.endedAtEpochMillis.toString()))
                }
                val safeCounters = TelemetryAllowlist.filterNumeric(run.counters)
                activeDao.insertEvent(
                    AppEventEntity(
                        timestamp = run.endedAtEpochMillis,
                        category = "sync",
                        outcome = when (run.outcome) {
                            "success" -> "success"
                            "skipped_permission" -> "skipped_permission"
                            else -> "failure"
                        },
                        durationMs = run.durationMs.coerceAtLeast(0),
                        correlationId = run.traceId,
                        attributesJson = buildJsonObject {
                            safeCounters.forEach { (k, v) -> put(k, v) }
                            put("trigger", run.trigger)
                            run.lastSuccessfulStage?.let { put("lastSuccessfulStage", it) }
                            run.failedStage?.let { put("failedStage", it) }
                            if (run.errorKind != null) {
                                put("errorKind", run.errorKind)
                                put("exceptionType", run.exceptionType ?: "Exception")
                                put("appFrames", buildJsonArray {
                                    run.appFrames.take(8).forEach { add(JsonPrimitive(it)) }
                                })
                            }
                        }.toString(),
                        appVersion = run.appVersion,
                        commitSha = run.commitSha,
                    ),
                )
                activeDao.pruneEvents(run.endedAtEpochMillis - 14 * DAY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagStageError = e
        }

        val diagStageEnd = System.currentTimeMillis().coerceAtLeast(diagStageStart)
        val diagStage = StageSpanRecord(
            traceId = run.traceId,
            spanId = diagSpanId,
            parentSpanId = run.rootSpanId,
            name = "diagnostics.record",
            stage = "diagnostics.record",
            status = if (diagStageError == null) "ok" else "error",
            startEpochMillis = diagStageStart,
            endEpochMillis = diagStageEnd,
            durationMs = (diagStageEnd - diagStageStart).coerceAtLeast(0),
            errorKind = diagStageError?.let { DiagnosticErrorClassifier.classify(it) },
            exceptionType = diagStageError?.let { DiagnosticErrorClassifier.sanitizeExceptionType(it) },
            appFrames = diagStageError?.let { DiagnosticErrorClassifier.sanitizeAppFrames(it) }.orEmpty(),
        )

        val isFailure = run.outcome != "success"
        val enrichedCounters = if (isFailure) {
            TelemetryAllowlist.filterNumeric(TelemetryRuntimeContext.captureHeapCounters() + run.counters)
        } else {
            TelemetryAllowlist.filterNumeric(run.counters)
        }
        val crumbs = if (isFailure && run.breadcrumbs.isEmpty()) {
            flightRecorder.recentBreadcrumbs(maxItems = 10, upToEpochMillis = run.endedAtEpochMillis)
        } else {
            run.breadcrumbs
        }
        val finalRun = run.copy(
            counters = enrichedCounters,
            stages = run.stages + diagStage,
            threadKind = run.threadKind ?: TelemetryRuntimeContext.sanitizeThreadKind(Thread.currentThread().name),
            breadcrumbs = crumbs,
        )
        runCatching { flightRecorder.recordSyncRun(finalRun) }
        runCatching { otlpExporter.enqueueSyncRun(finalRun) }
        return finalRun
    }

    /**
     * Reconciles historical process exits / uncaught crash checkpoints on startup and enqueues
     * any discovered aborted sync runs and abnormal process exits (crash / ANR / OOM) to OTLP.
     */
    fun reconcileAndExportStartupExits(context: Context): ReconciledExitBatch {
        if (!enabled) return ReconciledExitBatch()
        return runCatching {
            val batch = flightRecorder.collectAndReconcileAndroidProcessExits(context)
            enqueueReconciledBatch(batch)
            batch
        }.getOrDefault(ReconciledExitBatch())
    }

    fun reconcileAndExportExits(exits: List<ProcessExitInput>): ReconciledExitBatch {
        if (!enabled) return ReconciledExitBatch()
        return runCatching {
            val batch = flightRecorder.reconcileProcessExits(exits)
            enqueueReconciledBatch(batch)
            batch
        }.getOrDefault(ReconciledExitBatch())
    }

    private fun enqueueReconciledBatch(batch: ReconciledExitBatch) {
        batch.abortedRun?.let { aborted ->
            runCatching { otlpExporter.enqueueSyncRun(aborted) }
        }
        batch.abnormalExits.forEach { exit ->
            val crumbs = flightRecorder.recentBreadcrumbs(maxItems = 10, upToEpochMillis = exit.timestampEpochMillis)
            runCatching { otlpExporter.enqueueProcessExit(exit, crumbs) }
        }
    }

    /**
     * Records a multi-stage operation trace (e.g. `chat.turn` or `weekly_ai.analyze`) to
     * LocalFlightRecorder, Room `app_events`, and OtlpTraceExporter with stage breakdown and breadcrumbs.
     */
    suspend fun recordOperationTrace(
        category: String,
        spanName: String,
        traceId: String,
        rootSpanId: String,
        startedAtMs: Long,
        endedAtMs: Long,
        outcome: String,
        stages: List<StageSpanRecord>,
        counters: Map<String, Long> = emptyMap(),
        stringAttributes: Map<String, String> = emptyMap(),
        failedStage: String? = null,
        error: Throwable? = null,
    ) {
        if (!enabled) return
        try {
            require(category in CATEGORIES && outcome in OUTCOMES)
            val statusOk = outcome == "success"
            val mergedCounters = if (!statusOk) {
                TelemetryAllowlist.filterNumeric(TelemetryRuntimeContext.captureHeapCounters() + counters)
            } else {
                TelemetryAllowlist.filterNumeric(counters)
            }
            val safeStrings = TelemetryAllowlist.filterStrings(
                buildMap {
                    putAll(stringAttributes)
                    put("thread.kind", TelemetryRuntimeContext.sanitizeThreadKind(Thread.currentThread().name))
                    failedStage?.let { put("error.stage", it) }
                },
            )
            val durationMs = (endedAtMs - startedAtMs).coerceAtLeast(0)
            val errorKind = error?.let { DiagnosticErrorClassifier.classify(it) }
            val exceptionType = error?.let { DiagnosticErrorClassifier.sanitizeExceptionType(it) }
            val appFrames = error?.let { DiagnosticErrorClassifier.sanitizeAppFrames(it) }.orEmpty()
            val crumbs = if (!statusOk) {
                flightRecorder.recentBreadcrumbs(maxItems = 10, upToEpochMillis = endedAtMs)
            } else {
                emptyList()
            }

            val flightEvent = FlightEventRecord(
                timestampEpochMillis = endedAtMs,
                traceId = traceId,
                spanId = rootSpanId,
                parentSpanId = "",
                category = category,
                outcome = outcome,
                durationMs = durationMs,
                counters = mergedCounters,
                stringAttributes = safeStrings,
                errorKind = errorKind,
                exceptionType = exceptionType,
                appFrames = appFrames,
                appVersion = BuildConfig.VERSION_NAME,
                commitSha = BuildConfig.COMMIT_SHA,
            )
            flightRecorder.recordEvent(flightEvent)
            otlpExporter.enqueueOperationTrace(
                spanName = spanName,
                traceId = traceId,
                rootSpanId = rootSpanId,
                startedAtMs = startedAtMs,
                endedAtMs = endedAtMs,
                statusOk = statusOk,
                stages = stages,
                counters = mergedCounters,
                stringAttributes = safeStrings,
                errorKind = errorKind,
                exceptionType = exceptionType,
                failedStage = failedStage,
                appFrames = appFrames,
                breadcrumbs = crumbs,
            )

            dao?.let { activeDao ->
                activeDao.insertEvent(
                    AppEventEntity(
                        timestamp = endedAtMs,
                        category = category,
                        outcome = outcome,
                        durationMs = durationMs,
                        correlationId = traceId,
                        attributesJson = buildJsonObject {
                            mergedCounters.forEach { (k, v) -> put(k, v) }
                            safeStrings.forEach { (k, v) -> put(k, v) }
                            if (error != null) {
                                put("errorKind", errorKind ?: "internal")
                                put("exceptionType", exceptionType ?: "Exception")
                                put("appFrames", buildJsonArray {
                                    appFrames.forEach { add(JsonPrimitive(it)) }
                                })
                            }
                        }.toString(),
                        appVersion = BuildConfig.VERSION_NAME,
                        commitSha = BuildConfig.COMMIT_SHA,
                    ),
                )
                activeDao.pruneEvents(endedAtMs - 14 * DAY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            /* telemetry must not break user operations */
        }
    }

    suspend fun exportReportJsonObject(
        availability: String = "UNKNOWN",
        grantedPermissions: List<String> = emptyList(),
        exportedAtMs: Long = System.currentTimeMillis(),
    ): JsonObject {
        val runs = flightRecorder.syncRuns(limit = 30)
        val exits = flightRecorder.processExits(limit = 20)
        val flightEvents = flightRecorder.events(limit = 100)
        val checkpoint = flightRecorder.readCheckpoint()
        val latestRun = runs.firstOrNull()
        val roomLastSyncSuccess = runCatching { dao?.metadata("lastSyncSuccess")?.toLongOrNull() }.getOrNull()
        val roomLastSyncOutcome = runCatching { dao?.metadata("lastSyncOutcome") }.getOrNull()
        val roomLastSyncTraceId = runCatching { dao?.metadata("lastSyncTraceId") }.getOrNull()

        return buildJsonObject {
            put("schemaVersion", 1)
            put("generatedAt", Instant.ofEpochMilli(exportedAtMs).toString())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("commitSha", BuildConfig.COMMIT_SHA)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("databaseSchemaVersion", 12)
            put("captureEnabled", enabled)
            put("detailedCaptureEnabled", detailed)
            put("otlpExportEnabled", otlpSettings.enabled)
            put("otlpEndpointConfigured", otlpSettings.endpointUrl().isNotBlank())
            put("gcpProjectId", otlpSettings.gcpProjectId())
            put("healthConnectAvailability", availability)
            put("grantedPermissions", buildJsonArray {
                grantedPermissions.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("lastSyncSummary", buildJsonObject {
                put("traceId", (latestRun?.traceId ?: roomLastSyncTraceId)?.let(::JsonPrimitive) ?: JsonNull)
                put("outcome", (latestRun?.outcome ?: roomLastSyncOutcome)?.let(::JsonPrimitive) ?: JsonNull)
                put("lastSuccessfulStage", latestRun?.lastSuccessfulStage?.let(::JsonPrimitive) ?: JsonNull)
                put("failedStage", latestRun?.failedStage?.let(::JsonPrimitive) ?: JsonNull)
                put("errorKind", latestRun?.errorKind?.let(::JsonPrimitive) ?: JsonNull)
                put("lastSyncSuccessEpochMillis", (latestRun?.takeIf { it.outcome == "success" }?.endedAtEpochMillis ?: roomLastSyncSuccess)?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("activeStageCheckpoint", checkpoint?.let { reportJson.encodeToJsonElement(it) } ?: JsonNull)
            put("syncRuns", reportJson.encodeToJsonElement(runs))
            put("recentProcessExits", reportJson.encodeToJsonElement(exits))
            put("recentEvents", reportJson.encodeToJsonElement(flightEvents))
            put("privacyGuarantee", "Operational metadata, stage timings, record counts, and allowlisted error codes only. No health measurements, chat messages, prompts, or credentials.")
        }
    }

    suspend fun exportReportJson(
        availability: String = "UNKNOWN",
        grantedPermissions: List<String> = emptyList(),
        exportedAtMs: Long = System.currentTimeMillis(),
    ): String = reportJson.encodeToString(
        JsonObject.serializer(),
        exportReportJsonObject(availability, grantedPermissions, exportedAtMs),
    )

    suspend fun capture(messageId: Long, payload: JsonObject) {
        if (!enabled || !detailed) return
        val activeDao = dao ?: return
        try {
            val text = payload.toString()
            if (text.toByteArray().size > 320_000) return
            activeDao.saveEvidence(ResponseEvidenceEntity(messageId, System.currentTimeMillis(), crypto.encrypt(text)))
            activeDao.pruneEvidence(System.currentTimeMillis() - 7 * DAY)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun evidence(messageId: Long): JsonElement? {
        val activeDao = dao ?: return null
        activeDao.pruneEvidence(System.currentTimeMillis() - 7 * DAY)
        val item = activeDao.evidence(messageId) ?: return null
        return runCatching { Json.parseToJsonElement(crypto.decrypt(item.encryptedPayload)) }.getOrNull()
    }

    suspend fun prune() {
        runCatching { flightRecorder.prune() }
        runCatching {
            dao?.pruneEvents(System.currentTimeMillis() - 14 * DAY)
            dao?.pruneEvidence(System.currentTimeMillis() - 7 * DAY)
            dao?.pruneReports(System.currentTimeMillis() - 90 * DAY)
        }
    }

    suspend fun clear() {
        runCatching { flightRecorder.clear() }
        runCatching { otlpExporter.clearBuffer() }
        runCatching {
            dao?.clearEvents()
            dao?.clearEvidence()
        }
    }

    companion object {
        const val DAY = 86_400_000L
        val CATEGORIES = setOf("sync", "report", "chat", "weekly_ai", "ui_sync", "ui_goal", "ui_tab", "tunnel", "mcp")
        val OUTCOMES = setOf("started", "success", "failure", "skipped_permission", "stopped", "expired", "locked", "network", "auth", "os_timeout", "task_removed", "deleted", "replaced", "permission_denied")
        val COUNTERS = TelemetryAllowlist.ALLOWED_NUMERIC_KEYS
        fun id(): String = W3cTraceContext.generateTraceId()
        fun traceId(): String = W3cTraceContext.generateTraceId()
        fun spanId(): String = W3cTraceContext.generateSpanId()
        fun defaultReportFileName(): String = "body-mon-diagnostics-${java.time.LocalDate.now()}.json"
    }
}
