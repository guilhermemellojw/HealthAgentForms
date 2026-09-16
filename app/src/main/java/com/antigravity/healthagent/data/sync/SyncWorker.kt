package com.antigravity.healthagent.data.sync

import android.content.Context
import com.antigravity.healthagent.domain.logger.AppLogger
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antigravity.healthagent.domain.repository.HouseRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.data.settings.SettingsManager

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val houseRepository: HouseRepository,
    private val syncRepository: SyncRepository,
    private val settingsManager: SettingsManager,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val syncOpId = nextSyncOpId.incrementAndGet()
        AppLogger.d("SyncWorker", "[#$syncOpId] Starting background synchronization (Attempt: $runAttemptCount)...")
        
        if (runAttemptCount > 3) {
            AppLogger.e("SyncWorker", "[#$syncOpId] Too many attempts. Giving up to save battery.")
            return Result.failure()
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            AppLogger.w("SyncWorker", "[#$syncOpId] No user logged in. Skipping background sync.")
            return Result.success()
        }

        return try {
            com.antigravity.healthagent.utils.TimeManager.synchronizeTime(applicationContext)

            val isEditing = try {
                withTimeout(5000) {
                    settingsManager.remoteAgentUid.take(1).first() != null
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                AppLogger.w("SyncWorker", "[#$syncOpId] Timeout waiting for remoteAgentUid - assuming not editing")
                false
            }

            if (isEditing) {
                AppLogger.w("SyncWorker", "[#$syncOpId] Admin Edit mode active. Skipping background sync.")
                return Result.success()
            }

            val agentName = withTimeoutOrNull(2000) { settingsManager.cachedUser.take(1).first()?.agentName } ?: ""
            val uid = currentUser.uid

            val pullResult = syncRepository.pullCloudDataToLocal(uid)
            if (pullResult.isFailure) {
                AppLogger.w("SyncWorker", "[#$syncOpId] Background pull failed: ${pullResult.exceptionOrNull()?.message}")
                return Result.retry()
            }

            val houses = houseRepository.getAllHousesOnce(uid)
            val activities = houseRepository.getAllDayActivitiesOnce(uid)
            
            if (houses.isEmpty() && activities.isEmpty()) {
                AppLogger.d("SyncWorker", "[#$syncOpId] No data to push.")
                return Result.success()
            }

            val result = syncRepository.pushLocalDataToCloud(houses, activities, uid)
            
            if (result.isSuccess) {
                AppLogger.d("SyncWorker", "[#$syncOpId] Sync successful.")
                Result.success()
            } else {
                val exception = result.exceptionOrNull()
                val errorMsg = exception?.message ?: "Erro de sincronização em segundo plano"
                AppLogger.e("SyncWorker", "[#$syncOpId] Sync failed: $errorMsg")
                
                if (errorMsg.contains("Acesso negado", ignoreCase = true) == true) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            AppLogger.e("SyncWorker", "[#$syncOpId] Error in sync worker", e)
            Result.failure()
        }
    }

    companion object {
        private val nextSyncOpId = AtomicLong(0)
    }
}
