package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.repository.BackupRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.repository.AuthRepository
import javax.inject.Inject

class RestoreFromTimelineUseCase @Inject constructor(
    private val backupRepository: BackupRepository,
    private val syncRepository: SyncRepository,
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(agentUid: String, storagePath: String): Result<Unit> {
        return try {
            // 1. Download Backup
            val backupDataResult = backupRepository.downloadBackup(storagePath)
            if (backupDataResult.isFailure) return Result.failure(backupDataResult.exceptionOrNull() ?: Exception("Failed to download backup"))
            val backupData = backupDataResult.getOrThrow()

            // 2. Fetch target agent name
            val usersResult = authRepository.fetchAllUsers()
            val user = usersResult.getOrNull()?.find { it.uid == agentUid }
            val targetName = (user?.agentName ?: user?.email ?: "Unknown Agent").uppercase()

            // 3. Normalize data for the target agent (Safety check)
            val houses = backupData.houses.map { it.copy(agentUid = agentUid, agentName = targetName, isSynced = false, lastUpdated = System.currentTimeMillis(), editedByAdmin = true) }
            val activities = backupData.dayActivities.map { it.copy(agentUid = agentUid, agentName = targetName, isSynced = false, lastUpdated = System.currentTimeMillis(), editedByAdmin = true) }

            // 4. Push to Cloud (Force Replace)
            val syncResult = syncRepository.pushLocalDataToCloud(
                houses = houses,
                activities = activities,
                targetUid = agentUid,
                shouldReplace = true
            )

            if (syncResult.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(syncResult.exceptionOrNull() ?: Exception("Failed to sync restored data to cloud"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
