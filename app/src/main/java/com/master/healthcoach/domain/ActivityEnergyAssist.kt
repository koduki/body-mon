package com.master.healthcoach.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Independent, above-rest ACTIVE-energy reference, not a calibration of Xiaomi calories.
 *
 * Walking outside recorded workouts: 0.5 kcal / kg / km (level walking, net).
 * Workouts: (gross MET - 1) * kg * hours. Time-interval steps overlapping
 * workouts are removed to avoid double counting; concurrent workouts are
 * assigned to the highest-MET session, once.
 *
 * Heart rate is used ONLY inside an identified workout, with >=50% minute
 * coverage (>=2 distinct minutes), a known age, and plausible readings.
 * A bounded 0.85..1.15 modifier of the activity MET is a sensitivity
 * adjustment, NOT a heart-rate calorimetry equation or personalized VO2 test.
 *
 * 2024 Adult Compendium: https://pacompendium.com/adult-compendium/
 */
object ActivityEnergyAssist {
    const val VERSION = "2026-11-activity-hr-v1"
    const val INTERPRETATION =
        "活動補助推定は歩数（運動セッションと重なる歩数を除外）と運動種別・時間のMET値で活動消費のみを計算します。" +
            "運動中の心拍が十分に観測された区間のみ、年齢による推定最大心拍比でMETを±15%以内補正します。" +
            "推定最大心拍・MET・心拍補正は個人に校正されておらず、真値・信頼区間・摂取目標ではありません。" +
            "未記録活動、坂道、荷物、電動アシストなどを正確に反映しません。Xiaomi値は独立した比較対象です。"

    enum class Activity(val grossMet: Double) {
        WALKING(3.5), RUNNING(8.3), CYCLING(5.5),
        STRENGTH(5.0), MORNING_ROUTINE(4.0),
        HIKING(6.0), HIIT(8.0), SWIMMING(6.0),
        ROWING(6.0), OTHER(4.0)
    }

    data class Steps(val startMs: Long, val endMs: Long, val count: Long)
    data class Session(val id: String, val startMs: Long, val endMs: Long, val activity: Activity)
    data class Heart(val timeMs: Long, val bpm: Long)
    data class SessionResult(
        val activeKcal: Double,
        val heartObservedMinutes: Int,
        val heartAdjustedMinutes: Int,
        /** Observed minutes only, Z1..Z5, percentages of age-estimated HRmax. */
        val observedZoneMinutes: List<Int>,
    )
    data class Result(
        val activeKcal: Double,
        val walkingKcal: Double,
        val exerciseKcal: Double,
        val heartObservedMinutes: Int,
        val heartAdjustedMinutes: Int,
        val observedZoneMinutes: List<Int>,
        val sessions: Map<String, SessionResult>,
    )

    /**
     * Returns null when required walking inputs are missing. Never substitutes 0 for
     * missing steps; HR absence gracefully falls back to activity MET (no correction).
     */
    fun estimate(
        dayStartMs: Long,
        dayEndMs: Long,
        weightKg: Double?,
        heightCm: Double?,
        sex: String?,
        ageYears: Int?,
        dailySteps: Long?,
        stepIntervals: List<Steps>,
        sessions: List<Session>,
        heartSamples: List<Heart>,
    ): Result? {
        if (dayEndMs <= dayStartMs) return null
        val weight = weightKg?.takeIf { it.isFinite() && it in 20.0..400.0 } ?: return null
        val height = heightCm?.takeIf { it.isFinite() && it in 100.0..250.0 } ?: return null
        val stepsCount = dailySteps?.takeIf { it >= 0 } ?: return null

        val clipped = sessions.mapNotNull { s ->
            val start = max(dayStartMs, s.startMs)
            val end = min(dayEndMs, s.endMs)
            if (end > start) s.copy(startMs = start, endMs = end) else null
        }
        val stepRecords = stepIntervals.filter { it.count >= 0 && it.endMs > it.startMs &&
            it.endMs > dayStartMs && it.startMs < dayEndMs }
        val recordStepTotal = stepRecords.sumOf { s ->
            val duration = (s.endMs - s.startMs).toDouble()
            s.count * overlapMs(s.startMs, s.endMs, dayStartMs, dayEndMs) / duration
        }
        // If aggregate has steps but we cannot place them on the timeline,
        // do not pretend to have removed workout-step overlap.
        if (stepsCount > 0 && recordStepTotal <= 0.0) return null
        val excluded = clipped.map { it.startMs to it.endMs }
        val recordStepOutside = stepRecords.sumOf { s ->
            val duration = (s.endMs - s.startMs).toDouble()
            val inDay = overlapMs(s.startMs, s.endMs, dayStartMs, dayEndMs)
            val inWorkout = unionOverlapMs(s.startMs, s.endMs, excluded, dayStartMs, dayEndMs)
            s.count * (inDay - inWorkout).coerceAtLeast(0L) / duration
        }
        // Rescale by the source's daily aggregate to tolerate small record-boundary
        // differences, but never derive steps from heart rate or calories.
        val outsideSteps = if (stepsCount == 0L) 0.0
            else (stepsCount * (recordStepOutside / recordStepTotal)).coerceIn(0.0, stepsCount.toDouble())
        val strideMeters = height * EnergyModel.strideRatio(sex) / 100.0
        val walkingKcal = outsideSteps * strideMeters / 1000.0 *
            weight * EnergyModel.WALKING_NET_KCAL_PER_KG_KM

        val maximumHr = ageYears?.takeIf { it in 18..90 }?.let { 208.0 - 0.7 * it }
        val validHeart = heartSamples.filter {
            it.timeMs >= dayStartMs && it.timeMs < dayEndMs && it.bpm in 40..220
        }
        val endpoints = clipped.flatMap { listOf(it.startMs, it.endMs) }.distinct().sorted()
        data class MutableSession(
            var energy: Double = 0.0,
            var observed: Int = 0,
            var adjusted: Int = 0,
            val zones: IntArray = IntArray(5),
        )
        val perSession = linkedMapOf<String, MutableSession>()
        for (idx in 0 until endpoints.lastIndex) {
            val from = endpoints[idx]
            val to = endpoints[idx + 1]
            if (to <= from) continue
            // No double counting when two sources describe overlapping activities.
            val winner = clipped.filter { it.startMs < to && it.endMs > from }
                .sortedWith(compareByDescending<Session> { it.activity.grossMet }.thenBy { it.id })
                .firstOrNull() ?: continue
            val durationMinutes = (to - from) / 60_000.0
            val durationKcalFactor = weight * durationMinutes / 60.0
            val observedMinutes = validHeart
                .asSequence().filter { it.timeMs >= from && it.timeMs < to }
                .groupBy { (it.timeMs - from) / 60_000L }
                .values.map { group -> group.map { it.bpm }.average() }
            val zoneMinutes = IntArray(5)
            if (maximumHr != null) {
                observedMinutes.forEach { bpm ->
                    val ratio = bpm / maximumHr
                    val zone = when {
                        ratio < 0.60 -> 0
                        ratio < 0.70 -> 1
                        ratio < 0.80 -> 2
                        ratio < 0.90 -> 3
                        else -> 4
                    }
                    zoneMinutes[zone]++
                }
            }
            val hasValidHeart = maximumHr != null &&
                observedMinutes.size >= 2 &&
                observedMinutes.size >= ceil(durationMinutes * 0.5).toInt()
            val heartFactor = if (hasValidHeart) {
                val ratio = observedMinutes.average() / maximumHr!!
                (1.0 + (ratio - 0.70)).coerceIn(0.85, 1.15)
            } else 1.0
            // Only ABOVE-REST MET counts as active kcal, never as total calories.
            val kcal = max(0.0, winner.activity.grossMet * heartFactor - 1.0) * durationKcalFactor
            val target = perSession.getOrPut(winner.id) { MutableSession() }
            target.energy += kcal
            target.observed += observedMinutes.size
            if (hasValidHeart) target.adjusted += ceil(durationMinutes).toInt()
            for (z in 0..4) target.zones[z] += zoneMinutes[z]
        }
        val sumWorkouts = perSession.values.sumOf { it.energy }
        val zoneTotals = (0..4).map { z -> perSession.values.sumOf { it.zones[z] } }
        return Result(
            activeKcal = walkingKcal + sumWorkouts,
            walkingKcal = walkingKcal,
            exerciseKcal = sumWorkouts,
            heartObservedMinutes = perSession.values.sumOf { it.observed },
            heartAdjustedMinutes = perSession.values.sumOf { it.adjusted },
            observedZoneMinutes = zoneTotals,
            sessions = perSession.mapValues { (_, v) ->
                SessionResult(v.energy, v.observed, v.adjusted, v.zones.toList())
            },
        )
    }

    private fun overlapMs(a: Long, b: Long, c: Long, d: Long): Long =
        (min(b, d) - max(a, c)).coerceAtLeast(0L)

    private fun unionOverlapMs(
        start: Long,
        end: Long,
        ranges: List<Pair<Long, Long>>,
        dayStart: Long,
        dayEnd: Long,
    ): Long {
        var lastEnd = max(start, dayStart)
        val boundEnd = min(end, dayEnd)
        var sum = 0L
        ranges.sortedBy { it.first }.forEach { (a, b) ->
            val lo = max(max(a, start), dayStart)
            val hi = min(min(b, end), dayEnd)
            if (hi > lo) {
                val from = max(lastEnd, lo)
                if (hi > from) sum += hi - from
                lastEnd = max(lastEnd, hi)
            }
        }
        return sum.coerceAtMost((boundEnd - max(start, dayStart)).coerceAtLeast(0L))
    }
}
