package com.master.healthcoach.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.master.healthcoach.BuildConfig
import com.master.healthcoach.HealthCoachApplication
import com.master.healthcoach.data.diagnostics.DiagnosticsStore
import com.master.healthcoach.data.diagnostics.SyncRunDiagnostics

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
                container.repository.sync(trigger = "worker")
            } else {
                val traceId = DiagnosticsStore.traceId()
                val now = System.currentTimeMillis()
                container.diagnostics.recordSyncRun(
                    SyncRunDiagnostics(
                        runId = traceId,
                        traceId = traceId,
                        rootSpanId = DiagnosticsStore.spanId(),
                        trigger = "worker",
                        startedAtEpochMillis = now,
                        endedAtEpochMillis = now,
                        durationMs = 0,
                        outcome = "skipped_permission",
                        lastSuccessfulStage = null,
                        failedStage = "healthconnect.permissions",
                        errorKind = "permission",
                        appVersion = BuildConfig.VERSION_NAME,
                        commitSha = BuildConfig.COMMIT_SHA,
                    ),
                )
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
