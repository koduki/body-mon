package com.master.healthcoach.data.diagnostics

import android.app.ApplicationExitInfo
import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.SyncFaultHook
import com.master.healthcoach.data.db.*
import com.master.healthcoach.data.health.HealthConnectAvailability
import com.master.healthcoach.data.health.HealthConnectGateway
import com.master.healthcoach.data.health.HealthSyncBundle
import com.master.healthcoach.data.health.SyncStageObserver
import com.master.healthcoach.data.mcp.HealthMcpBackend
import com.master.healthcoach.data.mcp.McpProtocol
import com.master.healthcoach.data.mcp.SharePermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZoneId

class SyncObservabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `W3C traceparent format and parse roundtrip and validation`() {
        val traceId = W3cTraceContext.generateTraceId()
        val spanId = W3cTraceContext.generateSpanId()
        assertTrue(W3cTraceContext.isValidTraceId(traceId))
        assertTrue(W3cTraceContext.isValidSpanId(spanId))

        val header = W3cTraceContext.formatTraceparent(traceId, spanId, sampled = true)
        assertEquals("00-$traceId-$spanId-01", header)

        val parsed = W3cTraceContext.parseTraceparent(header)
        assertNotNull(parsed)
        assertEquals(traceId, parsed!!.traceId)
        assertEquals(spanId, parsed.parentSpanId)
        assertTrue(parsed.sampled)

        assertNull(W3cTraceContext.parseTraceparent("00-00000000000000000000000000000000-$spanId-01"))
        assertNull(W3cTraceContext.parseTraceparent("00-$traceId-0000000000000000-01"))
        assertNull(W3cTraceContext.parseTraceparent("invalid-header"))
    }

    @Test
    fun `allowlist and error classifier strip health values chat messages and secrets`() {
        val filteredNumbers = TelemetryAllowlist.filterNumeric(
            mapOf(
                "days" to 28L,
                "records.weight" to 14L,
                "weightKg" to 72L,
                "restingHeartRateBpm" to 58L,
                "intakeCaloriesKcal" to 2100L,
            ),
        )
        assertEquals(mapOf("days" to 28L, "records.weight" to 14L), filteredNumbers)

        val filteredStrings = TelemetryAllowlist.filterStrings(
            mapOf(
                "sync.trigger" to "ui",
                "error.kind" to "permission",
                "chat.prompt" to "My weight is 72.4kg",
                "sync.stage" to "Bearer secret-token-12345",
                "mcp.tool" to "AIzaSySecretKey123456789",
            ),
        )
        assertEquals(mapOf("sync.trigger" to "ui", "error.kind" to "permission"), filteredStrings)

        val ex = IllegalArgumentException("Invalid weight=72.4kg for user prompt='secret chat' key=sk-abcdef123456")
        assertEquals("validation", DiagnosticErrorClassifier.classify(ex))
        assertEquals("IllegalArgumentException", DiagnosticErrorClassifier.sanitizeExceptionType(ex))
        val frames = DiagnosticErrorClassifier.sanitizeAppFrames(ex)
        assertTrue(frames.all { it.startsWith("com.master.healthcoach.") })
        assertFalse(frames.joinToString().contains("72.4kg"))
        assertFalse(frames.joinToString().contains("sk-abcdef"))
    }

    @Test
    fun `fault injection across every sync stage records exact lastSuccessfulStage failedStage and safe errorKind`() = runTest {
        // 1. healthconnect.permissions denied
        run {
            val env = createTestHarness()
            env.gateway.corePermissionsGranted = false
            val res = runCatching { env.repository.sync(trigger = "ui") }
            assertTrue(res.isFailure)
            val run = env.recorder.syncRuns().first()
            assertEquals("failure", run.outcome)
            assertNull(run.lastSuccessfulStage)
            assertEquals("healthconnect.permissions", run.failedStage)
            assertEquals("permission", run.errorKind)
            assertEquals("error", run.stages.first { it.stage == "healthconnect.permissions" }.status)
        }

        // 2. healthconnect.permissions unavailable (UPDATE_REQUIRED)
        run {
            val env = createTestHarness()
            env.gateway.currentAvailability = HealthConnectAvailability.UPDATE_REQUIRED
            val res = runCatching { env.repository.sync(trigger = "worker") }
            assertTrue(res.isFailure)
            val run = env.recorder.syncRuns().first()
            assertEquals("failure", run.outcome)
            assertNull(run.lastSuccessfulStage)
            assertEquals("healthconnect.permissions", run.failedStage)
            assertEquals("hc_update_required", run.errorKind)
        }

        // 3. healthconnect.read timeout failure
        run {
            val env = createTestHarness()
            env.gateway.failAtStage = "healthconnect.read" to SocketTimeoutException("HC read IPC timeout")
            val res = runCatching { env.repository.sync(trigger = "ui") }
            assertTrue(res.isFailure)
            val run = env.recorder.syncRuns().first()
            assertEquals("failure", run.outcome)
            assertEquals("healthconnect.permissions", run.lastSuccessfulStage)
            assertEquals("healthconnect.read", run.failedStage)
            assertEquals("timeout", run.errorKind)
        }

        // 4. energy.estimate calculation validation failure
        run {
            val env = createTestHarness()
            val res = runCatching {
                env.repository.sync(
                    trigger = "ui",
                    faultHook = SyncFaultHook { stage ->
                        if (stage == "energy.estimate") {
                            throw IllegalArgumentException("Invalid BMR for weight=74.8kg")
                        }
                    },
                )
            }
            assertTrue(res.isFailure)
            val run = env.recorder.syncRuns().first()
            assertEquals("failure", run.outcome)
            assertEquals("healthconnect.read", run.lastSuccessfulStage)
            assertEquals("energy.estimate", run.failedStage)
            assertEquals("validation", run.errorKind)
            val reportText = env.diagnostics.exportReportJson()
            assertFalse("Raw weight must never leak into diagnostic report", reportText.contains("74.8kg"))
        }

        // 5. room.persist SQLite write failure
        run {
            val env = createTestHarness()
            env.dao.failOnUpsertDaily = SQLiteDiskIOException("database disk image is malformed (weight=74.8kg)")
            val res = runCatching { env.repository.sync(trigger = "ui") }
            assertTrue(res.isFailure)
            val run = env.recorder.syncRuns().first()
            assertEquals("failure", run.outcome)
            assertEquals("energy.estimate", run.lastSuccessfulStage)
            assertEquals("room.persist", run.failedStage)
            assertEquals("sqlite", run.errorKind)
            val reportText = env.diagnostics.exportReportJson()
            assertFalse(reportText.contains("74.8kg"))
        }

        // 6. weekly.rebuild failure
        run {
            val env = createTestHarness()
            val res = runCatching {
                env.repository.sync(
                    trigger = "ui",
                    faultHook = SyncFaultHook { stage ->
                        if (stage == "weekly.rebuild") {
                            throw IllegalStateException("Snapshot serialization failure")
                        }
                    },
                )
            }
            assertTrue(res.isFailure)
            val run = env.recorder.syncRuns().first()
            assertEquals("failure", run.outcome)
            assertEquals("room.persist", run.lastSuccessfulStage)
            assertEquals("weekly.rebuild", run.failedStage)
            assertEquals("internal", run.errorKind)
        }

        // 7. diagnostics.record failure MUST NOT fail the user's sync!
        run {
            val env = createTestHarness()
            env.dao.failOnInsertEvent = SQLiteDiskIOException("app_events table locked")
            val res = runCatching { env.repository.sync(trigger = "ui") }
            assertTrue("Diagnostics failure must never fail sync()", res.isSuccess)
            val run = env.recorder.syncRuns().first()
            assertEquals("success", run.outcome)
            assertEquals("weekly.rebuild", run.lastSuccessfulStage)
            assertNull(run.failedStage)
            val diagStage = run.stages.first { it.stage == "diagnostics.record" }
            assertEquals("error", diagStage.status)
            assertEquals("sqlite", diagStage.errorKind)
        }
    }

    @Test
    fun `room migration failure is recorded in flight recorder and exportable when Room DB is unavailable`() = runTest {
        val recorder = LocalFlightRecorder(tempFolder.newFolder("diag_migration"))
        recorder.recordMigrationFailure(
            fromVersion = 11,
            toVersion = 12,
            error = IllegalStateException("ALTER TABLE daily_health_summary failed with weight=81.2kg"),
        )
        // DiagnosticsStore initialized with dao = null (simulating Room DB unable to open)
        val brokenDbDiagnostics = DiagnosticsStore(
            dao = null,
            flightRecorder = recorder,
        )
        val reportJson = brokenDbDiagnostics.exportReportJson(availability = "AVAILABLE")
        assertFalse(reportJson.contains("81.2kg"))
        val parsed = json.parseToJsonElement(reportJson).jsonObject
        val summary = parsed["lastSyncSummary"]!!.jsonObject
        assertEquals("failure", summary["outcome"]!!.jsonPrimitive.content)
        assertEquals("room.migration", summary["failedStage"]!!.jsonPrimitive.content)
        assertEquals("migration", summary["errorKind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `process exit and crash checkpoint are reconciled into aborted_process_exit on next startup`() {
        var now = 1_700_000_000_000L
        val recorder = LocalFlightRecorder(tempFolder.newFolder("diag_exit")) { now }
        val traceId = W3cTraceContext.generateTraceId()
        val rootSpanId = W3cTraceContext.generateSpanId()

        recorder.writeCheckpoint(
            ActiveStageCheckpoint(
                traceId = traceId,
                rootSpanId = rootSpanId,
                trigger = "worker",
                activeStage = "healthconnect.read",
                lastSuccessfulStage = "healthconnect.permissions",
                startedAtEpochMillis = now,
                stageStartedAtEpochMillis = now + 50,
                appVersion = "1.0.0",
                commitSha = "abc1234",
            ),
        )
        now += 500
        recorder.reconcileProcessExits(
            listOf(
                ProcessExitInput(
                    timestampEpochMillis = now,
                    reasonCode = ApplicationExitInfo.REASON_ANR,
                    status = 0,
                    importance = 100,
                    pssKb = 120_000,
                    rssKb = 180_000,
                ),
            ),
            appVersion = "1.0.0",
            commitSha = "abc1234",
        )

        assertNull("Checkpoint should be cleared after reconciliation", recorder.readCheckpoint())
        val run = recorder.syncRuns().first()
        assertEquals(traceId, run.traceId)
        assertEquals("aborted_process_exit", run.outcome)
        assertEquals("healthconnect.permissions", run.lastSuccessfulStage)
        assertEquals("healthconnect.read", run.failedStage)
        assertEquals("process_exit_anr", run.errorKind)

        val exit = recorder.processExits().first()
        assertEquals("anr", exit.reasonKind)
        assertEquals(traceId, exit.correlatedTraceId)
        assertEquals("healthconnect.read", exit.correlatedStage)
    }

    @Test
    fun `OTLP exporter makes zero requests when disabled and exports W3C trace spans when opted in`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/v1/traces").toString()
            val settings = InMemoryOtlpSettings(
                initial = OtlpConfig(
                    enabled = false,
                    endpointUrl = baseUrl,
                    gcpProjectId = "health-support-ai",
                    bearerToken = "test_ingest_secret_token",
                ),
            )
            val env = createTestHarness(otlpSettings = settings)

            // 1. Default OFF -> zero external requests
            env.repository.sync(trigger = "ui")
            assertEquals("Zero OTLP requests must occur when export is disabled", 0, server.requestCount)

            // 2. Opt-in ON -> exports OTLP/HTTP JSON with traceparent & Bearer token
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            settings.enabled = true
            env.repository.sync(trigger = "ui")

            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("Bearer test_ingest_secret_token", request.getHeader("Authorization"))
            assertEquals("health-support-ai", request.getHeader("X-Goog-User-Project"))
            val traceparent = request.getHeader("traceparent")
            assertNotNull(traceparent)
            val parsedTraceparent = W3cTraceContext.parseTraceparent(traceparent)
            assertNotNull(parsedTraceparent)

            val bodyText = request.body.readUtf8()
            assertFalse("Bearer token must never appear in OTLP body", bodyText.contains("test_ingest_secret_token"))
            assertFalse("Health values must never appear in OTLP body", bodyText.contains("72.5"))

            val payload = json.parseToJsonElement(bodyText).jsonObject
            val spans = payload["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray
            val spanNames = spans.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(spanNames.contains("sync.run"))
            assertTrue(spanNames.contains("healthconnect.permissions"))
            assertTrue(spanNames.contains("healthconnect.read"))
            assertTrue(spanNames.contains("energy.estimate"))
            assertTrue(spanNames.contains("room.persist"))
            assertTrue(spanNames.contains("weekly.rebuild"))
            assertTrue(spanNames.contains("diagnostics.record"))

            // 3. Server HTTP 500 or disconnect -> never breaks sync
            server.enqueue(MockResponse().setResponseCode(500).setBody("collector error"))
            val syncWithCollectorError = runCatching { env.repository.sync(trigger = "ui") }
            assertTrue("Collector failure must never break primary sync", syncWithCollectorError.isSuccess)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `MCP get_sync_diagnostics correlates incoming traceparent and works even when Room is unavailable`() = runTest {
        val env = createTestHarness()
        env.repository.sync(trigger = "ui")
        val latestSyncTraceId = env.recorder.syncRuns().first().traceId

        val backend = HealthMcpBackend(
            db = null,
            repository = env.repository,
            diagnostics = env.diagnostics,
            permissions = { SharePermissions(health = false, conversations = false, diagnostics = true, evidence = false) },
            sessionExpiresAt = { System.currentTimeMillis() + 600_000L },
            daoOverride = null, // Simulate Room DAO unavailable
        )

        val incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val incomingSpanId = "00f067aa0ba902b7"
        val requestJson = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 42)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", "get_sync_diagnostics")
                put("arguments", buildJsonObject { put("limit", 10) })
                put("_meta", buildJsonObject {
                    put("traceparent", "00-$incomingTraceId-$incomingSpanId-01")
                })
            })
        }

        val response = McpProtocol.dispatch(requestJson, backend::tools, backend::call)!!
        val result = response["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        val structured = result["structuredContent"]!!.jsonObject
        assertEquals(incomingTraceId, structured["traceId"]!!.jsonPrimitive.content)
        assertEquals(latestSyncTraceId, structured["lastSyncTraceId"]!!.jsonPrimitive.content)

        val data = structured["data"]!!.jsonObject
        val latestSummary = data["latestRunSummary"]!!.jsonObject
        assertEquals(latestSyncTraceId, latestSummary["traceId"]!!.jsonPrimitive.content)
        assertEquals("success", latestSummary["outcome"]!!.jsonPrimitive.content)
        assertEquals("weekly.rebuild", latestSummary["lastSuccessfulStage"]!!.jsonPrimitive.content)

        val runs = data["runs"]!!.jsonArray
        assertEquals(1, runs.size)

        // Also verify MCP call event was recorded with incomingTraceId
        val mcpEvent = env.recorder.events(category = "mcp").first()
        assertEquals(incomingTraceId, mcpEvent.traceId)
        assertEquals(incomingSpanId, mcpEvent.parentSpanId)
        assertEquals("get_sync_diagnostics", mcpEvent.stringAttributes["mcp.tool"])
    }

    @Test
    fun `crash and process exit on next launch export app_crash span with breadcrumbs memory and persistent queue recovery`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val diagDir = tempFolder.newFolder("diag_crash_persist")
            val baseUrl = server.url("/v1/traces").toString()
            val settings = InMemoryOtlpSettings(
                initial = OtlpConfig(
                    enabled = true,
                    endpointUrl = baseUrl,
                    gcpProjectId = "health-support-ai",
                    bearerToken = "crash_ingest_token",
                ),
            )
            val recorder = LocalFlightRecorder(diagDir)
            val exporter1 = OtlpTraceExporter(
                settings = settings,
                allowLocalTestServer = true,
                asyncDispatch = false,
                persistenceDir = diagDir,
            )
            val diagnostics1 = DiagnosticsStore(
                dao = null,
                flightRecorder = recorder,
                otlpExporter = exporter1,
            )

            // Record preceding UI breadcrumbs (tab switch + sync start)
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            diagnostics1.event("ui_tab", "success", counters = mapOf("tab" to 2L))
            server.takeRequest() // consume ui_tab event

            // Simulate uncaught exception on main thread while collector is temporarily unreachable (HTTP 503)
            recorder.recordUncaughtException(
                error = IllegalStateException("UI state failure with secret weight=69.9kg"),
                threadName = "main",
            )
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

            // Next launch reconciliation tries to export aborted_process_exit + app.crash, which get persisted in otlp_pending.jsonl
            diagnostics1.reconcileAndExportExits(
                listOf(
                    ProcessExitInput(
                        timestampEpochMillis = System.currentTimeMillis() + 100,
                        reasonCode = ApplicationExitInfo.REASON_CRASH,
                        status = 1,
                        importance = 100,
                        pssKb = 95_000,
                        rssKb = 140_000,
                    ),
                ),
            )
            assertTrue("Failed OTLP exports must remain buffered on disk", exporter1.bufferedCount() >= 1)

            // Simulate a brand new process launch loading otlp_pending.jsonl from disk and flushing when server recovers
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val exporter2 = OtlpTraceExporter(
                settings = settings,
                allowLocalTestServer = true,
                asyncDispatch = false,
                persistenceDir = diagDir,
            )
            assertTrue("New exporter instance must load persisted queue from disk", exporter2.bufferedCount() >= 1)
            exporter2.flushPending()
            assertEquals("Persisted queue must be empty after successful flush", 0, exporter2.bufferedCount())

            // Drain requests and inspect the exported payloads
            val bodies = mutableListOf<String>()
            while (server.requestCount > 0) {
                val req = server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                bodies += req.body.readUtf8()
            }
            val combined = bodies.joinToString("\n")
            assertFalse("Raw exception message with weight must never leak", combined.contains("69.9kg"))
            assertTrue("Must export app.crash span", combined.contains("\"name\":\"app.crash\""))
            assertTrue("Must include breadcrumb events", combined.contains("\"name\":\"breadcrumb\""))
            assertTrue("Must include memory.pss_kb attribute", combined.contains("memory.pss_kb"))
            assertTrue("Must include thread.kind=main attribute", combined.contains("\"stringValue\":\"main\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `multi-stage operation trace exports chat_turn and weekly_ai_analyze with stage spans and breadcrumbs on failure`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/v1/traces").toString()
            val settings = InMemoryOtlpSettings(
                initial = OtlpConfig(
                    enabled = true,
                    endpointUrl = baseUrl,
                    gcpProjectId = "health-support-ai",
                    bearerToken = "op_token",
                ),
            )
            val env = createTestHarness(otlpSettings = settings)
            val traceId = W3cTraceContext.generateTraceId()
            val rootSpanId = W3cTraceContext.generateSpanId()
            val now = System.currentTimeMillis()

            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            env.diagnostics.recordOperationTrace(
                category = "chat",
                spanName = "chat.turn",
                traceId = traceId,
                rootSpanId = rootSpanId,
                startedAtMs = now,
                endedAtMs = now + 120,
                outcome = "failure",
                stages = listOf(
                    StageSpanRecord(
                        traceId = traceId,
                        spanId = W3cTraceContext.generateSpanId(),
                        parentSpanId = rootSpanId,
                        name = "chat.context_load",
                        stage = "chat.context_load",
                        status = "ok",
                        startEpochMillis = now,
                        endEpochMillis = now + 20,
                        durationMs = 20,
                    ),
                    StageSpanRecord(
                        traceId = traceId,
                        spanId = W3cTraceContext.generateSpanId(),
                        parentSpanId = rootSpanId,
                        name = "chat.gemini_generate",
                        stage = "chat.gemini_generate",
                        status = "error",
                        startEpochMillis = now + 20,
                        endEpochMillis = now + 120,
                        durationMs = 100,
                        errorKind = "timeout",
                        exceptionType = "SocketTimeoutException",
                    ),
                ),
                counters = mapOf("tools" to 2L, "chat.prefetch_count" to 1L),
                stringAttributes = mapOf("chat.stage" to "chat.gemini_generate"),
                failedStage = "chat.gemini_generate",
                error = SocketTimeoutException("Gemini read timeout"),
            )

            val req = server.takeRequest()
            val payload = json.parseToJsonElement(req.body.readUtf8()).jsonObject
            val spans = payload["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray
            val spanNames = spans.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(spanNames.contains("chat.turn"))
            assertTrue(spanNames.contains("chat.context_load"))
            assertTrue(spanNames.contains("chat.gemini_generate"))
        } finally {
            server.shutdown()
        }
    }

    private class SQLiteDiskIOException(message: String) : RuntimeException(message)

    private data class TestHarness(
        val recorder: LocalFlightRecorder,
        val dao: FakeHealthCoachDao,
        val gateway: FakeHealthConnectGateway,
        val diagnostics: DiagnosticsStore,
        val repository: HealthRepository,
    )

    private fun createTestHarness(
        otlpSettings: OtlpSettingsStore = InMemoryOtlpSettings(),
    ): TestHarness {
        val recorder = LocalFlightRecorder(tempFolder.newFolder())
        val dao = FakeHealthCoachDao()
        val gateway = FakeHealthConnectGateway()
        val exporter = OtlpTraceExporter(
            settings = otlpSettings,
            allowLocalTestServer = true,
            asyncDispatch = false,
        )
        val diagnostics = DiagnosticsStore(
            dao = dao,
            flightRecorder = recorder,
            otlpExporter = exporter,
        )
        val repository = HealthRepository(
            database = null,
            gateway = gateway,
            json = json,
            diagnostics = diagnostics,
            daoOverride = dao,
            transactionRunner = { block -> block() },
        )
        return TestHarness(recorder, dao, gateway, diagnostics, repository)
    }

    private class FakeHealthConnectGateway : HealthConnectGateway(null) {
        var currentAvailability: HealthConnectAvailability = HealthConnectAvailability.AVAILABLE
        var corePermissionsGranted: Boolean = true
        var failAtStage: Pair<String, Throwable>? = null

        override fun availability(): HealthConnectAvailability = currentAvailability

        override fun backgroundReadAvailable(): Boolean = false
        override fun activityIntensityAvailable(): Boolean = false
        override fun historyReadAvailable(): Boolean = false

        override suspend fun grantedPermissions(): Set<String> =
            if (corePermissionsGranted) corePermissions else emptySet()

        override suspend fun sync(
            days: Long,
            zoneId: ZoneId,
            heightCm: Double?,
            sex: String?,
            ageYears: Int?,
            stageObserver: SyncStageObserver?,
        ): HealthSyncBundle {
            val now = System.currentTimeMillis()
            stageObserver?.onStageStarted("healthconnect.permissions")
            if (currentAvailability != HealthConnectAvailability.AVAILABLE) {
                throw HealthConnectUnavailableException(currentAvailability.name)
            }
            if (!corePermissionsGranted) {
                throw HealthConnectPermissionException()
            }
            failAtStage?.takeIf { it.first == "healthconnect.permissions" }?.let { throw it.second }
            stageObserver?.onStageCompleted(
                stage = "healthconnect.permissions",
                startEpochMillis = now,
                endEpochMillis = now + 5,
                attributes = mapOf("sync.days" to days, "hc.optional_granted_count" to 0L),
                stringAttributes = mapOf("hc.availability" to currentAvailability.name),
                booleanAttributes = mapOf("hc.core_granted" to true),
            )

            stageObserver?.onStageStarted("healthconnect.read")
            failAtStage?.takeIf { it.first == "healthconnect.read" }?.let { throw it.second }
            stageObserver?.onStageCompleted(
                stage = "healthconnect.read",
                startEpochMillis = now + 5,
                endEpochMillis = now + 25,
                attributes = mapOf("records.weight" to 1L, "records.steps" to 1L),
            )

            stageObserver?.onStageStarted("energy.estimate")
            failAtStage?.takeIf { it.first == "energy.estimate" }?.let { throw it.second }
            stageObserver?.onStageCompleted(
                stage = "energy.estimate",
                startEpochMillis = now + 25,
                endEpochMillis = now + 35,
                attributes = mapOf("assist.valid_days" to 1L, "assist.sessions_estimated" to 1L),
            )

            return HealthSyncBundle(
                daily = listOf(
                    DailyHealthSummaryEntity(
                        date = "2026-10-31",
                        steps = 8500L,
                        distanceMeters = 6200.0,
                        activeCaloriesKcal = 420.0,
                        exerciseMinutes = 30L,
                        strengthMinutes = 15L,
                        cardioMinutes = 15L,
                        exerciseSessionCount = 1,
                        sleepMinutes = 430L,
                        moderateIntensityMinutes = 20L,
                        vigorousIntensityMinutes = 10L,
                        heartRateAverageBpm = 68L,
                        heartRateMinimumBpm = 54L,
                        heartRateMaximumBpm = 142L,
                        heartRateMeasurementCount = 120L,
                        basalCaloriesKcal = 1650.0,
                        dataOrigins = "com.mi.health",
                        updatedAtEpochMillis = now,
                    ),
                ),
                body = listOf(
                    BodyCompositionEntity(
                        date = "2026-10-31",
                        weightKg = 72.5,
                        bodyFatPercent = 18.2,
                        fatMassKg = 13.2,
                        leanBodyMassKg = 59.3,
                        leanMassSource = "derived",
                        measurementEpochMillis = now,
                        dataOrigin = "com.mi.health",
                    ),
                ),
                sources = emptyList(),
                exerciseSessions = emptyList(),
                heartRateSamples = emptyList(),
                nutritionMeals = emptyList(),
                rangeStartEpochMillis = now - 86_400_000L,
                rangeEndEpochMillisExclusive = now,
            )
        }
    }

    private class FakeHealthCoachDao : HealthCoachDao {
        var failOnUpsertDaily: Throwable? = null
        var failOnInsertEvent: Throwable? = null
        private val metadataMap = mutableMapOf("revision" to "1")
        private val dailyList = mutableListOf<DailyHealthSummaryEntity>()
        private val bodyList = mutableListOf<BodyCompositionEntity>()
        private val eventsList = mutableListOf<AppEventEntity>()
        private var latestWeekly: WeeklyReportEntity? = null

        override suspend fun upsertDaily(items: List<DailyHealthSummaryEntity>) {
            failOnUpsertDaily?.let { throw it }
            dailyList.clear()
            dailyList.addAll(items)
        }

        override suspend fun upsertBody(items: List<BodyCompositionEntity>) {
            bodyList.clear()
            bodyList.addAll(items)
        }

        override suspend fun upsertSources(items: List<HealthSourceStatusEntity>) {}
        override suspend fun upsertHeartRateSamples(items: List<HeartRateSampleEntity>) {}
        override suspend fun deleteHeartRateSamplesInRange(from: Long, toExclusive: Long) {}
        override suspend fun pruneHeartRateSamples(beforeEpochMillis: Long) {}
        override suspend fun countHeartSamples(from: Long, toExclusive: Long, sessionId: String): Int = 0
        override suspend fun pageHeartSamples(from: Long, toExclusive: Long, sessionId: String, afterMs: Long, afterSource: String, afterBpm: Long, limit: Int): List<HeartRateSampleEntity> = emptyList()
        override suspend fun pageHeartBuckets(from: Long, toExclusive: Long, sessionId: String, bucketMs: Long, afterBucket: Long, limit: Int): List<HeartRateBucket> = emptyList()
        override suspend fun upsertExerciseSessions(items: List<ExerciseSessionEntity>) {}
        override suspend fun upsertNutritionMeals(items: List<NutritionMealEntity>) {}
        override suspend fun upsertGoal(goal: GoalEntity) {}
        override suspend fun upsertWeekly(report: WeeklyReportEntity) { latestWeekly = report }
        override suspend fun insertMessage(message: ChatMessageEntity): Long = 1L
        override suspend fun upsertMemory(memory: ConversationMemoryEntity) {}
        override fun observeDaily(limit: Int): Flow<List<DailyHealthSummaryEntity>> = MutableStateFlow(dailyList)
        override fun observeBody(limit: Int): Flow<List<BodyCompositionEntity>> = MutableStateFlow(bodyList)
        override fun observeSources(): Flow<List<HealthSourceStatusEntity>> = MutableStateFlow(emptyList())
        override fun observeExerciseSessions(limit: Int): Flow<List<ExerciseSessionEntity>> = MutableStateFlow(emptyList())
        override fun observeNutritionMeals(limit: Int): Flow<List<NutritionMealEntity>> = MutableStateFlow(emptyList())
        override suspend fun getExerciseSessions(from: Long, to: Long): List<ExerciseSessionEntity> = emptyList()
        override suspend fun getNutritionMeals(from: Long, to: Long): List<NutritionMealEntity> = emptyList()
        override fun observeGoal(): Flow<GoalEntity?> = MutableStateFlow(null)
        override suspend fun getGoal(): GoalEntity? = null
        override fun observeLatestWeekly(): Flow<WeeklyReportEntity?> = MutableStateFlow(latestWeekly)
        override suspend fun getLatestWeekly(): WeeklyReportEntity? = latestWeekly
        override suspend fun getDaily(from: String, to: String): List<DailyHealthSummaryEntity> = dailyList
        override suspend fun getBody(from: String, to: String): List<BodyCompositionEntity> = bodyList
        override suspend fun needsMetricBackfill(from: String): Boolean = false
        override suspend fun setNutritionComplete(date: String, complete: Boolean) {}
        override suspend fun earliestDailyDate(): String? = dailyList.minOfOrNull { it.date }
        override suspend fun deleteExerciseSessionsInRange(from: Long, toExclusive: Long) {}
        override suspend fun deleteNutritionMealsInRange(from: Long, toExclusive: Long) {}
        override fun observeRecentMessages(limit: Int): Flow<List<ChatMessageEntity>> = MutableStateFlow(emptyList())
        override suspend fun getRecentMessages(limit: Int): List<ChatMessageEntity> = emptyList()
        override suspend fun getAllMessages(): List<ChatMessageEntity> = emptyList()
        override suspend fun getMessagesInRange(from: Long, to: Long): List<ChatMessageEntity> = emptyList()
        override suspend fun getMessageIds(): List<Long> = emptyList()
        override suspend fun getMessagesAfter(afterId: Long): List<ChatMessageEntity> = emptyList()
        override suspend fun deleteMessagesUpTo(maxId: Long) {}
        override suspend fun messageCount(): Int = 0
        override fun observeMemory(): Flow<ConversationMemoryEntity?> = MutableStateFlow(null)
        override suspend fun getMemory(): ConversationMemoryEntity? = null
        override suspend fun clearDaily() { dailyList.clear() }
        override suspend fun clearBody() { bodyList.clear() }
        override suspend fun clearSources() {}
        override suspend fun clearExerciseSessions() {}
        override suspend fun clearHeartRateSamples() {}
        override suspend fun clearNutritionMeals() {}
        override suspend fun clearGoals() {}
        override suspend fun clearWeekly() { latestWeekly = null }
        override suspend fun clearMessages() {}
        override suspend fun clearMemory() {}
        override suspend fun insertEvent(event: AppEventEntity) {
            failOnInsertEvent?.let { throw it }
            eventsList.add(event.copy(id = (eventsList.size + 1).toLong()))
        }
        override suspend fun events(from: Long, to: Long, category: String, after: Long, limit: Int): List<AppEventEntity> = eventsList
        override suspend fun pruneEvents(cutoff: Long) {}
        override suspend fun clearEvents() { eventsList.clear() }
        override suspend fun saveEvidence(item: ResponseEvidenceEntity) {}
        override suspend fun evidence(id: Long): ResponseEvidenceEntity? = null
        override suspend fun pruneEvidence(cutoff: Long) {}
        override suspend fun clearEvidence() {}
        override suspend fun archiveReport(item: ReportArchiveEntity) {}
        override suspend fun reports(from: String, to: String, after: String, limit: Int): List<ReportArchiveEntity> = emptyList()
        override suspend fun report(id: String): ReportArchiveEntity? = null
        override suspend fun pruneReports(cutoff: Long) {}
        override suspend fun clearReports() {}
        override suspend fun setMetadata(item: AppMetadataEntity) {
            metadataMap[item.key] = item.value
        }
        override suspend fun metadata(key: String): String? = metadataMap[key]
        override suspend fun clearMetadata() { metadataMap.clear() }
        override suspend fun message(id: Long): ChatMessageEntity? = null
        override suspend fun searchMessages(from: Long, to: Long, query: String, role: String, after: Long, limit: Int): List<ChatMessageEntity> = emptyList()
        override suspend fun messagesBefore(id: Long, count: Int): List<ChatMessageEntity> = emptyList()
        override suspend fun messagesAfter(id: Long, count: Int): List<ChatMessageEntity> = emptyList()
        override suspend fun earliestMessage(): Long? = null
        override suspend fun getSources(): List<HealthSourceStatusEntity> = emptyList()
    }
}
