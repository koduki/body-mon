package com.master.healthcoach.data.health

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.DataOrigin
import kotlin.reflect.KClass

/** Mi Fitness owns wearable metrics. Never fill gaps with phone/Fitbit records. */
internal object WearableSourcePolicy {
    const val PACKAGE = "com.xiaomi.wearable"
    val origins = setOf(DataOrigin(PACKAGE))
    private val wearableTypes = setOf(
        StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
        ExerciseSessionRecord::class, SleepSessionRecord::class, HeartRateRecord::class,
        ActivityIntensityRecord::class, BasalMetabolicRateRecord::class, TotalCaloriesBurnedRecord::class,
    )
    fun originsFor(type: KClass<out Record>): Set<DataOrigin> =
        if (type in wearableTypes) origins else emptySet()
}
