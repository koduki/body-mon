package com.master.healthcoach.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class NutritionRecordSnapshot(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val mealType: Int,
    val energyKcal: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbohydrateGrams: Double?,
    val origin: String,
)

data class NutritionMeal(
    val date: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val mealLabel: String,
    val mealType: Int,
    val energyKcal: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbohydrateGrams: Double?,
    val recordCount: Int,
    val origin: String,
    val isDailyTotal: Boolean,
) {
    val pfc: PfcBalance
        get() = PfcBalance.from(energyKcal, proteinGrams, fatGrams, carbohydrateGrams)
}

enum class PfcVerdict {
    ON_TARGET,
    WATCH,
    HOLD,
}

data class PfcBalance(
    val energyKcal: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val carbohydrateGrams: Double?,
    val proteinEnergyPercent: Double?,
    val fatEnergyPercent: Double?,
    val carbohydrateEnergyPercent: Double?,
    val verdict: PfcVerdict,
    val label: String,
) {
    companion object {
        const val PROTEIN_ENERGY_MIN_PERCENT = 15.0

        fun from(
            energyKcal: Double?,
            proteinGrams: Double?,
            fatGrams: Double?,
            carbohydrateGrams: Double?,
        ): PfcBalance {
            val energy = energyKcal?.takeIf { it > 0 }
                ?: NutritionMacros.atwaterEnergyKcal(proteinGrams, fatGrams, carbohydrateGrams)
            val proteinPercent = NutritionMacros.proteinEnergyPercent(proteinGrams, energy)
            val fatPercent = NutritionMacros.fatEnergyPercent(fatGrams, energy)
            val carbohydratePercent = NutritionMacros.carbohydrateEnergyPercent(
                carbohydrateGrams,
                energy,
            )
            if (energy == null || proteinPercent == null || fatPercent == null) {
                return PfcBalance(
                    energyKcal = energyKcal,
                    proteinGrams = proteinGrams,
                    fatGrams = fatGrams,
                    carbohydrateGrams = carbohydrateGrams,
                    proteinEnergyPercent = proteinPercent,
                    fatEnergyPercent = fatPercent,
                    carbohydrateEnergyPercent = carbohydratePercent,
                    verdict = PfcVerdict.HOLD,
                    label = "判定保留",
                )
            }
            val issues = buildList {
                when {
                    fatPercent > BodyRecompositionCoachPolicy.FAT_ENERGY_CAUTION_PERCENT ->
                        add("脂質多め")
                    fatPercent > BodyRecompositionCoachPolicy.FAT_ENERGY_TARGET_MAX_PERCENT ->
                        add("脂質やや多め")
                    fatPercent < BodyRecompositionCoachPolicy.FAT_ENERGY_TARGET_MIN_PERCENT ->
                        add("脂質少なめ")
                }
                if (proteinPercent < PROTEIN_ENERGY_MIN_PERCENT) add("たんぱく質少なめ")
            }
            val fatOnTarget = fatPercent in
                BodyRecompositionCoachPolicy.FAT_ENERGY_TARGET_MIN_PERCENT..
                    BodyRecompositionCoachPolicy.FAT_ENERGY_TARGET_MAX_PERCENT
            val proteinOk = proteinPercent >= PROTEIN_ENERGY_MIN_PERCENT
            val onTarget = issues.isEmpty() && fatOnTarget && proteinOk
            return PfcBalance(
                energyKcal = energyKcal ?: energy,
                proteinGrams = proteinGrams,
                fatGrams = fatGrams,
                carbohydrateGrams = carbohydrateGrams,
                proteinEnergyPercent = proteinPercent,
                fatEnergyPercent = fatPercent,
                carbohydrateEnergyPercent = carbohydratePercent,
                verdict = if (onTarget) PfcVerdict.ON_TARGET else PfcVerdict.WATCH,
                label = if (onTarget) "目安内" else issues.joinToString("・"),
            )
        }
    }
}

object NutritionMealClusterer {
    const val CLUSTER_GAP_MS = 45L * 60L * 1_000L
    const val DAILY_SPAN_MS = 12L * 60L * 60L * 1_000L

    fun cluster(
        records: List<NutritionRecordSnapshot>,
        zoneId: ZoneId,
    ): List<NutritionMeal> {
        if (records.isEmpty()) return emptyList()
        return records
            .groupBy { record ->
                Instant.ofEpochMilli(record.startEpochMillis)
                    .atZone(zoneId)
                    .toLocalDate()
            }
            .toSortedMap()
            .flatMap { (date, dayRecords) -> preferMealScoped(clusterDay(date, dayRecords, zoneId)) }
    }

    private fun clusterDay(
        date: LocalDate,
        records: List<NutritionRecordSnapshot>,
        zoneId: ZoneId,
    ): List<NutritionMeal> {
        val clusters = mutableListOf<MutableList<NutritionRecordSnapshot>>()
        records.sortedWith(
            compareBy<NutritionRecordSnapshot> { it.startEpochMillis }
                .thenBy { it.mealType },
        ).forEach { record ->
            if (isUntypedDailySpan(record)) {
                clusters.add(mutableListOf(record))
                return@forEach
            }
            val current = clusters.lastOrNull()
            if (current != null &&
                !current.any(::isUntypedDailySpan) &&
                belongs(current, record)
            ) {
                current.add(record)
            } else {
                clusters.add(mutableListOf(record))
            }
        }
        return clusters.map { clusterRecords -> toMeal(date, clusterRecords, zoneId) }
            .sortedWith(
                compareBy<NutritionMeal> { it.startEpochMillis }
                    .thenBy { mealTypeSortKey(it.mealType) },
            )
    }

    /**
     * 同じ日に日次合計と食事単位が混在する場合は、食事単位だけを残す。
     * あすけんが日次合計だけを書く日はそのまま1件として扱う。
     */
    internal fun preferMealScoped(meals: List<NutritionMeal>): List<NutritionMeal> {
        val scoped = meals.filterNot { it.isDailyTotal }
        return scoped.ifEmpty { meals }
    }

    private fun belongs(
        cluster: List<NutritionRecordSnapshot>,
        record: NutritionRecordSnapshot,
    ): Boolean {
        if (!compatibleMealTypes(cluster, record)) return false
        val latestStart = cluster.maxOf { it.startEpochMillis }
        val earliestStart = cluster.minOf { it.startEpochMillis }
        val latestEnd = cluster.maxOf { it.endEpochMillis }
        val closeStart = record.startEpochMillis - latestStart <= CLUSTER_GAP_MS
        val overlaps = record.startEpochMillis < latestEnd &&
            record.endEpochMillis > earliestStart
        return closeStart || overlaps
    }

    /**
     * あすけんはアドバイス閲覧時に朝昼夕をまとめて書き出すことがある。
     * start/endが近くても、既知のmealTypeが違うレコードは別の1食として扱う。
     */
    private fun compatibleMealTypes(
        cluster: List<NutritionRecordSnapshot>,
        record: NutritionRecordSnapshot,
    ): Boolean {
        val recordType = knownMealType(record.mealType) ?: return true
        val clusterTypes = cluster.mapNotNull { knownMealType(it.mealType) }.toSet()
        if (clusterTypes.isEmpty()) return true
        return clusterTypes.singleOrNull() == recordType
    }

    private fun toMeal(
        date: LocalDate,
        records: List<NutritionRecordSnapshot>,
        zoneId: ZoneId,
    ): NutritionMeal {
        val start = records.minOf { it.startEpochMillis }
        val end = records.maxOf { it.endEpochMillis }
        val mealType = majorityMealType(records)
        val typedMeal = knownMealType(mealType) != null
        val dailyTotal = !typedMeal &&
            (records.any(::isUntypedDailySpan) || end - start >= DAILY_SPAN_MS)
        val label = if (dailyTotal) {
            "日次合計"
        } else {
            mealLabel(mealType, start, zoneId)
        }
        return NutritionMeal(
            date = date.toString(),
            startEpochMillis = start,
            endEpochMillis = end,
            mealLabel = label,
            mealType = mealType,
            energyKcal = sumOrNull(records) { it.energyKcal },
            proteinGrams = sumOrNull(records) { it.proteinGrams },
            fatGrams = sumOrNull(records) { it.fatGrams },
            carbohydrateGrams = sumOrNull(records) { it.carbohydrateGrams },
            recordCount = records.size,
            origin = records.map { it.origin }.distinct().sorted().joinToString(", "),
            isDailyTotal = dailyTotal,
        )
    }

    private fun majorityMealType(records: List<NutritionRecordSnapshot>): Int {
        val known = records.mapNotNull { knownMealType(it.mealType) }
        if (known.isEmpty()) return NutritionWriteShape.MEAL_TYPE_UNKNOWN
        return known.groupingBy { it }.eachCount().maxBy { it.value }.key
    }

    private fun mealLabel(mealType: Int, startEpochMillis: Long, zoneId: ZoneId): String {
        val typed = NutritionWriteShape.mealTypeLabel(mealType)
        if (typed != "不明") return typed
        val hour = Instant.ofEpochMilli(startEpochMillis).atZone(zoneId).hour
        return when (hour) {
            in 5 until 10 -> "朝"
            in 10 until 15 -> "昼"
            in 15 until 21 -> "夕"
            else -> "間食"
        }
    }

    private fun isUntypedDailySpan(record: NutritionRecordSnapshot): Boolean =
        knownMealType(record.mealType) == null &&
            record.endEpochMillis - record.startEpochMillis >= DAILY_SPAN_MS

    private fun knownMealType(mealType: Int): Int? =
        mealType.takeIf {
            it in NutritionWriteShape.MEAL_TYPE_BREAKFAST..NutritionWriteShape.MEAL_TYPE_SNACK
        }

    private fun mealTypeSortKey(mealType: Int): Int = when (mealType) {
        NutritionWriteShape.MEAL_TYPE_BREAKFAST -> 0
        NutritionWriteShape.MEAL_TYPE_LUNCH -> 1
        NutritionWriteShape.MEAL_TYPE_DINNER -> 2
        NutritionWriteShape.MEAL_TYPE_SNACK -> 3
        else -> 4
    }

    private fun sumOrNull(
        records: List<NutritionRecordSnapshot>,
        value: (NutritionRecordSnapshot) -> Double?,
    ): Double? {
        val values = records.mapNotNull(value)
        if (values.isEmpty()) return null
        return values.sum()
    }
}
