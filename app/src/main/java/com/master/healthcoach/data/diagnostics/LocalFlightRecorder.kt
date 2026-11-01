package com.master.healthcoach.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.master.healthcoach.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class ProcessExitInput(
    val timestampEpochMillis: Long,
    val reasonCode: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long = 0,
    val rssKb: Long = 0,
)

data class ReconciledExitBatch(
    val abortedRun: SyncRunDiagnostics? = null,
    val abnormalExits: List<ProcessExitRecord> = emptyList(),
)

/**
 * Room-independent circular local diagnostic flight recorder.
 * Survives Room migration failures, SQLite corruption, OTLP outages, and MCP disconnects.
 * Stores operational metadata only (never health values, chat messages, or credentials).
 */
class LocalFlightRecorder(
    val directory: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context, clockMs: () -> Long = { System.currentTimeMillis() }) : this(
        File(context.filesDir, "diagnostics"),
        clockMs,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val lock = Any()
    private val syncRunsFile get() = File(directory, "sync_runs.jsonl")
    private val eventsFile get() = File(directory, "events.jsonl")
    private val exitsFile get() = File(directory, "process_exits.jsonl")
    private val checkpointFile get() = File(directory, "active_stage.json")
    private val stateFile get() = File(directory, "recorder_state.json")

    private var windowMinute: Long = 0L
    private var writesInWindow: Int = 0

    @Synchronized
    fun writeCheckpoint(checkpoint: ActiveStageCheckpoint) {
        runCatching {
            ensureDir()
            val text = json.encodeToString(checkpoint)
            if (text.toByteArray().size <= MAX_LINE_BYTES) {
                checkpointFile.writeText(text, Charsets.UTF_8)
            }
        }
    }

    @Synchronized
    fun clearCheckpoint() {
        runCatching {
            if (checkpointFile.exists()) checkpointFile.delete()
        }
    }

    @Synchronized
    fun readCheckpoint(): ActiveStageCheckpoint? = runCatching {
        if (!checkpointFile.exists()) return null
        json.decodeFromString<ActiveStageCheckpoint>(checkpointFile.readText(Charsets.UTF_8))
    }.getOrNull()

    @Synchronized
    fun recordSyncRun(run: SyncRunDiagnostics) {
        runCatching {
            ensureDir()
            val sanitizedStages = run.stages.map { stage ->
                stage.copy(
                    attributes = TelemetryAllowlist.filterNumeric(stage.attributes),
                    stringAttributes = TelemetryAllowlist.filterStrings(stage.stringAttributes),
                    booleanAttributes = TelemetryAllowlist.filterBooleans(stage.booleanAttributes),
                    errorKind = stage.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS },
                    appFrames = stage.appFrames.take(8),
                )
            }
            val sanitized = run.copy(
                counters = TelemetryAllowlist.filterNumeric(run.counters),
                errorKind = run.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS },
                appFrames = run.appFrames.take(8),
                stages = sanitizedStages,
            )
            val existing = readJsonLines<SyncRunDiagnostics>(syncRunsFile)
                .filterNot { it.traceId == sanitized.traceId }
            val cutoff = clockMs() - RETENTION_MS
            val updated = (existing + sanitized)
                .filter { it.startedAtEpochMillis >= cutoff }
                .sortedBy { it.startedAtEpochMillis }
                .takeLast(MAX_SYNC_RUNS)
            writeJsonLinesBounded(syncRunsFile, updated)
        }
    }

    @Synchronized
    fun recordEvent(event: FlightEventRecord) {
        runCatching {
            val now = clockMs()
            val minute = now / 60_000L
            if (minute != windowMinute) {
                windowMinute = minute
                writesInWindow = 0
            }
            if (++writesInWindow > MAX_EVENTS_PER_MINUTE) return
            ensureDir()
            val sanitized = event.copy(
                counters = TelemetryAllowlist.filterNumeric(event.counters),
                stringAttributes = TelemetryAllowlist.filterStrings(event.stringAttributes),
                errorKind = event.errorKind?.takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS },
                appFrames = event.appFrames.take(8),
            )
            val cutoff = now - RETENTION_MS
            val existing = readJsonLines<FlightEventRecord>(eventsFile)
            val updated = (existing + sanitized)
                .filter { it.timestampEpochMillis >= cutoff }
                .takeLast(MAX_EVENTS)
            writeJsonLinesBounded(eventsFile, updated)
        }
    }

    @Synchronized
    fun recordMigrationFailure(
        fromVersion: Int,
        toVersion: Int,
        error: Throwable,
        appVersion: String = BuildConfig.VERSION_NAME,
        commitSha: String = BuildConfig.COMMIT_SHA,
    ): SyncRunDiagnostics {
        val now = clockMs()
        val traceId = W3cTraceContext.generateTraceId()
        val rootSpanId = W3cTraceContext.generateSpanId()
        val stageSpanId = W3cTraceContext.generateSpanId()
        val frames = DiagnosticErrorClassifier.sanitizeAppFrames(error)
        val exceptionType = DiagnosticErrorClassifier.sanitizeExceptionType(error)
        val stage = StageSpanRecord(
            traceId = traceId,
            spanId = stageSpanId,
            parentSpanId = rootSpanId,
            name = "room.migration",
            stage = "room.migration",
            status = "error",
            startEpochMillis = now,
            endEpochMillis = now,
            durationMs = 0,
            attributes = mapOf(
                "db.from_version" to fromVersion.toLong(),
                "db.to_version" to toVersion.toLong(),
            ),
            errorKind = "migration",
            exceptionType = exceptionType,
            appFrames = frames,
        )
        val run = SyncRunDiagnostics(
            runId = traceId,
            traceId = traceId,
            rootSpanId = rootSpanId,
            trigger = "db_open",
            startedAtEpochMillis = now,
            endedAtEpochMillis = now,
            durationMs = 0,
            outcome = "failure",
            lastSuccessfulStage = null,
            failedStage = "room.migration",
            errorKind = "migration",
            exceptionType = exceptionType,
            appFrames = frames,
            counters = mapOf(
                "db.from_version" to fromVersion.toLong(),
                "db.to_version" to toVersion.toLong(),
            ),
            stages = listOf(stage),
            appVersion = appVersion,
            commitSha = commitSha,
            databaseSchemaVersion = toVersion,
        )
        recordSyncRun(run)
        return run
    }

    @Synchronized
    fun recordUncaughtException(
        error: Throwable,
        threadName: String? = Thread.currentThread().name,
        appVersion: String = BuildConfig.VERSION_NAME,
        commitSha: String = BuildConfig.COMMIT_SHA,
    ) {
        runCatching {
            ensureDir()
            val errorKind = DiagnosticErrorClassifier.classify(error)
            val exceptionType = DiagnosticErrorClassifier.sanitizeExceptionType(error)
            val frames = DiagnosticErrorClassifier.sanitizeAppFrames(error)
            val threadKind = TelemetryRuntimeContext.sanitizeThreadKind(threadName)
            val heapCounters = TelemetryRuntimeContext.captureHeapCounters()
            val current = readCheckpoint()
            if (current != null) {
                writeCheckpoint(
                    current.copy(
                        uncaughtExceptionType = exceptionType,
                        uncaughtErrorKind = errorKind,
                        uncaughtAppFrames = frames,
                        uncaughtThreadKind = threadKind,
                        memoryCounters = heapCounters,
                    ),
                )
            } else {
                writeCheckpoint(
                    ActiveStageCheckpoint(
                        traceId = W3cTraceContext.generateTraceId(),
                        rootSpanId = W3cTraceContext.generateSpanId(),
                        trigger = "uncaught_exception",
                        activeStage = "app.runtime",
                        lastSuccessfulStage = null,
                        startedAtEpochMillis = clockMs(),
                        stageStartedAtEpochMillis = clockMs(),
                        appVersion = appVersion,
                        commitSha = commitSha,
                        uncaughtExceptionType = exceptionType,
                        uncaughtErrorKind = errorKind,
                        uncaughtAppFrames = frames,
                        uncaughtThreadKind = threadKind,
                        memoryCounters = heapCounters,
                    ),
                )
            }
        }
    }

    @Synchronized
    fun reconcileProcessExits(
        exits: List<ProcessExitInput>,
        appVersion: String = BuildConfig.VERSION_NAME,
        commitSha: String = BuildConfig.COMMIT_SHA,
    ): ReconciledExitBatch = runCatching {
        ensureDir()
        val now = clockMs()
        val cutoff = now - RETENTION_MS
        val existingExits = readJsonLines<ProcessExitRecord>(exitsFile)
            .filter { it.timestampEpochMillis >= cutoff }
        val knownTimestamps = existingExits.map { it.timestampEpochMillis }.toSet()
        val checkpoint = readCheckpoint()
        val newExitRecords = exits
            .filter { it.timestampEpochMillis !in knownTimestamps && it.timestampEpochMillis >= cutoff }
            .sortedBy { it.timestampEpochMillis }
            .map { input ->
                val reasonKind = mapExitReason(input.reasonCode)
                val correlates = checkpoint != null && input.timestampEpochMillis >= checkpoint.startedAtEpochMillis - 5_000L
                val exitErrorKind = if (correlates && checkpoint?.uncaughtErrorKind != null && checkpoint.uncaughtErrorKind != "internal") {
                    checkpoint.uncaughtErrorKind
                } else {
                    "process_exit_$reasonKind".takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS } ?: "process_exit_other"
                }
                ProcessExitRecord(
                    timestampEpochMillis = input.timestampEpochMillis,
                    reasonCode = input.reasonCode,
                    reasonKind = reasonKind,
                    status = input.status,
                    importance = input.importance,
                    pssKb = input.pssKb,
                    rssKb = input.rssKb,
                    correlatedTraceId = if (correlates) checkpoint?.traceId else W3cTraceContext.generateTraceId(),
                    correlatedStage = if (correlates) checkpoint?.activeStage else null,
                    appVersion = appVersion,
                    commitSha = commitSha,
                    errorKind = exitErrorKind,
                    exceptionType = if (correlates) checkpoint?.uncaughtExceptionType else "ProcessExit:$reasonKind",
                    appFrames = if (correlates) checkpoint?.uncaughtAppFrames.orEmpty() else emptyList(),
                    threadKind = if (correlates) checkpoint?.uncaughtThreadKind else null,
                    memoryCounters = if (correlates) checkpoint?.memoryCounters.orEmpty() else emptyMap(),
                )
            }.toMutableList()

        var reconciledAbortedRun: SyncRunDiagnostics? = null
        if (checkpoint != null) {
            val matchingExit = newExitRecords.lastOrNull { it.correlatedTraceId == checkpoint.traceId }
                ?: newExitRecords.lastOrNull()
            val exitReason = matchingExit?.reasonKind ?: "crash"
            val exitErrorKind = checkpoint.uncaughtErrorKind?.takeIf { it != "internal" }
                ?: "process_exit_$exitReason".takeIf { it in DiagnosticErrorClassifier.SAFE_ERROR_KINDS }
                ?: "process_exit_other"
            val endMs = matchingExit?.timestampEpochMillis?.coerceAtLeast(checkpoint.stageStartedAtEpochMillis) ?: now
            val crumbs = recentBreadcrumbs(maxItems = 10, upToEpochMillis = endMs)
            val memCounters = buildMap {
                putAll(checkpoint.memoryCounters)
                if (matchingExit != null) {
                    if (matchingExit.pssKb > 0) put("memory.pss_kb", matchingExit.pssKb)
                    if (matchingExit.rssKb > 0) put("memory.rss_kb", matchingExit.rssKb)
                    if (matchingExit.importance > 0) put("process.importance", matchingExit.importance.toLong())
                }
            }
            val abortedStage = StageSpanRecord(
                traceId = checkpoint.traceId,
                spanId = W3cTraceContext.generateSpanId(),
                parentSpanId = checkpoint.rootSpanId,
                name = checkpoint.activeStage,
                stage = checkpoint.activeStage,
                status = "error",
                startEpochMillis = checkpoint.stageStartedAtEpochMillis,
                endEpochMillis = endMs,
                durationMs = (endMs - checkpoint.stageStartedAtEpochMillis).coerceAtLeast(0),
                attributes = TelemetryAllowlist.filterNumeric(memCounters),
                stringAttributes = buildMap {
                    checkpoint.uncaughtThreadKind?.let { put("thread.kind", it) }
                    put("exit.reason", exitReason)
                },
                errorKind = exitErrorKind,
                exceptionType = checkpoint.uncaughtExceptionType ?: "ProcessExit:$exitReason",
                appFrames = checkpoint.uncaughtAppFrames,
            )
            val abortedRun = SyncRunDiagnostics(
                runId = checkpoint.traceId,
                traceId = checkpoint.traceId,
                rootSpanId = checkpoint.rootSpanId,
                trigger = checkpoint.trigger,
                startedAtEpochMillis = checkpoint.startedAtEpochMillis,
                endedAtEpochMillis = endMs,
                durationMs = (endMs - checkpoint.startedAtEpochMillis).coerceAtLeast(0),
                outcome = "aborted_process_exit",
                lastSuccessfulStage = checkpoint.lastSuccessfulStage,
                failedStage = checkpoint.activeStage,
                errorKind = exitErrorKind,
                exceptionType = checkpoint.uncaughtExceptionType ?: "ProcessExit:$exitReason",
                appFrames = checkpoint.uncaughtAppFrames,
                counters = TelemetryAllowlist.filterNumeric(memCounters),
                stages = checkpoint.completedStages + abortedStage,
                appVersion = checkpoint.appVersion,
                commitSha = checkpoint.commitSha,
                databaseSchemaVersion = checkpoint.databaseSchemaVersion,
                threadKind = checkpoint.uncaughtThreadKind,
                breadcrumbs = crumbs,
            )
            recordSyncRun(abortedRun)
            reconciledAbortedRun = abortedRun

            // If no OS ApplicationExitInfo matched (e.g., API 29 or immediate restart), synthesize an exit record.
            if (matchingExit == null) {
                newExitRecords += ProcessExitRecord(
                    timestampEpochMillis = endMs,
                    reasonCode = ApplicationExitInfo.REASON_CRASH,
                    reasonKind = "crash",
                    status = 0,
                    importance = 100,
                    correlatedTraceId = checkpoint.traceId,
                    correlatedStage = checkpoint.activeStage,
                    appVersion = checkpoint.appVersion,
                    commitSha = checkpoint.commitSha,
                    errorKind = exitErrorKind,
                    exceptionType = checkpoint.uncaughtExceptionType ?: "ProcessExit:crash",
                    appFrames = checkpoint.uncaughtAppFrames,
                    threadKind = checkpoint.uncaughtThreadKind,
                    memoryCounters = TelemetryAllowlist.filterNumeric(memCounters),
                )
            }
            clearCheckpoint()
        }

        if (newExitRecords.isNotEmpty()) {
            val merged = (existingExits + newExitRecords)
                .sortedBy { it.timestampEpochMillis }
                .takeLast(MAX_PROCESS_EXITS)
            writeJsonLinesBounded(exitsFile, merged)
        }
        val abnormal = newExitRecords.filter { it.reasonKind !in setOf("user_requested", "other") || it.errorKind != null }
        ReconciledExitBatch(
            abortedRun = reconciledAbortedRun,
            abnormalExits = abnormal,
        )
    }.getOrDefault(ReconciledExitBatch())

    fun collectAndReconcileAndroidProcessExits(
        context: Context,
        appVersion: String = BuildConfig.VERSION_NAME,
        commitSha: String = BuildConfig.COMMIT_SHA,
    ): ReconciledExitBatch {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return reconcileProcessExits(emptyList(), appVersion, commitSha)
        }
        val inputs = runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching emptyList()
            am.getHistoricalProcessExitReasons(context.packageName, 0, 10).map { info ->
                ProcessExitInput(
                    timestampEpochMillis = info.timestamp,
                    reasonCode = info.reason,
                    status = info.status,
                    importance = info.importance,
                    pssKb = info.pss,
                    rssKb = info.rss,
                )
            }
        }.getOrDefault(emptyList())
        return reconcileProcessExits(inputs, appVersion, commitSha)
    }

    @Synchronized
    fun recentBreadcrumbs(
        maxItems: Int = 10,
        upToEpochMillis: Long = Long.MAX_VALUE,
    ): List<BreadcrumbRecord> = runCatching {
        readJsonLines<FlightEventRecord>(eventsFile)
            .filter { it.timestampEpochMillis <= upToEpochMillis }
            .sortedBy { it.timestampEpochMillis }
            .takeLast(maxItems.coerceIn(1, 20))
            .map { ev ->
                BreadcrumbRecord(
                    timestampEpochMillis = ev.timestampEpochMillis,
                    category = ev.category,
                    outcome = ev.outcome,
                    counters = TelemetryAllowlist.filterNumeric(ev.counters),
                    stringAttributes = TelemetryAllowlist.filterStrings(ev.stringAttributes),
                )
            }
    }.getOrDefault(emptyList())

    @Synchronized
    fun syncRuns(traceIdFilter: String = "", limit: Int = 50): List<SyncRunDiagnostics> = runCatching {
        val runs = readJsonLines<SyncRunDiagnostics>(syncRunsFile)
            .sortedByDescending { it.startedAtEpochMillis }
        val filtered = if (traceIdFilter.isBlank()) {
            runs
        } else {
            runs.filter { it.traceId == traceIdFilter || it.runId == traceIdFilter }
        }
        filtered.take(limit.coerceIn(1, MAX_SYNC_RUNS))
    }.getOrDefault(emptyList())

    @Synchronized
    fun events(
        fromMs: Long = 0L,
        toMs: Long = Long.MAX_VALUE,
        category: String = "",
        limit: Int = 100,
    ): List<FlightEventRecord> = runCatching {
        readJsonLines<FlightEventRecord>(eventsFile)
            .filter {
                it.timestampEpochMillis in fromMs..toMs &&
                    (category.isEmpty() || it.category == category)
            }
            .sortedByDescending { it.timestampEpochMillis }
            .take(limit.coerceIn(1, MAX_EVENTS))
    }.getOrDefault(emptyList())

    @Synchronized
    fun processExits(limit: Int = 20): List<ProcessExitRecord> = runCatching {
        readJsonLines<ProcessExitRecord>(exitsFile)
            .sortedByDescending { it.timestampEpochMillis }
            .take(limit.coerceIn(1, MAX_PROCESS_EXITS))
    }.getOrDefault(emptyList())

    @Synchronized
    fun prune(nowMs: Long = clockMs()) {
        runCatching {
            if (!directory.exists()) return
            val cutoff = nowMs - RETENTION_MS
            val runs = readJsonLines<SyncRunDiagnostics>(syncRunsFile)
                .filter { it.startedAtEpochMillis >= cutoff }
                .takeLast(MAX_SYNC_RUNS)
            writeJsonLinesBounded(syncRunsFile, runs)

            val events = readJsonLines<FlightEventRecord>(eventsFile)
                .filter { it.timestampEpochMillis >= cutoff }
                .takeLast(MAX_EVENTS)
            writeJsonLinesBounded(eventsFile, events)

            val exits = readJsonLines<ProcessExitRecord>(exitsFile)
                .filter { it.timestampEpochMillis >= cutoff }
                .takeLast(MAX_PROCESS_EXITS)
            writeJsonLinesBounded(exitsFile, exits)
        }
    }

    @Synchronized
    fun clear() {
        runCatching {
            if (syncRunsFile.exists()) syncRunsFile.delete()
            if (eventsFile.exists()) eventsFile.delete()
            if (exitsFile.exists()) exitsFile.delete()
            if (checkpointFile.exists()) checkpointFile.delete()
            if (stateFile.exists()) stateFile.delete()
        }
    }

    private fun ensureDir() {
        if (!directory.exists()) directory.mkdirs()
    }

    private inline fun <reified T> readJsonLines(file: File): List<T> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) null else runCatching { json.decodeFromString<T>(trimmed) }.getOrNull()
        }
    }

    private inline fun <reified T> writeJsonLinesBounded(file: File, items: List<T>) {
        if (items.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        val lines = items.mapNotNull { item ->
            val encoded = json.encodeToString(item)
            if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES) encoded else null
        }.toMutableList()
        while (lines.isNotEmpty() && lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } > MAX_FILE_BYTES) {
            lines.removeAt(0)
        }
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    companion object {
        const val RETENTION_MS = 14 * 86_400_000L
        const val MAX_SYNC_RUNS = 60
        const val MAX_EVENTS = 400
        const val MAX_PROCESS_EXITS = 30
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_LINE_BYTES = 32 * 1024
        const val MAX_EVENTS_PER_MINUTE = 120

        fun mapExitReason(reasonCode: Int): String = when (reasonCode) {
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "oom"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "user_requested"
            else -> "other"
        }
    }
}
