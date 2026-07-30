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
)

@Serializable
data class AdviceResponse(
    val summary: String,
    val positiveChange: String? = null,
    val caution: String? = null,
    val nextActions: List<String> = emptyList(),
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

