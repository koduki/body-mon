package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.domain.WeeklyReportBuilder
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Every statistic is anchored to the requested end date, including historical queries. */
internal fun bodyTrendSummary(
    end: LocalDate,
    body: List<BodyCompositionEntity>,
    zoneId: ZoneId,
): JsonObject {
    val snapshot = WeeklyReportBuilder.build(today = end, daily = emptyList(), body = body, zoneId = zoneId, includeToday = true)
    val medianStart = end.minusDays(6)
    val trendStart = end.minusDays(27)
    val medianRecords = body.filter { LocalDate.parse(it.date) in medianStart..end }
    val trendRecords = body.filter { LocalDate.parse(it.date) in trendStart..end }
    return buildJsonObject {
        put("statisticsAsOfDate", end.toString())
        put("medianFrom", medianStart.toString())
        put("medianTo", end.toString())
        put("trendFrom", trendStart.toString())
        put("trendTo", end.toString())
        put("medianWeightMeasurementDays", medianRecords.count { it.weightKg != null })
        put("medianFatMeasurementDays", medianRecords.count { it.fatMassKg != null })
        put("medianLeanMeasurementDays", medianRecords.count { it.leanBodyMassKg != null })
        put("trendWeightMeasurementDays", trendRecords.count { it.weightKg != null })
        put("trendFatMeasurementDays", trendRecords.count { it.fatMassKg != null })
        put("trendLeanMeasurementDays", trendRecords.count { it.leanBodyMassKg != null })
        mapOf(
            "currentWeightMedianKg" to snapshot.currentWeightMedianKg,
            "currentFatMassMedianKg" to snapshot.currentFatMassMedianKg,
            "currentLeanMassMedianKg" to snapshot.currentLeanMassMedianKg,
            "weightTrendKgPerWeek" to snapshot.weightTrendKgPerWeek,
            "weightLossRatePercentPerWeek" to snapshot.weightLossRatePercentPerWeek,
            "fatMassTrendKgPerWeek" to snapshot.fatMassTrendKgPerWeek,
            "leanMassTrendKgPerWeek" to snapshot.leanMassTrendKgPerWeek,
        ).forEach { (key, value) -> put(key, value?.let { JsonPrimitive(it) } ?: JsonNull) }
    }
}
