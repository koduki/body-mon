package com.master.healthcoach.data.diagnostics

import android.content.Context
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.db.*
import com.master.healthcoach.data.security.EncryptedTextStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

/** Only fixed labels and numeric counters can enter general telemetry. */
class DiagnosticsStore(context: Context, private val dao: HealthCoachDao) {
    private val prefs = context.getSharedPreferences("diagnostic_capture", Context.MODE_PRIVATE)
    private val crypto = EncryptedTextStore("health_coach_evidence_key")
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }
    var detailed: Boolean
        get() = prefs.getBoolean("detailed", false)
        set(value) { prefs.edit().putBoolean("detailed", value).commit() }

    suspend fun event(category: String, outcome: String, durationMs: Long = 0, correlation: String = "", counters: Map<String, Long> = emptyMap(), error: Throwable? = null) {
        if (!enabled) return
        require(category in CATEGORIES && outcome in OUTCOMES)
        require(counters.keys.all { it in COUNTERS })
        val safeCorrelation = correlation.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{0,64}")) }.orEmpty()
        try {
            dao.insertEvent(AppEventEntity(timestamp = System.currentTimeMillis(), category = category, outcome = outcome,
                durationMs = durationMs.coerceAtLeast(0), correlationId = safeCorrelation,
                attributesJson = buildJsonObject {
                    counters.forEach { (k, v) -> put(k, v) }
                    if (error != null) {
                        put("errorKind", when (error) {
                            is SecurityException -> "permission"
                            is java.io.IOException -> "io"
                            is IllegalArgumentException -> "validation"
                            else -> "internal"
                        })
                        put("appFrames", buildJsonArray {
                            error.stackTrace.filter { it.className.startsWith("com.master.healthcoach.") }.take(8).forEach { frame ->
                                val symbol = frame.className + "." + frame.methodName
                                if (symbol.matches(Regex("[A-Za-z0-9_.$<>-]{1,220}"))) add(JsonPrimitive(symbol + ":" + frame.lineNumber))
                            }
                        })
                    }
                }.toString(),
                appVersion = BuildConfig.VERSION_NAME, commitSha = BuildConfig.COMMIT_SHA))
            dao.pruneEvents(System.currentTimeMillis() - 14 * DAY)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* telemetry must not break health operations */ }
    }
    suspend fun capture(messageId: Long, payload: JsonObject) {
        if (!enabled || !detailed) return
        try {
            val text = payload.toString()
            if (text.toByteArray().size > 320_000) return
            dao.saveEvidence(ResponseEvidenceEntity(messageId, System.currentTimeMillis(), crypto.encrypt(text)))
            dao.pruneEvidence(System.currentTimeMillis() - 7 * DAY)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    suspend fun evidence(messageId: Long): JsonElement? {
        dao.pruneEvidence(System.currentTimeMillis() - 7 * DAY)
        val item = dao.evidence(messageId) ?: return null
        return runCatching { Json.parseToJsonElement(crypto.decrypt(item.encryptedPayload)) }.getOrNull()
    }
    suspend fun prune() {
        dao.pruneEvents(System.currentTimeMillis() - 14 * DAY)
        dao.pruneEvidence(System.currentTimeMillis() - 7 * DAY)
        dao.pruneReports(System.currentTimeMillis() - 90 * DAY)
    }
    suspend fun clear() { dao.clearEvents(); dao.clearEvidence() }
    companion object {
        const val DAY = 86_400_000L
        val CATEGORIES = setOf("sync", "report", "chat", "weekly_ai", "ui_sync", "ui_goal", "ui_tab", "tunnel", "mcp")
        val OUTCOMES = setOf("started", "success", "failure", "skipped_permission", "stopped", "expired", "locked", "network", "auth", "os_timeout", "task_removed", "deleted", "replaced", "permission_denied")
        val COUNTERS = setOf("days", "body", "sessions", "meals", "tools", "bytes", "polls", "emptyPolls", "retries", "errors", "batteryStart", "batteryEnd", "chargingStart", "chargingEnd", "cpuMs", "tab")
        fun id(): String = UUID.randomUUID().toString()
    }
}
