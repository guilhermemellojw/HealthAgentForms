package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import javax.inject.Inject

class CleanupBrokenHousesUseCase @Inject constructor(
    private val houseRepository: HouseRepository,
    private val syncRepository: SyncRepository
) {
    suspend operator fun invoke(agentUid: String): Result<Int> {
        return try {
            // 1. Identify houses with "SEM RUA", "SEM Nº", etc. (empty address fields)
            val brokenHouses = houseRepository.getEmptyHouses(agentUid)
            
            val closedDates = houseRepository.getDayActivitiesByAgentSnapshot(agentUid)
                .filter { it.isClosed && !it.isManualUnlock }
                .map { it.date.replace("/", "-") }
                .toSet()

            val activeBrokenHouses = brokenHouses.filter { house ->
                house.data.replace("/", "-") !in closedDates
            }

            if (activeBrokenHouses.isEmpty()) {
                return Result.success(0)
            }
            
            // 2. Surgically delete them (Local + Cloud Tombstones)
            val deleteResult = syncRepository.deleteHousesSurgically(agentUid, activeBrokenHouses)
            
            if (deleteResult.isSuccess) {
                Result.success(activeBrokenHouses.size)
            } else {
                Result.failure(deleteResult.exceptionOrNull() ?: Exception("Failed to perform surgical cleanup"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
