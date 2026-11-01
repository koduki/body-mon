package com.master.healthcoach.data.diagnostics

import android.content.Context
import android.os.Build
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.security.EncryptedTextStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class OtlpConfig(
    val enabled: Boolean = false,
    val endpointUrl: String = "",
    val gcpProjectId: String = DEFAULT_GCP_PROJECT_ID,
    val bearerToken: String = "",
) {
    val active: Boolean
        get() = enabled && endpointUrl.isNotBlank()

    companion object {
        const val DEFAULT_GCP_PROJECT_ID = "health-support-ai"
    }
}

interface OtlpSettingsStore {
    var enabled: Boolean
    fun endpointUrl(): String
    fun gcpProjectId(): String
    fun hasToken(): Boolean
    fun load(): OtlpConfig
    fun save(
        enabled: Boolean,
        endpointUrl: String,
        gcpProjectId: String,
        bearerToken: String,
        allowLocalTestServer: Boolean = false,
    )
    fun clear()
}

class AndroidOtlpSettings(context: Context) : OtlpSettingsStore {
    private val prefs = context.getSharedPreferences("otlp_telemetry_settings", Context.MODE_PRIVATE)
    private val crypto = EncryptedTextStore("health_coach_otlp_key")

    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false) // Explicit opt-in, default OFF
        set(value) {
            prefs.edit().putBoolean("enabled", value).commit()
        }

    override fun endpointUrl(): String = prefs.getString("endpoint_url", "").orEmpty()

    override fun gcpProjectId(): String =
        prefs.getString("gcp_project_id", OtlpConfig.DEFAULT_GCP_PROJECT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: OtlpConfig.DEFAULT_GCP_PROJECT_ID

    override fun hasToken(): Boolean = prefs.contains("bearer_token")

    override fun load(): OtlpConfig {
        val token = prefs.getString("bearer_token", null)?.let {
            runCatching { crypto.decrypt(it) }.getOrNull()
        }.orEmpty()
        return OtlpConfig(
            enabled = enabled,
            endpointUrl = endpointUrl(),
            gcpProjectId = gcpProjectId(),
            bearerToken = token,
        )
    }

    override fun save(
        enabled: Boolean,
        endpointUrl: String,
        gcpProjectId: String,
        bearerToken: String,
        allowLocalTestServer: Boolean,
    ) {
        val trimmedEndpoint = endpointUrl.trim()
        val trimmedProject = gcpProjectId.trim().ifBlank { OtlpConfig.DEFAULT_GCP_PROJECT_ID }
        if (enabled || trimmedEndpoint.isNotEmpty()) {
            require(OtlpTraceExporter.isValidEndpoint(trimmedEndpoint, allowLocalTestServer)) {
                "OTLPエンドポイントURLは https:// で始まる必要があります"
            }
        }
        require(OtlpTraceExporter.PROJECT_ID_REGEX.matches(trimmedProject)) {
            "GCPプロジェクトIDの形式を確認してください"
        }
        val editor = prefs.edit()
            .putBoolean("enabled", enabled)
            .putString("endpoint_url", trimmedEndpoint)
            .putString("gcp_project_id", trimmedProject)
        if (bearerToken.isNotBlank()) {
            val trimmedToken = bearerToken.trim()
            require(trimmedToken.length <= 4096 && !trimmedToken.any { it == '\r' || it == '\n' }) {
                "トークンの形式を確認してください"
            }
            editor.putString("bearer_token", crypto.encrypt(trimmedToken))
        }
        editor.commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}

class InMemoryOtlpSettings(
    initial: OtlpConfig = OtlpConfig(),
    private val allowLocalTestServer: Boolean = true,
) : OtlpSettingsStore {
    private var current = initial

    override var enabled: Boolean
        get() = current.enabled
        set(value) {
            current = current.copy(enabled = value)
        }

    override fun endpointUrl(): String = current.endpointUrl
    override fun gcpProjectId(): String = current.gcpProjectId
    override fun hasToken(): Boolean = current.bearerToken.isNotBlank()
    override fun load(): OtlpConfig = current

    override fun save(
        enabled: Boolean,
        endpointUrl: String,
        gcpProjectId: String,
        bearerToken: String,
        allowLocalTestServer: Boolean,
    ) {
        val trimmedEndpoint = endpointUrl.trim()
        val trimmedProject = gcpProjectId.trim().ifBlank { OtlpConfig.DEFAULT_GCP_PROJECT_ID }
        if (enabled || trimmedEndpoint.isNotEmpty()) {
            require(OtlpTraceExporter.isValidEndpoint(trimmedEndpoint, allowLocalTestServer || this.allowLocalTestServer))
        }
        require(OtlpTraceExporter.PROJECT_ID_REGEX.matches(trimmedProject))
        val nextToken = if (bearerToken.isNotBlank()) bearerToken.trim() else current.bearerToken
        current = OtlpConfig(
            enabled = enabled,
            endpointUrl = trimmedEndpoint,
            gcpProjectId = trimmedProject,
            bearerToken = nextToken,
        )
    }

    override fun clear() {
        current = OtlpConfig()
    }
}

/**
 * Bounded, opt-in OTLP/HTTP JSON trace exporter targeting GCP (Cloud Run OTel Collector or telemetry.googleapis.com).
 * Never blocks or fails primary app operations.
 */
class OtlpTraceExporter(
    val settings: OtlpSettingsStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val allowLocalTestServer: Boolean = false,
    private val asyncDispatch: Boolean = true,
    private val persistenceDir: File? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<QueuedExport>()
    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingFile: File? get() = persistenceDir?.let { File(it, "otlp_pending.jsonl") }

    @Serializable
    data class QueuedExport(
        val traceId: String,
        val rootSpanId: String,
        val payloadJson: String,
    )

    init {
        loadPersistedQueue()
    }

    @Synchronized
    private fun loadPersistedQueue() {
        runCatching {
            val file = pendingFile ?: return
            if (!file.exists()) return
            val loaded = file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) null else runCatching { queueJson.decodeFromString<QueuedExport>(trimmed) }.getOrNull()
            }.takeLast(MAX_BUFFERED_PAYLOADS)
            queue.clear()
            queue.addAll(loaded)
        }
    }

    @Synchronized
    private fun persistQueueLocked() {
        runCatching {
            val file = pendingFile ?: return
            if (queue.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val dir = file.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            val lines = queue.mapNotNull { item ->
                val encoded = queueJson.encodeToString(item)
                if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES + 1024) encoded else null
            }
            file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    fun enqueueSyncRun(run: SyncRunDiagnostics) {
        try {
            val config = settings.load()
            if (!config.active) return
            val payload = buildSyncRunOtlpJson(run, config.gcpProjectId).toString()
            enqueuePayload(
                QueuedExport(
                    traceId = run.traceId,
                    rootSpanId = run.rootSpanId,
                    payloadJson = payload,
                ),
            )
        } catch (_: Throwable) {
            // Never propagate telemetry export errors to callers.
        }
    }

    fun enqueueProcessExit(
        exit: ProcessExitRecord,
        breadcrumbs: List<BreadcrumbRecord> = emptyList(),
    ) {
        try {
            val config = settings.load()
            if (!config.active) return
            val traceId = exit.correlatedTraceId?.takeIf { W3cTraceContext.isValidTraceId(it) } ?: W3cTraceContext.generateTraceId()
            val spanId = W3cTraceContext.generateSpanId()
            val payload = buildProcessExitOtlpJson(
                exit = exit,
                traceId = traceId,
                spanId = spanId,
                breadcrumbs = breadcrumbs,
                gcpProjectId = config.gcpProjectId,
            ).toString()
            enqueuePayload(
                QueuedExport(
                    traceId = traceId,
                    rootSpanId = spanId,
                    payloadJson = payload,
                ),
            )
        } catch (_: Throwable) {
            // Never propagate telemetry export errors to callers.
        }
    }

    fun enqueueOperationTrace(
        spanName: String,
        traceId: String,
        rootSpanId: String,
        startedAtMs: Long,
        endedAtMs: Long,
        statusOk: Boolean,
        stages: List<StageSpanRecord>,
        counters: Map<String, Long> = emptyMap(),
        stringAttributes: Map<String, String> = emptyMap(),
        errorKind: String? = null,
        exceptionType: String? = null,
        failedStage: String? = null,
        appFrames: List<String> = emptyList(),
        breadcrumbs: List<BreadcrumbRecord> = emptyList(),
    ) {
        try {
            val config = settings.load()
            if (!config.active) return
            val safeTraceId = if (W3cTraceContext.isValidTraceId(traceId)) traceId else W3cTraceContext.generateTraceId()
            val safeRootSpanId = if (W3cTraceContext.isValidSpanId(rootSpanId)) rootSpanId else W3cTraceContext.generateSpanId()
            val rootAttrs = buildAttributeArray(
                numeric = TelemetryAllowlist.filterNumeric(counters),
                strings = buildMap {
                    putAll(TelemetryAllowlist.filterStrings(stringAttributes))
                    failedStage?.let { put("error.stage", it) }
                    errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }?.let { put("error.kind", it) }
                    exceptionType?.let { put("error.type", it) }
                },
                booleans = emptyMap(),
            )
            val rootSpan = buildSpanObject(
                traceId = safeTraceId,
                spanId = safeRootSpanId,
                parentSpanId = "",
                name = spanName,
                startEpochMillis = startedAtMs,
                endEpochMillis = endedAtMs.coerceAtLeast(startedAtMs),
                statusOk = statusOk,
                attributes = rootAttrs,
                errorKind = errorKind,
                exceptionType = exceptionType,
                failedStage = failedStage,
                appFrames = appFrames,
                breadcrumbs = if (!statusOk) breadcrumbs else emptyList(),
            )
            val childSpans = stages.map { stage ->
                val stageSpanId = if (W3cTraceContext.isValidSpanId(stage.spanId)) stage.spanId else W3cTraceContext.generateSpanId()
                val parentId = if (W3cTraceContext.isValidSpanId(stage.parentSpanId)) stage.parentSpanId else safeRootSpanId
                val stageAttrs = buildAttributeArray(
                    numeric = TelemetryAllowlist.filterNumeric(stage.attributes),
                    strings = buildMap {
                        putAll(TelemetryAllowlist.filterStrings(stage.stringAttributes))
                        if (stage.stage in TelemetryAllowlist.SYNC_STAGES) {
                            put("sync.stage", stage.stage)
                        }
                        stage.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }?.let { put("error.kind", it) }
                        stage.exceptionType?.let { put("error.type", it) }
                    },
                    booleans = TelemetryAllowlist.filterBooleans(stage.booleanAttributes),
                )
                buildSpanObject(
                    traceId = safeTraceId,
                    spanId = stageSpanId,
                    parentSpanId = parentId,
                    name = stage.name,
                    startEpochMillis = stage.startEpochMillis,
                    endEpochMillis = stage.endEpochMillis.coerceAtLeast(stage.startEpochMillis),
                    statusOk = stage.status != "error",
                    attributes = stageAttrs,
                    errorKind = stage.errorKind,
                    exceptionType = stage.exceptionType,
                    failedStage = if (stage.status == "error") stage.stage else null,
                    appFrames = stage.appFrames,
                )
            }
            val payload = wrapResourceSpans(
                gcpProjectId = config.gcpProjectId,
                appVersion = BuildConfig.VERSION_NAME,
                commitSha = BuildConfig.COMMIT_SHA,
                schemaVersion = 12,
                spans = listOf(rootSpan) + childSpans,
            ).toString()
            enqueuePayload(
                QueuedExport(
                    traceId = safeTraceId,
                    rootSpanId = safeRootSpanId,
                    payloadJson = payload,
                ),
            )
        } catch (_: Throwable) {
            // Never propagate telemetry export errors to callers.
        }
    }

    fun enqueueEvent(event: FlightEventRecord) {
        try {
            val config = settings.load()
            if (!config.active) return
            val payload = buildEventOtlpJson(event, config.gcpProjectId).toString()
            enqueuePayload(
                QueuedExport(
                    traceId = event.traceId,
                    rootSpanId = event.spanId,
                    payloadJson = payload,
                ),
            )
        } catch (_: Throwable) {
            // Never propagate telemetry export errors to callers.
        }
    }

    @Synchronized
    fun bufferedCount(): Int = queue.size

    @Synchronized
    fun clearBuffer() {
        queue.clear()
        persistQueueLocked()
    }

    private fun enqueuePayload(item: QueuedExport) {
        if (item.payloadJson.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return
        synchronized(this) {
            while (queue.size >= MAX_BUFFERED_PAYLOADS) {
                queue.removeFirst()
            }
            queue.addLast(item)
            persistQueueLocked()
        }
        if (asyncDispatch) {
            scope.launch { flushPending() }
        } else {
            flushPending()
        }
    }

    fun flushPending() {
        try {
            val config = settings.load()
            if (!config.active) {
                clearBuffer()
                return
            }
            if (!isValidEndpoint(config.endpointUrl, allowLocalTestServer)) {
                return
            }
            var processed = 0
            while (processed < MAX_BUFFERED_PAYLOADS) {
                val next = synchronized(this) {
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                processed++
                val sent = sendOnce(config, next)
                if (!sent) {
                    // Re-queue at front if capacity allows, then stop this flush cycle.
                    synchronized(this) {
                        if (queue.size < MAX_BUFFERED_PAYLOADS && settings.enabled) {
                            queue.addFirst(next)
                        }
                        persistQueueLocked()
                    }
                    break
                } else {
                    synchronized(this) {
                        persistQueueLocked()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Best-effort telemetry export; swallow all runtime/network failures.
        }
    }

    private fun sendOnce(config: OtlpConfig, item: QueuedExport): Boolean = try {
        val traceparent = W3cTraceContext.formatTraceparent(item.traceId, item.rootSpanId, sampled = true)
        val builder = Request.Builder()
            .url(config.endpointUrl)
            .header("Content-Type", "application/json")
            .header("traceparent", traceparent)
            .header("X-Goog-User-Project", config.gcpProjectId)
            .post(item.payloadJson.toRequestBody(JSON_MEDIA_TYPE))
        if (config.bearerToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.bearerToken}")
        }
        client.newCall(builder.build()).execute().use { response ->
            response.code in 200..299
        }
    } catch (_: Throwable) {
        false
    }

    fun buildSyncRunOtlpJson(
        run: SyncRunDiagnostics,
        gcpProjectId: String = OtlpConfig.DEFAULT_GCP_PROJECT_ID,
    ): JsonObject {
        val traceId = if (W3cTraceContext.isValidTraceId(run.traceId)) run.traceId else W3cTraceContext.generateTraceId()
        val rootSpanId = if (W3cTraceContext.isValidSpanId(run.rootSpanId)) run.rootSpanId else W3cTraceContext.generateSpanId()
        val rootAttrs = buildAttributeArray(
            numeric = TelemetryAllowlist.filterNumeric(run.counters),
            strings = buildMap {
                put("sync.trigger", run.trigger)
                put("sync.outcome", run.outcome)
                run.lastSuccessfulStage?.let { put("sync.stage", it) }
                run.failedStage?.let { put("error.stage", it) }
                run.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }?.let { put("error.kind", it) }
                run.exceptionType?.let { put("error.type", it) }
                run.threadKind?.let { put("thread.kind", it) }
            },
            booleans = emptyMap(),
        )
        val rootSpan = buildSpanObject(
            traceId = traceId,
            spanId = rootSpanId,
            parentSpanId = "",
            name = "sync.run",
            startEpochMillis = run.startedAtEpochMillis,
            endEpochMillis = run.endedAtEpochMillis.coerceAtLeast(run.startedAtEpochMillis),
            statusOk = run.outcome == "success",
            attributes = rootAttrs,
            errorKind = run.errorKind,
            exceptionType = run.exceptionType,
            failedStage = run.failedStage,
            appFrames = run.appFrames,
            breadcrumbs = if (run.outcome != "success") run.breadcrumbs else emptyList(),
        )
        val childSpans = run.stages.map { stage ->
            val stageSpanId = if (W3cTraceContext.isValidSpanId(stage.spanId)) stage.spanId else W3cTraceContext.generateSpanId()
            val parentId = if (W3cTraceContext.isValidSpanId(stage.parentSpanId)) stage.parentSpanId else rootSpanId
            val stageAttrs = buildAttributeArray(
                numeric = TelemetryAllowlist.filterNumeric(stage.attributes),
                strings = buildMap {
                    putAll(TelemetryAllowlist.filterStrings(stage.stringAttributes))
                    if (stage.stage in TelemetryAllowlist.SYNC_STAGES) {
                        put("sync.stage", stage.stage)
                    }
                    stage.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }?.let { put("error.kind", it) }
                    stage.exceptionType?.let { put("error.type", it) }
                },
                booleans = TelemetryAllowlist.filterBooleans(stage.booleanAttributes),
            )
            buildSpanObject(
                traceId = traceId,
                spanId = stageSpanId,
                parentSpanId = parentId,
                name = stage.name,
                startEpochMillis = stage.startEpochMillis,
                endEpochMillis = stage.endEpochMillis.coerceAtLeast(stage.startEpochMillis),
                statusOk = stage.status != "error",
                attributes = stageAttrs,
                errorKind = stage.errorKind,
                exceptionType = stage.exceptionType,
                failedStage = if (stage.status == "error") stage.stage else null,
                appFrames = stage.appFrames,
            )
        }
        return wrapResourceSpans(
            gcpProjectId = gcpProjectId,
            appVersion = run.appVersion,
            commitSha = run.commitSha,
            schemaVersion = run.databaseSchemaVersion,
            spans = listOf(rootSpan) + childSpans,
        )
    }

    fun buildProcessExitOtlpJson(
        exit: ProcessExitRecord,
        traceId: String = exit.correlatedTraceId?.takeIf { W3cTraceContext.isValidTraceId(it) } ?: W3cTraceContext.generateTraceId(),
        spanId: String = W3cTraceContext.generateSpanId(),
        breadcrumbs: List<BreadcrumbRecord> = emptyList(),
        gcpProjectId: String = OtlpConfig.DEFAULT_GCP_PROJECT_ID,
    ): JsonObject {
        val numericAttrs = buildMap {
            putAll(exit.memoryCounters)
            if (exit.pssKb > 0) put("memory.pss_kb", exit.pssKb)
            if (exit.rssKb > 0) put("memory.rss_kb", exit.rssKb)
            if (exit.importance > 0) put("process.importance", exit.importance.toLong())
            put("exit.status_code", exit.status.toLong())
            put("exit.reason_code", exit.reasonCode.toLong())
        }
        val resolvedErrorKind = exit.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }
            ?: "process_exit_${exit.reasonKind}".takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }
            ?: "process_exit_other"
        val stringAttrs = buildMap {
            put("exit.reason", exit.reasonKind)
            put("error.kind", resolvedErrorKind)
            exit.exceptionType?.let { put("error.type", it) }
            exit.correlatedStage?.let {
                put("sync.stage", it)
                put("error.stage", it)
            }
            exit.threadKind?.let { put("thread.kind", it) }
        }
        val attrs = buildAttributeArray(
            numeric = TelemetryAllowlist.filterNumeric(numericAttrs),
            strings = TelemetryAllowlist.filterStrings(stringAttrs),
            booleans = emptyMap(),
        )
        val spanName = if (exit.reasonKind in setOf("crash", "crash_native")) "app.crash" else "process.exit"
        val span = buildSpanObject(
            traceId = traceId,
            spanId = spanId,
            parentSpanId = "",
            name = spanName,
            startEpochMillis = exit.timestampEpochMillis,
            endEpochMillis = exit.timestampEpochMillis,
            statusOk = false,
            attributes = attrs,
            errorKind = resolvedErrorKind,
            exceptionType = exit.exceptionType ?: "ProcessExit:${exit.reasonKind}",
            failedStage = exit.correlatedStage ?: "app.runtime",
            appFrames = exit.appFrames,
            breadcrumbs = breadcrumbs,
        )
        return wrapResourceSpans(
            gcpProjectId = gcpProjectId,
            appVersion = exit.appVersion,
            commitSha = exit.commitSha,
            schemaVersion = 12,
            spans = listOf(span),
        )
    }

    fun buildEventOtlpJson(
        event: FlightEventRecord,
        gcpProjectId: String = OtlpConfig.DEFAULT_GCP_PROJECT_ID,
    ): JsonObject {
        val traceId = if (W3cTraceContext.isValidTraceId(event.traceId)) event.traceId else W3cTraceContext.generateTraceId()
        val spanId = if (W3cTraceContext.isValidSpanId(event.spanId)) event.spanId else W3cTraceContext.generateSpanId()
        val parentSpanId = if (W3cTraceContext.isValidSpanId(event.parentSpanId)) event.parentSpanId else ""
        val endMs = event.timestampEpochMillis
        val startMs = (endMs - event.durationMs.coerceAtLeast(0)).coerceAtLeast(0)
        val attrs = buildAttributeArray(
            numeric = TelemetryAllowlist.filterNumeric(event.counters),
            strings = buildMap {
                putAll(TelemetryAllowlist.filterStrings(event.stringAttributes))
                put("sync.outcome", event.outcome)
                event.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }?.let { put("error.kind", it) }
                event.exceptionType?.let { put("error.type", it) }
            },
            booleans = emptyMap(),
        )
        val span = buildSpanObject(
            traceId = traceId,
            spanId = spanId,
            parentSpanId = parentSpanId,
            name = "app.${event.category}",
            startEpochMillis = startMs,
            endEpochMillis = endMs,
            statusOk = event.outcome !in setOf("failure", "auth", "permission_denied"),
            attributes = attrs,
            errorKind = event.errorKind,
            exceptionType = event.exceptionType,
            failedStage = null,
            appFrames = event.appFrames,
        )
        return wrapResourceSpans(
            gcpProjectId = gcpProjectId,
            appVersion = event.appVersion,
            commitSha = event.commitSha,
            schemaVersion = 12,
            spans = listOf(span),
        )
    }

    private fun wrapResourceSpans(
        gcpProjectId: String,
        appVersion: String,
        commitSha: String,
        schemaVersion: Int,
        spans: List<JsonObject>,
    ): JsonObject = buildJsonObject {
        put("resourceSpans", buildJsonArray {
            add(buildJsonObject {
                put("resource", buildJsonObject {
                    put("attributes", buildJsonArray {
                        add(stringAttr("service.name", "body-mon-android"))
                        add(stringAttr("service.version", appVersion))
                        add(stringAttr("git.commit.sha", commitSha))
                        add(intAttr("db.schema_version", schemaVersion.toLong()))
                        add(intAttr("os.sdk_int", Build.VERSION.SDK_INT.toLong()))
                        add(stringAttr("app.session_id", TelemetryRuntimeContext.sessionId))
                        add(stringAttr("gcp.project_id", gcpProjectId))
                    })
                })
                put("scopeSpans", buildJsonArray {
                    add(buildJsonObject {
                        put("scope", buildJsonObject {
                            put("name", "com.master.healthcoach.diagnostics")
                            put("version", "1.0.0")
                        })
                        put("spans", JsonArray(spans))
                    })
                })
            })
        })
    }

    private fun buildSpanObject(
        traceId: String,
        spanId: String,
        parentSpanId: String,
        name: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        statusOk: Boolean,
        attributes: JsonArray,
        errorKind: String?,
        exceptionType: String?,
        failedStage: String?,
        appFrames: List<String>,
        breadcrumbs: List<BreadcrumbRecord> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("traceId", traceId)
        put("spanId", spanId)
        if (parentSpanId.isNotEmpty()) {
            put("parentSpanId", parentSpanId)
        }
        put("name", name)
        put("kind", 1) // SPAN_KIND_INTERNAL
        put("startTimeUnixNano", (startEpochMillis * 1_000_000L).toString())
        put("endTimeUnixNano", (endEpochMillis * 1_000_000L).toString())
        put("attributes", attributes)
        if (!statusOk || errorKind != null || breadcrumbs.isNotEmpty()) {
            put("events", buildJsonArray {
                breadcrumbs.takeLast(10).forEach { crumb ->
                    add(buildJsonObject {
                        put("timeUnixNano", (crumb.timestampEpochMillis * 1_000_000L).toString())
                        put("name", "breadcrumb")
                        put("attributes", buildJsonArray {
                            add(stringAttr("breadcrumb.category", crumb.category))
                            add(stringAttr("breadcrumb.outcome", crumb.outcome))
                            TelemetryAllowlist.filterNumeric(crumb.counters).forEach { (k, v) ->
                                add(intAttr(k, v))
                            }
                            TelemetryAllowlist.filterStrings(crumb.stringAttributes).forEach { (k, v) ->
                                add(stringAttr(k, v))
                            }
                        })
                    })
                }
                if (!statusOk || errorKind != null) {
                    add(buildJsonObject {
                        put("timeUnixNano", (endEpochMillis * 1_000_000L).toString())
                        put("name", "exception")
                        put("attributes", buildJsonArray {
                            add(stringAttr("exception.type", exceptionType ?: "Exception"))
                            errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }?.let {
                                add(stringAttr("error.kind", it))
                            }
                            failedStage?.let {
                                add(stringAttr("error.stage", it))
                            }
                            if (appFrames.isNotEmpty()) {
                                add(stringAttr("error.app_frames", appFrames.take(8).joinToString(";")))
                            }
                        })
                    })
                }
            })
        }
        put("status", buildJsonObject {
            put("code", if (statusOk) 1 else 2) // 1 = STATUS_CODE_OK, 2 = STATUS_CODE_ERROR
            if (!statusOk && errorKind != null) {
                put("message", errorKind)
            }
        })
    }

    private fun buildAttributeArray(
        numeric: Map<String, Long>,
        strings: Map<String, String>,
        booleans: Map<String, Boolean>,
    ): JsonArray = buildJsonArray {
        TelemetryAllowlist.filterNumeric(numeric).forEach { (k, v) -> add(intAttr(k, v)) }
        TelemetryAllowlist.filterStrings(strings).forEach { (k, v) -> add(stringAttr(k, v)) }
        TelemetryAllowlist.filterBooleans(booleans).forEach { (k, v) -> add(boolAttr(k, v)) }
    }

    private fun stringAttr(key: String, value: String): JsonObject = buildJsonObject {
        put("key", key)
        put("value", buildJsonObject { put("stringValue", value) })
    }

    private fun intAttr(key: String, value: Long): JsonObject = buildJsonObject {
        put("key", key)
        put("value", buildJsonObject { put("intValue", value.toString()) })
    }

    private fun boolAttr(key: String, value: Boolean): JsonObject = buildJsonObject {
        put("key", key)
        put("value", buildJsonObject { put("boolValue", value) })
    }

    companion object {
        const val MAX_BUFFERED_PAYLOADS = 32
        const val MAX_PAYLOAD_BYTES = 128 * 1024
        val PROJECT_ID_REGEX = Regex("^[a-z][a-z0-9-]{4,29}$")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun isValidEndpoint(url: String, allowLocalTestServer: Boolean = false): Boolean {
            if (url.length > 512 || url.any { it.isWhitespace() }) return false
            if (url.startsWith("https://")) return true
            if (allowLocalTestServer && url.startsWith("http://")) {
                return true
            }
            return false
        }
    }
}
