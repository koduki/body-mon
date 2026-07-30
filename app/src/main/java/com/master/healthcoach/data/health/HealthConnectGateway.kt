package com.master.healthcoach.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.domain.BodyCompositionCalculator
import com.master.healthcoach.domain.TimedMeasurement
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthSyncBundle(
    val daily: List<DailyHealthSummaryEntity>,
    val body: List<BodyCompositionEntity>,
    val sources: List<HealthSourceStatusEntity>,
    val exerciseSessions: List<ExerciseSessionEntity>,
)

enum class HealthConnectAvailability {
    AVAILABLE,
    UPDATE_REQUIRED,
    UNAVAILABLE,
}

class HealthConnectGateway(private val context: Context) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val corePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    fun availability(): HealthConnectAvailability = when (
        HealthConnectClient.getSdkStatus(context)
    ) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HealthConnectAvailability.UPDATE_REQUIRED
        else -> HealthConnectAvailability.UNAVAILABLE
    }

    fun backgroundReadAvailable(): Boolean =
        availability() == HealthConnectAvailability.AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun requestedPermissions(): Set<String> = buildSet {
        addAll(corePermissions)
        if (backgroundReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    suspend fun grantedPermissions(): Set<String> =
        if (availability() == HealthConnectAvailability.AVAILABLE) {
            client.permissionController.getGrantedPermissions()
        } else {
            emptySet()
        }

    suspend fun hasCorePermissions(): Boolean = grantedPermissions().containsAll(corePermissions)

    suspend fun hasBackgroundPermission(): Boolean =
        !backgroundReadAvailable() ||
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in grantedPermissions()

    suspend fun sync(days: Long = 28, zoneId: ZoneId = ZoneId.systemDefault()): HealthSyncBundle {
        check(availability() == HealthConnectAvailability.AVAILABLE) {
            "Health Connect is not available"
        }

        val endDateExclusive = LocalDate.now(zoneId).plusDays(1)
        val startDate = endDateExclusive.minusDays(days)
        val start = startDate.atStartOfDay(zoneId).toInstant()
        val end = endDateExclusive.atStartOfDay(zoneId).toInstant()

        val weights = readAll<WeightRecord>(start, end)
        val bodyFats = readAll<BodyFatRecord>(start, end)
        val leanMasses = readAll<LeanBodyMassRecord>(start, end)
        val exercises = readAll<ExerciseSessionRecord>(start, end)
        val stepRecords = readAll<StepsRecord>(start, end)
        val distanceRecords = readAll<DistanceRecord>(start, end)
        val activeCalorieRecords = readAll<ActiveCaloriesBurnedRecord>(start, end)

        val daily = (0 until days).map { offset ->
            val date = startDate.plusDays(offset)
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val result = aggregateActivity(dayStart, dayEnd)
            val sessions = exercises.filter { session ->
                session.startTime >= dayStart && session.startTime < dayEnd
            }
            val strengthMinutes = sessions
                .filter { classifyExercise(it.exerciseType) == ExerciseClass.STRENGTH }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0) }
            val cardioMinutes = sessions
                .filter { classifyExercise(it.exerciseType) == ExerciseClass.CARDIO }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0) }
            val allMinutes = sessions.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0)
            }
            val origins = (
                sessions.filter { it.startTime >= dayStart && it.startTime < dayEnd }
                    .map { it.metadata.dataOrigin.packageName } +
                    stepRecords.filter { it.startTime >= dayStart && it.startTime < dayEnd }
                        .map { it.metadata.dataOrigin.packageName } +
                    distanceRecords.filter { it.startTime >= dayStart && it.startTime < dayEnd }
                        .map { it.metadata.dataOrigin.packageName } +
                    activeCalorieRecords.filter {
                        it.startTime >= dayStart && it.startTime < dayEnd
                    }.map { it.metadata.dataOrigin.packageName }
                )
                .distinct()
                .sorted()
                .joinToString(", ")

            DailyHealthSummaryEntity(
                date = date.toString(),
                steps = result[StepsRecord.COUNT_TOTAL],
                distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                activeCaloriesKcal = result[
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                ]?.inKilocalories,
                exerciseMinutes = allMinutes,
                strengthMinutes = strengthMinutes,
                cardioMinutes = cardioMinutes,
                exerciseSessionCount = sessions.size,
                dataOrigins = origins,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }

        val body = (0 until days).mapNotNull { offset ->
            val date = startDate.plusDays(offset)
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val calculation = BodyCompositionCalculator.calculate(
                weights = weights.filterIn(dayStart, dayEnd) {
                    TimedMeasurement(
                        it.time.toEpochMilli(),
                        it.weight.inKilograms,
                        it.metadata.dataOrigin.packageName,
                    )
                },
                bodyFatPercentages = bodyFats.filterIn(dayStart, dayEnd) {
                    TimedMeasurement(
                        it.time.toEpochMilli(),
                        it.percentage.value,
                        it.metadata.dataOrigin.packageName,
                    )
                },
                leanMasses = leanMasses.filterIn(dayStart, dayEnd) {
                    TimedMeasurement(
                        it.time.toEpochMilli(),
                        it.mass.inKilograms,
                        it.metadata.dataOrigin.packageName,
                    )
                },
            )
            if (calculation.weightKg == null) return@mapNotNull null
            BodyCompositionEntity(
                date = date.toString(),
                weightKg = calculation.weightKg,
                bodyFatPercent = calculation.bodyFatPercent,
                fatMassKg = calculation.fatMassKg,
                leanBodyMassKg = calculation.leanMassKg,
                leanMassSource = calculation.leanMassSource,
                measurementEpochMillis = calculation.measurementEpochMillis,
                dataOrigin = calculation.origin,
            )
        }

        val checkedAt = System.currentTimeMillis()
        val sources = listOf(
            sourceStatus("体重", weights, { it.time }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus("体脂肪率", bodyFats, { it.time }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus("除脂肪量", leanMasses, { it.time }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus("運動セッション", exercises, { it.startTime }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus("歩数", stepRecords, { it.startTime }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus("距離", distanceRecords, { it.startTime }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus(
                "活動消費",
                activeCalorieRecords,
                { it.startTime },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
            ),
        )

        val exerciseSessions = exercises.map { session ->
            val origin = session.metadata.dataOrigin.packageName
            ExerciseSessionEntity(
                recordId = "$origin:${session.metadata.id}",
                startEpochMillis = session.startTime.toEpochMilli(),
                endEpochMillis = session.endTime.toEpochMilli(),
                exerciseType = session.exerciseType,
                exerciseLabel = exerciseLabel(session.exerciseType),
                category = classifyExercise(session.exerciseType).name.lowercase(),
                durationMinutes = Duration.between(session.startTime, session.endTime)
                    .toMinutes().coerceAtLeast(0),
                dataOrigin = origin,
            )
        }

        return HealthSyncBundle(
            daily = daily,
            body = body,
            sources = sources,
            exerciseSessions = exerciseSessions,
        )
    }

    private suspend fun aggregateActivity(start: Instant, end: Instant): AggregationResult =
        client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ),
        )

    private suspend inline fun <reified T : Record> readAll(
        start: Instant,
        end: Instant,
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private inline fun <T> List<T>.filterIn(
        start: Instant,
        end: Instant,
        crossinline mapper: (T) -> TimedMeasurement,
    ): List<TimedMeasurement> = map(mapper).filter {
        val instant = Instant.ofEpochMilli(it.epochMillis)
        instant >= start && instant < end
    }

    private fun <T> sourceStatus(
        label: String,
        records: List<T>,
        time: (T) -> Instant,
        origin: (T) -> String,
        checkedAt: Long,
    ): HealthSourceStatusEntity = HealthSourceStatusEntity(
        recordType = label,
        recordCount = records.size,
        latestRecordEpochMillis = records.maxOfOrNull { time(it).toEpochMilli() },
        origins = records.map(origin).distinct().sorted().joinToString(", "),
        status = if (records.isEmpty()) "未取得" else "取得可能",
        checkedAtEpochMillis = checkedAt,
    )

    private enum class ExerciseClass { STRENGTH, CARDIO, OTHER }

    private fun classifyExercise(type: Int): ExerciseClass = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        -> ExerciseClass.STRENGTH

        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        -> ExerciseClass.CARDIO

        else -> ExerciseClass.OTHER
    }

    private fun exerciseLabel(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "筋力トレーニング"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "ウェイトリフティング"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "自重トレーニング"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "ウォーキング"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "ランニング"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "トレッドミル"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "サイクリング"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "エアロバイク"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "オープンウォータースイム"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "プール"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "ローイング"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "ローイングマシン"
        else -> "その他の運動"
    }
}
