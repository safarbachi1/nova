package com.example.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.NovaVpnApplication
import java.util.concurrent.TimeUnit

class DiscoveryMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NovaVpnApplication ?: return Result.failure()
        val container = app.container
        return try {
            container.logger.info("WorkManager", "Starting scheduled European source refresh and cleanup")
            val summary = container.discoveryEngine.refreshAllEnabledSources()
            val cleaned = container.repository.cleanupStaleServers()
            container.logger.info(
                "WorkManager",
                "Maintenance finished: saved=${summary.savedValidated}, reachable=${summary.reachableCount}, prunedStale=$cleaned"
            )
            Result.success()
        } catch (t: Throwable) {
            container.logger.error("WorkManager", "Scheduled refresh failed: ${t.message}")
            Result.retry()
        }
    }
}

object DiscoveryWorkScheduler {
    private const val UNIQUE_WORK_NAME = "nova_vpn_periodic_discovery"

    fun scheduleOrCancel(context: Context, enabled: Boolean, intervalHours: Int) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val clampedHours = intervalHours.coerceIn(1, 48).toLong()
        val request = PeriodicWorkRequestBuilder<DiscoveryMaintenanceWorker>(clampedHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
