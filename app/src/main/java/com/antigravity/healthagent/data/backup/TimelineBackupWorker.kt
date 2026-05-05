package com.antigravity.healthagent.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.domain.repository.BackupRepository

@HiltWorker
class TimelineBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val houseDao: HouseDao,
    private val dayActivityDao: DayActivityDao,
    private val backupRepository: BackupRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val officialAgentName = inputData.getString("officialAgentName") ?: return Result.failure()

        Log.d("TimelineBackupWorker", "Starting timeline backup for $uid ($officialAgentName) in background...")

        return try {
            val allHouses = houseDao.getHousesByAgentSnapshot(officialAgentName, uid)
            val allActivities = dayActivityDao.getAllDayActivities(officialAgentName, uid)
            
            val backupData = BackupData(
                houses = allHouses,
                dayActivities = allActivities,
                sourceAgentUid = uid,
                sourceAgentName = officialAgentName
            )
            
            val result = backupRepository.uploadTimelineBackup(uid, backupData)
            if (result.isSuccess) {
                Log.i("TimelineBackupWorker", "Timeline Backup uploaded successfully for $uid")
                Result.success()
            } else {
                Log.w("TimelineBackupWorker", "Timeline Backup failed: ${result.exceptionOrNull()?.message}")
                // Return success anyway to not clutter the WorkManager with retries for a non-critical feature.
                // The next sync will enqueue another one.
                Result.success()
            }
        } catch (e: Exception) {
            Log.e("TimelineBackupWorker", "Error executing timeline backup", e)
            Result.success()
        }
    }
}
