package com.master.healthcoach.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val UNIQUE_WORK = "health-connect-daily-sync"

    fun schedule(context: Context) {
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().plusDays(1).atTime(3, 0).atZone(now.zone)
        if (next <= now) next = next.plusDays(1)
        val initialDelay = Duration.between(now, next)
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

