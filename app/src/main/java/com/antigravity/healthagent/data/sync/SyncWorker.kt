package com.antigravity.healthagent.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val houseRepository: HouseRepository,
    private val syncRepository: SyncRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "Starting background synchronization...")
        
        return try {
            val houses = houseRepository.getAllHousesOnce()
            val activities = houseRepository.getAllDayActivitiesOnce()
            
            if (houses.isEmpty()) {
                Log.d("SyncWorker", "No data to sync.")
                return Result.success()
            }

            val result = syncRepository.pushLocalDataToCloud(houses, activities)
            
            if (result.isSuccess) {
                Log.d("SyncWorker", "Sync successful.")
                Result.success()
            } else {
                Log.e("SyncWorker", "Sync failed: ${result.exceptionOrNull()?.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error in sync worker", e)
            Result.failure()
        }
    }
}
