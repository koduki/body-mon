package com.master.healthcoach.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "daily_health_summary")
data class DailyHealthSummaryEntity(
    @PrimaryKey val date: String,
    val steps: Long?,
    val distanceMeters: Double?,
    val activeCaloriesKcal: Double?,
    val exerciseMinutes: Long,
    val strengthMinutes: Long,
    @ColumnInfo(defaultValue = "0") val morningRoutineMinutes: Long = 0,
    val cardioMinutes: Long,
    val exerciseSessionCount: Int,
    val sleepMinutes: Long?,
    val sleepSessionMinutes: Long? = null,
    @ColumnInfo(defaultValue = "'legacy_session'") val sleepDurationSource: String = "legacy_session",
    @ColumnInfo(defaultValue = "0") val cyclingMinutes: Long = 0,
    val sleepStartEpochMillis: Long? = null,
    val sleepEndEpochMillis: Long? = null,
    val sleepHeartRateAverageBpm: Long? = null,
    val moderateIntensityMinutes: Long?,
    val vigorousIntensityMinutes: Long?,
    val heartRateAverageBpm: Long?,
    val heartRateMinimumBpm: Long?,
    val heartRateMaximumBpm: Long?,
    val heartRateMeasurementCount: Long?,
    val basalCaloriesKcal: Double?,
    val intakeCaloriesKcal: Double? = null,
    val proteinGrams: Double? = null,
    val totalFatGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    @ColumnInfo(defaultValue = "0") val mealCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val nutritionComplete: Boolean = false,
    @ColumnInfo(defaultValue = "'legacy'") val nutritionRecordStatus: String = "legacy",
    @ColumnInfo(defaultValue = "'{}'") val metricOriginsJson: String = "{}",
    val heartRateCoverageMinutes: Int? = null,
    val heartRateOutsideExerciseSamples: Int? = null,
    @ColumnInfo(defaultValue = "0") val hikingMinutes: Long = 0,
    @ColumnInfo(defaultValue = "0") val hiitMinutes: Long = 0,
    val dataOrigins: String,
    val updatedAtEpochMillis: Long,
)

val DailyHealthSummaryEntity.estimatedEnergyBalanceKcal: Double?
    get() {
        if (!nutritionComplete) return null
        val intake = intakeCaloriesKcal ?: return null
        val basal = basalCaloriesKcal ?: return null
        val active = activeCaloriesKcal ?: return null
        return intake - basal - active
    }

@Serializable
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

@Serializable
@Entity(tableName = "health_source_status")
data class HealthSourceStatusEntity(
    @PrimaryKey val recordType: String,
    val recordCount: Int,
    val latestRecordEpochMillis: Long?,
    val origins: String,
    val status: String,
    val checkedAtEpochMillis: Long,
)

@Serializable
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

@Serializable
@Entity(tableName = "nutrition_meals")
data class NutritionMealEntity(
    @PrimaryKey val recordId: String,
    val date: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val mealLabel: String,
    val mealType: Int,
    val intakeCaloriesKcal: Double?,
    val proteinGrams: Double?,
    val totalFatGrams: Double?,
    val carbohydrateGrams: Double?,
    val recordCount: Int,
    val dataOrigin: String,
    @ColumnInfo(defaultValue = "0") val isDailyRecord: Boolean = false,
)

@Serializable
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: Int = 1,
    val age: Int? = null,
    val heightCm: Double? = null,
    val sex: String? = null,
    val dietStartDate: String? = null,
    val deadline: String? = null,
    val targetFatMassKg: Double? = null,
    val minimumLeanMassKg: Double? = null,
    val dailySteps: Long? = null,
    val weeklyExerciseSessions: Int? = null,
    val dailyActiveCaloriesKcal: Double? = null,
    val monthlyWeightLossKg: Double? = null,
)

@Serializable
@Entity(tableName = "weekly_reports")
data class WeeklyReportEntity(
    @PrimaryKey val weekStart: String,
    val snapshotJson: String,
    val generatedAtEpochMillis: Long,
)

@Serializable
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val attachmentNames: String? = null,
    val createdAtEpochMillis: Long,
    val modelId: String? = null,
    val appVersionName: String? = null,
    val policyVersion: String? = null,
    val toolTraceJson: String? = null,
)

@Serializable
@Entity(tableName = "conversation_memory")
data class ConversationMemoryEntity(
    @PrimaryKey val id: Int = 1,
    val summary: String,
    val summarizedThroughMessageId: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
@Entity(tableName = "app_events")
data class AppEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val category: String,
    val outcome: String,
    val durationMs: Long,
    val correlationId: String,
    val attributesJson: String,
    val appVersion: String,
    val commitSha: String,
)

@Entity(tableName = "response_evidence")
data class ResponseEvidenceEntity(
    @PrimaryKey val messageId: Long,
    val timestamp: Long,
    val encryptedPayload: String,
)

@Serializable
@Entity(tableName = "report_archive")
data class ReportArchiveEntity(
    @PrimaryKey val id: String,
    val periodStart: String,
    val periodEnd: String,
    val generatedAt: Long,
    val revision: Long,
    val policyVersion: String,
    val commitSha: String,
    val snapshotJson: String,
    val inputsJson: String,
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(@PrimaryKey val key: String, val value: String)
