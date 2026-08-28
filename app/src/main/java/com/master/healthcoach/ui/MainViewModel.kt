package com.master.healthcoach.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.master.healthcoach.HealthCoachApplication
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.ChatMessageEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import com.master.healthcoach.data.db.NutritionMealEntity
import com.master.healthcoach.data.db.WeeklyReportEntity
import com.master.healthcoach.data.health.HealthConnectAvailability
import com.master.healthcoach.data.llm.ChatAttachment
import com.master.healthcoach.data.llm.ChatAttachmentReader
import com.master.healthcoach.data.security.SecureApiKeyStore
import com.master.healthcoach.domain.AdviceResponse
import com.master.healthcoach.domain.WeeklySnapshot
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

data class MainUiState(
    val daily: List<DailyHealthSummaryEntity> = emptyList(),
    val body: List<BodyCompositionEntity> = emptyList(),
    val sources: List<HealthSourceStatusEntity> = emptyList(),
    val exerciseSessions: List<ExerciseSessionEntity> = emptyList(),
    val nutritionMeals: List<NutritionMealEntity> = emptyList(),
    val goal: GoalEntity? = null,
    val weekly: WeeklySnapshot? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val conversationMemory: String? = null,
    val availability: HealthConnectAvailability = HealthConnectAvailability.UNAVAILABLE,
    val grantedPermissions: Set<String> = emptySet(),
    val requiredPermissions: Set<String> = emptySet(),
    val corePermissions: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val isSending: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val modelId: String = SecureApiKeyStore.DEFAULT_MODEL,
    val weeklyAdvice: AdviceResponse? = null,
    val chatAttachments: List<ChatAttachment> = emptyList(),
    val isAddingAttachments: Boolean = false,
    val message: String? = null,
) {
    val hasCorePermissions: Boolean
        get() = corePermissions.all { it in grantedPermissions }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as HealthCoachApplication).container
    private val repository = container.repository
    private val attachmentReader = ChatAttachmentReader(application.contentResolver)
    private val transient = MutableStateFlow(
        MainUiState(
            availability = repository.availability(),
            requiredPermissions = repository.requestedPermissions(),
            corePermissions = repository.corePermissions(),
            apiKeyConfigured = container.apiKeyStore.hasKey(),
            modelId = container.apiKeyStore.modelId(),
        ),
    )

    private val healthData = combine(
        repository.daily,
        repository.body,
        repository.sources,
        combine(repository.exerciseSessions, repository.nutritionMeals) { sessions, meals ->
            sessions to meals
        },
    ) { daily, body, sources, sessionsAndMeals ->
        HealthData(daily, body, sources, sessionsAndMeals.first, sessionsAndMeals.second)
    }

    private val appData = combine(
        repository.goal,
        repository.latestWeekly,
        repository.messages,
        repository.memory,
    ) { goal, weekly, messages, memory ->
        AppData(goal, weekly, messages, memory?.summary?.takeIf { it.isNotBlank() })
    }

    val uiState: StateFlow<MainUiState> = combine(
        healthData,
        appData,
        transient,
    ) { health, app, local ->
        local.copy(
            daily = health.daily,
            body = health.body,
            sources = health.sources,
            exerciseSessions = health.exerciseSessions,
            nutritionMeals = health.nutritionMeals,
            goal = app.goal,
            weekly = app.weeklyEntity?.let {
                runCatching { container.json.decodeFromString<WeeklySnapshot>(it.snapshotJson) }
                    .getOrNull()
            },
            messages = app.messages,
            conversationMemory = app.conversationMemory,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), transient.value)

    init {
        refreshPermissions(syncWhenGranted = true)
        viewModelScope.launch {
            runCatching { repository.pruneChatHistory() }
        }
    }

    fun refreshPermissions(syncWhenGranted: Boolean = false) {
        viewModelScope.launch {
            val granted = runCatching { repository.grantedPermissions() }.getOrDefault(emptySet())
            transient.value = transient.value.copy(
                availability = repository.availability(),
                requiredPermissions = repository.requestedPermissions(),
                grantedPermissions = granted,
            )
            if (syncWhenGranted && repository.hasCorePermissions()) sync()
        }
    }

    fun sync() {
        if (transient.value.isSyncing) return
        viewModelScope.launch {
            update { it.copy(isSyncing = true, message = null) }
            runCatching { repository.sync() }
                .onSuccess { update { it.copy(message = "Health Connectを更新しました") } }
                .onFailure { error -> update { it.copy(message = error.userMessage()) } }
            update { it.copy(isSyncing = false) }
        }
    }

    fun saveGoal(goal: GoalEntity) {
        viewModelScope.launch {
            runCatching {
                validateGoal(goal)
                repository.saveGoal(goal)
            }
                .onSuccess { update { it.copy(message = "目標を保存しました") } }
                .onFailure { update { state -> state.copy(message = it.userMessage()) } }
        }
    }

    fun saveApiKey(apiKey: String, modelId: String) {
        runCatching {
            container.apiKeyStore.save(apiKey)
            container.apiKeyStore.saveModelId(modelId)
        }.onSuccess {
            update {
                it.copy(
                    apiKeyConfigured = true,
                    modelId = container.apiKeyStore.modelId(),
                    message = "Gemini API設定を保存しました",
                )
            }
        }.onFailure { error -> update { it.copy(message = error.userMessage()) } }
    }

    fun clearApiKey() {
        container.apiKeyStore.clear()
        update { it.copy(apiKeyConfigured = false, message = "APIキーを削除しました") }
    }

    fun sendChat(message: String) {
        val attachments = transient.value.chatAttachments
        if (
            (message.isBlank() && attachments.isEmpty()) ||
            transient.value.isSending ||
            transient.value.isAddingAttachments
        ) {
            return
        }
        viewModelScope.launch {
            update { it.copy(isSending = true, message = null) }
            runCatching { container.chatCoordinator.sendMessage(message, attachments) }
                .onSuccess {
                    update { state -> state.copy(chatAttachments = emptyList()) }
                }
                .onFailure { error -> update { it.copy(message = error.userMessage()) } }
            update { it.copy(isSending = false) }
        }
    }

    fun addChatAttachments(uris: List<Uri>) {
        if (
            uris.isEmpty() ||
            transient.value.isSending ||
            transient.value.isAddingAttachments
        ) {
            return
        }
        viewModelScope.launch {
            val existing = transient.value.chatAttachments
            update { it.copy(isAddingAttachments = true, message = null) }
            runCatching { attachmentReader.read(uris, existing) }
                .onSuccess { selection ->
                    update { state ->
                        state.copy(
                            chatAttachments = (state.chatAttachments + selection.attachments)
                                .distinctBy { it.id },
                            message = selection.warnings
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString("\n"),
                        )
                    }
                }
                .onFailure { error ->
                    update { it.copy(message = error.userMessage()) }
                }
            update { it.copy(isAddingAttachments = false) }
        }
    }

    fun removeChatAttachment(id: String) {
        if (transient.value.isSending) return
        update { state ->
            state.copy(chatAttachments = state.chatAttachments.filterNot { it.id == id })
        }
    }

    fun analyzeWeek() {
        if (transient.value.isSending) return
        viewModelScope.launch {
            update { it.copy(isSending = true, message = null) }
            runCatching { container.chatCoordinator.analyzeLatestWeek() }
                .onSuccess { advice -> update { it.copy(weeklyAdvice = advice) } }
                .onFailure { error -> update { it.copy(message = error.userMessage()) } }
            update { it.copy(isSending = false) }
        }
    }

    fun clearLocalData() {
        viewModelScope.launch {
            runCatching {
                repository.clearAll()
                container.apiKeyStore.clear()
            }.onSuccess {
                update {
                    it.copy(
                        weeklyAdvice = null,
                        apiKeyConfigured = false,
                        chatAttachments = emptyList(),
                        conversationMemory = null,
                        message = "端末内データを削除しました",
                    )
                }
            }.onFailure { error -> update { it.copy(message = error.userMessage()) } }
        }
    }

    fun consumeMessage() = update { it.copy(message = null) }

    private fun update(block: (MainUiState) -> MainUiState) {
        transient.value = block(transient.value)
    }

    private fun Throwable.userMessage(): String = message?.take(180) ?: "処理に失敗しました"

    private fun validateGoal(goal: GoalEntity) {
        require(goal.age == null || goal.age in 13..120) {
            "年齢は13〜120の範囲で入力してください"
        }
        require(goal.heightCm == null || goal.heightCm in 100.0..250.0) {
            "身長は100〜250cmの範囲で入力してください"
        }
        require(goal.targetFatMassKg == null || goal.targetFatMassKg > 0.0) {
            "目標脂肪量は0より大きい値にしてください"
        }
        require(goal.minimumLeanMassKg == null || goal.minimumLeanMassKg > 0.0) {
            "維持する除脂肪量は0より大きい値にしてください"
        }
        require(goal.dailySteps == null || goal.dailySteps in 1..100_000) {
            "歩数目標は1〜100,000歩の範囲で入力してください"
        }
        require(
            goal.weeklyExerciseSessions == null ||
                    goal.weeklyExerciseSessions in 0..7,
        ) {
            "週の朝トレ目標日数は0〜7日の範囲で入力してください"
        }
        require(
            goal.dailyActiveCaloriesKcal == null ||
                    goal.dailyActiveCaloriesKcal in 1.0..5_000.0,
        ) {
            "活動消費目標は1〜5,000kcalの範囲で入力してください"
        }
        require(
            goal.dietStartDate == null ||
                    runCatching { LocalDate.parse(goal.dietStartDate) }.isSuccess,
        ) {
            "減量開始日はYYYY-MM-DD形式で入力してください"
        }
        require(
            goal.deadline == null ||
                    runCatching { LocalDate.parse(goal.deadline) }.isSuccess,
        ) {
            "目標期限はYYYY-MM-DD形式で入力してください"
        }
    }

    private data class HealthData(
        val daily: List<DailyHealthSummaryEntity>,
        val body: List<BodyCompositionEntity>,
        val sources: List<HealthSourceStatusEntity>,
        val exerciseSessions: List<ExerciseSessionEntity>,
        val nutritionMeals: List<NutritionMealEntity>,
    )

    private data class AppData(
        val goal: GoalEntity?,
        val weeklyEntity: WeeklyReportEntity?,
        val messages: List<ChatMessageEntity>,
        val conversationMemory: String?,
    )
}
