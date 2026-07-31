package com.master.healthcoach.data.llm

import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.security.SecureApiKeyStore
import com.master.healthcoach.domain.AdviceResponse
import com.master.healthcoach.domain.WeeklySnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

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
        val answer = gemini.chatWithTools(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            systemInstruction = systemPrompt(memory, goal?.let {
                "年齢=${it.age}, 身長=${it.heightCm}cm, 性別=${it.sex}, " +
                    "期限=${it.deadline}, 目標脂肪量=${it.targetFatMassKg}kg, " +
                    "維持する除脂肪量=${it.minimumLeanMassKg}kg"
            }),
            turns = recent,
            toolExecutor = tools::execute,
        )
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
            appendLine(json.encodeToString(WeeklySnapshot.serializer(), snapshot))
            appendLine("目標: $goal")
            appendLine("会話から確認済みの習慣: ${repository.getMemory()?.summary.orEmpty()}")
            appendLine("習慣と数値の関連は、根拠がある範囲だけhabitInsightsへ最大3件示してください。")
        }
        val raw = gemini.generateStructuredAdvice(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            systemInstruction = systemPrompt(repository.getMemory()?.summary, goal?.toString()),
            prompt = prompt,
        )
        return json.decodeFromString(raw)
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

    private fun systemPrompt(memory: String?, profile: String?): String = """
        あなたは個人用ダイエット支援アプリの健康コーチです。回答は日本語で簡潔かつ具体的にします。
        Health Connectの数値は、必要な場合だけ提供されたローカル関数を呼び出して取得してください。
        データなしで数値を推測しないでください。BIA体組成は水分等で変動するため、単日値を断定しません。
        除脂肪量は筋肉量そのものではなく、筋肉維持の参考指標として扱ってください。
        医療診断、疾病の推測、服薬指示、極端な食事制限は行いません。
        食事記録はないため一般的な食事改善だけ提案でき、摂取カロリーや赤字量を断定しません。
        データ不足時は限界を明示し、行動案は最大3つに絞ります。
        会話メモリーは利用者が話した確認済みの習慣・制約です。数値との因果関係を
        推測せず、関連を述べる場合は観測事実と仮説を分けてください。

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
