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
            container.diagnostics.prune()
            if (
                container.repository.hasCorePermissions() &&
                container.repository.hasBackgroundPermission()
            ) {
                container.repository.sync()
            } else {
                container.diagnostics.event("sync", "skipped_permission")
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
