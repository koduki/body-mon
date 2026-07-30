package com.master.healthcoach.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_health_summary")
data class DailyHealthSummaryEntity(
    @PrimaryKey val date: String,
    val steps: Long?,
    val distanceMeters: Double?,
    val activeCaloriesKcal: Double?,
    val exerciseMinutes: Long,
    val strengthMinutes: Long,
    val cardioMinutes: Long,
    val exerciseSessionCount: Int,
    val sleepMinutes: Long?,
    val moderateIntensityMinutes: Long?,
    val vigorousIntensityMinutes: Long?,
    val heartRateAverageBpm: Long?,
    val heartRateMinimumBpm: Long?,
    val heartRateMaximumBpm: Long?,
    val heartRateMeasurementCount: Long?,
    val basalCaloriesKcal: Double?,
    val dataOrigins: String,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "body_composition_daily")
data class BodyCompositionEntity(
    @PrimaryKey val date: String,
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    val fatMassKg: Double?,
    val leanBodyMassKg: Double?,
    val leanMassSource: String?,
    val measurementEpochMillis: Long?,
    val dataOrigin: String?,
)

@Entity(tableName = "health_source_status")
data class HealthSourceStatusEntity(
    @PrimaryKey val recordType: String,
    val recordCount: Int,
    val latestRecordEpochMillis: Long?,
    val origins: String,
    val status: String,
    val checkedAtEpochMillis: Long,
)

@Entity(tableName = "exercise_sessions")
data class ExerciseSessionEntity(
    @PrimaryKey val recordId: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val exerciseType: Int,
    val exerciseLabel: String,
    val category: String,
    val durationMinutes: Long,
    val dataOrigin: String,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: Int = 1,
    val age: Int? = null,
    val heightCm: Double? = null,
    val sex: String? = null,
    val deadline: String? = null,
    val targetFatMassKg: Double? = null,
    val minimumLeanMassKg: Double? = null,
    val dailySteps: Long? = null,
    val weeklyExerciseSessions: Int? = null,
    val dailyActiveCaloriesKcal: Double? = null,
)

@Entity(tableName = "weekly_reports")
data class WeeklyReportEntity(
    @PrimaryKey val weekStart: String,
    val snapshotJson: String,
    val generatedAtEpochMillis: Long,
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "conversation_memory")
data class ConversationMemoryEntity(
    @PrimaryKey val id: Int = 1,
    val summary: String,
    val summarizedThroughMessageId: Long,
    val updatedAtEpochMillis: Long,
)
