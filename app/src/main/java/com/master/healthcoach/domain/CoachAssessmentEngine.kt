package com.master.healthcoach.domain

import java.util.Locale

enum class CoachVerdict(val label: String) {
    ON_TRACK("順調"),
    WATCH("要観察"),
    ADJUST("調整推奨"),
    NEED_MORE_DATA("判定保留"),
}

enum class CoachSignalLevel {
    POSITIVE,
    INFORMATION,
    CAUTION,
}

data class CoachSignal(
    val code: String,
    val level: CoachSignalLevel,
    val title: String,
    val evidence: String,
    val action: String? = null,
    val priority: Int = 0,
)

data class CoachAssessment(
    val verdict: CoachVerdict,
    val signals: List<CoachSignal>,
    val nextActions: List<String>,
    val dataLimitations: List<String>,
    val confidence: String,
    val policyVersion: String = BodyRecompositionCoachPolicy.VERSION,
)

/**
 * Executable, testable coaching policy. Dashboard-only KPIs are interpreted here and
 * remain on device; callers decide how to present the resulting assessment.
 */
object CoachAssessmentEngine {
    fun assess(snapshot: WeeklySnapshot): CoachAssessment {
        val signals = buildList<CoachSignal> {
            addMeasurementQualitySignal(snapshot)
            addWeightLossRateSignal(snapshot)
            addBodyCompositionSignal(snapshot)
            addEnergyBalanceWeightSignal(snapshot)
            addMorningRoutineSignal(snapshot)
            addRecoverySignal(snapshot)
            addActivitySignal(snapshot)
            addNutritionSignal(snapshot)
        }
        val limitations = buildList<String> {
            addAll(snapshot.dataLimitations)
            add(
                "朝トレの実施日数だけでは筋力維持そのものを判定できません。" +
                    "月1回のパーソナルで同じ種目の重量・回数を確認してください",
            )
        }.distinct()
        val actions = signals
            .filter { it.level == CoachSignalLevel.CAUTION }
            .sortedByDescending { it.priority }
            .mapNotNull { it.action }
            .distinct()
            .take(BodyRecompositionCoachPolicy.MAX_ACTIONS)
        return CoachAssessment(
            verdict = verdict(signals, snapshot),
            signals = signals.sortedByDescending { it.priority },
            nextActions = actions,
            dataLimitations = limitations,
            confidence = confidence(snapshot),
        )
    }

    private fun MutableList<CoachSignal>.addMeasurementQualitySignal(
        snapshot: WeeklySnapshot,
    ) {
        val consistency = snapshot.measurementTimeConsistencyPercent ?: return
        if (consistency < BodyRecompositionCoachPolicy.MIN_MEASUREMENT_CONSISTENCY_PERCENT) {
            add(
                CoachSignal(
                    code = "MEASUREMENT_CONSISTENCY_LOW",
                    level = CoachSignalLevel.CAUTION,
                    title = "測定条件を先に整える",
                    evidence = "28日内で代表時刻の±90分に入った測定は$consistency%です",
                    action = "寝起き・トイレ後・飲食前・同程度の服装で測定時刻をそろえる",
                    priority = 95,
                ),
            )
        }
    }

    private fun MutableList<CoachSignal>.addWeightLossRateSignal(
        snapshot: WeeklySnapshot,
    ) {
        val rate = snapshot.weightLossRatePercentPerWeek ?: return
        val formatted = rate.percent()
        when {
            rate > BodyRecompositionCoachPolicy.TARGET_LOSS_RATE_MAX_PERCENT -> add(
                CoachSignal(
                    code = "LOSS_RATE_FAST",
                    level = CoachSignalLevel.CAUTION,
                    title = "減量ペースが速い",
                    evidence = "28日傾向は体重の$formatted%/週減です",
                    action = "減量をさらに強めず、空腹・疲労・睡眠と次回パーソナルの重量・回数を確認する",
                    priority = 100,
                ),
            )

            rate >= BodyRecompositionCoachPolicy.TARGET_LOSS_RATE_MIN_PERCENT -> add(
                CoachSignal(
                    code = "LOSS_RATE_ON_TARGET",
                    level = CoachSignalLevel.POSITIVE,
                    title = "減量ペースは運用帯内",
                    evidence = "28日傾向は体重の$formatted%/週減で、筋力維持を優先した目安内です",
                    priority = 60,
                ),
            )

            rate >= 0.0 -> add(
                CoachSignal(
                    code = "LOSS_RATE_CONSERVATIVE",
                    level = CoachSignalLevel.INFORMATION,
                    title = "減量ペースは緩やか",
                    evidence = "28日傾向は体重の$formatted%/週減です。短期的に強める必要はありません",
                    priority = 40,
                ),
            )

            else -> add(
                CoachSignal(
                    code = "WEIGHT_TREND_UP",
                    level = CoachSignalLevel.CAUTION,
                    title = "体重は増加傾向",
                    evidence = "28日傾向では体重が${(-rate).percent()}%/週増えています",
                    action = "単日の体重では判断せず、今後2週間の食事パターンと歩数維持を確認する",
                    priority = 70,
                ),
            )
        }
    }

    private fun MutableList<CoachSignal>.addBodyCompositionSignal(
        snapshot: WeeklySnapshot,
    ) {
        val leanTrend = snapshot.leanMassTrendKgPerWeek
        val fatTrend = snapshot.fatMassTrendKgPerWeek
        if (
            leanTrend != null &&
            leanTrend <= BodyRecompositionCoachPolicy.LEAN_TREND_CAUTION_KG_PER_WEEK
        ) {
            add(
                CoachSignal(
                    code = "LEAN_TREND_DOWN",
                    level = CoachSignalLevel.CAUTION,
                    title = "除脂肪量は要観察",
                    evidence = "BIA由来の28日傾向は${leanTrend.kgPerWeek()}です。" +
                        "筋肉減少とは断定できません",
                    action = "追加の減量強化はせず、次回パーソナルで同じ種目の重量・回数を比較する",
                    priority = if (
                        (snapshot.weightLossRatePercentPerWeek ?: 0.0) >
                        BodyRecompositionCoachPolicy.TARGET_LOSS_RATE_MAX_PERCENT
                    ) {
                        90
                    } else {
                        75
                    },
                ),
            )
        } else if (leanTrend != null && fatTrend != null && leanTrend >= 0.0 && fatTrend < 0.0) {
            add(
                CoachSignal(
                    code = "BODY_RECOMPOSITION_DIRECTION",
                    level = CoachSignalLevel.POSITIVE,
                    title = "体組成の方向性は良好",
                    evidence = "BIA上は脂肪量が減少し、除脂肪量は維持方向です",
                    priority = 55,
                ),
            )
        }
    }

    private fun MutableList<CoachSignal>.addEnergyBalanceWeightSignal(
        snapshot: WeeklySnapshot,
    ) {
        val analysis = EnergyBalanceWeightAnalyzer.analyze(snapshot)
        when (analysis.alignment) {
            EnergyBalanceWeightAlignment.INSUFFICIENT_DATA -> Unit

            EnergyBalanceWeightAlignment.ALIGNED_DEFICIT,
            EnergyBalanceWeightAlignment.ALIGNED_STABLE,
            -> add(
                CoachSignal(
                    code = "ENERGY_WEIGHT_ALIGNED",
                    level = CoachSignalLevel.INFORMATION,
                    title = analysis.title,
                    evidence = analysis.summary,
                    priority = 42,
                ),
            )

            EnergyBalanceWeightAlignment.ALIGNED_SURPLUS -> add(
                CoachSignal(
                    code = "ENERGY_WEIGHT_ALIGNED_SURPLUS",
                    level = CoachSignalLevel.INFORMATION,
                    title = analysis.title,
                    evidence = analysis.summary,
                    priority = 44,
                ),
            )

            EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP -> add(
                CoachSignal(
                    code = "ENERGY_WEIGHT_MISMATCH_DEFICIT",
                    level = CoachSignalLevel.CAUTION,
                    title = analysis.title,
                    evidence = analysis.summary + "。推定収支は参考値です",
                    action = analysis.guidance,
                    priority = 55,
                ),
            )

            EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN -> add(
                CoachSignal(
                    code = "ENERGY_WEIGHT_MISMATCH_SURPLUS",
                    level = CoachSignalLevel.CAUTION,
                    title = analysis.title,
                    evidence = analysis.summary + "。推定収支は参考値です",
                    action = analysis.guidance,
                    priority = 52,
                ),
            )
        }
    }

    private fun MutableList<CoachSignal>.addMorningRoutineSignal(
        snapshot: WeeklySnapshot,
    ) {
        val adherence = snapshot.morningRoutineAdherencePercent ?: return
        when {
            adherence >= BodyRecompositionCoachPolicy.ROUTINE_ADHERENCE_GOOD_PERCENT -> add(
                CoachSignal(
                    code = "MORNING_ROUTINE_ON_TRACK",
                    level = CoachSignalLevel.POSITIVE,
                    title = "朝トレ習慣は維持",
                    evidence = "${snapshot.morningRoutineDays}/" +
                        "${snapshot.morningRoutineTargetDays}日、" +
                        "継続率$adherence%です",
                    priority = 50,
                ),
            )

            adherence < BodyRecompositionCoachPolicy.ROUTINE_ADHERENCE_CAUTION_PERCENT -> add(
                CoachSignal(
                    code = "MORNING_ROUTINE_ADHERENCE_LOW",
                    level = CoachSignalLevel.CAUTION,
                    title = "朝トレ継続を立て直す",
                    evidence = "${snapshot.morningRoutineDays}/" +
                        "${snapshot.morningRoutineTargetDays}日、" +
                        "継続率$adherence%です",
                    action = "朝トレは負荷を増やすより、短い最低メニューで実施日を戻す",
                    priority = 80,
                ),
            )

            else -> add(
                CoachSignal(
                    code = "MORNING_ROUTINE_WATCH",
                    level = CoachSignalLevel.INFORMATION,
                    title = "朝トレ習慣はおおむね維持",
                    evidence = "${snapshot.morningRoutineDays}/" +
                        "${snapshot.morningRoutineTargetDays}日、" +
                        "継続率$adherence%です",
                    priority = 45,
                ),
            )
        }
    }

    private fun MutableList<CoachSignal>.addRecoverySignal(
        snapshot: WeeklySnapshot,
    ) {
        val sleepTargetDays = snapshot.sleepTargetHitDays
        if (
            sleepTargetDays != null &&
            snapshot.sleepMeasurementDays >= BodyRecompositionCoachPolicy.MIN_SLEEP_MEASUREMENT_DAYS
        ) {
            if (sleepTargetDays <= BodyRecompositionCoachPolicy.SLEEP_TARGET_DAYS_CAUTION_MAX) {
                add(
                    CoachSignal(
                        code = "SLEEP_OPPORTUNITY_LOW",
                        level = CoachSignalLevel.CAUTION,
                        title = "回復機会が少ない",
                        evidence = "7時間以上の主睡眠は7日中$sleepTargetDays 日です",
                        action = "減量や運動を強める前に、まず7時間の睡眠機会を確保する",
                        priority = 70,
                    ),
                )
            } else if (sleepTargetDays >= BodyRecompositionCoachPolicy.SLEEP_TARGET_DAYS_GOOD_MIN) {
                add(
                    CoachSignal(
                        code = "SLEEP_ON_TRACK",
                        level = CoachSignalLevel.POSITIVE,
                        title = "睡眠機会は確保",
                        evidence = "7時間以上の主睡眠は7日中$sleepTargetDays 日です",
                        priority = 45,
                    ),
                )
            }
        }
        val sleepHeartRateDelta = snapshot.sleepHeartRateBaselineDeltaBpm
        if (
            sleepHeartRateDelta != null &&
            sleepHeartRateDelta >= BodyRecompositionCoachPolicy.SLEEP_HEART_RATE_CAUTION_BPM
        ) {
            add(
                CoachSignal(
                    code = "SLEEP_HEART_RATE_UP",
                    level = CoachSignalLevel.CAUTION,
                    title = "回復シグナルを確認",
                    evidence = "睡眠中心拍が直前21日平均より+$sleepHeartRateDelta bpmです。" +
                        "診断値ではありません",
                    action = "数日続く場合は疲労・睡眠・飲酒・体調を振り返り、無理な追い込みを避ける",
                    priority = 65,
                ),
            )
        }
    }

    private fun MutableList<CoachSignal>.addActivitySignal(
        snapshot: WeeklySnapshot,
    ) {
        val baseline = snapshot.stepsBaselinePercent
        if (baseline != null && baseline < BodyRecompositionCoachPolicy.STEPS_BASELINE_CAUTION_PERCENT) {
            add(
                CoachSignal(
                    code = "STEPS_BASELINE_DOWN",
                    level = CoachSignalLevel.CAUTION,
                    title = "日常活動が低下",
                    evidence = "歩数は減量開始前28日平均の$baseline%です",
                    action = "推定消費カロリーを追うより、減量開始前の歩数水準へ戻す",
                    priority = 60,
                ),
            )
        } else if (baseline != null && baseline >= 100) {
            add(
                CoachSignal(
                    code = "STEPS_BASELINE_MAINTAINED",
                    level = CoachSignalLevel.POSITIVE,
                    title = "日常活動を維持",
                    evidence = "歩数は減量開始前28日平均の$baseline%です",
                    priority = 40,
                ),
            )
        }
    }

    private fun MutableList<CoachSignal>.addNutritionSignal(
        snapshot: WeeklySnapshot,
    ) {
        if (
            snapshot.nutritionMeasurementDays <
            BodyRecompositionCoachPolicy.MIN_NUTRITION_MEASUREMENT_DAYS
        ) {
            return
        }
        val protein = snapshot.proteinDailyAverageGrams
        val weight = snapshot.currentWeightMedianKg
        if (protein != null && weight != null && weight > 0) {
            val proteinPerKg = protein / weight
            if (proteinPerKg < BodyRecompositionCoachPolicy.PROTEIN_PER_KG_CAUTION) {
                add(
                    CoachSignal(
                        code = "PROTEIN_INTAKE_LOW",
                        level = CoachSignalLevel.CAUTION,
                        title = "たんぱく質の記録が少なめ",
                        evidence = "食事記録の平均は${protein.grams()}、" +
                            "体重1kgあたり${proteinPerKg.grams()}/日です。" +
                            "未記録の食事は含みません",
                        action = "減量を強める前に、記録済みのたんぱく質が食事の中心になっているか確認する",
                        priority = 58,
                    ),
                )
            }
        }
        val fatShare = snapshot.fatEnergyPercent ?: return
        val formattedShare = String.format(Locale.ROOT, "%.0f", fatShare)
        when {
            fatShare >= BodyRecompositionCoachPolicy.FAT_ENERGY_CAUTION_PERCENT -> add(
                CoachSignal(
                    code = "FAT_SHARE_HIGH",
                    level = CoachSignalLevel.CAUTION,
                    title = "脂質のエネルギー比が高め",
                    evidence = "記録上の脂質はエネルギー比$formattedShare%です。" +
                        "低脂質の運用目安は15〜25%です",
                    action = "調理油・ドレッシング・間食など、脂質の出どころを確認してから量を調整する",
                    priority = 62,
                ),
            )
            fatShare in BodyRecompositionCoachPolicy.FAT_ENERGY_TARGET_MIN_PERCENT..BodyRecompositionCoachPolicy.FAT_ENERGY_TARGET_MAX_PERCENT -> add(
                CoachSignal(
                    code = "FAT_SHARE_ON_TARGET",
                    level = CoachSignalLevel.POSITIVE,
                    title = "脂質比は低脂質の目安内",
                    evidence = "記録上の脂質はエネルギー比$formattedShare%です",
                    priority = 48,
                ),
            )
            fatShare < BodyRecompositionCoachPolicy.FAT_ENERGY_TARGET_MIN_PERCENT -> add(
                CoachSignal(
                    code = "FAT_SHARE_VERY_LOW",
                    level = CoachSignalLevel.INFORMATION,
                    title = "脂質比はかなり低め",
                    evidence = "記録上の脂質はエネルギー比$formattedShare%です。" +
                        "必須脂肪酸まで落とす必要はありません",
                    priority = 35,
                ),
            )
        }
    }

    private fun verdict(
        signals: List<CoachSignal>,
        snapshot: WeeklySnapshot,
    ): CoachVerdict {
        val codes = signals.map { it.code }.toSet()
        return when {
            "LOSS_RATE_FAST" in codes ||
                "MORNING_ROUTINE_ADHERENCE_LOW" in codes ||
                (
                    "LEAN_TREND_DOWN" in codes &&
                        (snapshot.weightLossRatePercentPerWeek ?: 0.0) >
                        BodyRecompositionCoachPolicy.TARGET_LOSS_RATE_MAX_PERCENT
                    ) -> CoachVerdict.ADJUST

            signals.any { it.level == CoachSignalLevel.CAUTION } -> CoachVerdict.WATCH
            signals.any { it.level == CoachSignalLevel.POSITIVE } -> CoachVerdict.ON_TRACK
            snapshot.weightLossRatePercentPerWeek == null -> CoachVerdict.NEED_MORE_DATA
            else -> CoachVerdict.WATCH
        }
    }

    private fun confidence(snapshot: WeeklySnapshot): String {
        val measurementConsistency = snapshot.measurementTimeConsistencyPercent ?: 100
        return when {
            snapshot.weightLossRatePercentPerWeek == null -> "low"
            measurementConsistency <
                BodyRecompositionCoachPolicy.MIN_MEASUREMENT_CONSISTENCY_PERCENT -> "low"
            snapshot.trendMeasurementDays >= 14 && snapshot.bodyMeasurementDays >= 5 -> "high"
            else -> "medium"
        }
    }

    private fun Double.percent(): String = String.format(Locale.ROOT, "%.2f", this)

    private fun Double.kgPerWeek(): String =
        String.format(Locale.ROOT, "%+.2f kg/週", this)

    private fun Double.grams(): String = String.format(Locale.ROOT, "%.1f g", this)
}

object BodyRecompositionCoachPolicy {
    const val VERSION = "2026-08-28-energy-weight"
    const val MAX_ACTIONS = 2
    const val TARGET_LOSS_RATE_MIN_PERCENT = 0.3
    const val TARGET_LOSS_RATE_MAX_PERCENT = 0.7
    const val LEAN_TREND_CAUTION_KG_PER_WEEK = -0.20
    const val ROUTINE_ADHERENCE_GOOD_PERCENT = 80
    const val ROUTINE_ADHERENCE_CAUTION_PERCENT = 70
    const val STEPS_BASELINE_CAUTION_PERCENT = 90
    const val MIN_MEASUREMENT_CONSISTENCY_PERCENT = 70
    const val MIN_SLEEP_MEASUREMENT_DAYS = 5
    const val SLEEP_TARGET_DAYS_CAUTION_MAX = 3
    const val SLEEP_TARGET_DAYS_GOOD_MIN = 5
    const val SLEEP_HEART_RATE_CAUTION_BPM = 5
    const val MIN_NUTRITION_MEASUREMENT_DAYS = 5
    const val PROTEIN_PER_KG_CAUTION = 1.6
    const val FAT_ENERGY_TARGET_MIN_PERCENT = 15.0
    const val FAT_ENERGY_TARGET_MAX_PERCENT = 25.0
    const val FAT_ENERGY_CAUTION_PERCENT = 30.0

    val systemInstruction: String = """
        専門家としての優先順位は、体重を速く落とすことではなく、筋力と回復を守りながら
        脂肪を減らすことです。このアプリの食事方針は低脂質ダイエットです。
        助言は食事内容、運動量、摂取カロリーと消費のバランス、PFC（グラムとエネルギー比）
        の観点から行ってください。観測事実、解釈、提案を混同しないでください。
        0.3〜0.7%体重/週はこのアプリの保守的な運用目安で、医学的な普遍閾値ではありません。
        脂質エネルギー比は15〜25%を低脂質の運用目安とし、30%超は出どころを確認します。
        たんぱく質は減量中の筋力維持のため、体重1kgあたり1.6gを下回らないか確認します。
        極端な脂質カット、摂取ゼロ、医療的な治療食は提案しません。
        Health Connectの「その他のワークアウト」は、朝の5分ルーティンとして扱います。
        軽い筋トレと有酸素運動の両方として評価しますが、実施日数は筋トレ日数ではなく
        朝トレ習慣KPIに使い、筋力維持の証明にはしません。筋力は月1回のパーソナルで
        同じ種目の重量・回数を比較して確認するよう案内してください。
        BIA由来の脂肪量・除脂肪量は28日傾向でも補助シグナルに限定し、筋肉増減を断定しません。
        活動消費カロリーや基礎代謝から、摂取量やカロリー赤字を逆算しません。
        Health Connectの栄養記録がある日だけ、摂取カロリーとPFCを観測値として扱います。
        欠測日を0kcalとせず、食事回数はNutritionRecordのstart/endクラスタから数えます。
        推定エネルギー収支は参考値であり減量成否の判定には使いません。
        摂取と活動消費・基礎代謝の比較、および体重の推移を必ず対照して述べてください。
        収支と体重の方向が食い違う場合は、赤字の強化ではなく記録漏れ・測定条件・水分変動を疑います。
        未記録の食事、調味・調理油、間食、飲酒、運動量の内訳など、不明点は断定せず、
        先に確認質問を最大2件出してください。十分な情報が揃ってから行動提案を出します。
        データが不足・矛盾する場合は結論を保留し、追加で確認すべき情報を示してください。
        行動提案は、効果が高く実行しやすい順に最大2つへ絞ってください。
    """.trimIndent()
}

object CoachResponseComposer {
    fun appendToChat(
        modelAnswer: String,
        assessment: CoachAssessment,
    ): String {
        val decisiveSignals = assessment.signals
            .filter { it.level != CoachSignalLevel.INFORMATION }
            .take(2)
        val visibleSignals = decisiveSignals.ifEmpty {
            assessment.signals.filter { it.level == CoachSignalLevel.INFORMATION }.take(1)
        }
        if (visibleSignals.isEmpty() && assessment.nextActions.isEmpty()) {
            return modelAnswer.trim()
        }
        return buildString {
            append(modelAnswer.trim())
            appendLine()
            appendLine()
            appendLine("専門家ビュー（端末内KPI判定）")
            appendLine("評価: ${assessment.verdict.label}")
            visibleSignals.forEach { signal ->
                appendLine("・${signal.title}: ${signal.evidence}")
            }
            if (assessment.nextActions.isNotEmpty()) {
                appendLine("今週の一手:")
                assessment.nextActions.forEachIndexed { index, action ->
                    appendLine("${index + 1}. $action")
                }
            }
            append("判定方針: ${assessment.policyVersion} / 確からしさ: ${assessment.confidence}")
        }
    }

    fun mergeStructuredAdvice(
        modelAdvice: AdviceResponse,
        assessment: CoachAssessment,
    ): AdviceResponse {
        val localPositive = assessment.signals
            .firstOrNull { it.level == CoachSignalLevel.POSITIVE }
            ?.let { "${it.title}: ${it.evidence}" }
        val localCaution = assessment.signals
            .filter { it.level == CoachSignalLevel.CAUTION }
            .take(2)
            .joinToString(" / ") { "${it.title}: ${it.evidence}" }
            .takeIf { it.isNotBlank() }
        return modelAdvice.copy(
            summary = "専門家判定は「${assessment.verdict.label}」です。${modelAdvice.summary}",
            positiveChange = combine(localPositive, modelAdvice.positiveChange),
            caution = combine(localCaution, modelAdvice.caution),
            nextActions = (
                assessment.nextActions + modelAdvice.nextActions
                ).distinct().take(BodyRecompositionCoachPolicy.MAX_ACTIONS),
            confidence = lowerConfidence(modelAdvice.confidence, assessment.confidence),
            dataLimitations = (
                modelAdvice.dataLimitations + assessment.dataLimitations
                ).distinct(),
        )
    }

    private fun combine(first: String?, second: String?): String? =
        listOfNotNull(first?.takeIf { it.isNotBlank() }, second?.takeIf { it.isNotBlank() })
            .distinct()
            .joinToString(" / ")
            .takeIf { it.isNotBlank() }

    private fun lowerConfidence(first: String, second: String): String {
        val order = mapOf("low" to 0, "medium" to 1, "high" to 2)
        return if ((order[first] ?: 1) <= (order[second] ?: 1)) first else second
    }
}
