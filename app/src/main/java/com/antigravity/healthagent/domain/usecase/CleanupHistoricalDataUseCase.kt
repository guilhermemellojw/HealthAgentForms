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
    suspend operator fun invoke(beforeDate: String, agentName: String, isGlobal: Boolean = false): Result<Unit> {
        return try {
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val limitDate = try { sdf.parse(beforeDate) } catch (e: Exception) { null } 
                ?: return Result.failure(Exception("Formato de data inválido. Use DD-MM-YYYY."))

            if (isGlobal) {
                // GLOBAL CLEANUP (Admin Only)
                val allAgentsResult = syncRepository.fetchAllAgentsData()
                if (allAgentsResult.isFailure) return Result.failure(allAgentsResult.exceptionOrNull() ?: Exception("Falha ao buscar agentes"))
                
                val agents = allAgentsResult.getOrNull() ?: emptyList()
                
                for (agent in agents) {
                    val housesToRemove = agent.houses.filter {
                        val d = try { sdf.parse(it.data) } catch (e: Exception) { null }
                        d != null && d.before(limitDate)
                    }
                    val activitiesToRemove = agent.activities.filter {
                        val d = try { sdf.parse(it.date) } catch (e: Exception) { null }
                        d != null && d.before(limitDate)
                    }

                    if (housesToRemove.isNotEmpty() || activitiesToRemove.isNotEmpty()) {
                        val houseKeys = housesToRemove.map { it.generateNaturalKey() }
                        val activityTombstones = activitiesToRemove.map { "${it.date}|${it.agentName.uppercase()}" }
                        
                        // Delete from cloud and record tombstones for THIS agent
                        syncRepository.recordBulkDeletions(houseKeys, activityTombstones, agent.uid)
                    }
                }
            }
            
            // LOCAL CLEANUP (Current Agent) - always performed if agentName is provided
            if (agentName.isNotBlank()) {
                val allLocalHouses = houseRepository.getAllHousesOnce().filter { it.agentName.equals(agentName, ignoreCase = true) }
                val allLocalActivities = houseRepository.getAllDayActivitiesOnce().filter { it.agentName.equals(agentName, ignoreCase = true) }

                val localHousesToRemove = allLocalHouses.filter {
                    val d = try { sdf.parse(it.data) } catch (e: Exception) { null }
                    d != null && d.before(limitDate)
                }
                val localActivitiesToRemove = allLocalActivities.filter {
                    val d = try { sdf.parse(it.date) } catch (e: Exception) { null }
                    d != null && d.before(limitDate)
                }

                if (localHousesToRemove.isNotEmpty() || localActivitiesToRemove.isNotEmpty()) {
                    // Delete locally from Room (now handles tombstones automatically)
                    val datesToDelete = localActivitiesToRemove.map { it.date }
                    if (datesToDelete.isNotEmpty()) {
                        houseRepository.deleteByAgentAndDates(agentName, datesToDelete)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
