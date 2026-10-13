package com.master.healthcoach.domain

import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MetricAvailabilityTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val now = 1791928800000L
    private fun day(offset: Long, value: Long? = null, timestamp: Long = now) = DailyHealthSummaryEntity(
        date = today.minusDays(offset).toString(), steps = value, distanceMeters = null, activeCaloriesKcal = null,
        exerciseMinutes = 0, strengthMinutes = 0, cardioMinutes = 0, exerciseSessionCount = 0,
        sleepMinutes = null, moderateIntensityMinutes = null, vigorousIntensityMinutes = null,
        heartRateAverageBpm = null, heartRateMinimumBpm = null, heartRateMaximumBpm = null,
        heartRateMeasurementCount = null, basalCaloriesKcal = null, dataOrigins = "", updatedAtEpochMillis = timestamp,
    )
    private fun steps(rows: List<DailyHealthSummaryEntity>, status: String = "未取得", timestamp: Long = now) =
        MetricAvailabilityPolicy.inspect(rows, emptyList(), listOf(HealthSourceStatusEntity("歩数", 0, null, "", status, timestamp)), today, now).first { it.label == "歩数" }

    @Test fun `30 completed checked days hide empty metric while 29 and duplicate days do not`() {
        assertEquals(MetricAvailabilityState.NO_DATA_30_DAYS, steps((1L..30).map { day(it) }).state)
        assertEquals(MetricAvailabilityState.CHECKING, steps((1L..29).map { day(it) } + day(29)).state)
        assertEquals(MetricAvailabilityState.CHECKING, steps((0L..29).map { day(it) }).state)
    }
    @Test fun `stale sync and old cached dates do not prove current 30 day absence`() {
        val stale = now - 3 * 86_400_000L
        assertEquals(MetricAvailabilityState.CHECKING, steps((1L..30).map { day(it) }, timestamp = stale).state)
        assertEquals(MetricAvailabilityState.CHECKING, steps((1L..30).map { day(it, timestamp = stale) }).state)
    }
    @Test fun `zero is observed and new data immediately restores availability`() {
        val missing = (1L..30).map { day(it) }
        assertEquals(MetricAvailabilityState.AVAILABLE, steps(missing + day(0, 0)).state)
        assertEquals(MetricAvailabilityState.AVAILABLE, steps(missing + day(0, 120)).state)
    }
    @Test fun `permission and device restrictions are separate from absence`() {
        assertEquals(MetricAvailabilityState.PERMISSION_REQUIRED, steps(emptyList(), "権限なし").state)
        assertEquals(MetricAvailabilityState.UNSUPPORTED, MetricAvailabilityPolicy.evaluate("中強度", false, 0, "端末非対応", true).state)
        assertEquals(MetricAvailabilityState.CHECKING, steps(emptyList()).state)
    }
}
