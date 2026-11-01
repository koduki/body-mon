package com.master.healthcoach

import android.app.Application
import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.db.HealthCoachDatabase
import com.master.healthcoach.data.diagnostics.DiagnosticsStore
import com.master.healthcoach.data.diagnostics.LocalFlightRecorder
import com.master.healthcoach.data.health.HealthConnectGateway
import com.master.healthcoach.data.llm.GeminiClient
import com.master.healthcoach.data.llm.HealthChatCoordinator
import com.master.healthcoach.data.security.SecureApiKeyStore
import com.master.healthcoach.work.SyncScheduler
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*

class HealthCoachApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installCrashCheckpointHook()
        SyncScheduler.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (container.diagnostics.enabled) {
                    container.diagnostics.reconcileAndExportStartupExits(this@HealthCoachApplication)
                }
                container.diagnostics.prune()
                container.diagnostics.otlpExporter.flushPending()
            }
        }
    }

    private fun installCrashCheckpointHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                if (this::container.isInitialized && container.diagnostics.enabled) {
                    container.flightRecorder.recordUncaughtException(throwable, threadName = thread.name)
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

class AppContainer(application: Application) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    val flightRecorder = LocalFlightRecorder(application)
    val database = HealthCoachDatabase.create(application, flightRecorder)
    val gateway = HealthConnectGateway(application)
    val diagnostics = DiagnosticsStore(application, database.dao(), flightRecorder)
    val tunnelSettings = com.master.healthcoach.data.mcp.TunnelSettings(application)
    val repository = HealthRepository(database, gateway, json, diagnostics)
    val apiKeyStore = SecureApiKeyStore(application)
    val geminiClient = GeminiClient(json)
    val chatCoordinator = HealthChatCoordinator(repository, geminiClient, apiKeyStore, json)
}

