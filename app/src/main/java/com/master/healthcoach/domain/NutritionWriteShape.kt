package com.master.healthcoach.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Health Connect の栄養レコードが「1日合計」か「食事単位」かを実機で見分けるための診断。
 * 食事回数そのものは start/end クラスタ（`NutritionMealClusterer`）で数える。
 */
data class NutritionInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val mealType: Int,
)

data class NutritionWriteShape(
    val recordDays: Int,
    val medianRecordsPerDay: Double?,
    val mealTypeLabels: List<String>,
    val medianDurationHours: Double?,
    val likelyDailyTotals: Boolean,
    val looksMealScoped: Boolean,
    val summary: String,
) {
    companion object {
        const val MEAL_TYPE_UNKNOWN = 0
        const val MEAL_TYPE_BREAKFAST = 1
        const val MEAL_TYPE_LUNCH = 2
        const val MEAL_TYPE_DINNER = 3
        const val MEAL_TYPE_SNACK = 4

        fun inspect(
            records: List<NutritionInterval>,
            zoneId: ZoneId,
        ): NutritionWriteShape {
            if (records.isEmpty()) {
                return NutritionWriteShape(
                    recordDays = 0,
                    medianRecordsPerDay = null,
                    mealTypeLabels = emptyList(),
                    medianDurationHours = null,
                    likelyDailyTotals = false,
                    looksMealScoped = false,
                    summary = "同期範囲に栄養レコードなし。食事回数はstart/endのクラスタから確定できない",
                )
            }
            val byDay = records.groupBy { record ->
                Instant.ofEpochMilli(record.startEpochMillis)
                    .atZone(zoneId)
                    .toLocalDate()
            }
            val counts = byDay.values.map { it.size.toDouble() }
            val medianRecordsPerDay = TrendMath.median(counts)
            val durationsHours = records.map { record ->
                (record.endEpochMillis - record.startEpochMillis)
                    .coerceAtLeast(0L) / 3_600_000.0
            }
            val medianDurationHours = TrendMath.median(durationsHours)
            val mealTypeLabels = records.map { mealTypeLabel(it.mealType) }
                .distinct()
                .sorted()
            val knownMealTypes = records.map { it.mealType }
                .filter { it in MEAL_TYPE_BREAKFAST..MEAL_TYPE_SNACK }
                .toSet()
            val onlyUnknownMealType = records.all { it.mealType == MEAL_TYPE_UNKNOWN }
            val likelyDailyTotals = (medianRecordsPerDay ?: 0.0) <= 1.5 &&
                (onlyUnknownMealType || (medianDurationHours ?: 0.0) >= 12.0)
            val looksMealScoped =
                ((medianRecordsPerDay ?: 0.0) >= 2.0 && knownMealTypes.size >= 2) ||
                    (
                        (medianRecordsPerDay ?: 0.0) >= 2.5 &&
                            (medianDurationHours ?: Double.MAX_VALUE) <= 3.0
                        )
            val durationText = medianDurationHours?.let { hours ->
                if (hours < 1.0) {
                    "区間中央値${(hours * 60.0).toInt()}分"
                } else {
                    "区間中央値${"%.1f".format(java.util.Locale.ROOT, hours)}時間"
                }
            } ?: "区間不明"
            val mealTypeText = if (mealTypeLabels.isEmpty()) {
                "mealTypeなし"
            } else {
                "mealTypeは${mealTypeLabels.joinToString("/")}"
            }
            val countText = medianRecordsPerDay?.let { count ->
                val display = if (count == count.toLong().toDouble()) {
                    count.toLong().toString()
                } else {
                    "%.1f".format(java.util.Locale.ROOT, count)
                }
                "1日あたり中央値${display}件"
            } ?: "件数不明"
            val interpretation = when {
                likelyDailyTotals ->
                    "日次合計の書き方に近く、区間が1日に近い場合は1食（日次合計）として扱う"
                looksMealScoped ->
                    "食事単位の可能性。start/endが近いレコードを1食にまとめて回数とPFCを出す"
                else ->
                    "粒度は未確定。start/endが近いレコードを1食として回数を数える"
            }
            return NutritionWriteShape(
                recordDays = byDay.size,
                medianRecordsPerDay = medianRecordsPerDay,
                mealTypeLabels = mealTypeLabels,
                medianDurationHours = medianDurationHours,
                likelyDailyTotals = likelyDailyTotals,
                looksMealScoped = looksMealScoped,
                summary = listOf(
                    "${byDay.size}日分",
                    countText,
                    mealTypeText,
                    durationText,
                    interpretation,
                ).joinToString("・"),
            )
        }

        fun mealTypeLabel(mealType: Int): String = when (mealType) {
            MEAL_TYPE_UNKNOWN -> "不明"
            MEAL_TYPE_BREAKFAST -> "朝"
            MEAL_TYPE_LUNCH -> "昼"
            MEAL_TYPE_DINNER -> "夕"
            MEAL_TYPE_SNACK -> "間食"
            else -> "その他($mealType)"
        }
    }
}
