package com.master.healthcoach.data.diagnostics

import com.master.healthcoach.data.mcp.TunnelAuthFailure
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.SecureRandom

class HealthConnectUnavailableException(
    val availability: String,
    message: String = "Health Connect is not available ($availability)",
) : IllegalStateException(message)

class HealthConnectPermissionException(
    message: String = "Health Connectの必須データ権限が不足しています",
) : SecurityException(message)

class RoomMigrationException(
    val fromVersion: Int,
    val toVersion: Int,
    cause: Throwable,
) : IllegalStateException("Room migration $fromVersion -> $toVersion failed", cause)

@Serializable
data class ParsedTraceContext(
    val traceId: String,
    val parentSpanId: String,
    val traceFlags: String = "01",
) {
    val sampled: Boolean get() = traceFlags == "01"
}

object W3cTraceContext {
    private val random = SecureRandom()
    private val TRACE_ID_REGEX = Regex("^[0-9a-f]{32}$")
    private val SPAN_ID_REGEX = Regex("^[0-9a-f]{16}$")
    private val TRACEPARENT_REGEX = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
    private const val ZERO_TRACE_ID = "00000000000000000000000000000000"
    private const val ZERO_SPAN_ID = "0000000000000000"

    fun generateTraceId(): String {
        val bytes = ByteArray(16)
        do {
            random.nextBytes(bytes)
        } while (bytes.all { it == 0.toByte() })
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateSpanId(): String {
        val bytes = ByteArray(8)
        do {
            random.nextBytes(bytes)
        } while (bytes.all { it == 0.toByte() })
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidTraceId(value: String): Boolean =
        TRACE_ID_REGEX.matches(value) && value != ZERO_TRACE_ID

    fun isValidSpanId(value: String): Boolean =
        SPAN_ID_REGEX.matches(value) && value != ZERO_SPAN_ID

    fun formatTraceparent(traceId: String, spanId: String, sampled: Boolean = true): String {
        val safeTrace = if (isValidTraceId(traceId)) traceId else generateTraceId()
        val safeSpan = if (isValidSpanId(spanId)) spanId else generateSpanId()
        return "00-$safeTrace-$safeSpan-${if (sampled) "01" else "00"}"
    }

    fun parseTraceparent(header: String?): ParsedTraceContext? {
        if (header.isNullOrBlank()) return null
        val match = TRACEPARENT_REGEX.matchEntire(header.trim().lowercase()) ?: return null
        val traceId = match.groupValues[1]
        val spanId = match.groupValues[2]
        val flags = match.groupValues[3]
        if (traceId == ZERO_TRACE_ID || spanId == ZERO_SPAN_ID) return null
        return ParsedTraceContext(traceId = traceId, parentSpanId = spanId, traceFlags = flags)
    }
}

object DiagnosticErrorClassifier {
    private val SAFE_SYMBOL_REGEX = Regex("^[A-Za-z0-9_.$<>-]{1,220}$")
    private val SAFE_CLASS_REGEX = Regex("^[A-Za-z0-9_$]{1,80}$")

    val SAFE_ERROR_KINDS = setOf(
        "permission",
        "hc_unavailable",
        "hc_update_required",
        "hc_remote",
        "timeout",
        "migration",
        "sqlite",
        "validation",
        "network",
        "auth",
        "process_exit_crash",
        "process_exit_crash_native",
        "process_exit_anr",
        "process_exit_oom",
        "process_exit_excessive_resource",
        "process_exit_signaled",
        "process_exit_user_requested",
        "process_exit_other",
        "internal",
    )

    fun classify(error: Throwable): String {
        val root = unwrap(error)
        return when {
            error is HealthConnectUnavailableException ->
                if (error.availability == "UPDATE_REQUIRED") "hc_update_required" else "hc_unavailable"
            root is HealthConnectUnavailableException ->
                if (root.availability == "UPDATE_REQUIRED") "hc_update_required" else "hc_unavailable"
            error is HealthConnectPermissionException || root is HealthConnectPermissionException -> "permission"
            error is SecurityException || root is SecurityException -> "permission"
            error is RoomMigrationException || root is RoomMigrationException -> "migration"
            isRoomMigrationFailure(error) || isRoomMigrationFailure(root) -> "migration"
            isSqliteException(error) || isSqliteException(root) -> "sqlite"
            error is TimeoutCancellationException || root is TimeoutCancellationException ||
                error is SocketTimeoutException || root is SocketTimeoutException ||
                error is InterruptedIOException || root is InterruptedIOException -> "timeout"
            isAndroidRemoteException(error) || isAndroidRemoteException(root) -> "hc_remote"
            error is TunnelAuthFailure || root is TunnelAuthFailure -> "auth"
            error is IOException || root is IOException -> "network"
            error is IllegalArgumentException || root is IllegalArgumentException -> "validation"
            else -> "internal"
        }
    }

    fun sanitizeExceptionType(error: Throwable): String {
        val simple = error.javaClass.simpleName
        return if (simple.isNotEmpty() && SAFE_CLASS_REGEX.matches(simple)) simple else "Exception"
    }

    fun sanitizeAppFrames(error: Throwable, maxFrames: Int = 8): List<String> {
        val frames = linkedSetOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4 && frames.size < maxFrames) {
            current.stackTrace
                ?.asSequence()
                ?.filter { it.className.startsWith("com.master.healthcoach.") }
                ?.forEach { frame ->
                    if (frames.size < maxFrames) {
                        val symbol = "${frame.className}.${frame.methodName}"
                        if (SAFE_SYMBOL_REGEX.matches(symbol)) {
                            frames += "$symbol:${frame.lineNumber}"
                        }
                    }
                }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return frames.toList()
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < 4) {
            val next = current.cause!!
            if (current is RoomMigrationException || current is HealthConnectUnavailableException || current is HealthConnectPermissionException) {
                return current
            }
            current = next
            depth++
        }
        return current
    }

    private fun isRoomMigrationFailure(error: Throwable): Boolean {
        val className = error.javaClass.name
        if (className.contains("Migration", ignoreCase = true)) return true
        val frames = error.stackTrace?.take(12).orEmpty()
        if (frames.any {
                it.className.startsWith("androidx.room.RoomOpenHelper") ||
                    it.className.startsWith("androidx.room.migration") ||
                    (it.className.startsWith("com.master.healthcoach.data.db.HealthCoachDatabase") && it.methodName == "migrate")
            }
        ) {
            return true
        }
        return false
    }

    private fun isSqliteException(error: Throwable): Boolean {
        var cls: Class<*>? = error.javaClass
        while (cls != null) {
            val name = cls.name
            val simple = cls.simpleName
            if (name == "android.database.sqlite.SQLiteException" ||
                name == "androidx.sqlite.SQLiteException" ||
                name.startsWith("android.database.sqlite.SQLite") ||
                name.startsWith("org.sqlite.SQLite") ||
                (simple.startsWith("SQLite") && simple.endsWith("Exception"))
            ) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    private fun isAndroidRemoteException(error: Throwable): Boolean {
        var cls: Class<*>? = error.javaClass
        while (cls != null) {
            val name = cls.name
            if (name == "android.os.RemoteException" ||
                name == "android.os.DeadObjectException" ||
                name == "android.os.TransactionTooLargeException"
            ) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }
}

object TelemetryRuntimeContext {
    val sessionId: String = W3cTraceContext.generateSpanId()

    fun sanitizeThreadKind(threadName: String?): String {
        val lower = threadName?.trim()?.lowercase().orEmpty()
        return when {
            lower == "main" || lower.startsWith("ui") -> "main"
            lower.startsWith("wm.task") || lower.contains("work") || lower.startsWith("pool-") -> "worker"
            lower.contains("dispatcher") || lower.startsWith("okhttp") || lower.contains("io") -> "io"
            lower.startsWith("binder:") -> "binder"
            else -> "other"
        }
    }

    fun captureHeapCounters(): Map<String, Long> = runCatching {
        val rt = Runtime.getRuntime()
        val totalKb = (rt.totalMemory() / 1024L).coerceAtLeast(0L)
        val freeKb = (rt.freeMemory() / 1024L).coerceAtLeast(0L)
        val usedKb = (totalKb - freeKb).coerceAtLeast(0L)
        val maxKb = (rt.maxMemory() / 1024L).coerceAtLeast(0L)
        mapOf(
            "memory.heap_used_kb" to usedKb,
            "memory.heap_free_kb" to freeKb,
            "memory.heap_max_kb" to maxKb,
        )
    }.getOrDefault(emptyMap())
}

object TelemetryAllowlist {
    val SYNC_STAGES = setOf(
        "sync.init",
        "room.migration",
        "healthconnect.permissions",
        "healthconnect.read",
        "energy.estimate",
        "room.persist",
        "weekly.rebuild",
        "diagnostics.record",
        // Chat & Weekly AI stages
        "chat.context_load",
        "chat.prefetch_tools",
        "chat.gemini_generate",
        "chat.memory_refresh",
        "weekly_ai.rebuild",
        "weekly_ai.gemini",
        // Crash & Process exit stages
        "app.runtime",
        "process.exit",
    )

    val ALLOWED_NUMERIC_KEYS = setOf(
        // Legacy & general counters
        "days",
        "body",
        "sessions",
        "heartSamples",
        "meals",
        "tools",
        "bytes",
        "polls",
        "emptyPolls",
        "retries",
        "errors",
        "batteryStart",
        "batteryEnd",
        "chargingStart",
        "chargingEnd",
        "cpuMs",
        "tab",
        // Stage-specific low-cardinality counters
        "sync.days",
        "hc.optional_granted_count",
        "records.weight",
        "records.body_fat",
        "records.steps",
        "records.distance",
        "records.exercise_all",
        "records.exercise_selected",
        "records.active_calories",
        "records.total_calories",
        "records.sleep",
        "records.heart_rate_records",
        "records.heart_rate_samples",
        "records.basal_rate",
        "records.intensity",
        "records.nutrition",
        "assist.valid_days",
        "assist.sessions_estimated",
        "db.daily_upserted",
        "db.body_upserted",
        "db.sources_upserted",
        "db.sessions_upserted",
        "db.heart_samples_upserted",
        "db.meals_upserted",
        "db.from_version",
        "db.to_version",
        "weekly.inputs_bytes",
        // Runtime / Crashlytics parity counters (memory, process state, chat/AI metrics)
        "memory.pss_kb",
        "memory.rss_kb",
        "memory.heap_used_kb",
        "memory.heap_free_kb",
        "memory.heap_max_kb",
        "process.importance",
        "exit.status_code",
        "exit.reason_code",
        "chat.prefetch_count",
        "chat.tool_calls",
        "chat.attachments_count",
    )

    val ALLOWED_STRING_KEYS = setOf(
        "sync.trigger",
        "sync.stage",
        "sync.outcome",
        "hc.availability",
        "mcp.tool",
        "mcp.outcome",
        "error.kind",
        "error.type",
        "error.stage",
        "thread.kind",
        "exit.reason",
        "chat.stage",
        "weekly_ai.stage",
        "app.session_id",
    )

    val ALLOWED_BOOLEAN_KEYS = setOf(
        "hc.core_granted",
        "hc.bg_granted",
        "hc.history_granted",
        "weekly.archived",
    )

    private val SAFE_TOKEN_VALUE_REGEX = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val FORBIDDEN_VALUE_PATTERNS = Regex("(?i)(bearer\\s+|sk-[a-z0-9]|AIza[0-9A-Za-z_-]|eyJ[A-Za-z0-9_-]+\\.|passToken|cookie|password|secret)")

    fun filterNumeric(counters: Map<String, Long>): Map<String, Long> =
        counters.filterKeys { it in ALLOWED_NUMERIC_KEYS }

    fun filterStrings(attrs: Map<String, String>): Map<String, String> =
        attrs.filter { (key, value) ->
            key in ALLOWED_STRING_KEYS &&
                SAFE_TOKEN_VALUE_REGEX.matches(value) &&
                !FORBIDDEN_VALUE_PATTERNS.containsMatchIn(value)
        }

    fun filterBooleans(attrs: Map<String, Boolean>): Map<String, Boolean> =
        attrs.filterKeys { it in ALLOWED_BOOLEAN_KEYS }
}

@Serializable
data class BreadcrumbRecord(
    val timestampEpochMillis: Long,
    val category: String,
    val outcome: String,
    val counters: Map<String, Long> = emptyMap(),
    val stringAttributes: Map<String, String> = emptyMap(),
)

@Serializable
data class StageSpanRecord(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,
    val name: String,
    val stage: String,
    val status: String, // "ok", "error", "skipped"
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val durationMs: Long,
    val attributes: Map<String, Long> = emptyMap(),
    val stringAttributes: Map<String, String> = emptyMap(),
    val booleanAttributes: Map<String, Boolean> = emptyMap(),
    val errorKind: String? = null,
    val exceptionType: String? = null,
    val appFrames: List<String> = emptyList(),
)

@Serializable
data class SyncRunDiagnostics(
    val runId: String,
    val traceId: String,
    val rootSpanId: String,
    val trigger: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val durationMs: Long,
    val outcome: String, // "success", "failure", "skipped_permission", "aborted_process_exit"
    val lastSuccessfulStage: String? = null,
    val failedStage: String? = null,
    val errorKind: String? = null,
    val exceptionType: String? = null,
    val appFrames: List<String> = emptyList(),
    val counters: Map<String, Long> = emptyMap(),
    val stages: List<StageSpanRecord> = emptyList(),
    val appVersion: String,
    val commitSha: String,
    val databaseSchemaVersion: Int = 12,
    val threadKind: String? = null,
    val breadcrumbs: List<BreadcrumbRecord> = emptyList(),
)

@Serializable
data class ActiveStageCheckpoint(
    val traceId: String,
    val rootSpanId: String,
    val trigger: String,
    val activeStage: String,
    val lastSuccessfulStage: String? = null,
    val startedAtEpochMillis: Long,
    val stageStartedAtEpochMillis: Long,
    val completedStages: List<StageSpanRecord> = emptyList(),
    val appVersion: String,
    val commitSha: String,
    val databaseSchemaVersion: Int = 12,
    val uncaughtExceptionType: String? = null,
    val uncaughtErrorKind: String? = null,
    val uncaughtAppFrames: List<String> = emptyList(),
    val uncaughtThreadKind: String? = null,
    val memoryCounters: Map<String, Long> = emptyMap(),
)

@Serializable
data class ProcessExitRecord(
    val timestampEpochMillis: Long,
    val reasonCode: Int,
    val reasonKind: String,
    val status: Int,
    val importance: Int,
    val pssKb: Long = 0,
    val rssKb: Long = 0,
    val correlatedTraceId: String? = null,
    val correlatedStage: String? = null,
    val appVersion: String,
    val commitSha: String,
    val errorKind: String? = null,
    val exceptionType: String? = null,
    val appFrames: List<String> = emptyList(),
    val threadKind: String? = null,
    val memoryCounters: Map<String, Long> = emptyMap(),
)

@Serializable
data class FlightEventRecord(
    val timestampEpochMillis: Long,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String = "",
    val category: String,
    val outcome: String,
    val durationMs: Long,
    val counters: Map<String, Long> = emptyMap(),
    val stringAttributes: Map<String, String> = emptyMap(),
    val errorKind: String? = null,
    val exceptionType: String? = null,
    val appFrames: List<String> = emptyList(),
    val appVersion: String,
    val commitSha: String,
)
