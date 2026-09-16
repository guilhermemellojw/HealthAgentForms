package com.antigravity.healthagent.domain.usecase

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.antigravity.healthagent.context.getContext
import com.antigravity.healthagent.data.sync.SyncWorker
import com.antigravity.healthagent.domain.logger.AppLogger
import javax.inject.Inject

class TriggerImmediateSyncUseCase @Inject constructor() {
    operator fun invoke() {
        try {
            val context = getContext() ?: return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        } catch (e: Exception) {
            AppLogger.e("TriggerImmediateSync", "Failed to trigger sync", e)
            throw e
        }
    }
}
