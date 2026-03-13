package com.antigravity.healthagent.domain.usecase

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.domain.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RestoreDataUseCase @Inject constructor(
    private val syncRepository: SyncRepository,
    private val authRepository: com.antigravity.healthagent.domain.repository.AuthRepository,
    private val backupManager: BackupManager
) {
    suspend operator fun invoke(context: Context, targetUid: String, fileUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Get the agent name for this UID
            val usersResult = authRepository.fetchAllUsers()
            val user = usersResult.getOrNull()?.find { it.uid == targetUid }
            val agentName = user?.agentName ?: "Restorado"

            // 2. Import the data using BackupManager
            val backupData = backupManager.importData(context, fileUri)
            
            // 3. Perform restoration via syncRepository
            val result = syncRepository.restoreLocalData(
                agentName = agentName,
                houses = backupData.houses,
                activities = backupData.dayActivities
            )
            
            if (result.isSuccess) {
                // 4. Automatically push to cloud after restoration to ensure consistency
                syncRepository.pushLocalDataToCloud(
                    houses = backupData.houses,
                    activities = backupData.dayActivities,
                    targetUid = targetUid,
                    shouldReplace = true
                )
            } else {
                result
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
