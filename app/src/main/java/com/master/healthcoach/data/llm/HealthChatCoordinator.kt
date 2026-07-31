package com.master.healthcoach.data.llm

import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.security.SecureApiKeyStore
import com.master.healthcoach.domain.AdviceResponse
import com.master.healthcoach.domain.BodyRecompositionCoachPolicy
import com.master.healthcoach.domain.CoachAssessmentEngine
import com.master.healthcoach.domain.CoachResponseComposer
import com.master.healthcoach.domain.WeeklySnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HealthChatCoordinator(
    private val repository: HealthRepository,
    private val gemini: GeminiClient,
    private val apiKeyStore: SecureApiKeyStore,
    private val json: Json,
) {
    private val tools = HealthToolExecutor(repository)

    suspend fun sendMessage(message: String): String {
        val apiKey = apiKeyStore.load() ?: error("設定画面でGemini APIキーを保存してください")
        repository.addMessage("user", message.trim())
        val memory = repository.getMemory()?.summary
        val goal = repository.getGoal()
        val recent = repository.getRecentMessages(CONTEXT_MESSAGE_LIMIT).map {
            GeminiTurn(it.role, it.content)
        }
        val modelAnswer = gemini.chatWithTools(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            systemInstruction = systemPrompt(memory, goal?.existingAiProfile()),
            turns = recent,
            toolExecutor = tools::execute,
        )
        val snapshot = latestSnapshot()
        val answer = snapshot?.let {
            CoachResponseComposer.appendToChat(
                modelAnswer = modelAnswer,
                assessment = CoachAssessmentEngine.assess(it),
            )
        } ?: modelAnswer
        repository.addMessage("assistant", answer)
        refreshHabitMemory(apiKey, force = false)
        return answer
    }

    suspend fun analyzeLatestWeek(): AdviceResponse {
        val apiKey = apiKeyStore.load() ?: error("設定画面でGemini APIキーを保存してください")
        refreshHabitMemory(apiKey, force = true)
        val report = repository.getLatestWeekly() ?: repository.rebuildWeekly()
        val snapshot = json.decodeFromString<WeeklySnapshot>(report.snapshotJson)
        val goal = repository.getGoal()
        val prompt = buildString {
            appendLine("次の週次健康サマリを分析してください。")
            appendLine(
                snapshot.existingAiContract().toString(),
            )
            appendLine("目標: ${goal?.existingAiGoal()}")
            appendLine("会話から確認済みの習慣: ${repository.getMemory()?.summary.orEmpty()}")
            appendLine("習慣と数値の関連は、根拠がある範囲だけhabitInsightsへ最大3件示してください。")
        }
        val raw = gemini.generateStructuredAdvice(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            systemInstruction = systemPrompt(
                repository.getMemory()?.summary,
                goal?.existingAiProfile(),
            ),
            prompt = prompt,
        )
        val modelAdvice = json.decodeFromString<AdviceResponse>(raw)
        return CoachResponseComposer.mergeStructuredAdvice(
            modelAdvice = modelAdvice,
            assessment = CoachAssessmentEngine.assess(snapshot),
        )
    }

    private suspend fun refreshHabitMemory(apiKey: String, force: Boolean) {
        val memory = repository.getMemory()
        val unsummarized = repository.getMessagesAfter(memory?.summarizedThroughMessageId ?: 0)
        val userMessages = unsummarized.filter { it.role == "user" }
        if (userMessages.isEmpty()) return
        if (!force && userMessages.size < HABIT_REFRESH_USER_MESSAGES) return
        val latestId = unsummarized.maxOf { it.id }
        val summary = gemini.summarizeConversation(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            currentMemory = memory?.summary,
            messages = userMessages.map { GeminiTurn(it.role, it.content) },
        )
        repository.saveMemory(summary, latestId)
    }

    private suspend fun latestSnapshot(): WeeklySnapshot? = runCatching {
        val report = repository.getLatestWeekly() ?: repository.rebuildWeekly()
        json.decodeFromString<WeeklySnapshot>(report.snapshotJson)
    }.getOrNull()

    private fun systemPrompt(memory: String?, profile: String?): String = """
        あなたは個人用ダイエット支援アプリの健康コーチです。回答は日本語で簡潔かつ具体的にします。
        Health Connectの数値は、必要な場合だけ提供されたローカル関数を呼び出して取得してください。
        データなしで数値を推測しないでください。BIA体組成は水分等で変動するため、単日値を断定しません。
        除脂肪量は筋肉量そのものではなく、筋肉維持の参考指標として扱ってください。
        医療診断、疾病の推測、服薬指示、極端な食事制限は行いません。
        食事記録はないため一般的な食事改善だけ提案でき、摂取カロリーや赤字量を断定しません。
        データ不足時は限界を明示し、行動案は最大2つに絞ります。
        会話メモリーは利用者が話した確認済みの習慣・制約です。数値との因果関係を
        推測せず、関連を述べる場合は観測事実と仮説を分けてください。

        ボディメイク専門コーチ方針:
        ${BodyRecompositionCoachPolicy.systemInstruction}

        利用者プロフィール:
        ${profile.orEmpty()}

        確認済みの習慣・生活上の制約:
        ${memory.orEmpty()}
    """.trimIndent()

    companion object {
        private const val CONTEXT_MESSAGE_LIMIT = 20
        private const val HABIT_REFRESH_USER_MESSAGES = 6
    }
}

internal fun GoalEntity.existingAiProfile(): String =
    "年齢=$age, 身長=${heightCm}cm, 性別=$sex, " +
        "期限=$deadline, 目標脂肪量=${targetFatMassKg}kg, " +
        "維持する除脂肪量=${minimumLeanMassKg}kg"

internal fun GoalEntity.existingAiGoal(): String =
    "${existingAiProfile()}, 1日歩数=$dailySteps, " +
        "週の筋トレ日数=$weeklyExerciseSessions, " +
        "参考活動消費=${dailyActiveCaloriesKcal}kcal"

/**
 * Dashboard-only KPIs stay on device. Manual Gemini analysis keeps the pre-existing
 * weekly payload contract instead of silently expanding sensitive health-data egress.
 */
internal fun WeeklySnapshot.existingAiContract(): JsonObject = buildJsonObject {
    put("weekStart", weekStart)
    put("weekEnd", weekEnd)
    fatMassChangeKg?.let { put("fatMassChangeKg", it) }
    leanMassChangeKg?.let { put("leanMassChangeKg", it) }
    weightChangeKg?.let { put("weightChangeKg", it) }
    put("bodyMeasurementDays", bodyMeasurementDays)
    stepsDailyAverage?.let { put("stepsDailyAverage", it) }
    activeCaloriesDailyAverage?.let { put("activeCaloriesDailyAverage", it) }
    put("exerciseSessions", exerciseSessions)
    put("exerciseMinutes", exerciseMinutes)
    put("strengthMinutes", strengthMinutes)
    put("cardioMinutes", cardioMinutes)
    previousWeekStepsDailyAverage?.let { put("previousWeekStepsDailyAverage", it) }
    previousWeekActiveCaloriesDailyAverage?.let {
        put("previousWeekActiveCaloriesDailyAverage", it)
    }
    put("dataLimitations", buildJsonArray {
        dataLimitations.forEach { add(JsonPrimitive(it)) }
    })
    sleepDailyAverageMinutes?.let { put("sleepDailyAverageMinutes", it) }
    put("sleepMeasurementDays", sleepMeasurementDays)
    moderateIntensityMinutes?.let { put("moderateIntensityMinutes", it) }
    vigorousIntensityMinutes?.let { put("vigorousIntensityMinutes", it) }
    heartRateAverageBpm?.let { put("heartRateAverageBpm", it) }
    heartRateMinimumBpm?.let { put("heartRateMinimumBpm", it) }
    heartRateMaximumBpm?.let { put("heartRateMaximumBpm", it) }
    put("heartRateMeasurementDays", heartRateMeasurementDays)
    basalCaloriesDailyAverage?.let { put("basalCaloriesDailyAverage", it) }
    put("basalCaloriesMeasurementDays", basalCaloriesMeasurementDays)
    previousWeekSleepDailyAverageMinutes?.let {
        put("previousWeekSleepDailyAverageMinutes", it)
    }
    previousWeekModerateIntensityMinutes?.let {
        put("previousWeekModerateIntensityMinutes", it)
    }
    previousWeekVigorousIntensityMinutes?.let {
        put("previousWeekVigorousIntensityMinutes", it)
    }
    previousWeekHeartRateAverageBpm?.let {
        put("previousWeekHeartRateAverageBpm", it)
    }
    previousWeekBasalCaloriesDailyAverage?.let {
        put("previousWeekBasalCaloriesDailyAverage", it)
    }
}
