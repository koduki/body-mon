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
            addStrengthSignal(snapshot)
            addRecoverySignal(snapshot)
            addActivitySignal(snapshot)
        }
        val limitations = buildList<String> {
            addAll(snapshot.dataLimitations)
            add(
                "運動日数だけでは筋力維持そのものを判定できません。" +
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

    private fun MutableList<CoachSignal>.addStrengthSignal(
        snapshot: WeeklySnapshot,
    ) {
        val adherence = snapshot.strengthAdherencePercent ?: return
        when {
            adherence >= BodyRecompositionCoachPolicy.STRENGTH_ADHERENCE_GOOD_PERCENT -> add(
                CoachSignal(
                    code = "STRENGTH_HABIT_ON_TRACK",
                    level = CoachSignalLevel.POSITIVE,
                    title = "朝トレ習慣は維持",
                    evidence = "${snapshot.strengthTrainingDays}/${snapshot.strengthTargetDays}日、" +
                        "継続率$adherence%です",
                    priority = 50,
                ),
            )

            adherence < BodyRecompositionCoachPolicy.STRENGTH_ADHERENCE_CAUTION_PERCENT -> add(
                CoachSignal(
                    code = "STRENGTH_ADHERENCE_LOW",
                    level = CoachSignalLevel.CAUTION,
                    title = "朝トレ継続を立て直す",
                    evidence = "${snapshot.strengthTrainingDays}/${snapshot.strengthTargetDays}日、" +
                        "継続率$adherence%です",
                    action = "朝トレは負荷を増やすより、短い最低メニューで実施日を戻す",
                    priority = 80,
                ),
            )

            else -> add(
                CoachSignal(
                    code = "STRENGTH_HABIT_WATCH",
                    level = CoachSignalLevel.INFORMATION,
                    title = "朝トレ習慣はおおむね維持",
                    evidence = "${snapshot.strengthTrainingDays}/${snapshot.strengthTargetDays}日、" +
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

    private fun verdict(
        signals: List<CoachSignal>,
        snapshot: WeeklySnapshot,
    ): CoachVerdict {
        val codes = signals.map { it.code }.toSet()
        return when {
            "LOSS_RATE_FAST" in codes ||
                "STRENGTH_ADHERENCE_LOW" in codes ||
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

    private fun confidence(snapshot: WeeklySnapshot): String = when {
        snapshot.weightLossRatePercentPerWeek == null -> "low"
        (snapshot.measurementTimeConsistencyPercent ?: 100) <
            BodyRecompositionCoachPolicy.MIN_MEASUREMENT_CONSISTENCY_PERCENT -> "low"
        snapshot.trendMeasurementDays >= 14 && snapshot.bodyMeasurementDays >= 5 -> "high"
        else -> "medium"
    }

    private fun Double.percent(): String = String.format(Locale.ROOT, "%.2f", this)

    private fun Double.kgPerWeek(): String =
        String.format(Locale.ROOT, "%+.2f kg/週", this)
}

object BodyRecompositionCoachPolicy {
    const val VERSION = "2026-07-31"
    const val MAX_ACTIONS = 2
    const val TARGET_LOSS_RATE_MIN_PERCENT = 0.3
    const val TARGET_LOSS_RATE_MAX_PERCENT = 0.7
    const val LEAN_TREND_CAUTION_KG_PER_WEEK = -0.20
    const val STRENGTH_ADHERENCE_GOOD_PERCENT = 80
    const val STRENGTH_ADHERENCE_CAUTION_PERCENT = 70
    const val STEPS_BASELINE_CAUTION_PERCENT = 90
    const val MIN_MEASUREMENT_CONSISTENCY_PERCENT = 70
    const val MIN_SLEEP_MEASUREMENT_DAYS = 5
    const val SLEEP_TARGET_DAYS_CAUTION_MAX = 3
    const val SLEEP_TARGET_DAYS_GOOD_MIN = 5
    const val SLEEP_HEART_RATE_CAUTION_BPM = 5

    val systemInstruction: String = """
        専門家としての優先順位は、体重を速く落とすことではなく、筋力と回復を守りながら
        脂肪を減らすことです。観測事実、解釈、提案を混同しないでください。
        0.3〜0.7%体重/週はこのアプリの保守的な運用目安で、医学的な普遍閾値ではありません。
        日々の軽い朝トレは習慣KPIとして扱い、筋力維持の証明にはしません。筋力は月1回の
        パーソナルで同じ種目の重量・回数を比較して確認するよう案内してください。
        BIA由来の脂肪量・除脂肪量は28日傾向でも補助シグナルに限定し、筋肉増減を断定しません。
        活動消費カロリーや基礎代謝から、摂取量やカロリー赤字を逆算しません。
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
