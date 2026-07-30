package com.master.healthcoach.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.master.healthcoach.HealthCoachApplication

class HealthSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HealthCoachApplication).container
        return runCatching {
            if (
                container.repository.hasCorePermissions() &&
                container.repository.hasBackgroundPermission()
            ) {
                container.repository.sync()
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
