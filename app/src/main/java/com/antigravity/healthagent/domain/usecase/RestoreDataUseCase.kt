package com.antigravity.healthagent.domain.usecase

import android.content.Context
import android.net.Uri
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RestoreDataUseCase @Inject constructor(
    private val syncRepository: SyncRepository,
    private val authRepository: AuthRepository,
    private val backupManager: BackupManager
) {
    suspend operator fun invoke(
        context: Context, 
        targetUid: String, 
        fileUri: Uri,
        targetDate: String? = null,
        existingDates: List<String> = emptyList(),
        isSingleDayImport: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Get the agent name for this UID
            val usersResult = authRepository.fetchAllUsers()
            val user = usersResult.getOrNull()?.find { it.uid == targetUid }
            val agentName = (user?.agentName ?: user?.email ?: "Unknown Agent").uppercase()
            
            // 2. Import the data using BackupManager
            val backupData = backupManager.importData(context, fileUri)
            
            // 3. Filtering by date if requested
            var rawHouses = backupData.houses
            var rawActivities = backupData.dayActivities
            
            // STRICT FILTERING: Prevent mixing of different agent identities in a single restore.
            // We only import records that belong to the targetUid or have no attribution (legacy).
            val targetNameUpper = agentName.uppercase()
            
            val initialHouseCount = rawHouses.size
            rawHouses = rawHouses.filter { 
                val matchesUid = it.agentUid.isNotBlank() && it.agentUid == targetUid
                val matchesName = it.agentName.isNotBlank() && it.agentName.equals(targetNameUpper, ignoreCase = true)
                val isLegacyUnassigned = it.agentUid.isBlank() && it.agentName.isBlank()
                
                matchesUid || matchesName || isLegacyUnassigned
            }
            rawActivities = rawActivities.filter { 
                val matchesUid = it.agentUid.isNotBlank() && it.agentUid == targetUid
                val matchesName = it.agentName.isNotBlank() && it.agentName.equals(targetNameUpper, ignoreCase = true)
                val isLegacyUnassigned = it.agentUid.isBlank() && it.agentName.isBlank()
                
                matchesUid || matchesName || isLegacyUnassigned
            }
            
            if (rawHouses.isEmpty() && rawActivities.isEmpty() && initialHouseCount > 0) {
                return@withContext Result.failure(Exception("Este backup não contém registros para o agente selecionado ($agentName)."))
            }

            var importedHouses = rawHouses
            var importedActivities = rawActivities
            var resolveDate = targetDate

            if (targetDate != null) {
                importedHouses = importedHouses.filter { it.data.replace("/", "-") == targetDate.replace("/", "-") }
                importedActivities = importedActivities.filter { it.date.replace("/", "-") == targetDate.replace("/", "-") }
                
                if (importedHouses.isEmpty() && importedActivities.isEmpty()) {
                    return@withContext Result.failure(Exception("Nenhuma produção encontrada no arquivo para a data $targetDate"))
                }

                // Conflict Resolution: Find next available empty day if targetDate is occupied
                if (existingDates.contains(targetDate.replace("/", "-"))) {
                    resolveDate = findNextAvailableDate(targetDate, existingDates)
                }
            } else if (isSingleDayImport) {
                // Smart Date Detection for single-day imports
                val firstDate = rawActivities.firstOrNull()?.date 
                               ?: rawHouses.firstOrNull()?.data
                
                if (firstDate != null) {
                    val normalizedFirstDate = firstDate.replace("/", "-")
                    
                    // CRITICAL FIX: Filter data to ONLY include the detected first date
                    // This prevents multiple days in a backup from being flattened into a single offset date.
                    importedHouses = importedHouses.filter { it.data.replace("/", "-") == normalizedFirstDate }
                    importedActivities = importedActivities.filter { it.date.replace("/", "-") == normalizedFirstDate }

                    if (existingDates.contains(normalizedFirstDate)) {
                        resolveDate = findNextAvailableDate(normalizedFirstDate, existingDates)
                    } else {
                        resolveDate = normalizedFirstDate
                    }
                } else {
                    return@withContext Result.failure(Exception("Nenhum dado encontrado no arquivo de backup."))
                }
            }

            // Normalize and potentially shift dates
            val normalizedHouses = importedHouses.map { house ->
                // SAFE ATTRIBUTION RULE:
                // We only re-assign/normalize if:
                // 1. The backup explicitly says this record belonged to the backup creator (sourceAgentUid matches)
                // 2. OR the backup is legacy (null sourceAgentUid) AND the record is either blank OR already belongs to the target.
                val isSourceOwner = when {
                    backupData.sourceAgentUid != null -> house.agentUid == backupData.sourceAgentUid
                    house.agentUid.isNotBlank() -> house.agentUid == targetUid 
                    else -> true // Completely blank attribution (legacy simple backup)
                }
                
                val finalAgentName = if (isSourceOwner || house.agentName.isBlank()) agentName else house.agentName
                val finalAgentUid = if (isSourceOwner || house.agentUid.isBlank()) targetUid else house.agentUid

                house.copy(
                    id = 0, 
                    agentName = finalAgentName,
                    agentUid = finalAgentUid,
                    number = if (house.number.trim() == "0") "" else house.number.trim().uppercase(),
                    sequence = if (house.sequence == 0) 0 else house.sequence,
                    complement = if (house.complement == 0) 0 else house.complement,
                    data = resolveDate ?: house.data.replace("/", "-"),
                    isSynced = false,
                    lastUpdated = System.currentTimeMillis()
                ) 
            }
            val normalizedActivities = importedActivities.map { activity ->
                val isSourceOwner = when {
                    backupData.sourceAgentUid != null -> activity.agentUid == backupData.sourceAgentUid
                    activity.agentUid.isNotBlank() -> activity.agentUid == targetUid
                    else -> true
                }
                val finalAgentName = if (isSourceOwner || activity.agentName.isBlank()) agentName else activity.agentName
                val finalAgentUid = if (isSourceOwner || activity.agentUid.isBlank()) targetUid else activity.agentUid

                activity.copy(
                    agentName = finalAgentName,
                    agentUid = finalAgentUid,
                    date = resolveDate ?: activity.date.replace("/", "-"),
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

    private fun findNextAvailableDate(startDate: String, existingDates: List<String>): String {
        val sdf = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US)
        val calendar = java.util.Calendar.getInstance()
        try {
            calendar.time = sdf.parse(startDate.replace("/", "-")) ?: java.util.Date()
        } catch (e: Exception) {
            return startDate // Fallback
        }

        var candidate = startDate.replace("/", "-")
        while (existingDates.contains(candidate)) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            candidate = sdf.format(calendar.time)
        }
        return candidate
    }
}
