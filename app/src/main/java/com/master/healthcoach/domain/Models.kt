package com.master.healthcoach.domain

import kotlinx.serialization.Serializable

data class TimedMeasurement(
    val epochMillis: Long,
    val value: Double,
    val origin: String,
)

@Serializable
data class WeeklySnapshot(
    val weekStart: String,
    val weekEnd: String,
    val fatMassChangeKg: Double?,
    val leanMassChangeKg: Double?,
    val weightChangeKg: Double?,
    val bodyMeasurementDays: Int,
    val stepsDailyAverage: Long?,
    val activeCaloriesDailyAverage: Double?,
    val exerciseSessions: Int,
    val exerciseMinutes: Long,
    val strengthMinutes: Long,
    val cardioMinutes: Long,
    val previousWeekStepsDailyAverage: Long?,
    val previousWeekActiveCaloriesDailyAverage: Double?,
    val dataLimitations: List<String>,
    val sleepDailyAverageMinutes: Long? = null,
    val sleepMeasurementDays: Int = 0,
    val moderateIntensityMinutes: Long? = null,
    val vigorousIntensityMinutes: Long? = null,
    val heartRateAverageBpm: Long? = null,
    val heartRateMinimumBpm: Long? = null,
    val heartRateMaximumBpm: Long? = null,
    val heartRateMeasurementDays: Int = 0,
    val basalCaloriesDailyAverage: Double? = null,
    val basalCaloriesMeasurementDays: Int = 0,
    val previousWeekSleepDailyAverageMinutes: Long? = null,
    val previousWeekModerateIntensityMinutes: Long? = null,
    val previousWeekVigorousIntensityMinutes: Long? = null,
    val previousWeekHeartRateAverageBpm: Long? = null,
    val previousWeekBasalCaloriesDailyAverage: Double? = null,
)

@Serializable
data class AdviceResponse(
    val summary: String,
    val positiveChange: String? = null,
    val caution: String? = null,
    val nextActions: List<String> = emptyList(),
    val habitInsights: List<String> = emptyList(),
    val confidence: String = "medium",
    val dataLimitations: List<String> = emptyList(),
)

data class BodyCalculation(
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    val fatMassKg: Double?,
    val leanMassKg: Double?,
    val leanMassSource: String?,
    val measurementEpochMillis: Long?,
    val origin: String?,
)
