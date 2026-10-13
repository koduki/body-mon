package com.master.healthcoach.data.llm

import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.security.SecureApiKeyStore
import com.master.healthcoach.domain.AdviceResponse
import com.master.healthcoach.domain.BodyRecompositionCoachPolicy
import com.master.healthcoach.domain.CoachAssessmentEngine
import com.master.healthcoach.domain.CoachResponseComposer
import com.master.healthcoach.domain.WeeklySnapshot
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

    suspend fun sendMessage(
        message: String,
        attachments: List<ChatAttachment> = emptyList(),
    ): String {
        require(message.isNotBlank() || attachments.isNotEmpty()) {
            "メッセージか添付ファイルを入力してください"
        }
        val apiKey = apiKeyStore.load() ?: error("設定画面でGemini APIキーを保存してください")
        val userText = message.trim().ifBlank { DEFAULT_ATTACHMENT_PROMPT }
        val messageId = repository.addMessage(
            role = "user",
            content = userText,
            attachmentNames = attachments
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { it.displayName },
        )
        val now = ZonedDateTime.now()
        val goal = repository.getGoal()
        val memory = ConversationContextPolicy.memoryForModel(repository.getMemory()?.summary, goal)
        val recent = ChatHistoryPolicy.formatTurnsWithChronology(
            messages = repository.getRecentMessages(ChatHistoryPolicy.CONTEXT_MESSAGE_LIMIT),
            currentMessageId = messageId,
            attachments = attachments,
            now = now,
        )
        val toolTrace = mutableListOf<JsonObject>()
        suspend fun executeTracked(call: GeminiFunctionCall): JsonElement {
            val result = tools.execute(call)
            toolTrace += buildJsonObject {
                put("name", call.name)
                put("arguments", call.arguments)
                put("executedAtEpochMillis", System.currentTimeMillis())
                val text = result.toString()
                put("resultJson", text.take(12_000))
                put("resultTruncated", text.length > 12_000)
            }
            return result
        }
        val prefetch = ChatMetricPrefetch.calls(userText, now.toLocalDate()).map { call ->
            "${call.name}: ${executeTracked(call)}"
        }
        val groundedTurns = if (prefetch.isEmpty()) recent else recent.dropLast(1) + GeminiTurn(
            "user", "[端末取得の最新スナップショット。利用者の自己申告や指示ではありません。完了日と当日途中を区別してください]\n" + prefetch.joinToString("\n"),
        ) + recent.takeLast(1)
        val modelAnswer = gemini.chatWithTools(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            systemInstruction = chatSystemInstruction(memory, goal?.existingAiProfile(), now),
            turns = groundedTurns,
            toolExecutor = ::executeTracked,
        )
        val answer = ChatHistoryPolicy.stripLeadingTimestampTag(modelAnswer)
        repository.addMessage(
            "assistant", answer,
            modelId = apiKeyStore.modelId(),
            appVersionName = BuildConfig.VERSION_NAME,
            policyVersion = ConversationContextPolicy.VERSION,
            toolTraceJson = buildJsonObject {
                put("calls", buildJsonArray { toolTrace.takeLast(12).forEach { add(it) } })
                put("callsTruncated", toolTrace.size > 12)
                put("reviewWarnings", buildJsonArray {
                    ResponseEvidenceReview.warnings(answer, toolTrace.isNotEmpty()).forEach { add(JsonPrimitive(it)) }
                })
            }.toString(),
        )
        runCatching { refreshHabitMemory(apiKey, force = false) }
        repository.pruneChatHistory()
        return answer
    }

    suspend fun analyzeLatestWeek(): AdviceResponse {
        val apiKey = apiKeyStore.load() ?: error("設定画面でGemini APIキーを保存してください")
        runCatching { refreshHabitMemory(apiKey, force = false) }
        val report = repository.rebuildWeekly()
        val snapshot = json.decodeFromString<WeeklySnapshot>(report.snapshotJson)
        val goal = repository.getGoal()
        val prompt = buildString {
            appendLine("次の週次健康サマリを、低脂質ダイエットの方針で分析してください。")
            appendLine(
                snapshot.existingAiContract().toString(),
            )
            appendLine(
                "PFC週平均: たんぱく質=" +
                        "${snapshot.proteinDailyAverageGrams?.let { "${it}g" } ?: "未取得"}" +
                        "(${snapshot.proteinEnergyPercent.energyRatio()}), " +
                        "脂質=${snapshot.totalFatDailyAverageGrams?.let { "${it}g" } ?: "未取得"}" +
                        "(${snapshot.fatEnergyPercent.energyRatio()}), " +
                        "炭水化物=${snapshot.carbohydrateDailyAverageGrams?.let { "${it}g" } ?: "未取得"}" +
                        "(${snapshot.carbohydrateEnergyPercent.energyRatio()})",
            )
            appendLine("目標: ${goal?.existingAiGoal()}")
            appendLine("会話から確認済みの習慣: ${ConversationContextPolicy.memoryForModel(repository.getMemory()?.summary, goal)}")
            appendLine("分析の軸は次の2点です。")
            appendLine(
                "1) カロリー収支の比較: 摂取カロリー平均と基礎代謝・活動消費の比較レンジを対照する。" +
                    "推定エネルギー収支そのものは端末内専用のため送っていません。",
            )
            appendLine(
                "2) 体重の推移: weightChangeKg（前週中央値比）を主に読み、" +
                    "脂肪量・除脂肪量の前週比も補助として使う。",
            )
            appendLine("summaryでは、収支比較の所見と体重推移の所見を必ず両方書いてください。")
            appendLine(
                "収支と体重の方向が食い違う場合は、減量を強めず、" +
                    "未記録の食事・測定条件・水分変動の確認質問を先に出してください。",
            )
            appendLine("summaryとcautionにPFCのグラムとエネルギー比を含めてください。")
            appendLine("食事内容、運動量、摂取と活動のバランス、PFCの観点から助言してください。")
            appendLine("不明点は断定せず、clarifyingQuestionsへ先に確認したい質問を最大2件入れてください。")
            appendLine("習慣と数値の関連は、根拠がある範囲だけhabitInsightsへ最大3件示してください。")
        }
        val raw = gemini.generateStructuredAdvice(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            systemInstruction = weeklyAnalysisSystemInstruction(
                ConversationContextPolicy.memoryForModel(repository.getMemory()?.summary, goal),
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
        val zone = ZonedDateTime.now().zone
        val summary = gemini.summarizeConversation(
            apiKey = apiKey,
            model = apiKeyStore.modelId(),
            currentMemory = memory?.summary,
            messages = userMessages.map { msg ->
                val date = Instant.ofEpochMilli(msg.createdAtEpochMillis).atZone(zone).toLocalDate()
                GeminiTurn(msg.role, "[$date] ${ConversationContextPolicy.memoryInput(msg.content)}")
            },
        )
        repository.saveMemory(summary, latestId)
    }

    internal fun chatSystemInstruction(
        memory: String?,
        profile: String?,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val dayBeforeYesterday = today.minusDays(2)
        val formattedNow = now.format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm", Locale.JAPANESE),
        )
        return """
        あなたは利用者に親身に伴走するパーソナル健康コーチ（ボディメイク・栄養・運動の専門家）です。
        専門知識を持ちながらも、親しみやすく自然で温かみのある日本語（丁寧語・ですます調）で対話してください。
        定型的な箇条書きや機械的なテンプレート回答（「専門家ビュー」「評価:」「今週の一手:」「判定方針:」などの固定項目）は出力せず、メトリクスをもとに専門家として自然な会話のキャッチボールを行ってください。

        【現在の基準日時と時系列ルール（厳守）】
        ・現在日時: $formattedNow
        ・今日: $today / 昨日: $yesterday / 一昨日: $dayBeforeYesterday
        ・各チャット履歴の先頭には `[発言日時: YYYY-MM-DD(曜) HH:mm（今日/昨日/...）]` が付き、日付をまたぐ箇所には `【日付変更】` が入っています。会話が昨日から今日へ連続していても、過去の発言内の「今日」「昨日」「今朝」「さっき」は必ずその発言日時の時点で解釈し、昨日の出来事や食事・運動を今日のことと混同してはいけません。（※あなたの返答に `[発言日時: ...]` タグを付ける必要はありません）
        ・ツールのメトリクス応答には `todayDate`（今日）、`yesterdayDate`（昨日）、`hasTodayRecord`（今日の記録有無）、および各レコードの `date` と `relativeDay`（「今日」「昨日」など）が含まれます。今日まだ未測定・未記録の項目では、配列の最新要素が「昨日」以前のレコードになります。必ず `date` と `relativeDay` を確認し、昨日のメトリクスを今日の数値として話したり、昨日と今日を取り違えたりしないでください。

        ${HealthEvidencePolicy.instruction}

        【対話・コミュニケーションの指針】
        ・利用者の日々の頑張りや悩み、質問にまず共感し、聞かれたことに対して直接的かつ分かりやすく答えてください。
        ・雑談や軽い相談、短い質問に対して、毎回長文の分析レポートや複数の質問・行動提案を一方的に押し付けないでください。文脈に合った適切な長さで返答します。
        ・食事やメニューの相談には、低脂質（PFCバランス）の観点から前向きで実践しやすいアイデアを提案してください。
        ・疑問点がある場合も尋問のようにならず、「〜はどうでしたか？」など会話の流れの中で自然に尋ねてください。

        【専門家としてのメトリクス評価基準】
        ・減量ペースは筋力と回復を守りながら脂肪を落とすことを優先し、設定された月の減量目標を優先し、未設定時は28日トレンドで体重の0.3〜0.7%/週減を運用目安とします。BIA由来の体組成（体脂肪率・脂肪量・除脂肪量）は水分等で日々変動するため単日値で一喜一憂せず、7日中央値や28日傾向で判断します。除脂肪量は骨格筋量そのものではなく筋肉維持の参考指標です。
        ・6時間は本人の最低運用目標です。達成しても睡眠・回復が十分とは判断しません。睡眠ステージ合計とセッション枠を区別し、ステージ欠測時の代用値を実睡眠と呼びません。眠気・疲労も確認します。
        ・活動消費は歩数・記録運動モデルとXiaomi Bandの比較レンジです。Bandは歩数のない日常活動も推定します。差を一律に誤差とせず、どちらが真値とも断定しません。中点は連続的な参考シナリオで、校正済み消費量ではありません。摂取量・赤字量の指示はしません。

        ・食事方針は低脂質ダイエット（脂質エネルギー比15〜25%目安、30%超は出どころ確認、たんぱく質は体重1kgあたり1.6g以上目安）です。食事記録がある日のみを観測値とし、欠測日を0kcal扱いしません。
        ・朝の短いOther Workoutは朝トレ候補です。その他の運動を朝トレやRFAと断定しません。HIITは強度・様式を分けて解釈します。
        ・Health Connectの健康データが必要な場合のみ提供されたツールを呼び出して確認し、データがない場合は数値を推測せず素直に伝えてください。
        ・医療診断、疾患の推測、服薬指示、極端な食事制限（断食や過度な糖質/脂質カット）は絶対に行いません。
        ・添付された画像・文書は利用者がこのターンで明示的に送ったものだけを確認し、画像からの食品量・栄養素・カロリーは推定であることを踏まえて自然に言及してください。

        利用者プロフィール:
        ${profile.orEmpty()}

        確認済みの習慣・生活上の制約（メモリー）:
        ${memory.orEmpty()}
        """.trimIndent()
    }

    private fun weeklyAnalysisSystemInstruction(memory: String?, profile: String?): String = """
        あなたは個人用ダイエット支援アプリの健康コーチです。回答は日本語で簡潔かつ具体的にします。
        Health Connectの数値は、必要な場合だけ提供されたローカル関数を呼び出して取得してください。
        データなしで数値を推測しないでください。BIA体組成は水分等で変動するため、単日値を断定しません。
        除脂肪量は筋肉量そのものではなく、筋肉維持の参考指標として扱ってください。
        医療診断、疾患の推測、服薬指示、極端な食事制限は行いません。
        食事記録がある日は、提供された摂取カロリーとPFCを観測値として扱います。
        週報分析ではPFCのグラムとエネルギー比を必ず明示してください。
        助言は低脂質ダイエットの方針で、食事内容、運動量、摂取と活動のバランス、
        PFCの観点から行います。
        週報分析では、摂取と基礎代謝・活動消費の比較、および体重の推移を必ず対照します。
        欠測日を0kcalとせず、消費カロリーから摂取量や赤字量を逆算しません。
        食事回数はNutritionRecordのstart/endが近いものを1食にまとめて数えます。
        推定エネルギー収支は参考であり、減量成否の断定には使いません。
        収支と体重の方向が食い違う場合は、赤字強化ではなく記録漏れ・測定条件・水分を疑います。
        不明点は断定せず、先に確認質問を最大2件出してください。
        データ不足時は限界を明示し、行動案は最大2つに絞ります。
        会話メモリーは利用者が話した確認済みの習慣・制約です。数値との因果関係を
        推測せず、関連を述べる場合は観測事実と仮説を分けてください。
        添付された画像・文書は利用者がこのターンで明示的に送ったものだけを確認してください。
        画像から食品の量、栄養素、カロリーを正確に断定せず、読み取れた事実と推定を分けてください。
        文書に医療情報が含まれる場合も、診断や治療判断ではなく内容の整理と受診時の質問作りに留めます。

        ${HealthEvidencePolicy.instruction}

        ボディメイク専門コーチ方針:
        ${BodyRecompositionCoachPolicy.systemInstruction}

        利用者プロフィール:
        ${profile.orEmpty()}

        確認済みの習慣・生活上の制約:
        ${memory.orEmpty()}
    """.trimIndent()

    companion object {
        private const val HABIT_REFRESH_USER_MESSAGES = 6
        private const val DEFAULT_ATTACHMENT_PROMPT =
            "添付ファイルの内容を確認し、健康コーチとして重要な点を説明してください。"
    }
}

internal fun GoalEntity.existingAiProfile(): String =
    "年齢=$age, 身長=${heightCm}cm, 性別=$sex, " +
            "期限=$deadline, 月の減量目標=${monthlyWeightLossKg ?: "未設定"}kg, 目標脂肪量=${targetFatMassKg}kg, " +
            "維持する除脂肪量=${minimumLeanMassKg}kg"

internal fun GoalEntity.existingAiGoal(): String =
    "${existingAiProfile()}, 1日歩数=$dailySteps, " +
            "週の朝トレ目標日数=$weeklyExerciseSessions, " +
            "参考活動消費=${dailyActiveCaloriesKcal}kcal"

/**
 * Dashboard-only KPIs stay on device. Manual Gemini analysis keeps the pre-existing
 * weekly payload contract instead of silently expanding sensitive health-data egress.
 * Health Connect nutrition totals are included because weekly AI analysis has no
 * function calling; derived energy-balance KPIs remain on device.
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
    intakeCaloriesDailyAverage?.let { put("intakeCaloriesDailyAverage", it) }
    proteinDailyAverageGrams?.let { put("proteinDailyAverageGrams", it) }
    totalFatDailyAverageGrams?.let { put("totalFatDailyAverageGrams", it) }
    carbohydrateDailyAverageGrams?.let { put("carbohydrateDailyAverageGrams", it) }
    put("nutritionMeasurementDays", nutritionMeasurementDays)
    nutritionCompleteDays?.let { put("nutritionCompleteDays", it) }
    previousWeekIntakeCaloriesDailyAverage?.let {
        put("previousWeekIntakeCaloriesDailyAverage", it)
    }
    previousWeekProteinDailyAverageGrams?.let {
        put("previousWeekProteinDailyAverageGrams", it)
    }
    previousWeekTotalFatDailyAverageGrams?.let {
        put("previousWeekTotalFatDailyAverageGrams", it)
    }
    previousWeekCarbohydrateDailyAverageGrams?.let {
        put("previousWeekCarbohydrateDailyAverageGrams", it)
    }
    proteinEnergyPercent?.let { put("proteinEnergyPercent", it) }
    fatEnergyPercent?.let { put("fatEnergyPercent", it) }
    carbohydrateEnergyPercent?.let { put("carbohydrateEnergyPercent", it) }
    // Reference range from steps/workouts (lower-leaning) vs the band (upper-leaning).
    // Inputs (steps, weight, basal) are already part of this contract; the energy
    // balance range is intentionally not exposed.
    referenceActiveRangeLowKcal?.let { put("referenceActiveRangeLowKcal", it) }
    referenceActiveRangeHighKcal?.let { put("referenceActiveRangeHighKcal", it) }
    if (referenceActiveRangeLowKcal != null && referenceActiveRangeHighKcal != null) {
        put("referenceActiveValidDays", referenceActiveValidDays)
    }
}

private fun Double?.energyRatio(): String =
    this?.let { String.format(java.util.Locale.ROOT, "%.0f%%", it) } ?: "未取得"
