package com.master.healthcoach.data.mcp

import android.os.Build
import androidx.room.withTransaction
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.db.*
import com.master.healthcoach.data.diagnostics.DiagnosticsStore
import com.master.healthcoach.data.llm.*
import com.master.healthcoach.domain.WeeklyReportBuilder
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

class HealthMcpBackend(
    private val db: HealthCoachDatabase,
    private val repository: HealthRepository,
    private val diagnostics: DiagnosticsStore,
    private val permissions: () -> SharePermissions,
    private val sessionExpiresAt: () -> Long,
) {
    private val dao = db.dao()
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val queries = HealthQueryService(repository)
    fun tools(): JsonArray = McpTools.available(permissions())
    suspend fun call(name: String, args: JsonObject): JsonElement {
        McpTools.check(name, args, permissions())
        // Permission queries touch Health Connect and should not hold a Room transaction.
        val granted = if (name == "get_app_diagnostics") runCatching { repository.grantedPermissions().sorted() }.getOrDefault(emptyList()) else emptyList()
        val result = db.withTransaction {
            val revision = dao.metadata("revision").orEmpty()
            val today = LocalDate.now()
            val to = date(args, "to", today)
            val from = date(args, "from", to.minusDays(27))
            if (from > to || ChronoUnit.DAYS.between(from, to) >= 90 || to > today) throw McpFailure("INVALID_RANGE")
            val zone = ZoneId.systemDefault()
            val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            val limit = number(args, "limit", 50, 1, 100)
            val fingerprint = digest(name + JsonObject(args.filterKeys { it != "cursor" }).toString())
            val cursor = decodeCursor(args.string("cursor"), revision, fingerprint)
            var next: String? = null
            var effectiveFrom = from.toString()
            var effectiveTo = to.toString()
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
                    put("databaseVersion", 10); put("policyVersion", ConversationContextPolicy.VERSION)
                    put("grantedPermissions", json.encodeToJsonElement(granted)); put("captureEnabled", diagnostics.enabled); put("detailedCapture", diagnostics.detailed)
                    val events = dao.events(start, end, "", 0, 10001)
                    put("eventCount", events.size); put("sampleLimited", events.size > 10000)
                    put("counts", buildJsonObject { events.groupingBy { it.category + ":" + it.outcome }.eachCount().forEach { (k,v) -> put(k,v) } })
                    put("retentionDays", 14); put("historicalEventsBeforeFeatureUnavailable", true)
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
            buildJsonObject {
                put("schemaVersion", 1); put("generatedAt", Instant.now().toString()); put("timeZone", zone.id)
                put("dataRevision", revision); put("snapshotId", revision + ":" + fingerprint)
                put("requestedPeriod", buildJsonObject { put("from", from.toString()); put("to", to.toString()) })
                put("effectivePeriod", buildJsonObject { put("from", effectiveFrom); put("to", effectiveTo) })
                put("lastSyncSuccessEpochMillis", dao.metadata("lastSyncSuccess")?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                put("lastSyncOutcome", dao.metadata("lastSyncOutcome")?.let(::JsonPrimitive) ?: JsonNull)
                put("todayIsPartial", true); put("data", data)
                put("nextCursor", next?.let { JsonPrimitive(encodeCursor(revision, fingerprint, it)) } ?: JsonNull)
                put("warnings", buildJsonArray { add(JsonPrimitive("Cached data only; null/missing is not zero. Metrics describe effectivePeriod; follow nextCursor for the remaining requested period.")) })
            }
        }
        McpTools.check(name, args, permissions())
        return result
    }
    private suspend fun reconstruct(args: JsonObject, inputs: Boolean): JsonObject {
        val end = date(args, "periodEnd", LocalDate.now().minusDays(1))
        if (end >= LocalDate.now() || end < LocalDate.now().minusDays(89)) throw McpFailure("INVALID_RANGE")
        val daily = repository.getDaily(end.minusDays(89), end)
        val body = repository.getBody(end.minusDays(89), end)
        val goal = dao.getGoal()
        return buildJsonObject {
            put("reportMode", "reconstructed"); put("commitSha", BuildConfig.COMMIT_SHA); put("policyVersion", ConversationContextPolicy.VERSION)
            put("snapshot", json.encodeToJsonElement(WeeklyReportBuilder.build(end.plusDays(1), daily, body, goal)))
            if (inputs) put("inputs", buildJsonObject { put("daily", json.encodeToJsonElement(daily)); put("body", json.encodeToJsonElement(body)); put("goal", json.encodeToJsonElement(goal)) })
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
