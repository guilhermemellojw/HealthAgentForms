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
     * Deletes houses and activities before the specified date for the given agent (or all agents if global).
     * Records tombstones in the cloud to prevent deleted data from returning on pull.
     * @param beforeDate Format: dd-MM-yyyy
     * @param agentName Name of the local agent (optional if global)
     * @param isGlobal If true, cleans up data for all agents in the cloud
     */
    suspend operator fun invoke(beforeDate: String, agentName: String, agentUid: String, isGlobal: Boolean = false): Result<Unit> {
        return try {
            val normalizedBeforeDate = beforeDate.replace("/", "-")
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val limitDate = try { sdf.parse(normalizedBeforeDate) } catch (e: Exception) { null } 
                ?: return Result.failure(Exception("Formato de data inválido. Use DD-MM-YYYY."))

            if (isGlobal) {
                // GLOBAL CLEANUP (Admin Only in Cloud)
                val allAgentsResult = syncRepository.fetchAllAgentsData()
                if (allAgentsResult.isFailure) return Result.failure(allAgentsResult.exceptionOrNull() ?: Exception("Falha ao buscar agentes"))
                
                val agents = allAgentsResult.getOrNull() ?: emptyList()
                
                for (agent in agents) {
                    val housesToRemove = agent.houses.filter {
                        val d = try { sdf.parse(it.data.replace("/", "-")) } catch (e: Exception) { null }
                        d != null && d.before(limitDate)
                    }
                    val activitiesToRemove = agent.activities.filter {
                        val d = try { sdf.parse(it.date.replace("/", "-")) } catch (e: Exception) { null }
                        d != null && d.before(limitDate)
                    }

                    if (housesToRemove.isNotEmpty() || activitiesToRemove.isNotEmpty()) {
                        val houseKeys = housesToRemove.map { it.generateNaturalKey() }
                        val activityTombstones = activitiesToRemove.map { "${it.date.replace("/", "-")}|${it.agentName.uppercase()}" }
                        
                        // Delete from cloud and record tombstones for THIS agent
                        syncRepository.recordBulkDeletions(houseKeys, activityTombstones, agent.uid)
                    }
                }
            }
            
            // LOCAL CLEANUP (Current Agent)
            if (agentName.isNotBlank()) {
                // repository.getAllHousesOnce and getAllDayActivitiesOnce are now isolated by default
                val allLocalHouses = houseRepository.getAllHousesOnce(agentName, agentUid)
                val allLocalActivities = houseRepository.getAllDayActivitiesOnce(agentName, agentUid)

                val localHousesToRemove = allLocalHouses.filter {
                    val d = try { sdf.parse(it.data.replace("/", "-")) } catch (e: Exception) { null }
                    d != null && d.before(limitDate)
                }
                val localActivitiesToRemove = allLocalActivities.filter {
                    val d = try { sdf.parse(it.date.replace("/", "-")) } catch (e: Exception) { null }
                    d != null && d.before(limitDate)
                }

                if (localHousesToRemove.isNotEmpty() || localActivitiesToRemove.isNotEmpty()) {
                    // 1. Collect all impacted dates to ensure Houses AND Activities are removed
                    val datesToDelete = (localHousesToRemove.map { it.data } + localActivitiesToRemove.map { it.date }).distinct()
                    
                    if (datesToDelete.isNotEmpty()) {
                        houseRepository.deleteByAgentAndDates(agentName, datesToDelete, agentUid)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
