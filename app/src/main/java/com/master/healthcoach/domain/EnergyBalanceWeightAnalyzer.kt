package com.master.healthcoach.domain

import java.util.Locale
import kotlin.math.abs

/**
 * On-device cross-check of estimated calorie balance against observed weight trend.
 * Energy balance stays a reference signal and never decides diet success by itself.
 */
object EnergyBalanceWeightAnalyzer {
    /** Rough tissue energy density used only for reference comparison. */
    const val KCAL_PER_KG = 7_700.0
    const val NEAR_ZERO_BALANCE_KCAL_PER_DAY = 150.0
    const val NEAR_ZERO_WEIGHT_KG_PER_WEEK = 0.15
    const val MISMATCH_GAP_KG_PER_WEEK = 0.35
    const val MIN_NUTRITION_DAYS = BodyRecompositionCoachPolicy.MIN_NUTRITION_MEASUREMENT_DAYS

    fun analyze(snapshot: WeeklySnapshot): EnergyBalanceWeightAnalysis {
        val intake = snapshot.intakeCaloriesDailyAverage
        val basal = snapshot.basalCaloriesDailyAverage
        val active = snapshot.activeCaloriesDailyAverage
        val balance = snapshot.estimatedEnergyBalanceDailyAverage
        val expenditure = if (basal != null && active != null) basal + active else null
        val observedTrend = snapshot.weightTrendKgPerWeek
            ?: snapshot.weightChangeKg
        val implied = balance?.let { it * 7.0 / KCAL_PER_KG }
        val gap = if (implied != null && observedTrend != null) {
            implied - observedTrend
        } else {
            null
        }

        if (
            snapshot.nutritionMeasurementDays < MIN_NUTRITION_DAYS ||
            balance == null ||
            observedTrend == null ||
            implied == null
        ) {
            return EnergyBalanceWeightAnalysis(
                intakeDailyAverageKcal = intake,
                expenditureDailyAverageKcal = expenditure,
                basalDailyAverageKcal = basal,
                activeDailyAverageKcal = active,
                estimatedBalanceDailyAverageKcal = balance,
                impliedWeightChangeKgPerWeek = implied,
                observedWeightTrendKgPerWeek = observedTrend,
                observedWeekOverWeekWeightChangeKg = snapshot.weightChangeKg,
                gapKgPerWeek = gap,
                alignment = EnergyBalanceWeightAlignment.INSUFFICIENT_DATA,
                summary = "対照に必要な摂取・消費・体重傾向が揃っていません",
                guidance = "食事記録が5日以上あり、体重の28日傾向か前週比があるときだけ対照します",
            )
        }

        val alignment = classify(balance = balance, observedTrend = observedTrend, gap = gap!!)
        return EnergyBalanceWeightAnalysis(
            intakeDailyAverageKcal = intake,
            expenditureDailyAverageKcal = expenditure,
            basalDailyAverageKcal = basal,
            activeDailyAverageKcal = active,
            estimatedBalanceDailyAverageKcal = balance,
            impliedWeightChangeKgPerWeek = implied,
            observedWeightTrendKgPerWeek = observedTrend,
            observedWeekOverWeekWeightChangeKg = snapshot.weightChangeKg,
            gapKgPerWeek = gap,
            alignment = alignment,
            summary = summary(alignment, balance, implied, observedTrend),
            guidance = guidance(alignment),
        )
    }

    private fun classify(
        balance: Double,
        observedTrend: Double,
        gap: Double,
    ): EnergyBalanceWeightAlignment {
        val nearZeroBalance = abs(balance) <= NEAR_ZERO_BALANCE_KCAL_PER_DAY
        val nearZeroWeight = abs(observedTrend) <= NEAR_ZERO_WEIGHT_KG_PER_WEEK
        val largeGap = abs(gap) >= MISMATCH_GAP_KG_PER_WEEK

        return when {
            nearZeroBalance && nearZeroWeight -> EnergyBalanceWeightAlignment.ALIGNED_STABLE
            balance < -NEAR_ZERO_BALANCE_KCAL_PER_DAY &&
                observedTrend <= -NEAR_ZERO_WEIGHT_KG_PER_WEEK &&
                !largeGap -> EnergyBalanceWeightAlignment.ALIGNED_DEFICIT
            balance > NEAR_ZERO_BALANCE_KCAL_PER_DAY &&
                observedTrend >= NEAR_ZERO_WEIGHT_KG_PER_WEEK &&
                !largeGap -> EnergyBalanceWeightAlignment.ALIGNED_SURPLUS
            balance < -NEAR_ZERO_BALANCE_KCAL_PER_DAY &&
                observedTrend >= -NEAR_ZERO_WEIGHT_KG_PER_WEEK ->
                EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP
            balance > NEAR_ZERO_BALANCE_KCAL_PER_DAY &&
                observedTrend <= -NEAR_ZERO_WEIGHT_KG_PER_WEEK ->
                EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN
            largeGap && balance < 0 && observedTrend > 0 ->
                EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP
            largeGap && balance > 0 && observedTrend < 0 ->
                EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN
            balance <= 0 && observedTrend <= 0 -> EnergyBalanceWeightAlignment.ALIGNED_DEFICIT
            else -> EnergyBalanceWeightAlignment.ALIGNED_SURPLUS
        }
    }

    private fun summary(
        alignment: EnergyBalanceWeightAlignment,
        balance: Double,
        implied: Double,
        observed: Double,
    ): String {
        val balanceLabel = String.format(Locale.ROOT, "%+.0f kcal/日", balance)
        val impliedLabel = String.format(Locale.ROOT, "%+.2f kg/週", implied)
        val observedLabel = String.format(Locale.ROOT, "%+.2f kg/週", observed)
        return when (alignment) {
            EnergyBalanceWeightAlignment.ALIGNED_DEFICIT ->
                "推定収支" + balanceLabel + "（換算" + impliedLabel +
                    "）と体重傾向" + observedLabel + "は同じ減少方向です"
            EnergyBalanceWeightAlignment.ALIGNED_SURPLUS ->
                "推定収支" + balanceLabel + "（換算" + impliedLabel +
                    "）と体重傾向" + observedLabel + "は同じ増加方向です"
            EnergyBalanceWeightAlignment.ALIGNED_STABLE ->
                "推定収支" + balanceLabel + "と体重傾向" + observedLabel +
                    "はどちらもほぼ横ばいです"
            EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP ->
                "推定収支は" + balanceLabel + "（換算" + impliedLabel +
                    "）なのに、体重傾向は" + observedLabel + "です"
            EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN ->
                "推定収支は" + balanceLabel + "（換算" + impliedLabel +
                    "）なのに、体重傾向は" + observedLabel + "です"
            EnergyBalanceWeightAlignment.INSUFFICIENT_DATA ->
                "対照データが不足しています"
        }
    }

    private fun guidance(alignment: EnergyBalanceWeightAlignment): String = when (alignment) {
        EnergyBalanceWeightAlignment.ALIGNED_DEFICIT ->
            "方向は揃っています。成否は推定収支ではなく、28日の減量ペースと行動KPIで判断します"
        EnergyBalanceWeightAlignment.ALIGNED_SURPLUS ->
            "方向は揃っています。単日ではなく今後2週間の食事パターンと歩数を確認してください"
        EnergyBalanceWeightAlignment.ALIGNED_STABLE ->
            "大きく崩れていません。変更するなら小さな一歩に留めます"
        EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP ->
            "未記録の食事・調味・飲酒、測定条件、むくみの影響を先に確認し、赤字をさらに強めないでください"
        EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN ->
            "デバイス消費の過大評価や水分変動の可能性があります。摂取をさらに減らす根拠にはしません"
        EnergyBalanceWeightAlignment.INSUFFICIENT_DATA ->
            "食事記録と体重測定が揃ってから対照します"
    }
}

enum class EnergyBalanceWeightAlignment {
    INSUFFICIENT_DATA,
    ALIGNED_DEFICIT,
    ALIGNED_SURPLUS,
    ALIGNED_STABLE,
    MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP,
    MISMATCH_SURPLUS_WEIGHT_DOWN,
}

data class EnergyBalanceWeightAnalysis(
    val intakeDailyAverageKcal: Double?,
    val expenditureDailyAverageKcal: Double?,
    val basalDailyAverageKcal: Double?,
    val activeDailyAverageKcal: Double?,
    val estimatedBalanceDailyAverageKcal: Double?,
    val impliedWeightChangeKgPerWeek: Double?,
    val observedWeightTrendKgPerWeek: Double?,
    val observedWeekOverWeekWeightChangeKg: Double?,
    val gapKgPerWeek: Double?,
    val alignment: EnergyBalanceWeightAlignment,
    val summary: String,
    val guidance: String,
) {
    val title: String = when (alignment) {
        EnergyBalanceWeightAlignment.INSUFFICIENT_DATA -> "収支と体重の対照は保留"
        EnergyBalanceWeightAlignment.ALIGNED_DEFICIT -> "収支と体重は減少方向で整合"
        EnergyBalanceWeightAlignment.ALIGNED_SURPLUS -> "収支と体重は増加方向で整合"
        EnergyBalanceWeightAlignment.ALIGNED_STABLE -> "収支と体重はほぼ横ばい"
        EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP ->
            "推定赤字でも体重は減っていない"
        EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN ->
            "推定黒字でも体重は減っている"
    }

    val isMismatch: Boolean =
        alignment == EnergyBalanceWeightAlignment.MISMATCH_DEFICIT_WEIGHT_STABLE_OR_UP ||
            alignment == EnergyBalanceWeightAlignment.MISMATCH_SURPLUS_WEIGHT_DOWN
}
