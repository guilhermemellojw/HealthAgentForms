package com.antigravity.healthagent.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antigravity.healthagent.data.repository.HouseRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
        Log.d("SyncWorker", "Starting background synchronization (Attempt: $runAttemptCount)...")
        
        if (runAttemptCount > 3) {
            Log.e("SyncWorker", "Too many attempts. Giving up to save battery.")
            return Result.failure()
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.w("SyncWorker", "No user logged in. Skipping background sync.")
            return Result.success()
        }

        return try {
            // Check if we are in Admin Edit mode. Skip if so to prevent data mixing.
            val isEditing = withTimeoutOrNull(2000) {
                settingsManager.remoteAgentUid.first()
            } != null

            if (isEditing) {
                Log.w("SyncWorker", "Admin Edit mode active. Skipping background sync.")
                return Result.success()
            }

            val houses = houseRepository.getAllHousesOnce()
            val activities = houseRepository.getAllDayActivitiesOnce()
            
            if (houses.isEmpty() && activities.isEmpty()) {
                Log.d("SyncWorker", "No data to sync.")
                return Result.success()
            }

            val result = syncRepository.pushLocalDataToCloud(houses, activities)
            
            if (result.isSuccess) {
                Log.d("SyncWorker", "Sync successful.")
                Result.success()
            } else {
                val exception = result.exceptionOrNull()
                val errorMsg = exception?.message ?: "Erro de sincronização em segundo plano"
                Log.e("SyncWorker", "Sync failed: $errorMsg")
                
                // If it's a permission error, don't retry
                if (errorMsg.contains("Acesso negado", ignoreCase = true) == true) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error in sync worker", e)
            Result.failure()
        }
    }
}
