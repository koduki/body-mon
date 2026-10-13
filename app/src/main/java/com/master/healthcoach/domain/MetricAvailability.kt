package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import java.time.LocalDate

/** Absence is a current integration limitation, never proof of permanent device incapability. */
enum class MetricAvailabilityState { AVAILABLE, CHECKING, NO_DATA_30_DAYS, PERMISSION_REQUIRED, UNSUPPORTED }
data class MetricAvailability(val label: String, val state: MetricAvailabilityState) {
    val hideEmptyMetric: Boolean get() = state in setOf(MetricAvailabilityState.NO_DATA_30_DAYS,
        MetricAvailabilityState.PERMISSION_REQUIRED, MetricAvailabilityState.UNSUPPORTED)
    val reason: String get() = when (state) {
        MetricAvailabilityState.NO_DATA_30_DAYS -> "現在の連携では取得不可（直近30日間データなし）"
        MetricAvailabilityState.PERMISSION_REQUIRED -> "権限が必要"
        MetricAvailabilityState.UNSUPPORTED -> "端末非対応"
        MetricAvailabilityState.CHECKING -> "確認中（30日分の同期が必要）"
        MetricAvailabilityState.AVAILABLE -> "取得あり"
    }
}

object MetricAvailabilityPolicy {
    fun evaluate(label: String, observed: Boolean, checkedDays: Int, sourceStatus: String?, fresh: Boolean): MetricAvailability {
        val state = when {
            observed -> MetricAvailabilityState.AVAILABLE
            sourceStatus == "権限なし" -> MetricAvailabilityState.PERMISSION_REQUIRED
            sourceStatus == "端末非対応" -> MetricAvailabilityState.UNSUPPORTED
            fresh && checkedDays >= 30 -> MetricAvailabilityState.NO_DATA_30_DAYS
            else -> MetricAvailabilityState.CHECKING
        }
        return MetricAvailability(label, state)
    }

    fun inspect(daily: List<DailyHealthSummaryEntity>, body: List<BodyCompositionEntity>,
        sources: List<HealthSourceStatusEntity>, today: LocalDate = LocalDate.now(), nowMillis: Long = System.currentTimeMillis()): List<MetricAvailability> {
        // Thirty completed dates plus today's new data (a new value immediately restores the UI).
        val from = today.minusDays(30).toString()
        val to = today.toString()
        val rows = daily.filter { it.date >= from && it.date <= to }
        val bodies = body.filter { it.date >= from && it.date <= to }
        val checkedDays = rows.filter { it.date < to && nowMillis - it.updatedAtEpochMillis in 0..(48 * 60 * 60 * 1000L) }.map { it.date }.distinct().size
        val result = mutableListOf<MetricAvailability>()
        fun metric(label: String, source: String, seen: Boolean) {
            val status = sources.firstOrNull { it.recordType == source }
            val fresh = status != null && nowMillis - status.checkedAtEpochMillis in 0..(48 * 60 * 60 * 1000L)
            result += evaluate(label, seen, checkedDays, status?.status, fresh)
        }
        metric("歩数", "歩数", rows.any { it.steps != null })
        metric("距離", "距離", rows.any { it.distanceMeters != null })
        metric("活動消費", "活動消費", rows.any { it.activeCaloriesKcal != null })
        metric("基礎代謝", "基礎代謝", rows.any { it.basalCaloriesKcal != null })
        metric("中強度", "アクティビティ強度", rows.any { it.moderateIntensityMinutes != null })
        metric("高強度", "アクティビティ強度", rows.any { it.vigorousIntensityMinutes != null })
        metric("睡眠時間", "睡眠", rows.any { it.sleepMinutes != null })
        metric("睡眠中心拍", "心拍数", rows.any { it.sleepHeartRateAverageBpm != null })
        metric("心拍数", "心拍数", rows.any { it.heartRateAverageBpm != null })
        metric("摂取カロリー", "栄養（摂取）", rows.any { it.intakeCaloriesKcal != null })
        metric("たんぱく質", "栄養（摂取）", rows.any { it.proteinGrams != null })
        metric("脂質", "栄養（摂取）", rows.any { it.totalFatGrams != null })
        metric("炭水化物", "栄養（摂取）", rows.any { it.carbohydrateGrams != null })
        metric("食事回数", "栄養（摂取）", rows.any { it.nutritionRecordStatus == "meal_records_unverified" && it.mealCount > 0 })
        metric("体重", "体重", bodies.any { it.weightKg != null })
        metric("体脂肪率", "体脂肪率", bodies.any { it.bodyFatPercent != null })
        metric("脂肪量", "体脂肪率", bodies.any { it.fatMassKg != null })
        metric("除脂肪量", "体脂肪率", bodies.any { it.leanBodyMassKg != null })
        return result
    }

    fun labelFor(title: String): String = when (title) {
        "中強度換算", "中強度換算の活動" -> "中強度"
        "推定睡眠時間", "睡眠区間合計（一部欠測）", "睡眠記録枠（代用）", "睡眠", "主睡眠", "睡眠時間・代用値", "6時間以上", "就寝時刻のばらつき" -> "睡眠時間"
        "本日の心拍" -> "心拍数"
        "1日平均歩数" -> "歩数"
        "除脂肪量（計算値）", "除脂肪量 28日" -> "除脂肪量"
        "脂肪量 28日" -> "脂肪量"
        "体重 28日", "実測の体重傾向" -> "体重"
        "摂取" -> "摂取カロリー"
        else -> title
    }
}
