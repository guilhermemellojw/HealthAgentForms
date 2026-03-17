package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

class CleanupHistoricalDataUseCase @Inject constructor(
    private val houseRepository: HouseRepository,
    private val syncRepository: SyncRepository
) {
    /**
     * Deletes houses and activities before the specified date for the given agent.
     * Records tombstones in the cloud to prevent deleted data from returning on pull.
     * @param beforeDate Format: dd-MM-yyyy
     * @param agentName Name of the agent whose data should be cleaned
     */
    suspend operator fun invoke(beforeDate: String, agentName: String): Result<Unit> {
        return try {
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val limitDate = try { sdf.parse(beforeDate) } catch (e: Exception) { null } 
                ?: return Result.failure(Exception("Formato de data inválido. Use DD-MM-YYYY."))

            // 1. Fetch all data to identify what needs tombstones
            val allHouses = houseRepository.getAllHousesOnce().filter { it.agentName.equals(agentName, ignoreCase = true) }
            val allActivities = houseRepository.getAllDayActivitiesOnce().filter { it.agentName.equals(agentName, ignoreCase = true) }

            val housesToRemove = allHouses.filter {
                val d = try { sdf.parse(it.data) } catch (e: Exception) { null }
                d != null && d.before(limitDate)
            }
            val activitiesToRemove = allActivities.filter {
                val d = try { sdf.parse(it.date) } catch (e: Exception) { null }
                d != null && d.before(limitDate)
            }

            if (housesToRemove.isEmpty() && activitiesToRemove.isEmpty()) {
                return Result.success(Unit)
            }

            // 2. Prepare tombstones for the cloud
            val houseKeys = housesToRemove.map { it.generateNaturalKey() }
            val activityTombstones = activitiesToRemove.map { "${it.date}|${it.agentName}" }

            // 3. Record tombstones in Cloud metadata (ensures Pulled data doesn't return)
            syncRepository.recordBulkDeletions(houseKeys, activityTombstones)

            // 4. Delete locally from Room
            val datesToDelete = activitiesToRemove.map { it.date }
            if (datesToDelete.isNotEmpty()) {
                houseRepository.deleteByAgentAndDates(agentName, datesToDelete)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
