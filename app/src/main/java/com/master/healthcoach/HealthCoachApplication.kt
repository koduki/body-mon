package com.master.healthcoach

import android.app.Application
import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.db.HealthCoachDatabase
import com.master.healthcoach.data.health.HealthConnectGateway
import com.master.healthcoach.data.llm.GeminiClient
import com.master.healthcoach.data.llm.HealthChatCoordinator
import com.master.healthcoach.data.security.SecureApiKeyStore
import com.master.healthcoach.work.SyncScheduler
import kotlinx.serialization.json.Json

class HealthCoachApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedule(this)
    }
}

class AppContainer(application: Application) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    val database = HealthCoachDatabase.create(application)
    val gateway = HealthConnectGateway(application)
    val repository = HealthRepository(database, gateway, json)
    val apiKeyStore = SecureApiKeyStore(application)
    val geminiClient = GeminiClient(json)
    val chatCoordinator = HealthChatCoordinator(repository, geminiClient, apiKeyStore, json)
}

