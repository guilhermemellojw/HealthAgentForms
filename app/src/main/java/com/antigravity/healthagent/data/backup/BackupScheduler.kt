package com.antigravity.healthagent.data.backup

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager by lazy { WorkManager.getInstance(context) }

    fun scheduleBackup(frequency: BackupFrequency) {
        if (frequency == BackupFrequency.OFF) {
            cancelBackup()
            return
        }

        val repeatInterval = when (frequency) {
            BackupFrequency.DAILY -> 1L
            BackupFrequency.WEEKLY -> 7L
            BackupFrequency.MONTHLY -> 30L // Approximation
            else -> 1L
        }

        val constraints = androidx.work.Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<BackupWorker>(repeatInterval, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(10, TimeUnit.MINUTES) // Avoid immediate heavy startup
            .addTag("auto_backup")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "AutoBackupWork",
            ExistingPeriodicWorkPolicy.UPDATE, // Update if exists to apply new frequency
            workRequest
        )
    }

    fun cancelBackup() {
        workManager.cancelUniqueWork("AutoBackupWork")
    }
}

enum class BackupFrequency {
    OFF,
    DAILY,
    WEEKLY,
    MONTHLY
}
