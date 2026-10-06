package com.master.healthcoach.domain

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A reference range in kcal. Never a single "true" value. */
data class EnergyRange(val lowKcal: Double, val highKcal: Double) {
    val widthKcal: Double get() = highKcal - lowKcal
}

/** One day's step-model and device values for the same day. */
data class ActiveEnergyEstimate(
    val stepModelKcal: Double,
    val deviceKcal: Double,
) {
    /** Step model tends to undercount non-step activity; the band tends to overcount. */
    val range: EnergyRange = EnergyRange(
        lowKcal = min(stepModelKcal, deviceKcal),
        highKcal = max(stepModelKcal, deviceKcal),
    )
}

/** Averages over days where both values exist. Values are rounded for display. */
data class ActiveEnergySummary(
    val stepModelDailyAverageKcal: Double,
    val deviceDailyAverageKcal: Double,
    val range: EnergyRange,
    val validDays: Int,
)

data class EnergyBalanceSummary(
    val range: EnergyRange,
    val validDays: Int,
)

/**
 * Reference estimate of active energy expenditure from steps and workout sessions.
 *
 * It complements, and never replaces, the device value: steps miss standing, desk and
 * household movement (so this is a lower-leaning estimate), while the band can pick up
 * wrist motion and heart-rate spikes (so it is an upper-leaning estimate). Results are
 * therefore expressed as a range. Nothing here is persisted or used for verdicts.
 *
 * Constants are fixed and versioned on purpose: tuning them to hit a desired number
 * would make the metric meaningless.
 */
object EnergyModel {
    const val VERSION = "2026-10-step-v1"

    /** Net (above-rest) cost of level walking, kcal per kg per km. */
    const val WALKING_NET_KCAL_PER_KG_KM = 0.5

    /** Net MET = Compendium gross MET - 1. 1 MET is treated as 1 kcal/kg/h. */
    const val MORNING_ROUTINE_NET_MET = 3.0
    const val STRENGTH_NET_MET = 2.5

    const val STRIDE_RATIO_FEMALE = 0.413
    const val STRIDE_RATIO_MALE = 0.415
    const val STRIDE_RATIO_UNKNOWN = 0.414

    /** Health Connect distance is used only when metres per step looks plausible. */
    const val MIN_STEP_LENGTH_METERS = 0.4
    const val MAX_STEP_LENGTH_METERS = 1.1

    const val WEIGHT_WINDOW_DAYS = 7L
    const val WEIGHT_FALLBACK_DAYS = 30L
    const val MIN_VALID_DAYS = 4
    const val DISPLAY_ROUNDING_KCAL = 10.0

    const val INTERPRETATION =
        "歩数と運動時間から換算した参考値で、立位・家事などの活動は含まれないため下限寄りです。" +
            "バンド値は手の動きや心拍で上振れしやすく上限寄りです。" +
            "どちらが正しいかは断定せず、体重の28日傾向を優先してください。" +
            "診断や摂取量の指示には使いません。"

    /**
     * Weight as known on [date]: median of the 7 days up to [date], otherwise the most
     * recent value within 30 days. Later measurements never rewrite earlier days.
     */
    fun weightKgOn(date: LocalDate, body: List<BodyCompositionEntity>): Double? {
        val dated = body.mapNotNull { item ->
            val weight = item.weightKg?.takeIf { it in 20.0..400.0 } ?: return@mapNotNull null
            val itemDate = runCatching { LocalDate.parse(item.date) }.getOrNull()
                ?: return@mapNotNull null
            if (itemDate > date) null else itemDate to weight
        }
        val windowStart = date.minusDays(WEIGHT_WINDOW_DAYS - 1)
        TrendMath.median(dated.filter { it.first >= windowStart }.map { it.second })
            ?.let { return it }
        val fallbackStart = date.minusDays(WEIGHT_FALLBACK_DAYS)
        return dated
            .filter { it.first >= fallbackStart }
            .maxByOrNull { it.first }
            ?.second
    }

    fun strideRatio(sex: String?): Double {
        val normalized = sex?.trim()?.lowercase().orEmpty()
        return when {
            normalized.isEmpty() -> STRIDE_RATIO_UNKNOWN
            normalized.startsWith("female") || normalized == "f" || normalized.contains("女") ->
                STRIDE_RATIO_FEMALE
            normalized.startsWith("male") || normalized == "m" || normalized.contains("男") ->
                STRIDE_RATIO_MALE
            else -> STRIDE_RATIO_UNKNOWN
        }
    }

    /**
     * Step/workout based active energy for one day, or null when an input is missing.
     * Zero steps is a real zero; a null step count is missing.
     *
     * Walking/running/cycling sessions are not added: walking is already in steps, and
     * other cardio cannot be separated from steps reliably in v1.
     */
    fun stepModelActiveKcal(
        day: DailyHealthSummaryEntity,
        weightKg: Double?,
        heightCm: Double?,
        sex: String?,
    ): Double? {
        val weight = weightKg?.takeIf { it > 0 } ?: return null
        val steps = day.steps ?: return null
        val walkingKm = when {
            steps <= 0L -> 0.0
            else -> usableDistanceKm(day.distanceMeters, steps)
                ?: estimatedDistanceKm(steps, heightCm, sex)
                ?: return null
        }
        val walking = walkingKm * weight * WALKING_NET_KCAL_PER_KG_KM
        val sessions = (
            day.morningRoutineMinutes.coerceAtLeast(0) * MORNING_ROUTINE_NET_MET +
                day.strengthMinutes.coerceAtLeast(0) * STRENGTH_NET_MET
            ) * weight / 60.0
        return walking + sessions
    }

    private fun usableDistanceKm(distanceMeters: Double?, steps: Long): Double? {
        val meters = distanceMeters?.takeIf { it > 0 } ?: return null
        val perStep = meters / steps
        return if (perStep in MIN_STEP_LENGTH_METERS..MAX_STEP_LENGTH_METERS) {
            meters / 1_000.0
        } else {
            null
        }
    }

    private fun estimatedDistanceKm(steps: Long, heightCm: Double?, sex: String?): Double? {
        val height = heightCm?.takeIf { it in 100.0..250.0 } ?: return null
        return steps * height * strideRatio(sex) / 100.0 / 1_000.0
    }

    /** Daily estimate; null unless both the step model and the device value exist. */
    fun dailyActive(
        day: DailyHealthSummaryEntity,
        body: List<BodyCompositionEntity>,
        heightCm: Double?,
        sex: String?,
    ): ActiveEnergyEstimate? {
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return null
        val device = day.activeCaloriesKcal ?: return null
        val model = stepModelActiveKcal(day, weightKgOn(date, body), heightCm, sex)
            ?: return null
        return ActiveEnergyEstimate(stepModelKcal = model, deviceKcal = device)
    }

    fun summarizeActive(
        days: List<DailyHealthSummaryEntity>,
        body: List<BodyCompositionEntity>,
        heightCm: Double?,
        sex: String?,
    ): ActiveEnergySummary? {
        val estimates = days.mapNotNull { dailyActive(it, body, heightCm, sex) }
        if (estimates.size < MIN_VALID_DAYS) return null
        val model = estimates.map { it.stepModelKcal }.average()
        val device = estimates.map { it.deviceKcal }.average()
        return ActiveEnergySummary(
            stepModelDailyAverageKcal = roundForDisplay(model),
            deviceDailyAverageKcal = roundForDisplay(device),
            range = EnergyRange(
                lowKcal = roundForDisplay(min(model, device)),
                highKcal = roundForDisplay(max(model, device)),
            ),
            validDays = estimates.size,
        )
    }

    /** Intake - basal - active, as a range. Null unless intake and basal are recorded. */
    fun dailyBalance(
        day: DailyHealthSummaryEntity,
        estimate: ActiveEnergyEstimate?,
    ): EnergyRange? {
        val intake = day.intakeCaloriesKcal ?: return null
        val basal = day.basalCaloriesKcal ?: return null
        val active = estimate?.range ?: return null
        return EnergyRange(
            lowKcal = intake - basal - active.highKcal,
            highKcal = intake - basal - active.lowKcal,
        )
    }

    fun summarizeBalance(
        days: List<DailyHealthSummaryEntity>,
        body: List<BodyCompositionEntity>,
        heightCm: Double?,
        sex: String?,
    ): EnergyBalanceSummary? {
        val ranges = days.mapNotNull { day ->
            dailyBalance(day, dailyActive(day, body, heightCm, sex))
        }
        if (ranges.size < MIN_VALID_DAYS) return null
        return EnergyBalanceSummary(
            range = EnergyRange(
                lowKcal = roundForDisplay(ranges.map { it.lowKcal }.average()),
                highKcal = roundForDisplay(ranges.map { it.highKcal }.average()),
            ),
            validDays = ranges.size,
        )
    }

    fun roundForDisplay(kcal: Double): Double =
        (kcal / DISPLAY_ROUNDING_KCAL).roundToLong() * DISPLAY_ROUNDING_KCAL

    const val MIN_ADAPTIVE_TDEE_INTAKE_DAYS = 21
    const val TDEE_DENSITY_LOW_KCAL_PER_KG = 6_000.0
    const val TDEE_DENSITY_HIGH_KCAL_PER_KG = 7_700.0

    /**
     * Retrospective energy expenditure check using 28-day weight trend and intake logs.
     * Requires at least 21 days of nutrition and an established weight trend.
     */
    fun checkAdaptiveTdee(
        weightTrendKgPerWeek: Double?,
        intakeDailyAverageKcal: Double?,
        intakeDaysInTrend: Int,
        basalCaloriesDailyAverage: Double?,
        deviceActiveDailyAverage: Double?,
        stepModelActiveDailyAverage: Double?,
    ): AdaptiveTdeeCheck? {
        if (intakeDaysInTrend < MIN_ADAPTIVE_TDEE_INTAKE_DAYS) return null
        val intake = intakeDailyAverageKcal ?: return null
        val weightTrend = weightTrendKgPerWeek ?: return null
        val basal = basalCaloriesDailyAverage ?: return null

        val perDaySlope = weightTrend / 7.0
        val burn1 = intake - (perDaySlope * TDEE_DENSITY_LOW_KCAL_PER_KG)
        val burn2 = intake - (perDaySlope * TDEE_DENSITY_HIGH_KCAL_PER_KG)

        val inferredRange = EnergyRange(
            lowKcal = roundForDisplay(min(burn1, burn2)),
            highKcal = roundForDisplay(max(burn1, burn2)),
        )
        val deviceTdee = deviceActiveDailyAverage?.let { roundForDisplay(basal + it) }
        val stepTdee = stepModelActiveDailyAverage?.let { roundForDisplay(basal + it) }

        return AdaptiveTdeeCheck(
            intakeDailyAverageKcal = roundForDisplay(intake),
            weightTrendKgPerWeek = weightTrend,
            inferredTdeeRange = inferredRange,
            deviceTdeeKcal = deviceTdee,
            stepModelTdeeKcal = stepTdee,
            intakeDays = intakeDaysInTrend,
            note = "28日間の食事記録と体重トレンドから逆算した実効消費です。" +
                "自己申告の食事記録は過少になりやすい点に注意し、3値の相対比較として見てください。" +
                "摂取量の指示ではありません。",
        )
    }
}

data class AdaptiveTdeeCheck(
    val intakeDailyAverageKcal: Double,
    val weightTrendKgPerWeek: Double,
    val inferredTdeeRange: EnergyRange,
    val deviceTdeeKcal: Double?,
    val stepModelTdeeKcal: Double?,
    val intakeDays: Int,
    val note: String,
)

