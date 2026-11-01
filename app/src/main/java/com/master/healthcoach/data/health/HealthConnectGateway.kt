package com.master.healthcoach.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
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
import com.master.healthcoach.data.db.NutritionMealEntity
import com.master.healthcoach.domain.HealthRecordEvidencePolicy
import com.master.healthcoach.domain.SourceIntervalEvidence
import com.master.healthcoach.domain.RecordedBasalRate
import com.master.healthcoach.domain.WorkoutHeartAnalytics
import com.master.healthcoach.data.db.HeartRateSampleEntity
import com.master.healthcoach.domain.ActivityEnergyAssist
import com.master.healthcoach.domain.BodyCompositionCalculator
import com.master.healthcoach.domain.NutritionInterval
import com.master.healthcoach.domain.NutritionMealClusterer
import com.master.healthcoach.domain.NutritionRecordSnapshot
import com.master.healthcoach.domain.NutritionWriteShape
import com.master.healthcoach.domain.TimedMeasurement
import com.master.healthcoach.domain.IntervalDuration
import com.master.healthcoach.domain.SleepDurationCalculator
import com.master.healthcoach.domain.SleepInterval
import com.master.healthcoach.domain.NutritionObservation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import com.master.healthcoach.domain.TrendMath
import com.master.healthcoach.data.diagnostics.HealthConnectPermissionException
import com.master.healthcoach.data.diagnostics.HealthConnectUnavailableException

interface SyncStageObserver {
    suspend fun onStageStarted(stage: String) {}
    suspend fun onStageCompleted(
        stage: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        attributes: Map<String, Long> = emptyMap(),
        stringAttributes: Map<String, String> = emptyMap(),
        booleanAttributes: Map<String, Boolean> = emptyMap(),
    ) {}
}

data class HealthSyncBundle(
    val daily: List<DailyHealthSummaryEntity>,
    val body: List<BodyCompositionEntity>,
    val sources: List<HealthSourceStatusEntity>,
    val exerciseSessions: List<ExerciseSessionEntity>,
    val heartRateSamples: List<HeartRateSampleEntity>,
    val nutritionMeals: List<NutritionMealEntity>,
    val rangeStartEpochMillis: Long,
    val rangeEndEpochMillisExclusive: Long,
)

enum class HealthConnectAvailability {
    AVAILABLE,
    UPDATE_REQUIRED,
    UNAVAILABLE,
}

open class HealthConnectGateway(private val context: Context?) {
    private val client by lazy { HealthConnectClient.getOrCreate(requireNotNull(context)) }

    val corePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    private val totalCaloriesPermission = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
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

    open fun availability(): HealthConnectAvailability = when (
        HealthConnectClient.getSdkStatus(requireNotNull(context))
    ) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HealthConnectAvailability.UPDATE_REQUIRED
        else -> HealthConnectAvailability.UNAVAILABLE
    }

    open fun backgroundReadAvailable(): Boolean =
        availability() == HealthConnectAvailability.AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    open fun activityIntensityAvailable(): Boolean =
        availability() == HealthConnectAvailability.AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_ACTIVITY_INTENSITY,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    open fun historyReadAvailable(): Boolean =
        availability() == HealthConnectAvailability.AVAILABLE &&
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    open fun requestedPermissions(): Set<String> = buildSet {
        addAll(corePermissions)
        add(sleepPermission)
        add(heartRatePermission)
        add(basalMetabolicRatePermission)
        add(nutritionPermission)
        add(totalCaloriesPermission)
        if (activityIntensityAvailable()) add(activityIntensityPermission)
        if (backgroundReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
        if (historyReadAvailable()) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    open suspend fun grantedPermissions(): Set<String> =
        if (availability() == HealthConnectAvailability.AVAILABLE) {
            client.permissionController.getGrantedPermissions()
        } else {
            emptySet()
        }

    open suspend fun hasCorePermissions(): Boolean = grantedPermissions().containsAll(corePermissions)

    open suspend fun hasBackgroundPermission(): Boolean =
        !backgroundReadAvailable() ||
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in grantedPermissions()

    open suspend fun hasHistoryPermission(): Boolean =
        historyReadAvailable() &&
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions()

    open suspend fun sync(
        days: Long = 28,
        zoneId: ZoneId = ZoneId.systemDefault(),
        heightCm: Double? = null,
        sex: String? = null,
        ageYears: Int? = null,
        stageObserver: SyncStageObserver? = null,
    ): HealthSyncBundle {
        val permStageStart = System.currentTimeMillis()
        stageObserver?.onStageStarted("healthconnect.permissions")
        val currentAvailability = availability()
        if (currentAvailability != HealthConnectAvailability.AVAILABLE) {
            throw HealthConnectUnavailableException(currentAvailability.name, "Health Connect is not available")
        }
        val granted = grantedPermissions()
        if (!granted.containsAll(corePermissions)) {
            throw HealthConnectPermissionException("Health Connectの必須データ権限が不足しています")
        }
        val canReadSleep = sleepPermission in granted
        val canReadHeartRate = heartRatePermission in granted
        val canReadBasalRate = basalMetabolicRatePermission in granted
        val intensitySupported = activityIntensityAvailable()
        val canReadIntensity = intensitySupported && activityIntensityPermission in granted
        val canReadNutrition = nutritionPermission in granted
        val canReadTotalCalories = totalCaloriesPermission in granted
        val optionalGrantedCount = listOf(
            canReadSleep,
            canReadHeartRate,
            canReadBasalRate,
            canReadIntensity,
            canReadNutrition,
            canReadTotalCalories,
        ).count { it }.toLong()
        val bgGranted = hasBackgroundPermission()
        val historyGranted = hasHistoryPermission()
        val permStageEnd = System.currentTimeMillis().coerceAtLeast(permStageStart)
        stageObserver?.onStageCompleted(
            stage = "healthconnect.permissions",
            startEpochMillis = permStageStart,
            endEpochMillis = permStageEnd,
            attributes = mapOf("hc.optional_granted_count" to optionalGrantedCount),
            stringAttributes = mapOf("hc.availability" to currentAvailability.name.lowercase()),
            booleanAttributes = mapOf(
                "hc.core_granted" to true,
                "hc.bg_granted" to bgGranted,
                "hc.history_granted" to historyGranted,
            ),
        )

        val endDateExclusive = LocalDate.now(zoneId).plusDays(1)
        val startDate = endDateExclusive.minusDays(days)
        val start = startDate.atStartOfDay(zoneId).toInstant()
        val end = endDateExclusive.atStartOfDay(zoneId).toInstant()

        val readStageStart = System.currentTimeMillis()
        stageObserver?.onStageStarted("healthconnect.read")
        val weights = readAll<WeightRecord>(start, end)
        val bodyFats = readAll<BodyFatRecord>(start, end)
        // Diagnose all writers; only Xiaomi records contribute to health values.
        val exerciseReadStart = start.minus(Duration.ofDays(1))
        val allExercises = readAll<ExerciseSessionRecord>(exerciseReadStart, end, unfiltered = true)
        val exercises = allExercises.filter {
            it.metadata.dataOrigin.packageName == WearableSourcePolicy.PACKAGE &&
                HealthRecordEvidencePolicy.overlaps(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), start.toEpochMilli(), end.toEpochMilli())
        }
        val totalCalorieRecords = readIfGranted<TotalCaloriesBurnedRecord>(canReadTotalCalories, start, end)
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
        val dailyAggregations = (0 until days).map { offset ->
            val date = startDate.plusDays(offset)
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            aggregateDaily(
                start = dayStart,
                end = dayEnd,
                includeHeartRate = canReadHeartRate,
                includeIntensity = canReadIntensity,
                includeNutrition = canReadNutrition,
            )
        }
        val readStageEnd = System.currentTimeMillis().coerceAtLeast(readStageStart)
        stageObserver?.onStageCompleted(
            stage = "healthconnect.read",
            startEpochMillis = readStageStart,
            endEpochMillis = readStageEnd,
            attributes = mapOf(
                "sync.days" to days,
                "records.weight" to weights.size.toLong(),
                "records.body_fat" to bodyFats.size.toLong(),
                "records.steps" to stepRecords.size.toLong(),
                "records.distance" to distanceRecords.size.toLong(),
                "records.exercise_all" to allExercises.size.toLong(),
                "records.exercise_selected" to exercises.size.toLong(),
                "records.active_calories" to activeCalorieRecords.size.toLong(),
                "records.total_calories" to totalCalorieRecords.size.toLong(),
                "records.sleep" to sleepRecords.size.toLong(),
                "records.heart_rate_records" to heartRateRecords.size.toLong(),
                "records.heart_rate_samples" to heartRateRecords.sumOf { it.samples.size }.toLong(),
                "records.basal_rate" to basalRateRecords.size.toLong(),
                "records.intensity" to intensityRecords.size.toLong(),
                "records.nutrition" to nutritionRecords.size.toLong(),
            ),
        )

        val estimateStageStart = System.currentTimeMillis()
        stageObserver?.onStageStarted("energy.estimate")
        val nutritionMeals = NutritionMealClusterer.cluster(
            nutritionRecords.map { record ->
                NutritionRecordSnapshot(
                    startEpochMillis = record.startTime.toEpochMilli(),
                    endEpochMillis = record.endTime.toEpochMilli(),
                    mealType = record.mealType,
                    energyKcal = record.energy?.inKilocalories,
                    proteinGrams = record.protein?.inGrams,
                    fatGrams = record.totalFat?.inGrams,
                    carbohydrateGrams = record.totalCarbohydrate?.inGrams,
                    origin = record.metadata.dataOrigin.packageName,
                )
            },
            zoneId,
        )
        val mealsByDate = nutritionMeals.groupBy { it.date }

        // Combine day-clipped estimates for sessions that cross midnight.
        val assistPerSession = mutableMapOf<String, MutableList<ActivityEnergyAssist.SessionResult>>()
        val daily = (0 until days).map { offset ->
            val date = startDate.plusDays(offset)
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            val sleepCandidates = sleepRecords.filter { it.endTime > dayStart && it.endTime <= dayEnd }
            val preferredSleep = sleepCandidates.filter { it.metadata.dataOrigin.packageName == "com.xiaomi.wearable" }
            val mainSleep = preferredSleep.ifEmpty { sleepCandidates }
                .maxByOrNull { Duration.between(it.startTime, it.endTime) }
            val sleepDuration = mainSleep?.let { sleep ->
                SleepDurationCalculator.calculate(
                    sleep.startTime,
                    sleep.endTime,
                    sleep.stages.filter { stage ->
                        stage.stage in setOf(
                            SleepSessionRecord.STAGE_TYPE_SLEEPING,
                            SleepSessionRecord.STAGE_TYPE_LIGHT,
                            SleepSessionRecord.STAGE_TYPE_DEEP,
                            SleepSessionRecord.STAGE_TYPE_REM,
                        )
                    }.map { SleepInterval(it.startTime, it.endTime) },
                    hasKnownStages = sleep.stages.any { it.stage != SleepSessionRecord.STAGE_TYPE_UNKNOWN },
                    knownStageIntervals = sleep.stages.filter { it.stage != SleepSessionRecord.STAGE_TYPE_UNKNOWN }
                        .map { SleepInterval(it.startTime, it.endTime) },
                )
            }
            val sleepHeartRateSamples = mainSleep?.let { sleep ->
                heartRateRecords.flatMap { it.samples }.filter { sample ->
                    sample.time >= sleep.startTime && sample.time <= sleep.endTime
                }
            }.orEmpty()
            val (result, otherResult) = dailyAggregations[offset.toInt()]
            val sessions = exercises.filter { session ->
                HealthRecordEvidencePolicy.overlaps(session.startTime.toEpochMilli(), session.endTime.toEpochMilli(), dayStart.toEpochMilli(), dayEnd.toEpochMilli())
            }
            fun minutesFor(selected: List<ExerciseSessionRecord>) = IntervalDuration.unionMinutes(dayStart, dayEnd,
                selected.map { SleepInterval(it.startTime, it.endTime) })
            val basalRate = HealthRecordEvidencePolicy.basalRate(basalRateRecords.map {
                RecordedBasalRate(it.metadata.dataOrigin.packageName, it.time.toEpochMilli(), it.basalMetabolicRate.inKilocaloriesPerDay)
            }, WearableSourcePolicy.PACKAGE, dayEnd.toEpochMilli())
            val strengthMinutes = minutesFor(sessions.filter { classifyExerciseSession(it.exerciseType, it.startTime, it.endTime, zoneId) == ExerciseClass.STRENGTH })
            val morningRoutineMinutes = minutesFor(sessions.filter { classifyExerciseSession(it.exerciseType, it.startTime, it.endTime, zoneId) == ExerciseClass.MORNING_ROUTINE })
            val cardioMinutes = minutesFor(sessions.filter { classifyExerciseSession(it.exerciseType, it.startTime, it.endTime, zoneId).contributesToCardioMinutes() })
            val cyclingMinutes = IntervalDuration.unionMinutes(
                dayStart,
                dayEnd,
                exercises.filter { session ->
                    session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING ||
                        session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
                }.map { SleepInterval(it.startTime, it.endTime) },
            )
            val allMinutes = minutesFor(sessions)
            val dayNutrition = nutritionRecords.filter { it.startTime >= dayStart && it.startTime < dayEnd }
            val dayHeartRecords = heartRateRecords.filter { it.startTime < dayEnd && it.endTime > dayStart }
            val dayHeartSamples = dayHeartRecords.flatMap { it.samples }.filter { it.time >= dayStart && it.time < dayEnd }
            val outsideExerciseSamples = dayHeartSamples.count { sample ->
                exercises.none { sample.time >= it.startTime && sample.time < it.endTime }
            }
            // Bodyweight from the preceding seven days, or most recent within 30 days.
            // Do not use future weigh-ins for a past day's energy calculation.
            val recentWeight = weights.filter {
                it.time < dayEnd && it.time >= dayEnd.minus(Duration.ofDays(7))
            }.map { it.weight.inKilograms }.filter { it in 20.0..400.0 }
            val weightForAssist = TrendMath.median(recentWeight)
                ?: weights.filter { it.time < dayEnd && it.time >= dayEnd.minus(Duration.ofDays(30)) }
                    .maxByOrNull { it.time }?.weight?.inKilograms
            val assist = ActivityEnergyAssist.estimate(
                dayStartMs = dayStart.toEpochMilli(),
                dayEndMs = dayEnd.toEpochMilli(),
                weightKg = weightForAssist,
                heightCm = heightCm,
                sex = sex,
                ageYears = ageYears,
                dailySteps = result[StepsRecord.COUNT_TOTAL],
                stepIntervals = stepRecords.filter {
                    it.startTime < dayEnd && it.endTime > dayStart
                }.map {
                    ActivityEnergyAssist.Steps(
                        it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.count
                    )
                },
                sessions = sessions.map {
                    ActivityEnergyAssist.Session(
                        id = "${it.metadata.dataOrigin.packageName}:${it.metadata.id}",
                        startMs = it.startTime.toEpochMilli(),
                        endMs = it.endTime.toEpochMilli(),
                        activity = assistActivity(it.exerciseType, it.startTime, it.endTime, zoneId),
                    )
                },
                heartSamples = dayHeartSamples.map {
                    ActivityEnergyAssist.Heart(it.time.toEpochMilli(), it.beatsPerMinute)
                },
            )
            assist?.sessions?.forEach { (id, energy) ->
                assistPerSession.getOrPut(id) { mutableListOf() }.add(energy)
            }
            val originsByMetric = buildJsonObject {
                put("wearableSourcePolicy", "xiaomi_only_v1")
                put("basalValueSource", if (basalRate == null) "missing_xiaomi_record" else "xiaomi_record_rate_kcal_per_day")
                put("exerciseSourceAudit", buildJsonArray {
                    HealthRecordEvidencePolicy.audit(allExercises.map {
                        SourceIntervalEvidence(it.metadata.dataOrigin.packageName, it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.lastModifiedTime.toEpochMilli())
                    }, dayStart.toEpochMilli(), dayEnd.toEpochMilli()).forEach { audit ->
                        add(buildJsonObject {
                            put("origin", audit.origin); put("count", audit.count)
                            put("latestEndEpochMillis", audit.latestEnd); put("latestModifiedEpochMillis", audit.latestModified)
                            put("selected", audit.origin == WearableSourcePolicy.PACKAGE)
                        })
                    }
                })
                put("exerciseSelectedRecordCount", sessions.size)
                put("totalCaloriesPermissionGranted", canReadTotalCalories)
                val rawTotals = totalCalorieRecords.filter { it.startTime < dayEnd && it.endTime > dayStart }
                put("totalCaloriesRawRecordCount", rawTotals.size)
                put("totalCaloriesRawSamples", buildJsonArray {
                    rawTotals.take(6).forEach { record -> add(buildJsonObject {
                        put("origin", record.metadata.dataOrigin.packageName)
                        put("startEpochMillis", record.startTime.toEpochMilli()); put("endEpochMillis", record.endTime.toEpochMilli())
                        put("energyKcal", record.energy.inKilocalories)
                        put("interpretation", "total_calories_record_not_active_calories")
                    }) }
                })
                put("totalCaloriesRawSamplesTruncated", rawTotals.size > 6)
                put("selectedWearableOrigin", WearableSourcePolicy.PACKAGE)
                put("missingWearableBehavior", "null; never substitute other sources")
                fun candidates(name: String, origins: List<String>) {
                    put(name, buildJsonArray { origins.distinct().sorted().forEach { add(JsonPrimitive(it)) } })
                }
                candidates("stepsCandidates", stepRecords.inDay(dayStart, dayEnd) { it.startTime }.map { it.metadata.dataOrigin.packageName })
                candidates("distanceCandidates", distanceRecords.inDay(dayStart, dayEnd) { it.startTime }.map { it.metadata.dataOrigin.packageName })
                candidates("activeCaloriesCandidates", activeCalorieRecords.filter { it.startTime < dayEnd && it.endTime > dayStart }.map { it.metadata.dataOrigin.packageName })
                candidates("heartRateCandidates", dayHeartRecords.map { it.metadata.dataOrigin.packageName })
                candidates("nutritionCandidates", dayNutrition.map { it.metadata.dataOrigin.packageName })
                mainSleep?.let { put("selectedSleepOrigin", it.metadata.dataOrigin.packageName) }
                put("activitySelection", "Xiaomi Mi Fitness only (com.xiaomi.wearable); no fallback to phone or Fitbit")
            }.toString()
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
                sleepMinutes = sleepDuration?.displayMinutes,
                sleepSessionMinutes = sleepDuration?.sessionMinutes,
                sleepDurationSource = sleepDuration?.source ?: "no_record",
                cyclingMinutes = cyclingMinutes,
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
                basalCaloriesKcal = basalRate,
                intakeCaloriesKcal = NutritionObservation.aggregateIfObserved(dayNutrition.map { it.energy?.inKilocalories }, otherResult?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories),
                proteinGrams = NutritionObservation.aggregateIfObserved(dayNutrition.map { it.protein?.inGrams }, otherResult?.get(NutritionRecord.PROTEIN_TOTAL)?.inGrams),
                totalFatGrams = NutritionObservation.aggregateIfObserved(dayNutrition.map { it.totalFat?.inGrams }, otherResult?.get(NutritionRecord.TOTAL_FAT_TOTAL)?.inGrams),
                carbohydrateGrams = NutritionObservation.aggregateIfObserved(dayNutrition.map { it.totalCarbohydrate?.inGrams }, otherResult?.get(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)?.inGrams),
                mealCount = mealsByDate[date.toString()]?.count { !it.isDailyTotal && it.mealType in 1..4 } ?: 0,
                nutritionRecordStatus = when {
                    dayNutrition.isEmpty() -> "no_records"
                    dayNutrition.all { it.energy == null && it.protein == null && it.totalFat == null && it.totalCarbohydrate == null } -> "empty_placeholder"
                    mealsByDate[date.toString()].orEmpty().any { it.isDailyTotal } -> "daily_record_unverified"
                    else -> "meal_records_unverified"
                },
                metricOriginsJson = originsByMetric,
                heartRateCoverageMinutes = dayHeartSamples.takeIf { it.isNotEmpty() }?.map { it.time.epochSecond / 60 }?.distinct()?.size,
                heartRateOutsideExerciseSamples = dayHeartSamples.takeIf { it.isNotEmpty() }?.let { outsideExerciseSamples },
                assistedActiveCaloriesKcal = assist?.activeKcal,
                assistedWalkingCaloriesKcal = assist?.walkingKcal,
                assistedExerciseCaloriesKcal = assist?.exerciseKcal,
                assistedHeartObservedMinutes = assist?.heartObservedMinutes,
                assistedHeartAdjustedMinutes = assist?.heartAdjustedMinutes,
                assistedZoneMinutesJson = assist?.observedZoneMinutes?.joinToString(prefix = "[", postfix = "]"),
                hikingMinutes = minutesFor(sessions.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_HIKING }),
                hiitMinutes = minutesFor(sessions.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING }),
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
            sourceStatus("運動セッション（取得元診断）", allExercises, { it.endTime }, { it.metadata.dataOrigin.packageName }, checkedAt),
            sourceStatus("総消費（元記録診断）", totalCalorieRecords, { it.endTime }, { it.metadata.dataOrigin.packageName }, checkedAt,
                if (canReadTotalCalories) null else "権限なし"),
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
            nutritionWriteShapeStatus(nutritionRecords, canReadNutrition, zoneId, checkedAt),
        )

        val workoutsForAssociation = exercises.map {
            WorkoutHeartAnalytics.Workout(
                "${it.metadata.dataOrigin.packageName}:${it.metadata.id}",
                it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.exerciseType
            )
        }
        val heartRateSamples = heartRateRecords.flatMap { record ->
            val id = "${record.metadata.dataOrigin.packageName}:${record.metadata.id}"
            // Restrict association candidates to the Health Connect record's time span
            // rather than scanning all workouts for every individual heart sample.
            val possibleWorkouts = workoutsForAssociation.filter {
                it.startMs <= record.endTime.toEpochMilli() + WorkoutHeartAnalytics.RECOVERY_WINDOW_MS &&
                    it.endMs + WorkoutHeartAnalytics.RECOVERY_WINDOW_MS >= record.startTime.toEpochMilli()
            }
            record.samples.mapNotNull { point ->
                val millis = point.time.toEpochMilli()
                if (millis < start.toEpochMilli() || millis >= end.toEpochMilli()) null
                else {
                    val association = WorkoutHeartAnalytics.associate(millis, possibleWorkouts)
                    HeartRateSampleEntity(
                        sourceRecordId = id, timestampEpochMillis = millis,
                        bpm = point.beatsPerMinute,
                        dataOrigin = record.metadata.dataOrigin.packageName,
                        sessionId = association.sessionId, phase = association.phase,
                    )
                }
            }
        }
        // The Room composite key deduplicates overlapping Health Connect records
        // on upsert. Avoid retaining a second large in-memory distinctBy set.
        val exerciseSessions = exercises.map { session ->
            val workout = WorkoutHeartAnalytics.Workout(
                "${session.metadata.dataOrigin.packageName}:${session.metadata.id}",
                session.startTime.toEpochMilli(), session.endTime.toEpochMilli(), session.exerciseType
            )
            val relatedHeart = heartRateRecords.asSequence()
                .filter { it.endTime.toEpochMilli() >= workout.startMs &&
                    it.startTime.toEpochMilli() <= workout.endMs + WorkoutHeartAnalytics.RECOVERY_WINDOW_MS }
                .flatMap { it.samples.asSequence() }
                .map { WorkoutHeartAnalytics.Heart(it.time.toEpochMilli(), it.beatsPerMinute) }
                .filter { it.timeMs >= workout.startMs && it.timeMs <= workout.endMs + WorkoutHeartAnalytics.RECOVERY_WINDOW_MS }
                .toList()
            val heart = WorkoutHeartAnalytics.recovery(workout, relatedHeart)
            val origin = session.metadata.dataOrigin.packageName
            ExerciseSessionEntity(
                recordId = "$origin:${session.metadata.id}",
                startEpochMillis = session.startTime.toEpochMilli(),
                endEpochMillis = session.endTime.toEpochMilli(),
                exerciseType = session.exerciseType,
                exerciseLabel = if (classifyExerciseSession(session.exerciseType, session.startTime, session.endTime, zoneId) == ExerciseClass.MORNING_ROUTINE) {
                    "朝トレ候補（短い一般運動）"
                } else exerciseLabel(session.exerciseType),
                category = classifyExerciseSession(session.exerciseType, session.startTime, session.endTime, zoneId).name.lowercase(),
                durationMinutes = Duration.between(session.startTime, session.endTime)
                    .toMinutes().coerceAtLeast(0),
                dataOrigin = origin,
                assistedActiveCaloriesKcal = assistPerSession["$origin:${session.metadata.id}"]
                    ?.sumOf { it.activeKcal },
                assistedHeartObservedMinutes = assistPerSession["$origin:${session.metadata.id}"]
                    ?.sumOf { it.heartObservedMinutes },
                assistedHeartAdjustedMinutes = assistPerSession["$origin:${session.metadata.id}"]
                    ?.sumOf { it.heartAdjustedMinutes },
                assistedZoneMinutesJson = assistPerSession["$origin:${session.metadata.id}"]
                    ?.let { parts ->
                        (0..4).map { index -> parts.sumOf { it.observedZoneMinutes[index] } }
                            .joinToString(prefix = "[", postfix = "]")
                    },
                workoutHeartAverageBpm = heart.heartAverageBpm,
                workoutHeartCoverageMinutes = heart.coverageMinutes,
                recoveryDrop1MinBpm = heart.drop1MinBpm,
                recoveryDrop2MinBpm = heart.drop2MinBpm,
            )
        }
        val mealEntities = nutritionMeals.mapIndexed { index, meal ->
            NutritionMealEntity(
                recordId = "${meal.date}:${meal.startEpochMillis}:${meal.endEpochMillis}:$index",
                date = meal.date,
                startEpochMillis = meal.startEpochMillis,
                endEpochMillis = meal.endEpochMillis,
                mealLabel = meal.mealLabel,
                mealType = meal.mealType,
                intakeCaloriesKcal = meal.energyKcal,
                proteinGrams = meal.proteinGrams,
                totalFatGrams = meal.fatGrams,
                carbohydrateGrams = meal.carbohydrateGrams,
                recordCount = meal.recordCount,
                dataOrigin = meal.origin,
                isDailyRecord = meal.isDailyTotal,
            )
        }
        val estimateStageEnd = System.currentTimeMillis().coerceAtLeast(estimateStageStart)
        stageObserver?.onStageCompleted(
            stage = "energy.estimate",
            startEpochMillis = estimateStageStart,
            endEpochMillis = estimateStageEnd,
            attributes = mapOf(
                "days" to daily.size.toLong(),
                "body" to body.size.toLong(),
                "sessions" to exerciseSessions.size.toLong(),
                "heartSamples" to heartRateSamples.size.toLong(),
                "meals" to mealEntities.size.toLong(),
                "assist.valid_days" to daily.count { it.assistedActiveCaloriesKcal != null }.toLong(),
                "assist.sessions_estimated" to assistPerSession.size.toLong(),
            ),
        )

        return HealthSyncBundle(
            daily = daily,
            body = body,
            sources = sources,
            exerciseSessions = exerciseSessions,
            heartRateSamples = heartRateSamples,
            nutritionMeals = mealEntities,
            rangeStartEpochMillis = start.toEpochMilli(),
            rangeEndEpochMillisExclusive = end.toEpochMilli(),
        )
    }

    /** Map Health Connect workout types to documented MET reference activities. */
    private fun assistActivity(
        type: Int,
        start: Instant,
        end: Instant,
        zone: ZoneId,
    ): ActivityEnergyAssist.Activity = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ActivityEnergyAssist.Activity.WALKING
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ActivityEnergyAssist.Activity.RUNNING
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> ActivityEnergyAssist.Activity.CYCLING
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> ActivityEnergyAssist.Activity.HIKING
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> ActivityEnergyAssist.Activity.SWIMMING
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> ActivityEnergyAssist.Activity.ROWING
        else -> when (classifyExerciseSession(type, start, end, zone)) {
            ExerciseClass.STRENGTH -> ActivityEnergyAssist.Activity.STRENGTH
            ExerciseClass.MORNING_ROUTINE -> ActivityEnergyAssist.Activity.MORNING_ROUTINE
            ExerciseClass.INTERVAL -> ActivityEnergyAssist.Activity.HIIT
            else -> ActivityEnergyAssist.Activity.OTHER
        }
    }

    private suspend fun aggregateDaily(
        start: Instant,
        end: Instant,
        includeHeartRate: Boolean,
        includeIntensity: Boolean,
        includeNutrition: Boolean,
    ): Pair<AggregationResult, AggregationResult?> {
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
            if (includeIntensity) {
                add(ActivityIntensityRecord.MODERATE_DURATION_TOTAL)
                add(ActivityIntensityRecord.VIGOROUS_DURATION_TOTAL)
            }
        }
        val otherMetrics = buildSet<AggregateMetric<*>> {
            if (includeNutrition) {
                add(NutritionRecord.ENERGY_TOTAL)
                add(NutritionRecord.PROTEIN_TOTAL)
                add(NutritionRecord.TOTAL_FAT_TOTAL)
                add(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)
            }
        }
        val wearable = client.aggregate(
            AggregateRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                dataOriginFilter = WearableSourcePolicy.origins,
            ),
        )
        val other = if (otherMetrics.isEmpty()) null else client.aggregate(
            AggregateRequest(
                metrics = otherMetrics,
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ),
        )
        return wearable to other
    }

    private suspend inline fun <reified T : Record> readIfGranted(
        granted: Boolean,
        start: Instant,
        end: Instant,
    ): List<T> = if (granted) readAll(start, end) else emptyList()

    private suspend inline fun <reified T : Record> readAll(
        start: Instant,
        end: Instant,
        unfiltered: Boolean = false,
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    dataOriginFilter = if (unfiltered) emptySet() else WearableSourcePolicy.originsFor(T::class),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken?.takeIf { it.isNotBlank() }
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

    private fun nutritionWriteShapeStatus(
        records: List<NutritionRecord>,
        canReadNutrition: Boolean,
        zoneId: ZoneId,
        checkedAt: Long,
    ): HealthSourceStatusEntity {
        val shape = NutritionWriteShape.inspect(
            records.map {
                NutritionInterval(
                    startEpochMillis = it.startTime.toEpochMilli(),
                    endEpochMillis = it.endTime.toEpochMilli(),
                    mealType = it.mealType,
                )
            },
            zoneId,
        )
        return HealthSourceStatusEntity(
            recordType = "栄養（書込粒度）",
            recordCount = records.size,
            latestRecordEpochMillis = records.maxOfOrNull { it.startTime.toEpochMilli() },
            origins = shape.summary,
            status = when {
                !canReadNutrition -> "権限なし"
                records.isEmpty() -> "未取得"
                else -> "取得可能"
            },
            checkedAtEpochMillis = checkedAt,
        )
    }

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
