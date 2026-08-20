package com.master.healthcoach.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ActivityIntensityRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import com.master.healthcoach.domain.BodyCompositionCalculator
import com.master.healthcoach.domain.TimedMeasurement
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

data class HealthSyncBundle(
    val daily: List<DailyHealthSummaryEntity>,
    val body: List<BodyCompositionEntity>,
    val sources: List<HealthSourceStatusEntity>,
    val exerciseSessions: List<ExerciseSessionEntity>,
    val rangeStartEpochMillis: Long,
    val rangeEndEpochMillisExclusive: Long,
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
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    private val sleepPermission =
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    private val heartRatePermission =
        HealthPermission.getReadPermission(HeartRateRecord::class)
    private val basalMetabolicRatePermission =
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class)
    private val activityIntensityPermission =
        HealthPermission.getReadPermission(ActivityIntensityRecord::class)
    private val nutritionPermission =
        HealthPermission.getReadPermission(NutritionRecord::class)

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

    fun activityIntensityAvailable(): Boolean =
        availability() == HealthConnectAvailability.AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_ACTIVITY_INTENSITY,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun historyReadAvailable(): Boolean =
        availability() == HealthConnectAvailability.AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun requestedPermissions(): Set<String> = buildSet {
        addAll(corePermissions)
        add(sleepPermission)
        add(heartRatePermission)
        add(basalMetabolicRatePermission)
        add(nutritionPermission)
        if (activityIntensityAvailable()) add(activityIntensityPermission)
        if (backgroundReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
        if (historyReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
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

    suspend fun hasHistoryPermission(): Boolean =
        historyReadAvailable() &&
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions()

    suspend fun sync(days: Long = 28, zoneId: ZoneId = ZoneId.systemDefault()): HealthSyncBundle {
        check(availability() == HealthConnectAvailability.AVAILABLE) {
            "Health Connect is not available"
        }
        val granted = grantedPermissions()
        check(granted.containsAll(corePermissions)) {
            "Health Connectの必須データ権限が不足しています"
        }
        val canReadSleep = sleepPermission in granted
        val canReadHeartRate = heartRatePermission in granted
        val canReadBasalRate = basalMetabolicRatePermission in granted
        val intensitySupported = activityIntensityAvailable()
        val canReadIntensity = intensitySupported && activityIntensityPermission in granted
        val canReadNutrition = nutritionPermission in granted

        val endDateExclusive = LocalDate.now(zoneId).plusDays(1)
        val startDate = endDateExclusive.minusDays(days)
        val start = startDate.atStartOfDay(zoneId).toInstant()
        val end = endDateExclusive.atStartOfDay(zoneId).toInstant()

        val weights = readAll<WeightRecord>(start, end)
        val bodyFats = readAll<BodyFatRecord>(start, end)
        val exercises = readAll<ExerciseSessionRecord>(start, end)
        val stepRecords = readAll<StepsRecord>(start, end)
        val distanceRecords = readAll<DistanceRecord>(start, end)
        val activeCalorieRecords = readAll<ActiveCaloriesBurnedRecord>(start, end)
        val sleepRecords = readIfGranted<SleepSessionRecord>(
            canReadSleep,
            start.minus(Duration.ofDays(1)),
            end,
        )
        val heartRateRecords = readIfGranted<HeartRateRecord>(
            canReadHeartRate,
            start.minus(Duration.ofDays(1)),
            end,
        )
        val basalRateRecords = readIfGranted<BasalMetabolicRateRecord>(
            canReadBasalRate,
            start,
            end,
        )
        val intensityRecords = readIfGranted<ActivityIntensityRecord>(
            canReadIntensity,
            start,
            end,
        )
        val nutritionRecords = readIfGranted<NutritionRecord>(
            canReadNutrition,
            start,
            end,
        )

        val daily = (0 until days).map { offset ->
            val date = startDate.plusDays(offset)
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val mainSleep = sleepRecords
                .filter { it.endTime > dayStart && it.endTime <= dayEnd }
                .maxByOrNull { Duration.between(it.startTime, it.endTime) }
            val sleepHeartRateSamples = mainSleep?.let { sleep ->
                heartRateRecords.flatMap { it.samples }.filter { sample ->
                    sample.time >= sleep.startTime && sample.time <= sleep.endTime
                }
            }.orEmpty()
            val result = aggregateDaily(
                start = dayStart,
                end = dayEnd,
                includeHeartRate = canReadHeartRate,
                includeBasalRate = canReadBasalRate,
                includeIntensity = canReadIntensity,
                includeNutrition = canReadNutrition,
            )
            val sessions = exercises.filter { session ->
                session.startTime >= dayStart && session.startTime < dayEnd
            }
            val strengthMinutes = sessions
                .filter { classifyExercise(it.exerciseType) == ExerciseClass.STRENGTH }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0) }
            val morningRoutineMinutes = sessions
                .filter { classifyExercise(it.exerciseType) == ExerciseClass.MORNING_ROUTINE }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0) }
            val cardioMinutes = sessions
                .filter { classifyExercise(it.exerciseType).contributesToCardioMinutes() }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0) }
            val allMinutes = sessions.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0)
            }
            val origins = buildList {
                addAll(sessions.map { it.metadata.dataOrigin.packageName })
                addAll(stepRecords.inDay(dayStart, dayEnd) { it.startTime }
                    .map { it.metadata.dataOrigin.packageName })
                addAll(distanceRecords.inDay(dayStart, dayEnd) { it.startTime }
                    .map { it.metadata.dataOrigin.packageName })
                addAll(activeCalorieRecords.inDay(dayStart, dayEnd) { it.startTime }
                    .map { it.metadata.dataOrigin.packageName })
                addAll(sleepRecords.filter { it.startTime < dayEnd && it.endTime > dayStart }
                    .map { it.metadata.dataOrigin.packageName })
                addAll(heartRateRecords.filter {
                    it.startTime < dayEnd && it.endTime > dayStart
                }.map { it.metadata.dataOrigin.packageName })
                addAll(basalRateRecords.inDay(dayStart, dayEnd) { it.time }
                    .map { it.metadata.dataOrigin.packageName })
                addAll(intensityRecords.filter {
                    it.startTime < dayEnd && it.endTime > dayStart
                }.map { it.metadata.dataOrigin.packageName })
                addAll(nutritionRecords.filter {
                    it.startTime < dayEnd && it.endTime > dayStart
                }.map { it.metadata.dataOrigin.packageName })
            }.distinct().sorted().joinToString(", ")

            DailyHealthSummaryEntity(
                date = date.toString(),
                steps = result[StepsRecord.COUNT_TOTAL],
                distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                activeCaloriesKcal = result[
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                ]?.inKilocalories,
                exerciseMinutes = allMinutes,
                strengthMinutes = strengthMinutes,
                morningRoutineMinutes = morningRoutineMinutes,
                cardioMinutes = cardioMinutes,
                exerciseSessionCount = sessions.size,
                sleepMinutes = mainSleep?.let {
                    Duration.between(it.startTime, it.endTime).toMinutes()
                        .coerceAtLeast(0)
                },
                sleepStartEpochMillis = mainSleep?.startTime?.toEpochMilli(),
                sleepEndEpochMillis = mainSleep?.endTime?.toEpochMilli(),
                sleepHeartRateAverageBpm = sleepHeartRateSamples
                    .takeIf { it.isNotEmpty() }
                    ?.map { it.beatsPerMinute }
                    ?.average()
                    ?.roundToLong(),
                moderateIntensityMinutes = result[
                    ActivityIntensityRecord.MODERATE_DURATION_TOTAL
                ]?.toMinutes(),
                vigorousIntensityMinutes = result[
                    ActivityIntensityRecord.VIGOROUS_DURATION_TOTAL
                ]?.toMinutes(),
                heartRateAverageBpm = result[HeartRateRecord.BPM_AVG],
                heartRateMinimumBpm = result[HeartRateRecord.BPM_MIN],
                heartRateMaximumBpm = result[HeartRateRecord.BPM_MAX],
                heartRateMeasurementCount = result[HeartRateRecord.MEASUREMENTS_COUNT],
                basalCaloriesKcal = result[
                    BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL
                ]?.inKilocalories,
                intakeCaloriesKcal = result[NutritionRecord.ENERGY_TOTAL]?.inKilocalories,
                proteinGrams = result[NutritionRecord.PROTEIN_TOTAL]?.inGrams,
                totalFatGrams = result[NutritionRecord.TOTAL_FAT_TOTAL]?.inGrams,
                carbohydrateGrams = result[NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL]?.inGrams,
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
            HealthSourceStatusEntity(
                recordType = "除脂肪量（計算）",
                recordCount = body.count { it.leanBodyMassKg != null },
                latestRecordEpochMillis = body.mapNotNull {
                    it.measurementEpochMillis
                }.maxOrNull(),
                origins = body.mapNotNull { it.dataOrigin }.distinct().joinToString(", "),
                status = if (body.any { it.leanBodyMassKg != null }) "計算可能" else "未取得",
                checkedAtEpochMillis = checkedAt,
            ),
            sourceStatus(
                "運動セッション",
                exercises,
                { it.startTime },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
            ),
            sourceStatus("歩数", stepRecords, { it.startTime }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus("距離", distanceRecords, { it.startTime }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus(
                "活動消費",
                activeCalorieRecords,
                { it.startTime },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
            ),
            sourceStatus(
                "睡眠",
                sleepRecords,
                { it.endTime },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
                if (canReadSleep) null else "権限なし",
            ),
            sourceStatus(
                "心拍数",
                heartRateRecords,
                { it.endTime },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
                if (canReadHeartRate) null else "権限なし",
            ),
            sourceStatus(
                "基礎代謝",
                basalRateRecords,
                { it.time },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
                if (canReadBasalRate) null else "権限なし",
            ),
            sourceStatus(
                "アクティビティ強度",
                intensityRecords,
                { it.endTime },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
                when {
                    !intensitySupported -> "端末非対応"
                    !canReadIntensity -> "権限なし"
                    else -> null
                },
            ),
            sourceStatus(
                "栄養（摂取）",
                nutritionRecords,
                { it.startTime },
                { it.metadata.dataOrigin.packageName },
                checkedAt,
                if (canReadNutrition) null else "権限なし",
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
            rangeStartEpochMillis = start.toEpochMilli(),
            rangeEndEpochMillisExclusive = end.toEpochMilli(),
        )
    }

    private suspend fun aggregateDaily(
        start: Instant,
        end: Instant,
        includeHeartRate: Boolean,
        includeBasalRate: Boolean,
        includeIntensity: Boolean,
        includeNutrition: Boolean,
    ): AggregationResult {
        val metrics = buildSet<AggregateMetric<*>> {
            add(StepsRecord.COUNT_TOTAL)
            add(DistanceRecord.DISTANCE_TOTAL)
            add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (includeHeartRate) {
                add(HeartRateRecord.BPM_AVG)
                add(HeartRateRecord.BPM_MIN)
                add(HeartRateRecord.BPM_MAX)
                add(HeartRateRecord.MEASUREMENTS_COUNT)
            }
            if (includeBasalRate) {
                add(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL)
            }
            if (includeIntensity) {
                add(ActivityIntensityRecord.MODERATE_DURATION_TOTAL)
                add(ActivityIntensityRecord.VIGOROUS_DURATION_TOTAL)
            }
            if (includeNutrition) {
                add(NutritionRecord.ENERGY_TOTAL)
                add(NutritionRecord.PROTEIN_TOTAL)
                add(NutritionRecord.TOTAL_FAT_TOTAL)
                add(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)
            }
        }
        return client.aggregate(
            AggregateRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ),
        )
    }

    private suspend inline fun <reified T : Record> readIfGranted(
        granted: Boolean,
        start: Instant,
        end: Instant,
    ): List<T> = if (granted) readAll(start, end) else emptyList()

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

    private inline fun <T> List<T>.inDay(
        start: Instant,
        end: Instant,
        time: (T) -> Instant,
    ): List<T> = filter { time(it) >= start && time(it) < end }

    private fun <T> sourceStatus(
        label: String,
        records: List<T>,
        time: (T) -> Instant,
        origin: (T) -> String,
        checkedAt: Long,
        stateOverride: String? = null,
    ): HealthSourceStatusEntity = HealthSourceStatusEntity(
        recordType = label,
        recordCount = records.size,
        latestRecordEpochMillis = records.maxOfOrNull { time(it).toEpochMilli() },
        origins = records.map(origin).distinct().sorted().joinToString(", "),
        status = stateOverride ?: if (records.isEmpty()) "未取得" else "取得可能",
        checkedAtEpochMillis = checkedAt,
    )

}
