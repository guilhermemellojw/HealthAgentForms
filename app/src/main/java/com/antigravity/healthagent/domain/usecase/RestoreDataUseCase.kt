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
            val agentName = user?.agentName?.trim()?.uppercase() ?: "RESTORADO"

            // 2. Import the data using BackupManager
            val backupData = backupManager.importData(context, fileUri)
            
            // Normalize the imported data so it aligns with the target user securely.
            // Critical: Reset the id to 0 so Room treats them as new entries instead of overriding existing rows.
            // Force the agentName to the target user's configured name to prevent natural key mismatches and duplication.
            val normalizedHouses = backupData.houses.map { 
                it.copy(
                    id = 0, 
                    agentName = if (it.agentName.isNotBlank()) it.agentName.trim().uppercase() else agentName.trim().uppercase(),
                    agentUid = targetUid,
                    number = if (it.number.trim() == "0") "" else it.number.trim().uppercase(),
                    sequence = it.sequence,
                    complement = it.complement,
                    data = it.data.replace("/", "-"),
                    isSynced = false,
                    lastUpdated = System.currentTimeMillis()
                ) 
            }
            val normalizedActivities = backupData.dayActivities.map { 
                it.copy(
                    agentName = if (it.agentName.isNotBlank()) it.agentName.trim().uppercase() else agentName.trim().uppercase(),
                    agentUid = targetUid,
                    date = it.date.replace("/", "-"),
                    isSynced = false,
                    lastUpdated = System.currentTimeMillis()
                ) 
            }
 
            // 3. Perform restoration locally ONLY if targetUid is the current user.
            // If an Admin is restoring data for someone else, we only push to the cloud.
            val currentUserUid = authRepository.getCurrentUserUid()
            if (targetUid == currentUserUid) {
                val result = syncRepository.restoreLocalData(
                    agentName = agentName,
                    houses = normalizedHouses,
                    activities = normalizedActivities,
                    agentUid = targetUid
                )
                if (result.isFailure) return@withContext result
            }

            // 4. Push to cloud for the specified targetUid
            syncRepository.pushLocalDataToCloud(
                houses = normalizedHouses,
                activities = normalizedActivities,
                targetUid = targetUid,
                shouldReplace = true
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
