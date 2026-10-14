package com.master.healthcoach.domain

data class SourceIntervalEvidence(val origin: String, val start: Long, val end: Long, val modified: Long)
data class OriginReadEvidence(val origin: String, val count: Int, val latestEnd: Long, val latestModified: Long)
data class RecordedBasalRate(val origin: String, val time: Long, val kcalPerDay: Double)

object HealthRecordEvidencePolicy {
    fun overlaps(start: Long, end: Long, from: Long, toExclusive: Long): Boolean =
        end > start && start < toExclusive && end > from

    fun audit(records: List<SourceIntervalEvidence>, from: Long, toExclusive: Long): List<OriginReadEvidence> =
        records.filter { overlaps(it.start, it.end, from, toExclusive) }.groupBy { it.origin }
            .map { (origin, rows) -> OriginReadEvidence(origin, rows.size, rows.maxOf { it.end }, rows.maxOf { it.modified }) }
            .sortedBy { it.origin }

    /** A device-written rate only. An aggregate alone is not evidence of a Xiaomi BMR record. */
    fun basalRate(records: List<RecordedBasalRate>, origin: String, toExclusive: Long): Double? =
        records.filter { it.origin == origin && it.time < toExclusive && it.kcalPerDay.isFinite() && it.kcalPerDay >= 0 }
            .maxByOrNull { it.time }?.kcalPerDay
}
