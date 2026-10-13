package com.master.healthcoach.data.llm

import com.master.healthcoach.data.HealthRepository
import com.master.healthcoach.domain.EnergyModel
import com.master.healthcoach.domain.NutritionMacros
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HealthToolExecutor(
    private val repository: HealthRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun execute(call: GeminiFunctionCall, userMessage: String? = null): JsonElement = when (call.name) {
        "get_body_composition" -> body(call)
        "get_activity_summary" -> activity(call)
        "get_exercise_summary" -> exercise(call)
        "get_sleep_summary" -> sleep(call)
        "get_heart_rate_summary" -> heartRate(call)
        "get_activity_intensity_summary" -> intensity(call)
        "get_metabolism_summary" -> metabolism(call)
        "get_nutrition_summary" -> nutrition(call)
        "get_goal_progress" -> goals()
        "update_profile" -> updateProfile(call, userMessage)
        else -> buildJsonObject { put("error", "未対応の関数: ${call.name}") }
    }

    private suspend fun updateProfile(call: GeminiFunctionCall, userMessage: String?): JsonElement {
        return try {
            val updated = ConversationProfileUpdate.apply(repository.getGoal(), call.arguments, userMessage)
            repository.saveGoal(updated)
            buildJsonObject {
                put("status", "saved")
                put("profile", updated.existingAiProfile())
                put("message", "会話のプロフィールを設定へ反映しました。設定画面で修正できます")
            }
        } catch (error: IllegalArgumentException) {
            buildJsonObject { put("error", error.message ?: "プロフィールを更新できません") }
        }
    }

    private fun range(call: GeminiFunctionCall): Pair<LocalDate, LocalDate> {
        val today = todayProvider()
        val to = call.arguments["to"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        val from = call.arguments["from"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: to.minusDays(27)
        return if (from <= to) from to to else to to from
    }

    private fun JsonObjectBuilder.putChronologyHeader(
        from: LocalDate,
        to: LocalDate,
        measuredDates: Set<String>,
    ) {
        val today = todayProvider()
        val yesterday = today.minusDays(1)
        put("todayDate", today.toString())
        put("yesterdayDate", yesterday.toString())
        put("from", from.toString())
        put("to", to.t…24276 tokens truncated…  subValue = compareDiff(
                            report.moderateEquivalentMinutes,
                            report.previousWeekModerateEquivalentMinutes,
                        )?.let { "前週比 ${signed(it.toDouble())}分" }
                            ?: "中強度＋高強度×2",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "朝トレ継続",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${report.morningRoutineDays}/" +
                                    "${report.morningRoutineTargetDays}日",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        val routineFraction = if (report.morningRoutineTargetDays > 0) {
                            report.morningRoutineDays.toFloat() /
                                report.morningRoutineTargetDays
                        } else {
                            0f
                        }
                        LinearProgressIndicator(
                            progress = { routineFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "前週 ${report.previousWeekMorningRoutineDays}日",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "計 ${report.morningRoutineMinutes}分",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Health Connectの「その他」を朝の5分ルーティンとして数えます。" +
                                "軽い筋トレ＋有酸素ですが、実際の筋力は月1回の" +
                                "パーソナルで同じ種目の負荷・回数を確認してください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "リカバリーシグナル",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "睡眠時間・代用値",
                                mainValue = report.sleepDailyAverageMinutes?.asHoursMinutes() ?: "未取得",
                                subValue = compareDiff(report.sleepDailyAverageMinutes, report.previousWeekSleepDailyAverageMinutes)?.let {
                                    "前週比 ${signed(it.toDouble())}分"
                                } ?: "前週データなし",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "睡眠中心拍",
                                mainValue = report.sleepHeartRateAverageBpm?.let { "$it bpm" }
                                    ?: "未取得",
                                subValue = report.sleepHeartRateBaselineDeltaBpm?.let {
                                    "直前21日比 ${signed(it.toDouble())} bpm"
                                } ?: "比較データ不足",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "6時間以上",
                                mainValue = report.sleepTargetHitDays?.let { "$it / ${report.sleepMeasurementDays}測定日" }
                                    ?: "未取得",
                                subValue = "本人の最低目標・回復十分の判定ではない",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "就寝時刻のばらつき",
                                mainValue = report.sleepScheduleDeviationMinutes?.let { "$it 分" }
                                    ?: "判定保留",
                                subValue = "中央値からの代表偏差",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "測定の品質",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "体組成の時刻統一",
                                mainValue = report.measurementTimeConsistencyPercent?.let {
                                    "$it%"
                                } ?: "判定保留",
                                subValue = "中央値±90分以内",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "28日計測日",
                                mainValue = "${report.trendMeasurementDays}日",
                                subValue = "8回・14日以上で判定",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "寝起き・トイレ後・飲食前・同程度の服装で統一。" +
                                "BIAの脂肪量・除脂肪量は単日値で判断しません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "食事・栄養",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        DailyPfcSummary(
                            title = "7日平均のPFC",
                            subtitle = "低脂質の運用目安は脂質エネルギー比15〜25%、たんぱく質は15%以上",
                            balance = report.takeIf { it.nutritionMeasurementDays > 0 }?.let {
                                PfcBalance.from(
                                    it.intakeCaloriesDailyAverage,
                                    it.proteinDailyAverageGrams,
                                    it.totalFatDailyAverageGrams,
                                    it.carbohydrateDailyAverageGrams,
                                )
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "食事回数",
                                mainValue = report.mealCountDailyAverage?.let {
                                    "${DecimalFormat("0.0").format(it)} 回/日"
                                } ?: "未取得",
                                subValue = "start/endクラスタ",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "摂取カロリー",
                                mainValue = report.intakeCaloriesDailyAverage.kcal("/日"),
                                subValue = compareDiff(
                                    report.intakeCaloriesDailyAverage,
                                    report.previousWeekIntakeCaloriesDailyAverage,
                                )?.let { "前週比 ${signed(it)} kcal" }
                                    ?: "記録 ${report.nutritionMeasurementDays}日",
                                icon = Icons.Default.Restaurant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "たんぱく質",
                                mainValue = report.proteinDailyAverageGrams.grams("/日"),
                                subValue = report.proteinEnergyPercent.energyPercentLabel()
                                    ?: compareDiff(
                                        report.proteinDailyAverageGrams,
                                        report.previousWeekProteinDailyAverageGrams,
                                    )?.let { "前週比 ${signed(it)} g" }
                                    ?: "あすけん由来の記録",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "脂質",
                                mainValue = report.totalFatDailyAverageGrams.grams("/日"),
                                subValue = report.fatEnergyPercent.energyPercentLabel()
                                    ?: compareDiff(
                                        report.totalFatDailyAverageGrams,
                                        report.previousWeekTotalFatDailyAverageGrams,
                                    )?.let { "前週比 ${signed(it)} g" }
                                    ?: "昨日までの7日平均",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "炭水化物",
                                mainValue = report.carbohydrateDailyAverageGrams.grams("/日"),
                                subValue = report.carbohydrateEnergyPercent.energyPercentLabel()
                                    ?: compareDiff(
                                        report.carbohydrateDailyAverageGrams,
                                        report.previousWeekCarbohydrateDailyAverageGrams,
                                    )?.let { "前週比 ${signed(it)} g" }
                                    ?: "昨日までの7日平均",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        val balanceText = if (report.referenceEnergyBalanceLowKcal != null && report.referenceEnergyBalanceHighKcal != null) {
                            EnergyRange(report.referenceEnergyBalanceLowKcal, report.referenceEnergyBalanceHighKcal).balanceRangeLabel() + "/日（モデル・デバイス比較レンジ）"
                        } else {
                            report.estimatedEnergyBalanceDailyAverage?.let { "${signed(it)} kcal/日（バンド値）" } ?: "未取得"
                        }
                        Text(
                            "推定収支 $balanceText。摂取−(基礎代謝+活動消費)で、モデルとデバイス推定の比較レンジです。" +
                                "あすけんが書き出すのは摂取カロリーとPFCのみです。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("参考値の内訳", fontWeight = FontWeight.SemiBold)
                        val activeText = if (report.referenceActiveRangeLowKcal != null && report.referenceActiveRangeHighKcal != null) {
                            EnergyRange(report.referenceActiveRangeLowKcal, report.referenceActiveRangeHighKcal).activeRangeLabel() + "/日（モデル・デバイス比較レンジ）"
                        } else {
                            report.activeCaloriesDailyAverage.kcal("/日")
                        }
                        Text(
                            "基礎代謝 ${
                                report.basalCaloriesDailyAverage.kcal("/日")
                            }・活動消費 $activeText",
                        )
                        Text(
                            "上の「カロリー収支と体重の対照」で摂取との差を見ます。" +
                                "Xiaomiは歩数のない活動も推定します。モデルとデバイスの差を比較し、真値や信頼区間とは扱いません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (report.adaptiveTdeeLowKcal != null && report.adaptiveTdeeHighKcal != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text("体重トレンド逆算（適応型検証・28日）", fontWeight = FontWeight.SemiBold)
                            Text(
                                "実効総消費 ${report.adaptiveTdeeLowKcal.roundToLong()} 〜 ${report.adaptiveTdeeHighKcal.roundToLong()} kcal/日" +
                                    "（食事記録 ${report.adaptiveTdeeIntakeDays}日分に基づく推計）",
                            )
                            Text(
                                "28日間の食事記録と体重トレンドから逆算した参考値です。" +
                                    "バンド推定や歩数換算との乖離を振り返るための事後検証用で、摂取量の指示ではありません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (report.dataLimitations.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("データ補足・注意点", fontWeight = FontWeight.SemiBold)
                            }
                            report.dataLimitations.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onAnalyzeWeek,
                    enabled = state.apiKeyConfigured && !state.isSending,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (state.isSending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.apiKeyConfigured) {
                            "専門コーチで今週を分析"
                        } else {
                            "設定でAPIキーを登録してください"
                        },
                    )
                }
            }
        }

        state.weeklyAdvice?.let { advice ->
            item {
                SectionHeader(
                    "専門コーチ分析",
                    "カロリー収支の比較と体重推移を軸に、低脂質・PFCも確認",
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(advice.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        HorizontalDivider()
                        advice.positiveChange?.let { AdviceBlock("よかった変化", it) }
                        advice.caution?.let { AdviceBlock("注意点", it) }
                        if (advice.habitInsights.isNotEmpty()) {
                            Text("習慣との関連", fontWeight = FontWeight.SemiBold)
                            advice.habitInsights.forEach { Text("・$it") }
                        }
                        if (advice.clarifyingQuestions.isNotEmpty()) {
                            Text("先に確認したいこと", fontWeight = FontWeight.SemiBold)
                            advice.clarifyingQuestions.forEachIndexed { index, question ->
                                Text("${index + 1}. $question")
                            }
                        }
                        if (advice.nextActions.isNotEmpty()) {
                            Text("次の一手", fontWeight = FontWeight.SemiBold)
                            advice.nextActions.forEachIndexed { index, action ->
                                Text("${index + 1}. $action")
                            }
                        }
                        if (advice.dataLimitations.isNotEmpty()) {
                            Text("判定の限界", fontWeight = FontWeight.SemiBold)
                            advice.dataLimitations.take(3).forEach { Text("・$it") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            SuggestionChip(onClick = {}, label = { Text("確からしさ: ${advice.confidence}") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    onSaveGoal: (GoalEntity) -> Unit,
    onSaveApiKey: (String, String) -> Unit,
    onClearApiKey: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onExportData: (Uri) -> Unit,
    onConfirmNutritionComplete: (String, Boolean) -> Unit,
    onClearLocalData: () -> Unit,
) {
    var age by rememberSaveable(state.goal) { mutableStateOf(state.goal?.age?.toString().orEmpty()) }
    var height by rememberSaveable(state.goal) { mutableStateOf(state.goal?.heightCm.clean().orEmpty()) }
    var sex by rememberSaveable(state.goal) { mutableStateOf(state.goal?.sex.orEmpty()) }
    var dietStartDate by rememberSaveable(state.goal) {
        mutableStateOf(state.goal?.dietStartDate.orEmpty())
    }
    var deadline by rememberSaveable(state.goal) { mutableStateOf(state.goal?.deadline.orEmpty()) }
    var fatTarget by rememberSaveable(state.goal) { mutableStateOf(state.goal?.targetFatMassKg.clean().orEmpty()) }
    var leanMinimum by rememberSaveable(state.goal) { mutableStateOf(state.goal?.minimumLeanMassKg.clean().orEmpty()) }
    var steps by rememberSaveable(state.goal) { mutableStateOf(state.goal?.dailySteps?.toString().orEmpty()) }
    var sessions by rememberSaveable(state.goal) { mutableStateOf(state.goal?.weeklyExerciseSessions?.toString().orEmpty()) }
    var monthlyLoss by rememberSaveable(state.goal) { mutableStateOf(state.goal?.monthlyWeightLossKg.clean().orEmpty()) }
    var calories by rememberSaveable(state.goal) { mutableStateOf(state.goal?.dailyActiveCaloriesKcal.clean().orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable(state.modelId) { mutableStateOf(state.modelId) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            onExportData(uri)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 10.dp, 16.dp, 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("プロフィールと目標", "会話で伝えた年齢・身長・性別も反映されます。違う場合はここで修正できます")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(age, { age = it }, "年齢", Modifier.weight(1f), KeyboardType.Number)
            SmallField(height, { height = it }, "身長 cm", Modifier.weight(1f), KeyboardType.Decimal)
            SmallField(sex, { sex = it }, "性別", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(
                dietStartDate,
                { dietStartDate = it },
                "減量開始日 YYYY-MM-DD",
                Modifier.weight(1f),
            )
            SmallField(
                deadline,
                { deadline = it },
                "目標期限 YYYY-MM-DD",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(fatTarget, { fatTarget = it }, "目標脂肪量 kg", Modifier.weight(1f), KeyboardType.Decimal)
            SmallField(leanMinimum, { leanMinimum = it }, "維持する除脂肪量 kg", Modifier.weight(1f), KeyboardType.Decimal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(steps, { steps = it }, "1日歩数", Modifier.weight(1f), KeyboardType.Number)
            SmallField(
                sessions,
                { sessions = it },
                "週の朝トレ目標日数",
                Modifier.weight(1f),
                KeyboardType.Number,
            )
        }
        SmallField(monthlyLoss, { monthlyLoss = it }, "月の減量目標 kg（任意）", Modifier.fillMaxWidth(), KeyboardType.Decimal)
        if (state.goal?.deadline?.let { runCatching { LocalDate.parse(it).isBefore(LocalDate.now()) }.getOrDefault(false) } == true) {
            Text("目標期限を過ぎています。現在の期限と減量ペースをご確認ください")
        }
        ConversationContextPolicy.profileConflicts(state.conversationMemory, state.goal).forEach { Text(it) }
        SmallField(
            calories,
            { calories = it },
            "参考：1日の活動消費目標 kcal",
            Modifier.fillMaxWidth(),
            KeyboardType.Decimal,
        )
        Text(
            "体組成は寝起き・トイレ後・飲食前・同程度の服装で測定すると、" +
                "28日トレンドのノイズを抑えられます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                onSaveGoal(
                    GoalEntity(
                        age = age.toIntOrNull(),
                        heightCm = height.toDoubleOrNull(),
                        sex = sex.ifBlank { null },
                        dietStartDate = dietStartDate.ifBlank { null },
                        deadline = deadline.ifBlank { null },
                        targetFatMassKg = fatTarget.toDoubleOrNull(),
                        minimumLeanMassKg = leanMinimum.toDoubleOrNull(),
                        dailySteps = steps.toLongOrNull(),
                        weeklyExerciseSessions = sessions.toIntOrNull(),
                        dailyActiveCaloriesKcal = calories.toDoubleOrNull(),
                        monthlyWeightLossKg = monthlyLoss.toDoubleOrNull()?.takeIf { it > 0 },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("目標を保存") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Gemini API", "キーはAndroid Keystoreで暗号化し、この端末だけに保存します")
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.apiKeyConfigured) "新しいAPIキーで置き換える" else "Gemini APIキー") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        SmallField(model, { model = it }, "モデルID", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSaveApiKey(apiKey, model); apiKey = "" },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("API設定を保存") }
            if (state.apiKeyConfigured) {
                OutlinedButton(onClick = onClearApiKey) { Text("キーを削除") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("実機データ診断", "Mi Fitness、eufy、あすけんが実際に書いた項目と、栄養の書込粒度を確認します")
        state.sources.forEach { source ->
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.recordType, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${source.recordCount}件・${source.origins.ifBlank { "提供元なし" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        source.latestRecordEpochMillis?.let {
                            Text("最新 ${formatInstant(it)}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(source.status, style = MaterialTheme.typography.labelSmall)
                    }
                    val healthy = source.status == "取得可能" || source.status == "計算可能"
                    Icon(
                        if (healthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                        null,
                        tint = if (healthy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
        if (state.sources.isEmpty()) Text("同期後に診断結果が表示されます。")
        if (!state.grantedPermissions.containsAll(state.requiredPermissions)) {
            Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("睡眠・心拍・栄養・履歴などの権限を許可")
            }
        }
        OutlinedButton(onClick = onOpenHealthConnect, modifier = Modifier.fillMaxWidth()) {
            Text("Health Connectの権限を管理")
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader(
            "会話メモリー",
            "チャット画面は直近20件を表示し、28日分の履歴を保持します。古い会話は習慣・制約の要約としてここに残ります",
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = state.conversationMemory
                    ?: "まだ要約はありません。チャットを続けると、確認した習慣や制約がここに集約されます。",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.conversationMemory == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("食事記録の範囲", "酒・間食も含め全食を記録した日だけチェックします。未確認日は収支を判定しません")
        state.daily.filter { it.intakeCaloriesKcal != null }.take(28).forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = day.nutritionComplete,
                    onCheckedChange = { onConfirmNutritionComplete(day.date, it) },
                )
                Text("${day.date} 全食記録済み")
            }
        }
        SectionHeader("データ管理", "28日間の実測データと会話履歴のJSON出力・端末内データの削除")
        Button(
            onClick = { exportLauncher.launch(HealthDataExporter.defaultFileName()) },
            enabled = !state.isExporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Description, null)
            }
            Spacer(Modifier.width(8.dp))
            Text("28日間の実測データと会話履歴をJSON出力")
        }
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("アプリ内データとAPIキーを削除")
        }
        Text(
            "Health Connect内の原データは削除しません。医療診断を目的とするアプリではありません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("アプリ情報", "現在インストールされているアプリのバージョン")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("バージョン", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = HealthDataExporter.formatAppVersionLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("端末内データを削除しますか？") },
            text = { Text("目標、集計、週報、AI会話、APIキーが削除されます。Health Connectの原データは残ります。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onClearLocalData() }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)) }
                Text(title, style = MaterialTheme.typography.labelLarge)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class TrendPoint(
    val date: LocalDate,
    val value: Double?,
    val details: List<String> = emptyList(),
)

@Composable
private fun TrendCard(
    title: String,
    unit: String,
    points: List<TrendPoint>,
    overlayPoints: List<TrendPoint> = emptyList(),
    summary: String? = null,
) {
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val measured = points.mapNotNull { it.value }
    val selected = selectedIndex?.let { points.getOrNull(it) }
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    measured.lastOrNull()?.let {
                        "${DecimalFormat("0.#").format(it)} $unit"
                    } ?: "未取得",
                )
            }
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (measured.size < 2) {
                Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
                    Text("表示に十分なデータがありません", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                InteractiveSparkline(
                    points = points,
                    overlayPoints = overlayPoints,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                    modifier = Modifier.fillMaxWidth().height(108.dp),
                )
                selected?.let { point ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(10.dp),
                    ) {
                        Text(
                            point.date.format(TREND_DETAIL_DATE_FORMAT),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            point.value?.let {
                                "${DecimalFormat("0.#").format(it)} $unit"
                            } ?: "未取得",
                            fontWeight = FontWeight.SemiBold,
                        )
                        point.details.forEach {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (overlayPoints.any { it.value != null }) {
                            "${measured.size}測定・太線は7日中央値"
                        } else {
                            "${measured.size}測定・タップで詳細"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "変化 ${signed(measured.last() - measured.first())} $unit",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveSparkline(
    points: List<TrendPoint>,
    overlayPoints: List<TrendPoint> = emptyList(),
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rawColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    val trendColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.tertiary
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(points, canvasSize) {
                detectTapGestures { offset ->
                    if (points.isNotEmpty() && canvasSize.width > 0) {
                        val fraction = (offset.x / canvasSize.width).coerceIn(0f, 1f)
                        onSelect((fraction * (points.size - 1)).roundToInt())
                    }
                }
            },
    ) {
        val values = (points + overlayPoints).mapNotNull { it.value }
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val span = (max - min).takeIf { it > 0.0001 } ?: 1.0
        val stepX = if (points.size > 1) size.width / (points.size - 1) else 0f
        fun drawSeries(
            series: List<TrendPoint>,
            color: androidx.compose.ui.graphics.Color,
            strokeWidth: Float,
            showDots: Boolean,
        ) {
            var path = Path()
            var pathPointCount = 0

            fun drawCurrentPath() {
                if (pathPointCount >= 2) {
                    drawPath(
                        path,
                        color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                        ),
                    )
                }
                path = Path()
                pathPointCount = 0
            }

            series.forEachIndexed { index, trendPoint ->
                val value = trendPoint.value
                if (value == null) {
                    drawCurrentPath()
                    return@forEachIndexed
                }
                val chartPoint = Offset(
                    x = stepX * index,
                    y = size.height -
                        (((value - min) / span).toFloat() * size.height * 0.82f) -
                        size.height * 0.09f,
                )
                if (pathPointCount == 0) {
                    path.moveTo(chartPoint.x, chartPoint.y)
                } else {
                    path.lineTo(chartPoint.x, chartPoint.y)
                }
                pathPointCount++
                if (showDots) drawCircle(color = color, radius = 3.5f, center = chartPoint)
            }
            drawCurrentPath()
        }

        drawSeries(points, rawColor, 2.5f, true)
        if (overlayPoints.isNotEmpty()) {
            drawSeries(overlayPoints, trendColor, 5f, false)
        } else {
            drawSeries(points, trendColor, 4f, false)
        }

        selectedIndex?.takeIf { it in points.indices }?.let { index ->
            val x = stepX * index
            drawLine(
                color = selectionColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
            points[index].value?.let { value ->
                val y = size.height -
                    (((value - min) / span).toFloat() * size.height * 0.82f) -
                    size.height * 0.09f
                drawCircle(color = selectionColor, radius = 8f, center = Offset(x, y))
            }
        }
    }
}

private val ProteinBarColor = Color(0xFF2A9D8F)
private val FatBarColor = Color(0xFFE9C46A)
private val CarbBarColor = Color(0xFF4C6EF5)

@Composable
private fun DailyPfcSummary(
    title: String,
    subtitle: String,
    balance: PfcBalance?,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (balance == null) {
            Text("未取得", style = MaterialTheme.typography.titleMedium)
        } else {
            PfcVerdictChip(balance)
            PfcStackedBar(balance)
            PfcLegend(balance)
            Text(
                listOfNotNull(
                    balance.proteinGrams?.let { "たんぱく質 ${DecimalFormat("0.0").format(it)}g" },
                    balance.fatGrams?.let { "脂質 ${DecimalFormat("0.0").format(it)}g" },
                    balance.carbohydrateGrams?.let { "炭水化物 ${DecimalFormat("0.0").format(it)}g" },
                ).joinToString(" ・ ").ifBlank { "グラム未取得" },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MealPfcChartCard(meals: List<NutritionMealEntity>) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("食事ごとのPFC", fontWeight = FontWeight.SemiBold)
            Text(
                "start/endが近いレコードを1食にまとめます。mealTypeが違う朝昼夕は別食として扱います",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().height(140.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                meals.forEach { meal ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PfcVerticalBar(
                            balance = meal.pfcBalance(),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Text(meal.mealLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            PfcLegend(null)
        }
    }
}

@Composable
private fun MealPfcRow(meal: NutritionMealEntity) {
    val balance = meal.pfcBalance()
    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(meal.mealLabel, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatClock(meal.startEpochMillis)}〜${formatClock(meal.endEpochMillis)}" +
                            if (meal.recordCount > 1) " ・${meal.recordCount}件" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PfcVerdictChip(balance)
            }
            PfcStackedBar(balance)
            Text(
                listOfNotNull(
                    meal.intakeCaloriesKcal?.roundToLong()?.let { "$it kcal" },
                    meal.proteinGrams?.let { "P ${DecimalFormat("0.0").format(it)}g" },
                    meal.totalFatGrams?.let { "F ${DecimalFormat("0.0").format(it)}g" },
                    meal.carbohydrateGrams?.let { "C ${DecimalFormat("0.0").format(it)}g" },
                ).joinToString(" ・ ").ifBlank { "栄養値未取得" },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun DailyPfcCompositionCard(
    dates: List<LocalDate>,
    dailyByDate: Map<String, DailyHealthSummaryEntity>,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1日のPFC構成", fontWeight = FontWeight.SemiBold)
            Text(
                "棒はエネルギー比。欠測日は空欄です",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                dates.forEach { date ->
                    val balance = dailyByDate[date.toString()]?.pfcBalance()
                    PfcVerticalBar(
                        balance = balance,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    dates.first().format(TREND_DATE_FORMAT),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    dates.last().format(TREND_DATE_FORMAT),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            PfcLegend(null)
        }
    }
}

@Composable
private fun PfcStackedBar(balance: PfcBalance, modifier: Modifier = Modifier) {
    val segments = pfcSegments(balance)
    Row(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (segments.isEmpty()) {
            Box(Modifier.fillMaxWidth().fillMaxHeight())
        } else {
            segments.forEach { segment ->
                Box(
                    Modifier
                        .weight(segment.fraction)
                        .fillMaxHeight()
                        .background(segment.color),
                )
            }
        }
    }
}

@Composable
private fun PfcVerticalBar(balance: PfcBalance?, modifier: Modifier = Modifier) {
    val segments = balance?.let { pfcSegments(it) }.orEmpty()
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (segments.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f))
        } else {
            // 下から C → F → P になるよう逆順で積み、weight で構成比を表す
            segments.asReversed().forEach { segment ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(segment.fraction.coerceAtLeast(0.0001f))
                        .background(segment.color),
                )
            }
        }
    }
}

@Composable
private fun PfcLegend(balance: PfcBalance?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PfcLegendItem("P", ProteinBarColor, balance?.proteinEnergyPercent)
        PfcLegendItem("F", FatBarColor, balance?.fatEnergyPercent)
        PfcLegendItem("C", CarbBarColor, balance?.carbohydrateEnergyPercent)
    }
}

@Composable
private fun PfcLegendItem(label: String, color: Color, percent: Double?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color),
        )
        Text(
            percent?.let { "$label ${DecimalFormat("0").format(it)}%" } ?: label,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PfcVerdictChip(balance: PfcBalance) {
    val colors = when (balance.verdict) {
        PfcVerdict.ON_TARGET -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        PfcVerdict.WATCH -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        PfcVerdict.HOLD -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Card(colors = colors) {
        Text(
            balance.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class PfcSegment(val fraction: Float, val color: Color)

private fun pfcSegments(balance: PfcBalance): List<PfcSegment> {
    val parts = listOfNotNull(
        balance.proteinEnergyPercent?.let { it.toFloat() to ProteinBarColor },
        balance.fatEnergyPercent?.let { it.toFloat() to FatBarColor },
        balance.carbohydrateEnergyPercent?.let { it.toFloat() to CarbBarColor },
    ).filter { it.first > 0f }
    val total = parts.sumOf { it.first.toDouble() }.toFloat()
    if (total <= 0f) return emptyList()
    return parts.map { PfcSegment(it.first / total, it.second) }
}

private fun DailyHealthSummaryEntity.pfcBalance(): PfcBalance =
    PfcBalance.from(intakeCaloriesKcal, proteinGrams, totalFatGrams, carbohydrateGrams)

private fun NutritionMealEntity.pfcBalance(): PfcBalance =
    PfcBalance.from(intakeCaloriesKcal, proteinGrams, totalFatGrams, carbohydrateGrams)

@Composable
private fun EmptyCard(title: String, description: String, action: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            action?.let { FilledTonalButton(onClick = it) { Text("同期する") } }
        }
    }
}

@Composable
private fun MetricChangeChip(
    label: String,
    value: Double?,
    unit: String,
    lowerIsBetter: Boolean?,
    modifier: Modifier = Modifier,
) {
    val favorable = lowerIsBetter?.let { lower ->
        value?.let { if (lower) it <= 0 else it >= 0 }
    }
    val containerColor = when (favorable) {
        true -> MaterialTheme.colorScheme.primaryContainer
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (favorable) {
        true -> MaterialTheme.colorScheme.onPrimaryContainer
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
            Text(
                value?.let { "${signed(it)} $unit" } ?: "データ無",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun ReportTile(
    title: String,
    mainValue: String,
    subValue: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                Text(title, style = MaterialTheme.typography.labelMedium)
            }
            Text(mainValue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subValue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun compareDiff(current: Long?, previous: Long?): Long? {
    if (current == null || previous == null) return null
    return current - previous
}

private fun compareDiff(current: Double?, previous: Double?): Double? {
    if (current == null || previous == null) return null
    return current - previous
}

@Composable
private fun AdviceBlock(title: String, body: String) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body)
    }
}

@Composable
private fun SmallField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}

private fun rollingBodyMedian(
    dates: List<LocalDate>,
    allBody: List<BodyCompositionEntity>,
    value: (BodyCompositionEntity) -> Double?,
): List<TrendPoint> {
    val measurements = allBody.mapNotNull { item ->
        value(item)?.let { LocalDate.parse(item.date) to it }
    }
    return dates.map { date ->
        val from = date.minusDays(6)
        TrendPoint(
            date = date,
            value = TrendMath.median(
                measurements.filter { it.first in from..date }.map { it.second },
            ),
        )
    }
}

private fun weightPaceGuidance(rate: Double?): String = when {
    rate == null -> "8回以上かつ14日以上の体重データが必要です"
    rate < 0.0 -> "体重は維持〜増加傾向。まず測定条件と摂取・活動の継続を確認"
    rate < 0.15 -> "ほぼ維持。2週間以上続く場合だけ行動を小さく調整"
    rate < 0.30 -> "ゆっくりした減量。筋力維持を優先するなら許容範囲"
    rate <= 0.70 -> "筋力維持を優先した目安帯（0.3〜0.7%/週）"
    rate <= 1.00 -> "やや速め。筋力・睡眠・除脂肪量傾向を確認"
    else -> "速すぎる可能性。摂取不足や回復不足を確認"
}

private fun leanTrendGuidance(trend: Double?): String = when {
    trend == null -> "判定データ不足"
    trend < -0.15 -> "低下傾向・条件と筋力を確認"
    else -> "明確な低下なし・BIA参考"
}

private fun Double?.signedOrMissing(unit: String): String =
    this?.let { "${signed(it)} $unit" } ?: "未取得"

private fun pfcEnergyRatio(report: WeeklySnapshot): String {
    val parts = listOfNotNull(
        report.proteinEnergyPercent?.let { "P ${DecimalFormat("0").format(it)}%" },
        report.fatEnergyPercent?.let { "F ${DecimalFormat("0").format(it)}%" },
        report.carbohydrateEnergyPercent?.let { "C ${DecimalFormat("0").format(it)}%" },
    )
    return parts.joinToString(" ・ ").ifBlank { "未取得" }
}

private fun Double?.energyPercentLabel(): String? =
    this?.let { "エネルギー比 ${DecimalFormat("0").format(it)}%" }

private val TREND_DATE_FORMAT = DateTimeFormatter.ofPattern("M/d")
private val TREND_DETAIL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d (E)", Locale.JAPAN)

private fun Long.asHoursMinutes(): String = "${this / 60}時間${this % 60}分"
private fun Double?.asDistance(): String = this?.let {
    if (it >= 1_000.0) {
        "${DecimalFormat("0.00").format(it / 1_000.0)} km"
    } else {
        "${it.roundToLong()} m"
    }
} ?: "未取得"
private fun Long.asFileSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "${DecimalFormat("0.#").format(this / 1024.0)} KB"
    else -> "${DecimalFormat("0.#").format(this / (1024.0 * 1024.0))} MB"
}
private fun Double?.kg(): String = this?.let { "${DecimalFormat("0.0").format(it)} kg" } ?: "未取得"
private fun Double?.grams(suffix: String = ""): String =
    this?.let { "${DecimalFormat("0.0").format(it)} g$suffix" } ?: "未取得"
private fun Double?.kcal(suffix: String = ""): String =
    this?.roundToLong()?.let { "$it kcal$suffix" } ?: "未取得"
private fun Double?.clean(): String? = this?.let { DecimalFormat("0.##").format(it) }
private fun signed(value: Double): String = if (value >= 0) "+${DecimalFormat("0.##").format(value)}" else DecimalFormat("0.##").format(value)
private fun formatClock(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
private fun formatInstant(epochMillis: Long): String = DateTimeFormatter.ofPattern("M/d HH:mm")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private fun EnergyRange.activeRangeLabel(): String {
    val low = lowKcal.roundToLong()
    val high = highKcal.roundToLong()
    return if (low == high) "$low kcal" else "$low 〜 $high kcal"
}

private fun EnergyRange.balanceRangeLabel(): String {
    val low = lowKcal.roundToLong()
    val high = highKcal.roundToLong()
    return if (low == high) "${signed(lowKcal)} kcal" else "${signed(lowKcal)} 〜 ${signed(highKcal)} kcal"
}
